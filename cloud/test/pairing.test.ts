import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as claimRoute from "@/app/api/devices/claim/route";
import * as pairRoute from "@/app/api/devices/pair/route";
import * as revokeRoute from "@/app/api/devices/[id]/revoke/route";
import * as unlockRoute from "@/app/api/devices/unlock/route";
import * as inboxRoute from "@/app/api/hub/inbox/route";
import * as opsDevicesRoute from "@/app/api/ops/devices/route";
import { sha256Hex } from "@/lib/auth";
import { deriveUnlockToken, unpairDevice } from "@/lib/devices";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { createOwner, createShop, healthPayload, ev, postEvents, provision, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";

let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

const OPS = () => ({ "x-remu-ops-key": h.deps.env.opsApiKey! });

describe("POST /api/ops/devices", () => {
  it("registers a provisioned hub once, behind the ops key", async () => {
    const r = await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers: OPS(), body: { pairing_code: "k7qm-2xpa", serial: "pi5-abc123" } });
    expect(r.status).toBe(201);
    const id = (r.json as unknown as { device_id: string }).device_id;
    expect(id).toMatch(/^d_[0-9A-Z]{26}$/);
    const row = (await t.db.query<Record<string, unknown>>("select pairing_code, status, generation, shop_id from devices where id = $1", [id]))[0];
    expect(row).toEqual({ pairing_code: "K7QM-2XPA", status: "provisioned", generation: 1, shop_id: null });
    const dup = await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers: OPS(), body: { pairing_code: "K7QM-2XPA", serial: "pi5-other" } });
    expect(dup.status).toBe(409);
    for (const headers of [{}, { "x-remu-ops-key": "wrong" }] as Record<string, string>[]) {
      expect((await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers, body: { pairing_code: "123456", serial: "pi5-x1" } })).status).toBe(401);
    }
  });

  it("fails closed when OPS_API_KEY is not configured", async () => {
    h.deps.env.opsApiKey = undefined;
    try {
      expect((await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers: { "x-remu-ops-key": "" }, body: { pairing_code: "123457", serial: "pi5-x2" } })).status).toBe(401);
    } finally {
      h.deps.env.opsApiKey = "test-ops-key";
    }
  });
});

describe("pairing and the claim poll (TASK-057)", () => {
  it("claim is pending before pairing, then returns the token exactly once; only its hash is stored", async () => {
    const owner = await createOwner(t, h, "pair1");
    const shopId = await createShop(h, owner.token);
    const p = await provision(h, "482913", "pi5-serial-0001");
    const before = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "482913", serial: "pi5-serial-0001" } });
    expect(before.status).toBe(202);
    expect(before.json).toEqual({ status: "pending" });

    const pr = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: "482913" } });
    expect(pr.status).toBe(200);
    expect(pr.json).toEqual({ device_id: p.deviceId }); // the owner app never receives the device token

    const after = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "482913", serial: "pi5-serial-0001" } });
    expect(after.status).toBe(200);
    const body = after.json as unknown as { status: string; device_token: string; device_id: string; shop_id: string };
    expect(body).toMatchObject({ status: "paired", device_id: p.deviceId, shop_id: shopId });
    expect(body.device_token.length).toBeGreaterThanOrEqual(43);
    const row = (await t.db.query<{ token_hash: string; last_seen_at: Date }>("select token_hash, last_seen_at from devices where id = $1", [p.deviceId]))[0]!;
    expect(row.token_hash).toBe(sha256Hex(body.device_token));
    expect(row.last_seen_at).not.toBeNull();
    const dump = JSON.stringify(await t.db.query("select * from devices"));
    expect(dump).not.toContain(body.device_token);
    expect(allLogs()).not.toContain(body.device_token);

    const again = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "482913", serial: "pi5-serial-0001" } });
    expect(again.status).toBe(409);

    // The pairing queued the config message for the hub.
    const inbox = await call(inboxRoute, "GET", { path: "/api/hub/inbox", token: body.device_token });
    expect((inbox.json as unknown as { messages: { type: string; payload: unknown }[] }).messages[0]).toMatchObject({
      type: "config",
      payload: { shop_name: "Test Jewellers", notice_language: "en", script_version: "hi-IN-v1.0", notice_version: "wa-v1.0" },
    });
  });

  it("a used code cannot be reused, also not by another shop", async () => {
    const a = await setupShop(t, h, "pairA");
    const other = await createOwner(t, h, "pairB");
    const otherShop = await createShop(h, other.token, "Other Sarees", "Surat");
    const reuse = await call(pairRoute, "POST", { path: "/api/devices/pair", token: a.owner.token, body: { shop_id: a.shopId, pairing_code: a.code } });
    expect(reuse.status).toBe(409);
    const steal = await call(pairRoute, "POST", { path: "/api/devices/pair", token: other.token, body: { shop_id: otherShop, pairing_code: a.code } });
    expect(steal.status).toBe(409);
    const row = (await t.db.query<{ shop_id: string }>("select shop_id from devices where id = $1", [a.deviceId]))[0]!;
    expect(row.shop_id).toBe(a.shopId);
    // Pairing into a shop the caller does not own is forbidden.
    const p = await provision(h);
    const foreign = await call(pairRoute, "POST", { path: "/api/devices/pair", token: other.token, body: { shop_id: a.shopId, pairing_code: p.code } });
    expect(foreign.status).toBe(403);
  });

  it("wrong codes get 401, write nothing, and are rate limited", async () => {
    const owner = await createOwner(t, h, "pairRL");
    const shopId = await createShop(h, owner.token);
    const p = await provision(h);
    for (let i = 0; i < 5; i++) {
      const r = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: `99999${i}` } });
      expect(r.status).toBe(401);
    }
    const limited = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: p.code } });
    expect(limited.status).toBe(429);
    const row = (await t.db.query<{ status: string }>("select status from devices where id = $1", [p.deviceId]))[0]!;
    expect(row.status).toBe("provisioned");
    h.clock.advance(61_000);
    const ok = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: p.code } });
    expect(ok.status).toBe(200);
    expect(allLogs()).not.toContain("999990");
  });

  it("claim with a wrong serial or code is refused and rate limited per address", async () => {
    const p = await provision(h, "771100", "pi5-serial-7711");
    const wrong = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "771100", serial: "pi5-serial-0000" }, headers: { "x-forwarded-for": "203.0.113.7" } });
    expect(wrong.status).toBe(401);
    for (let i = 0; i < 9; i++) {
      await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "000000", serial: "pi5-serial-0000" }, headers: { "x-forwarded-for": "203.0.113.7" } });
    }
    const limited = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "771100", serial: "pi5-serial-7711" }, headers: { "x-forwarded-for": "203.0.113.7" } });
    expect(limited.status).toBe(429);
    const elsewhere = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "771100", serial: "pi5-serial-7711" }, headers: { "x-forwarded-for": "198.51.100.9" } });
    expect(elsewhere.status).toBe(202);
    expect(p.deviceId).toBeTruthy();
  });
});

