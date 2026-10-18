import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as shopsRoute from "@/app/api/shops/route";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, createOwner, enrolEvents, ev, healthPayload, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

// TASK-056 (owner accounts, shop creation, RLS isolation) and TASK-113 (RLS audit).
let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

describe("POST /api/shops", () => {
  it("creates the shop with v1.0 versions, notice language and the owner staff row", async () => {
    const o = await createOwner(t, h, "own1");
    const r = await call(shopsRoute, "POST", { path: "/api/shops", token: o.token, body: { name: "Sharma Jewellers", city: "Jaipur", notice_language: "hi" } });
    expect(r.status).toBe(201);
    const id = (r.json as unknown as { id: string }).id;
    expect(id).toBe("sharma-jewellers-jaipur");
    const shop = (await t.db.query<Record<string, unknown>>("select * from shops where id = $1", [id]))[0]!;
    expect(shop).toMatchObject({ script_version: "hi-IN-v1.0", notice_version: "wa-v1.0", settings_json: { notice_language: "hi" } });
    const staff = await t.db.query("select role, user_id from staff where shop_id = $1", [id]);
    expect(staff).toEqual([{ role: "owner", user_id: o.userId }]);
    // A second shop with the same name gets a distinct id.
    const r2 = await call(shopsRoute, "POST", { path: "/api/shops", token: o.token, body: { name: "Sharma Jewellers", city: "Jaipur", notice_language: "en" } });
    expect((r2.json as unknown as { id: string }).id).not.toBe(id);
  });

  it("needs a valid session and a strict body", async () => {
    expect((await call(shopsRoute, "POST", { path: "/api/shops", body: { name: "A", city: "B", notice_language: "en" } })).status).toBe(401);
    expect((await call(shopsRoute, "POST", { path: "/api/shops", token: "token-forged", body: { name: "A", city: "B", notice_language: "en" } })).status).toBe(401);
    const o = await createOwner(t, h, "own2");
    const bad = await call(shopsRoute, "POST", { path: "/api/shops", token: o.token, body: { name: "A", city: "B", notice_language: "fr" } });
    expect(bad.status).toBe(400);
    const extra = await call(shopsRoute, "POST", { path: "/api/shops", token: o.token, body: { name: "A", city: "B", notice_language: "en", owner_id: "x" } });
    expect(extra.status).toBe(400);
    expect(extra.json).toMatchObject({ error: "invalid" });
  });
});

