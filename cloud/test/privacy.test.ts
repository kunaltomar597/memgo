import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as fcmTokenRoute from "@/app/api/devices/fcm-token/route";
import * as exportRoute from "@/app/api/shops/[id]/consent-export/route";
import * as cardRoute from "@/app/api/visits/[id]/card/route";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";
import { fixture, postWebhook, unix } from "./helpers/whatsapp";

// Privacy invariants across a whole member lifecycle (T-P-09 for the cloud).
let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

describe("a full lifecycle leaves no PII in logs and no cloud-authored consent record", () => {
  it("enrol, deliver, thumbs-up, alert, card, STOP, export", async () => {
    const NAME = "Priyanka Zzyzx";
    const PHONE = "+919812345678";
    const NOTE = "Prefers the corner seat";
    const s = await setupShop(t, h, "priv");
    await call(fcmTokenRoute, "POST", { path: "/api/devices/fcm-token", token: s.owner.token, body: { shop_id: s.shopId, fcm_token: "fcm-privacy-token-01" } });
    const chain = new ChainBuilder(s.shopId);
    const e = enrolEvents(s.shopId, chain, { phone: PHONE, name: NAME });
    (e.events[1]!.payload as { notes: string }).notes = NOTE;
    await postEvents(h, s.deviceToken, e.events);
    const consentAfterEnrol = (await t.db.query("select id from consent_records")).length;
    const wamid = (await t.db.query<{ wamid: string }>("select wamid from whatsapp_messages where direction = 'out'"))[0]!.wamid;
    const from = PHONE.slice(1);
    await postWebhook(fixture("status_delivered", { wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: from }), "test-app-secret");
    await postWebhook(fixture("button_thumbs_up", { from, msg_id: "wamid.IN.PRIV1", ts: unix("2026-10-18T12:14:00Z"), context_wamid: wamid }), "test-app-secret");
    const visit = nextId("v");
    await postEvents(h, s.deviceToken, [
      ev("visit_summary", { id: visit, member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30", camera: "gate", score: 0.66, frames_agreed: 3, greeted: false, greeted_by: null, not_them: false }),
      ev("alert", { visit_id: visit, member_id: e.memberId, ts: "2026-10-19T11:00:00+05:30" }),
    ]);
    const card = await call(cardRoute, "GET", { path: `/api/visits/${visit}/card`, token: s.owner.token, params: { id: visit } });
    expect((card.json as unknown as { display_name: string }).display_name).toBe(NAME);
    await postWebhook(fixture("text_message", { from, msg_id: "wamid.IN.PRIV2", ts: unix("2026-10-18T12:15:00Z"), body: "STOP" }), "test-app-secret");
    await call(exportRoute, "POST", { path: `/api/shops/${s.shopId}/consent-export?format=pdf`, token: s.owner.token, params: { id: s.shopId } });

    // The cloud appended no consent record of its own through any of this.
    expect((await t.db.query("select id from consent_records")).length).toBe(consentAfterEnrol);
    // Logs carry ids only.
    const logs = allLogs();
    for (const leak of [NAME, "Priyanka", PHONE, from, "12345678", NOTE, "Gold necklace", "fcm-privacy-token-01", s.deviceToken, wamid.length > 0 ? "c0nv3rs4t10n1d" : ""]) {
      expect(logs, leak).not.toContain(leak);
    }
    expect(logs).toContain(e.memberId);
    // The plain number lives only in whatsapp_messages.phone_e164.
    const tables = await t.db.query<{ table_name: string }>("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'");
    for (const { table_name } of tables) {
      const dump = JSON.stringify(await t.db.query(`select * from ${table_name}`));
      if (table_name === "whatsapp_messages") continue;
      expect(dump, table_name).not.toContain(PHONE);
    }
    const wa = await t.db.query<{ phone_e164: string | null }>("select phone_e164 from whatsapp_messages where direction = 'out'");
    expect(wa[0]!.phone_e164).toBe(PHONE);
    // The FCM push carried ids only.
    for (const c of h.fcmCalls()) {
      expect(c.body).not.toContain(NAME);
      expect(c.body).not.toContain(NOTE);
    }
  });
});
