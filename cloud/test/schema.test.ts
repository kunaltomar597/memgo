import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createTestDb, type TestDb } from "./helpers/db";
import * as healthRoute from "@/app/api/health/route";
import { call } from "./helpers/http";

// TASK-003, TASK-055: the skeleton route and the mirror schema.
let t: TestDb;
beforeAll(async () => {
  t = await createTestDb();
});
afterAll(async () => t.close());

const MIRROR_TABLES = [
  "consent_records", "devices", "hash_anchors", "households", "members", "purchases", "push_tokens",
  "shops", "staff", "visits", "whatsapp_messages",
];
const CLOUD_TABLES = [
  "device_health_log", "export_log", "hub_events", "inbox_messages", "member_transitions", "ops_alerts",
  "pilot_metrics_snapshots", "rate_limit_hits", "shop_pilot_facts",
];

describe("GET /api/health", () => {
  it("returns ok", async () => {
    const r = await call(healthRoute, "GET", { path: "/api/health" });
    expect(r.status).toBe(200);
    expect(r.json).toEqual({ ok: true });
  });
});

describe("migrations", () => {
  it("create exactly the expected tables", async () => {
    const rows = await t.db.query<{ table_name: string }>(
      "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1",
    );
    expect(rows.map((r) => r.table_name)).toEqual([...MIRROR_TABLES, ...CLOUD_TABLES].sort());
  });

  it("visits has no clip_id and members has no phone_enc", async () => {
    const cols = await t.db.query<{ table_name: string; column_name: string }>(
      "select table_name, column_name from information_schema.columns where table_schema = 'public'",
    );
    const names = cols.map((c) => `${c.table_name}.${c.column_name}`);
    expect(names).not.toContain("visits.clip_id");
    expect(names).not.toContain("members.phone_enc");
    expect(names).toContain("push_tokens.fcm_token");
    expect(names).toContain("inbox_messages.payload");
    for (const c of ["pairing_code", "token_hash", "hub_version", "last_seen_at", "data_unlock_token_hash"]) {
      expect(names).toContain(`devices.${c}`);
    }
  });

  it("enables RLS on every table (T-P-02 schema half: no bytea, no biometric column names)", async () => {
    const rows = await t.db.query<{ relname: string; relrowsecurity: boolean }>(
      "select c.relname, c.relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r'",
    );
    expect(rows.length).toBe(MIRROR_TABLES.length + CLOUD_TABLES.length);
    for (const r of rows) expect(r.relrowsecurity, r.relname).toBe(true);
    const bad = await t.db.query(
      `select table_name, column_name from information_schema.columns where table_schema = 'public'
       and (data_type = 'bytea' or column_name in ('vec_blob', 'template', 'image', 'clip', 'crop', 'embedding'))`,
    );
    expect(bad).toEqual([]);
  });

  it("anon can read nothing, including consent_records", async () => {
    for (const table of [...MIRROR_TABLES, ...CLOUD_TABLES]) {
      await expect(t.asRole("anon", (q) => q.query(`select * from ${table}`)), table).rejects.toThrow(/permission denied/);
    }
  });

  it("no role may UPDATE, DELETE or TRUNCATE consent_records", async () => {
    await t.db.query("insert into shops (id, name, city) values ('s1', 'S', 'C')");
    await t.db.query(
      `insert into consent_records (id, shop_id, member_id, ts, channel, script_version, notice_version, purposes_json,
       notice_delivery_json, prev_hash, record_hash, hash_ok, link_ok)
       values ('c_1', 's1', 'm_1', '2026-10-18T17:42:11+05:30', 'verbal_at_counter', 'hi-IN-v1.0', 'wa-v1.0', '{}', '{}',
       'sha256:genesis', 'sha256:x', true, true)`,
    );
    for (const role of ["anon", "authenticated", "service_role"]) {
      const priv = await t.db.query<{ u: boolean; d: boolean; t: boolean }>(
        `select has_table_privilege($1, 'public.consent_records', 'UPDATE') as u,
                has_table_privilege($1, 'public.consent_records', 'DELETE') as d,
                has_table_privilege($1, 'public.consent_records', 'TRUNCATE') as t`,
        [role],
      );
      expect(priv[0], role).toEqual({ u: false, d: false, t: false });
      await expect(t.asRole(role as "anon", (q) => q.query("update consent_records set bill_no = 'x'"))).rejects.toThrow();
      await expect(t.asRole(role as "anon", (q) => q.query("delete from consent_records"))).rejects.toThrow();
    }
    // Even the table owner (the server's DATABASE_URL role) is refused by the triggers.
    await expect(t.db.query("update consent_records set bill_no = 'x'")).rejects.toThrow(/append-only/);
    await expect(t.db.query("delete from consent_records")).rejects.toThrow(/append-only/);
    await expect(t.db.query("truncate consent_records")).rejects.toThrow(/append-only/);
    const left = await t.db.query("select * from consent_records");
    expect(left).toHaveLength(1);
  });

  it("the members trigger refuses active without notice delivery and leaving terminal states", async () => {
    await t.db.query(
      `insert into members (id, shop_id, display_name, phone_hash, status, member_since, created_at, updated_at)
       values ('m_t1', 's1', 'X', 'ab', 'pending_notice', '2026-10-18', now(), now())`,
    );
    await expect(t.db.query("update members set status = 'active' where id = 'm_t1'")).rejects.toThrow(/notice delivery/);
    await expect(
      t.db.query(
        `insert into members (id, shop_id, display_name, phone_hash, status, member_since, created_at, updated_at)
         values ('m_t2', 's1', 'X', 'ab', 'active', '2026-10-18', now(), now())`,
      ),
    ).rejects.toThrow(/notice delivery/);
    await t.db.query("update members set status = 'withdrawn' where id = 'm_t1'");
    await expect(t.db.query("update members set status = 'pending_notice' where id = 'm_t1'")).rejects.toThrow(/terminal/);
  });

  it("the cron migration is a no-op without pg_cron, and the ops job caller is not callable by clients", async () => {
    const fn = await t.db.query("select 1 from pg_proc where proname = 'remu_call_ops_job'");
    expect(fn).toHaveLength(1);
    for (const role of ["anon", "authenticated"]) {
      const r = await t.db.query<{ x: boolean }>("select has_function_privilege($1, 'public.remu_call_ops_job(text)', 'EXECUTE') as x", [role]);
      expect(r[0]!.x).toBe(false);
    }
  });
});