describe("Row Level Security: two shops, two owners", () => {
  const TABLES = ["shops", "staff", "households", "members", "consent_records", "purchases", "visits", "hash_anchors", "push_tokens", "devices", "whatsapp_messages", "inbox_messages"];
  let A: Awaited<ReturnType<typeof setupShop>>;
  let B: Awaited<ReturnType<typeof setupShop>>;

  beforeAll(async () => {
    A = await setupShop(t, h, "rlsa");
    B = await setupShop(t, h, "rlsb");
    for (const s of [A, B]) {
      const chain = new ChainBuilder(s.shopId);
      const e = enrolEvents(s.shopId, chain, { phone: s === A ? "+919800000011" : "+919800000012" });
      const hh = nextId("h");
      const visit = nextId("v");
      const res = await postEvents(h, s.deviceToken, [
        ...e.events,
        ev("household_upsert", { id: hh, shop_id: s.shopId, label: "Family", payer_member_id: e.memberId, member_ids: [e.memberId], created_at: "2026-10-18T17:42:11+05:30" }),
        ev("visit_summary", { id: visit, member_id: e.memberId, ts: "2026-10-18T18:00:00+05:30", camera: "gate", score: 0.61, frames_agreed: 3, greeted: false, greeted_by: null, not_them: false }),
        ev("health", healthPayload()),
      ]);
      expect(res.status, JSON.stringify(res.json)).toBe(200);
      expect((res.json as unknown as { results: { status: string }[] }).results.every((x) => x.status === "acked"), JSON.stringify(res.json)).toBe(true);
      await t.db.query("insert into hash_anchors (shop_id, date, chain_head_hash, match) values ($1, '2026-10-18', $2, true)", [s.shopId, chain.head]);
      await t.db.query("insert into push_tokens (shop_id, user_id, fcm_token) values ($1, $2, $3)", [s.shopId, s.owner.userId, `fcm-token-${s.shopId}`]);
    }
  });

  it("an owner reads only their own shop's rows in every mirrored table", async () => {
    for (const table of ["shops", "staff", "households", "members", "consent_records", "purchases", "visits", "hash_anchors", "push_tokens"]) {
      const col = table === "shops" ? "id" : "shop_id";
      const rows = await t.asUser(A.owner.userId, (q) => q.query<Record<string, string>>(`select ${col} as shop from ${table}`));
      expect(rows.rows.length, table).toBeGreaterThan(0);
      expect(new Set(rows.rows.map((r) => r.shop)), table).toEqual(new Set([A.shopId]));
    }
  });

  it("an owner cannot read cloud-internal tables at all", async () => {
    for (const table of ["devices", "whatsapp_messages", "inbox_messages", "hub_events", "rate_limit_hits", "export_log"]) {
      await expect(t.asUser(A.owner.userId, (q) => q.query(`select * from ${table}`)), table).rejects.toThrow(/permission denied/);
    }
  });

  it("an owner cannot insert or update another shop's rows in any mirrored table", async () => {
    const tryInsert = (sql: string, params: unknown[] = []) => t.asUser(A.owner.userId, (q) => q.query(sql, params));
    await expect(tryInsert("insert into members (id, shop_id, display_name, phone_hash, status, member_since, created_at, updated_at) values ('m_x', $1, 'X', 'ab', 'pending_notice', '2026-10-18', now(), now())", [B.shopId])).rejects.toThrow();
    await expect(tryInsert("insert into consent_records (id, shop_id, member_id, ts, channel, script_version, notice_version, purposes_json, notice_delivery_json, prev_hash, record_hash, hash_ok, link_ok) values ('c_x', $1, 'm_x', 't', 'sweep', 'a', 'b', '{}', '{}', 'p', 'r', true, true)", [B.shopId])).rejects.toThrow();
    await expect(tryInsert("insert into staff (id, shop_id, name, role) values ('s_x', $1, 'X', 'owner')", [B.shopId])).rejects.toThrow();
    await expect(tryInsert("insert into push_tokens (shop_id, user_id, fcm_token) values ($1, $2, 'fcm-token-evil-0001')", [B.shopId, A.owner.userId])).rejects.toThrow();
    await expect(tryInsert("insert into visits (id, shop_id, member_id, ts, camera, score, frames_agreed) values ('v_x', $1, 'm_x', now(), 'gate', 0.5, 3)", [B.shopId])).rejects.toThrow();
    for (const table of ["households", "purchases", "hash_anchors"]) {
      await expect(tryInsert(`insert into ${table} (shop_id) values ($1)`, [B.shopId]), table).rejects.toThrow();
    }
    // Updates: no write grant on mirrored tables; shops update is limited to own shop by policy.
    await expect(tryInsert("update members set notes = 'x' where shop_id = $1", [B.shopId])).rejects.toThrow(/permission denied/);
    const upd = await t.asUser(A.owner.userId, (q) => q.query("update shops set name = 'Hacked' where id = $1", [B.shopId]));
    expect(upd.affectedRows ?? 0).toBe(0);
    const name = await t.db.query<{ name: string }>("select name from shops where id = $1", [B.shopId]);
    expect(name[0]!.name).not.toBe("Hacked");
    // Deleting another owner's push token touches nothing.
    const del = await t.asUser(A.owner.userId, (q) => q.query("delete from push_tokens where shop_id = $1", [B.shopId]));
    expect(del.affectedRows ?? 0).toBe(0);
    expect(TABLES.length).toBeGreaterThan(0);
  });

  it("an owner can rename their own shop", async () => {
    const upd = await t.asUser(A.owner.userId, (q) => q.query("update shops set city = 'Jodhpur' where id = $1", [A.shopId]));
    expect(upd.affectedRows).toBe(1);
  });
});
