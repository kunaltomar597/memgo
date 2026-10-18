import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { jwtVerify } from "jose";
import * as fcmTokenRoute from "@/app/api/devices/fcm-token/route";
import { buildFcmMessage } from "@/lib/fcm";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, createOwner, enrolEvents, ev, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

// TASK-068: FCM relay, ids only.
let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

describe("POST /api/devices/fcm-token", () => {
  it("writes push_tokens for the signed-in owner of that shop only", async () => {
    const s = await setupShop(t, h, "tok");
    const r = await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: s.owner.token, body: { shop_id: s.shopId, fcm_token: "fcm:APA91b-owner-phone-token-1" } });
    expect(r.status).toBe(204);
    const rows = await t.db.query("select shop_id, user_id from push_tokens where fcm_token = 'fcm:APA91b-owner-phone-token-1'");
    expect(rows).toEqual([{ shop_id: s.shopId, user_id: s.owner.userId }]);
    // Re-registering the same token updates the row.
    await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: s.owner.token, body: { shop_id: s.shopId, fcm_token: "fcm:APA91b-owner-phone-token-1" } });
    expect(await t.db.query("select 1 from push_tokens where fcm_token = 'fcm:APA91b-owner-phone-token-1'")).toHaveLength(1);
    const stranger = await createOwner(t, h, "tokx");
    expect((await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: stranger.token, body: { shop_id: s.shopId, fcm_token: "fcm:APA91b-stranger-0001" } })).status).toBe(403);
    expect((await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", body: { shop_id: s.shopId, fcm_token: "fcm:APA91b-anon-00001" } })).status).toBe(401);
  });
});

describe("alert relay", () => {
  it("fans an alert out to every push token of the shop, high priority, with exactly shop_id, visit_id and member_id", async () => {
    const a = await setupShop(t, h, "fa");
    const b = await setupShop(t, h, "fb");
    for (const [s, n] of [[a, 2], [b, 1]] as const) {
      for (let i = 0; i < n; i++) {
        await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: s.owner.token, body: { shop_id: s.shopId, fcm_token: `fcm-${s.shopId}-${i}-token` } });
      }
    }
    const e = enrolEvents(a.shopId, new ChainBuilder(a.shopId), { phone: "+919800000901", name: "Secret Name" });
    await postEvents(h, a.deviceToken, e.events);
    const visitId = nextId("v");
    const before = h.fcmCalls().length;
    const alert = ev("alert", { visit_id: visitId, member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30" });
    await postEvents(h, a.deviceToken, [
      ev("visit_summary", { id: visitId, member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30", camera: "gate", score: 0.7, frames_agreed: 3, greeted: false, greeted_by: null, not_them: false }),
      alert,
    ]);
    const sent = h.fcmCalls().slice(before);
    expect(sent.map((c) => (JSON.parse(c.body) as { message: { token: string } }).message.token).sort()).toEqual([`fcm-${a.shopId}-0-token`, `fcm-${a.shopId}-1-token`]);
    for (const c of sent) {
      expect(c.url).toBe("https://fcm.googleapis.com/v1/projects/remu-test/messages:send");
      expect(c.headers.authorization).toBe("Bearer ya29.test-access");
      const msg = JSON.parse(c.body) as { message: Record<string, unknown> };
      expect(Object.keys(msg)).toEqual(["message"]);
      expect(Object.keys(msg.message).sort()).toEqual(["android", "data", "token"]);
      expect(msg.message.data).toEqual({ shop_id: a.shopId, visit_id: visitId, member_id: e.memberId });
      expect(Object.keys(msg.message.data as object).sort()).toEqual(["member_id", "shop_id", "visit_id"]);
      expect(msg.message.android).toEqual({ priority: "HIGH" });
      expect(c.body).not.toContain("Secret Name");
      expect(c.body).not.toContain("notification");
    }
    // Replaying the same alert event pushes nothing again.
    await postEvents(h, a.deviceToken, [alert]);
    expect(h.fcmCalls().length - before).toBe(2);
  });

  it("signs the OAuth assertion with the service account (RS256, messaging scope)", async () => {
    const tokenCall = h.calls.find((c) => c.url === "https://oauth2.googleapis.com/token");
    expect(tokenCall).toBeDefined();
    const params = new URLSearchParams(tokenCall!.body);
    expect(params.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    const { payload, protectedHeader } = await jwtVerify(params.get("assertion")!, h.fcm.publicKey, { audience: "https://oauth2.googleapis.com/token" });
    expect(protectedHeader.alg).toBe("RS256");
    expect(payload.iss).toBe("fcm-sender@remu-test.iam.gserviceaccount.com");
    expect(payload.scope).toBe("https://www.googleapis.com/auth/firebase.messaging");
    // The access token is cached: one exchange for every push so far.
    expect(h.calls.filter((c) => c.url === "https://oauth2.googleapis.com/token")).toHaveLength(1);
  });

  it("drops a token FCM reports as unregistered", async () => {
    const s = await setupShop(t, h, "fu");
    await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: s.owner.token, body: { shop_id: s.shopId, fcm_token: "fcm-dead-token-0001" } });
    h.fcm.unregistered.add("fcm-dead-token-0001");
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000902" });
    await postEvents(h, s.deviceToken, [...e.events, ev("alert", { visit_id: nextId("v"), member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30" })]);
    expect(await t.db.query("select 1 from push_tokens where fcm_token = 'fcm-dead-token-0001'")).toHaveLength(0);
  });

  it("buildFcmMessage carries nothing beyond the three ids even if given more", () => {
    const m = buildFcmMessage("t", { shop_id: "s", visit_id: "v", member_id: "m", display_name: "X" } as never);
    expect(m.message.data).toEqual({ shop_id: "s", visit_id: "v", member_id: "m" });
  });
});
