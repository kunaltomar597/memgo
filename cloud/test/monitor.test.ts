import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as hubsRoute from "@/app/api/ops/hubs/route";
import * as monitorRoute from "@/app/api/ops/jobs/hub-monitor/route";
import { createOpsNotifier } from "@/lib/opsNotifier";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, recordingFetch, type TestHarness } from "./helpers/deps";
import { ev, healthPayload, postEvents, provision, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

// TASK-119: hub monitoring and ops alerts.
let t: TestDb;
let h: TestHarness;
const OPS = { "x-remu-ops-key": "test-ops-key" };
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

const run = async () => (await call(monitorRoute, "POST", { path: "/api/ops/jobs/hub-monitor", headers: OPS, body: {} })).json as unknown as { checked: number; opened: number; resolved: number };

describe("hub monitor job", () => {
  it("alerts once when a hub is silent for 15 minutes or locked, and resolves when it recovers", async () => {
    h.clock.set(new Date("2026-10-18T12:00:00.000Z"));
    const s = await setupShop(t, h, "mon");
    await postEvents(h, s.deviceToken, [ev("health", healthPayload())]);
    expect(await run()).toEqual({ checked: 1, opened: 0, resolved: 0 });

    h.clock.advance(16 * 60_000);
    expect((await run()).opened).toBe(1);
    expect(h.opsAlerts.at(-1)).toMatchObject({ kind: "offline", state: "opened", device_id: s.deviceId, shop_id: s.shopId, since: "2026-10-18T17:30:00+05:30" });
    h.clock.advance(5 * 60_000);
    expect((await run()).opened).toBe(0); // no repeat while still offline

    await postEvents(h, s.deviceToken, [ev("health", healthPayload({ locked: true }))]);
    const r = await run();
    expect(r).toMatchObject({ opened: 1, resolved: 1 });
    expect(h.opsAlerts.slice(-2).map((a) => `${a.kind}:${a.state}`).sort()).toEqual(["locked:opened", "offline:resolved"]);

    await postEvents(h, s.deviceToken, [ev("health", healthPayload({ locked: false }))]);
    expect((await run()).resolved).toBe(1);
    const open = await t.db.query("select 1 from ops_alerts where resolved_at is null");
    expect(open).toHaveLength(0);
    // Ids only in the notification.
    for (const a of h.opsAlerts) expect(Object.keys(a).sort()).toEqual(["device_id", "kind", "shop_id", "since", "state"]);
  });

  it("GET /api/ops/hubs lists every device with its status", async () => {
    await provision(h, "550055", "pi5-unpaired-1");
    const r = await call(hubsRoute, "GET", { path: "/api/ops/hubs", headers: OPS });
    expect(r.status).toBe(200);
    const hubs = r.json as unknown as Record<string, unknown>[];
    expect(hubs.length).toBe(2);
    expect(hubs.find((x) => x.serial === "pi5-unpaired-1")).toMatchObject({ status: "provisioned", online: false, shop_id: null });
    const paired = hubs.find((x) => x.status === "paired")!;
    expect(paired).toMatchObject({ online: true, locked: false, chain_break: false, anchor_mismatch: false, generation: 1, open_alerts: [] });
    expect(JSON.stringify(hubs)).not.toMatch(/token_hash|pairing_code|unlock/);
  });

  it("the default notifier posts ids-only JSON to OPS_ALERT_WEBHOOK_URL", async () => {
    const { impl, calls } = recordingFetch({ "ops-alerts.example.test": () => new Response(null, { status: 204 }) });
    const n = createOpsNotifier("https://ops-alerts.example.test/hook", impl);
    await n.notify({ kind: "offline", state: "opened", device_id: "d_1", shop_id: "s", since: "2026-10-18T17:30:00+05:30" });
    expect(JSON.parse(calls[0]!.body)).toEqual({ source: "remu-cloud", kind: "offline", state: "opened", device_id: "d_1", shop_id: "s", since: "2026-10-18T17:30:00+05:30" });
  });
});
