import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as anchorRoute from "@/app/api/hub/anchor/route";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, healthPayload, memberUpsert, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";

let t: TestDb;
let h: TestHarness;
let S: Awaited<ReturnType<typeof setupShop>>;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
  S = await setupShop(t, h, "ing");
});
afterAll(async () => t.close());

type Results = { results: { id: string; status: string; reason?: string }[] };

describe("POST /api/hub/events (TASK-059)", () => {
  it("authenticates by device token", async () => {
    expect((await postEvents(h, "", [])).status).toBe(401);
    expect((await postEvents(h, "wrong-token", [])).status).toBe(401);
  });

  it("applies an enrolment batch and mirrors every table; replaying writes once", async () => {
    const chain = new ChainBuilder(S.shopId);
    const e = enrolEvents(S.shopId, chain, { phone: "+919800000101", name: "Ingest Member" });
    const staff = ev("staff_upsert", { id: nextId("s"), shop_id: S.shopId, name: "Ravi", role: "cashier", created_at: "2026-10-18T10:00:00+05:30" });
    const hh = ev("household_upsert", { id: nextId("h"), shop_id: S.shopId, label: "Ingest family", payer_member_id: e.memberId, member_ids: [e.memberId], created_at: "2026-10-18T17:42:11+05:30" });
    const visitId = nextId("v");
    const visit = ev("visit_summary", { id: visitId, member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30", camera: "gate", score: 0.62, frames_agreed: 4, greeted: false, greeted_by: null, not_them: false });
    const fb = ev("visit_feedback", { visit_id: visitId, greeted: true, greeted_by: "s_01JTESTCASHIER0000000000", not_them: false });
    const health = ev("health", healthPayload());
    const batch = [staff, ...e.events, hh, visit, fb, health];
    const r = await postEvents(h, S.deviceToken, batch);
    expect(r.status).toBe(200);
    const res = (r.json as unknown as Results).results;
    expect(res.map((x) => x.id)).toEqual(batch.map((b) => b.id));
    expect(res.every((x) => x.status === "acked")).toBe(true);

    const m = (await t.db.query<Record<string, unknown>>("select * from members where id = $1", [e.memberId]))[0]!;
    expect(m).toMatchObject({ status: "pending_notice", display_name: "Ingest Member", has_template: true, household_id: JSON.parse(JSON.stringify(hh.payload)).id });
    expect(await t.db.query("select id from consent_records where id = $1", [e.record.id])).toHaveLength(1);
    expect(await t.db.query("select 1 from purchases where member_id = $1", [e.memberId])).toHaveLength(1);
    expect(await t.db.query("select 1 from staff where shop_id = $1 and role = 'cashier'", [S.shopId])).toHaveLength(1);
    const v = (await t.db.query<Record<string, unknown>>("select greeted, greeted_by, not_them from visits where id = $1", [visitId]))[0];
    expect(v).toEqual({ greeted: true, greeted_by: "s_01JTESTCASHIER0000000000", not_them: false });
    const before = await t.db.query("select (select count(*) from consent_records)::int c, (select count(*) from purchases)::int p, (select count(*) from whatsapp_messages)::int w, (select count(*) from device_health_log)::int hl");
    expect(h.whatsappCalls()).toHaveLength(1);

    const replay = await postEvents(h, S.deviceToken, batch);
    expect((replay.json as unknown as Results).results.every((x) => x.status === "acked")).toBe(true);
    const after = await t.db.query("select (select count(*) from consent_records)::int c, (select count(*) from purchases)::int p, (select count(*) from whatsapp_messages)::int w, (select count(*) from device_health_log)::int hl");
    expect(after).toEqual(before);
    expect(h.whatsappCalls()).toHaveLength(1); // no second send
    // No PII reached the logs.
    expect(allLogs()).not.toContain("Ingest Member");
    expect(allLogs()).not.toContain("9800000101");
    expect(allLogs()).not.toContain("Asked about matching earrings");
  });

  it("never updates a mirrored consent record (a duplicate id with other content is ignored)", async () => {
    const chain = new ChainBuilder(S.shopId);
    chain.head = (await t.db.query<{ record_hash: string }>("select record_hash from consent_records where shop_id = $1 order by mirror_seq desc limit 1", [S.shopId]))[0]!.record_hash;
    const rec = chain.next({ member_id: "m_01JNOUPDATE00000000000000" });
    await postEvents(h, S.deviceToken, [ev("consent_record", rec)]);
    const changed = { ...rec, bill_no: "CHANGED" };
    const r = await postEvents(h, S.deviceToken, [ev("consent_record", changed)]);
    expect((r.json as unknown as Results).results[0]!.status).toBe("acked");
    const row = (await t.db.query<{ bill_no: string }>("select bill_no from consent_records where id = $1", [rec.id]))[0]!;
    expect(row.bill_no).toBe(rec.bill_no);
  });

  it("rejects an invalid event without blocking the ones after it", async () => {
    const bad = ev("member_upsert", { ...memberUpsert(S.shopId, nextId("m")), unknown_field: 1 });
    const unknown = ev("teleport", { x: 1 });
    const otherShop = ev("member_upsert", memberUpsert("someone-elses-shop", nextId("m")));
    const orphanPurchase = ev("purchase", { id: nextId("p"), member_id: nextId("m"), bill_no: "B1", amount: 10, items_text: null, occasion: null, ts: "2026-10-18T10:00:00+05:30" });
    const good = ev("health", healthPayload({ version: "0.1.1" }));
    const r = await postEvents(h, S.deviceToken, [bad, unknown, otherShop, orphanPurchase, good]);
    const res = (r.json as unknown as Results).results;
    expect(res.map((x) => x.status)).toEqual(["rejected", "rejected", "rejected", "rejected", "acked"]);
    expect(res[1]!.reason).toBe("unknown_type");
    expect(res[2]!.reason).toBe("shop_mismatch");
    expect(res[3]!.reason).toBe("unknown_member");
    const d = (await t.db.query<{ hub_version: string }>("select hub_version from devices where id = $1", [S.deviceId]))[0]!;
    expect(d.hub_version).toBe("0.1.1");
  });

  it("caps a batch at 200 events and a body at 1 MB", async () => {
    const many = Array.from({ length: 201 }, () => ev("health", healthPayload()));
    expect((await postEvents(h, S.deviceToken, many)).status).toBe(400);
    const big = await postEvents(h, S.deviceToken, [ev("health", { ...healthPayload(), version: "x".repeat(1_100_000) })]);
    expect(big.status).toBe(413);
  });
});

describe("payload_forbidden (privacy invariant 3)", () => {
  const cases: [string, Record<string, unknown>][] = [
    ["vec_blob", { vec_blob: [0.1, 0.2] }],
    ["template", { template: "abc" }],
    ["image", { image: "abc" }],
    ["crop_jpeg_b64", { crop_jpeg_b64: "abc" }],
    ["clip_id", { clip_id: "k_1" }],
    ["jpeg", { thumb_jpeg: "x" }],
    ["embedding", { face: { embedding: [1, 2] } }],
    ["has_template as non-boolean", { has_template: "yes" }],
  ];
  for (const [name, extra] of cases) {
    it(`refuses a payload with ${name} and writes nothing`, async () => {
      const id = nextId("m");
      const r = await postEvents(h, S.deviceToken, [ev("member_upsert", { ...memberUpsert(S.shopId, id), ...extra })]);
      expect(r.status).toBe(400);
      expect(r.json).toMatchObject({ error: "payload_forbidden" });
      expect(await t.db.query("select 1 from members where id = $1", [id])).toHaveLength(0);
    });
  }

  it("refuses any string over 1,024 characters that decodes as base64, and logs it", async () => {
    const blob = Buffer.alloc(2048, 0xff).toString("base64");
    const r = await postEvents(h, S.deviceToken, [ev("member_upsert", { ...memberUpsert(S.shopId, nextId("m")), notes: blob })]);
    expect(r.status).toBe(400);
    expect(r.json).toMatchObject({ error: "payload_forbidden" });
    expect(allLogs()).toContain('"event":"payload_forbidden"');
    expect(allLogs()).not.toContain(blob.slice(0, 100));
  });

  it("allows has_template as a boolean, template_deleted_at as a timestamp, and long plain text", async () => {
    const chain = new ChainBuilder(S.shopId);
    const rec = chain.next({ member_id: nextId("m"), template_deleted_at: "2026-10-18T18:00:00+05:30", channel: "owner_remove" });
    const r = await postEvents(h, S.deviceToken, [
      ev("member_upsert", { ...memberUpsert(S.shopId, nextId("m")), notes: "Likes kundan work. ".repeat(50).slice(0, 1000) }),
      ev("consent_record", rec),
    ]);
    expect(r.status).toBe(200);
  });
});

describe("consent chain check on ingest (TASK-060)", () => {
  async function freshShop(label: string) {
    return setupShop(t, h, label);
  }
  const flags = (id: string) =>
    t.db.query<{ chain_break: boolean; chain_break_record_id: string | null }>("select chain_break, chain_break_record_id from devices where id = $1", [id]).then((r) => r[0]!);

  it("an intact chain links from genesis and raises no flag", async () => {
    const s = await freshShop("chain1");
    const chain = new ChainBuilder(s.shopId);
    const recs = [1, 2, 3].map(() => chain.next({ member_id: nextId("m") }));
    await postEvents(h, s.deviceToken, recs.map((r) => ev("consent_record", r)));
    const rows = await t.db.query<{ hash_ok: boolean; link_ok: boolean }>("select hash_ok, link_ok from consent_records where shop_id = $1 order by mirror_seq", [s.shopId]);
    expect(rows).toEqual(recs.map(() => ({ hash_ok: true, link_ok: true })));
    expect(await flags(s.deviceId)).toEqual({ chain_break: false, chain_break_record_id: null });
  });

  it("a tampered record is stored as received and flags chain_break on the device", async () => {
    const s = await freshShop("chain2");
    const chain = new ChainBuilder(s.shopId);
    const r1 = chain.next({ member_id: nextId("m") });
    const r2 = { ...chain.next({ member_id: nextId("m") }), bill_no: "TAMPERED" };
    const res = await postEvents(h, s.deviceToken, [ev("consent_record", r1), ev("consent_record", r2)]);
    expect((res.json as unknown as Results).results.map((x) => x.status)).toEqual(["acked", "acked"]);
    const row = (await t.db.query<{ hash_ok: boolean; bill_no: string }>("select hash_ok, bill_no from consent_records where id = $1", [r2.id]))[0]!;
    expect(row).toEqual({ hash_ok: false, bill_no: "TAMPERED" });
    expect(await flags(s.deviceId)).toEqual({ chain_break: true, chain_break_record_id: r2.id });
    expect(allLogs()).toContain('"event":"chain_break"');
  });

  it("a missing link (a record skipped) flags the break at the first unlinked record", async () => {
    const s = await freshShop("chain3");
    const chain = new ChainBuilder(s.shopId);
    const r1 = chain.next({ member_id: nextId("m") });
    chain.next({ member_id: nextId("m") }); // never sent
    const r3 = chain.next({ member_id: nextId("m") });
    await postEvents(h, s.deviceToken, [ev("consent_record", r1), ev("consent_record", r3)]);
    expect(await flags(s.deviceId)).toEqual({ chain_break: true, chain_break_record_id: r3.id });
  });

  it("an out-of-order batch is linked in chain order, sorted on ts then id when unlinked", async () => {
    const s = await freshShop("chain4");
    const chain = new ChainBuilder(s.shopId);
    const recs = [
      chain.next({ member_id: nextId("m"), ts: "2026-10-18T10:00:00+05:30" }),
      chain.next({ member_id: nextId("m"), ts: "2026-10-18T10:05:00+05:30" }),
      chain.next({ member_id: nextId("m"), ts: "2026-10-18T10:10:00+05:30" }),
    ];
    const events = recs.map((r) => ev("consent_record", r));
    const shuffled = [events[2]!, events[0]!, events[1]!].map((e, i) => ({ ...e, id: [events[0]!.id, events[1]!.id, events[2]!.id][i]! }));
    const res = await postEvents(h, s.deviceToken, shuffled);
    expect((res.json as unknown as Results).results.every((x) => x.status === "acked")).toBe(true);
    const rows = await t.db.query<{ id: string; link_ok: boolean }>("select id, link_ok from consent_records where shop_id = $1 order by mirror_seq", [s.shopId]);
    expect(rows.map((r) => r.id)).toEqual(recs.map((r) => r.id));
    expect(rows.every((r) => r.link_ok)).toBe(true);
    expect((await flags(s.deviceId)).chain_break).toBe(false);
  });
});

describe("nightly hash anchor (TASK-061 cloud half)", () => {
  it("stores the anchor, compares it with the mirrored head and flags a mismatch", async () => {
    const s = await setupShop(t, h, "anc");
    const chain = new ChainBuilder(s.shopId);
    const recs = [chain.next({ member_id: nextId("m") }), chain.next({ member_id: nextId("m") })];
    await postEvents(h, s.deviceToken, recs.map((r) => ev("consent_record", r)));
    const ok = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-18", chain_head_hash: chain.head } });
    expect(ok.status).toBe(200);
    expect(ok.json).toEqual({ ok: true, match: true });
    let d = (await t.db.query<{ anchor_mismatch: boolean }>("select anchor_mismatch from devices where id = $1", [s.deviceId]))[0]!;
    expect(d.anchor_mismatch).toBe(false);

    const bad = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-19", chain_head_hash: `sha256:${"0".repeat(64)}` } });
    expect(bad.json).toEqual({ ok: true, match: false });
    d = (await t.db.query<{ anchor_mismatch: boolean }>("select anchor_mismatch from devices where id = $1", [s.deviceId]))[0]!;
    expect(d.anchor_mismatch).toBe(true);
    const anchors = await t.db.query("select to_char(date, 'YYYY-MM-DD') as date, match from hash_anchors where shop_id = $1 order by date", [s.shopId]);
    expect(anchors).toEqual([{ date: "2026-10-18", match: true }, { date: "2026-10-19", match: false }]);
    // One row per shop per day: a repeat for the same day replaces it.
    await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-19", chain_head_hash: chain.head } });
    expect(await t.db.query("select 1 from hash_anchors where shop_id = $1", [s.shopId])).toHaveLength(2);
    // An empty mirror anchors at genesis.
    const g = await setupShop(t, h, "ancg");
    const gen = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: g.deviceToken, body: { date: "2026-10-18", chain_head_hash: "sha256:genesis" } });
    expect(gen.json).toEqual({ ok: true, match: true });
    expect((await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: g.deviceToken, body: { date: "18-10-2026", chain_head_hash: "x" } })).status).toBe(400);
  });
});
