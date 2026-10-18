import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as metricsRoute from "@/app/api/ops/metrics/route";
import * as snapshotRoute from "@/app/api/ops/jobs/pilot-metrics/route";
import type { MetricsReport } from "@/lib/metrics";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, healthPayload, memberUpsert, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { fixture, postWebhook, unix } from "./helpers/whatsapp";

// TASK-127: pilot metrics per shop and in total.
let t: TestDb;
let h: TestHarness;
let S: Awaited<ReturnType<typeof setupShop>>;
const OPS = { "x-remu-ops-key": "test-ops-key" };

beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
  h.clock.set(new Date("2026-10-18T12:00:00.000Z"));
  S = await setupShop(t, h, "met");
  const chain = new ChainBuilder(S.shopId);
  // 3 face members (consent ts 17:42:11 IST = 12:12:11Z), 1 phone-only member (skip face).
  const faces = [1, 2, 3].map((i) => enrolEvents(S.shopId, chain, { phone: `+91980000300${i}` }));
  const phoneOnly = ev("member_upsert", memberUpsert(S.shopId, nextId("m"), { phone: "+919800003009", hasTemplate: false }));
  await postEvents(h, S.deviceToken, [...faces.flatMap((f) => f.events), phoneOnly]);
  const wamids = await t.db.query<{ wamid: string; member_id: string }>("select wamid, member_id from whatsapp_messages where direction = 'out' order by created_at, id");
  const from = (i: number) => `91980000300${i}`;
  // Member 1 delivered in 29 s, member 2 in 3 minutes, member 3 never.
  await postWebhook(fixture("status_delivered", { wamid: wamids[0]!.wamid, ts: unix("2026-10-18T12:12:40Z"), recipient: from(1) }), "test-app-secret");
  await postWebhook(fixture("status_delivered", { wamid: wamids[1]!.wamid, ts: unix("2026-10-18T12:15:11Z"), recipient: from(2) }), "test-app-secret");
  // Member 2 sends STOP; the hub deletes the template 20 minutes later and mirrors the chained record.
  await postWebhook(fixture("text_message", { from: from(2), msg_id: "wamid.IN.M1", ts: unix("2026-10-18T12:30:00Z"), body: "STOP" }), "test-app-secret");
  const del = chain.next({ member_id: faces[1]!.memberId, channel: "whatsapp_stop", withdrawn_at: "2026-10-18T18:00:00+05:30", template_deleted_at: "2026-10-18T18:20:00+05:30", cashier_id: null });
  // Visits: 4 alerts for member 1, 2 greeted, 1 not them.
  const visits = [0, 1, 2, 3].map((i) =>
    ev("visit_summary", { id: nextId("v"), member_id: faces[0]!.memberId, ts: `2026-10-1${8 + (i % 2)}T1${i}:00:00+05:30`, camera: "gate", score: 0.6, frames_agreed: 3, greeted: i < 2, greeted_by: null, not_them: i === 3 }),
  );
  // Health every 5 minutes for one hour on the 18th, two of them taken offline and still matching.
  const health = Array.from({ length: 12 }, (_, i) =>
    ev("health", healthPayload({ internet: i >= 10 ? false : true }), new Date(Date.UTC(2026, 9, 18, 11, i * 5) ).toISOString().replace(".000Z", "Z")),
  );
  await postEvents(h, S.deviceToken, [ev("consent_record", del), ...visits, ...health]);
  await t.db.query("insert into shop_pilot_facts (shop_id, hardware_cost_inr, setup_minutes) values ($1, 13850, 48)", [S.shopId]);
});
afterAll(async () => t.close());

async function report(): Promise<MetricsReport> {
  const r = await call(metricsRoute, "GET", { path: "/api/ops/metrics?from=2026-10-18&to=2026-10-19", headers: OPS });
  expect(r.status).toBe(200);
  return r.json as unknown as MetricsReport;
}

describe("GET /api/ops/metrics", () => {
  it("computes every success criterion per shop", async () => {
    const r = await report();
    expect(r.days).toBe(2);
    const m = r.shops.find((s) => s.scope === S.shopId)!.metrics;
    expect(m.face_membership_rate).toMatchObject({ numerator: 3, denominator: 4, value: 0.75, pass: true });
    expect(m.notices_delivered_within_2_min).toMatchObject({ numerator: 1, denominator: 3, pass: false });
    expect(m.alerts_within_3_s).toMatchObject({ value: null, pass: null });
    expect(m.not_them_per_day).toMatchObject({ numerator: 1, denominator: 2, value: 0.5, pass: true });
    expect(m.greeted_rate).toMatchObject({ numerator: 2, denominator: 4, value: 0.5, pass: true });
    expect(m.stored_photo_columns).toMatchObject({ value: 0, pass: true });
    expect(m.active_members_with_delivered_notice).toMatchObject({ numerator: 1, denominator: 1, value: 1, pass: true });
    expect(m.stop_deleted_within_1_h).toMatchObject({ numerator: 1, denominator: 1, value: 1, pass: true });
    expect(m.matching_through_outages).toMatchObject({ numerator: 2, denominator: 2, pass: true });
    expect(m.hub_uptime.numerator).toBe(12);
    expect(m.hardware_cost_inr).toMatchObject({ value: 13850, pass: true });
    expect(m.setup_minutes).toMatchObject({ value: 48, pass: true });
    expect(r.total.metrics.face_membership_rate).toMatchObject({ numerator: 3, denominator: 4 });
  });

  it("returns CSV with Accept: text/csv", async () => {
    const r = await call(metricsRoute, "GET", { path: "/api/ops/metrics?from=2026-10-18&to=2026-10-19", headers: { ...OPS, accept: "text/csv" } });
    expect(r.res.headers.get("content-type")).toBe("text/csv; charset=utf-8");
    const text = await r.res.text();
    const lines = text.trim().split("\r\n");
    expect(lines[0]).toBe("scope,metric,value,numerator,denominator,target,pass");
    expect(lines).toContain(`${S.shopId},face_membership_rate,0.75,3,4,>= 0.60,true`);
    expect(lines.some((l) => l.startsWith("total,greeted_rate,0.5,2,4"))).toBe(true);
  });

  it("validates the range", async () => {
    expect((await call(metricsRoute, "GET", { path: "/api/ops/metrics?from=2026-10-20&to=2026-10-18", headers: OPS })).status).toBe(400);
    expect((await call(metricsRoute, "GET", { path: "/api/ops/metrics?from=bad", headers: OPS })).status).toBe(400);
  });

  it("the nightly job stores a 60-day snapshot", async () => {
    const r = await call(snapshotRoute, "POST", { path: "/api/ops/jobs/pilot-metrics", headers: OPS, body: {} });
    expect(r.json).toMatchObject({ ok: true, from: "2026-08-19", to: "2026-10-17" });
    const rows = await t.db.query<{ metrics_json: MetricsReport }>("select metrics_json from pilot_metrics_snapshots");
    expect(rows).toHaveLength(1);
    expect(rows[0]!.metrics_json.days).toBe(60);
  });
});