describe("data unlock token (TASK-100)", () => {
  it("paired: returns HMAC(HUB_PAIRING_SECRET, id:generation) and stores only its hash", async () => {
    const s = await setupShop(t, h, "unl1");
    const r = await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: s.deviceToken });
    expect(r.status).toBe(200);
    const token = (r.json as unknown as { unlock_token: string }).unlock_token;
    expect(token).toBe(deriveUnlockToken(h.deps.env.hubPairingSecret!, s.deviceId, 1));
    expect(Buffer.from(token, "base64url")).toHaveLength(32);
    const row = (await t.db.query<{ data_unlock_token_hash: string }>("select data_unlock_token_hash from devices where id = $1", [s.deviceId]))[0]!;
    expect(row.data_unlock_token_hash).toBe(sha256Hex(token));
    expect(JSON.stringify(await t.db.query("select * from devices"))).not.toContain(token);
    expect(allLogs()).not.toContain(token);
    const r2 = await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: s.deviceToken });
    expect((r2.json as unknown as { unlock_token: string }).unlock_token).toBe(token); // stable within a generation
  });

  it("revoked: refuses, bumps the generation and queues device_revoked", async () => {
    const s = await setupShop(t, h, "unl2");
    const before = (await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: s.deviceToken })).json as unknown as { unlock_token: string };
    const other = await createOwner(t, h, "unl2x");
    expect((await call(revokeRoute, "POST", { path: `/api/devices/${s.deviceId}/revoke`, token: other.token, params: { id: s.deviceId } })).status).toBe(403);
    const rv = await call(revokeRoute, "POST", { path: `/api/devices/${s.deviceId}/revoke`, token: s.owner.token, params: { id: s.deviceId } });
    expect(rv.status).toBe(204);
    const r = await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: s.deviceToken });
    expect(r.status).toBe(403);
    const row = (await t.db.query<{ generation: number; status: string; data_unlock_token_hash: string | null }>("select generation, status, data_unlock_token_hash from devices where id = $1", [s.deviceId]))[0]!;
    expect(row).toEqual({ generation: 2, status: "revoked", data_unlock_token_hash: null });
    expect(deriveUnlockToken(h.deps.env.hubPairingSecret!, s.deviceId, 2)).not.toBe(before.unlock_token);
    // The revoked hub can still read its inbox and learns device_revoked; it can no longer post events.
    const inbox = await call(inboxRoute, "GET", { path: "/api/hub/inbox", token: s.deviceToken });
    expect((inbox.json as unknown as { messages: { type: string }[] }).messages.map((m) => m.type)).toContain("device_revoked");
    const evs = await postEvents(h, s.deviceToken, [ev("health", healthPayload())]);
    expect(evs.status).toBe(403);
  });

  it("unpaired: refuses (the device token no longer authenticates)", async () => {
    const s = await setupShop(t, h, "unl3");
    await unpairDevice(t.db, s.deviceId);
    const r = await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: s.deviceToken });
    expect(r.status).toBe(401);
    const row = (await t.db.query<{ generation: number }>("select generation from devices where id = $1", [s.deviceId]))[0]!;
    expect(row.generation).toBe(2);
  });

  it("no token, wrong token: 401", async () => {
    expect((await call(unlockRoute, "POST", { path: "/api/devices/unlock" })).status).toBe(401);
    expect((await call(unlockRoute, "POST", { path: "/api/devices/unlock", token: "not-a-device-token" })).status).toBe(401);
  });
});
