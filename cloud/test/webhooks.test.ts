import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as inboxRoute from "@/app/api/hub/inbox/route";
import * as webhookRoute from "@/app/api/webhooks/whatsapp/route";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, memberUpsert, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";
import { fixture, postWebhook, sign, unix } from "./helpers/whatsapp";

let t: TestDb;
let h: TestHarness;
const SECRET = "test-app-secret";
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

type Msg = { id: string; type: string; payload: Record<string, unknown> };
async function inbox(token: string, after = "0"): Promise<Msg[]> {
  const r = await call(inboxRoute, "GET", { path: `/api/hub/inbox?after=${after}`, token });
  return (r.json as unknown as { messages: Msg[] }).messages;
}

/** A shop with one enrolled pending member whose notice was sent. */
async function enrolled(label: string, phone: string) {
  const s = await setupShop(t, h, label);
  const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone, ts: "2026-10-18T17:42:11+05:30" });
  await postEvents(h, s.deviceToken, e.events);
  const row = (await t.db.query<{ wamid: string }>("select wamid from whatsapp_messages where member_id = $1 and direction = 'out'", [e.memberId]))[0]!;
  return { ...s, memberId: e.memberId, consentId: e.record.id, wamid: row.wamid, phone, from: phone.slice(1) };
}
const memberStatus = async (id: string) => (await t.db.query<{ status: string }>("select status from members where id = $1", [id]))[0]!.status;
const waRow = async (wamid: string) =>
  (await t.db.query<Record<string, unknown>>("select status, sent_at, delivered_at, read_at, delivery_seconds, raw_webhook_json, thumbs_up_at from whatsapp_messages where wamid = $1", [wamid]))[0]!;

describe("verification and signatures (TASK-064)", () => {
  it("answers the Meta challenge only with WHATSAPP_VERIFY_TOKEN", async () => {
    const ok = await call(webhookRoute, "GET", { path: "/api/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=test-verify-token&hub.challenge=1158201444" });
    expect(ok.status).toBe(200);
    expect(await ok.res.text()).toBe("1158201444");
    const bad = await call(webhookRoute, "GET", { path: "/api/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=1158201444" });
    expect(bad.status).toBe(403);
  });

  it("a bad or missing X-Hub-Signature-256 gets 401, writes nothing and does not log the body", async () => {
    const s = await enrolled("sig", "+919800000301");
    const raw = fixture("status_delivered", { wamid: s.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: s.from });
    const forged = await postWebhook(raw, SECRET, sign(raw, "wrong-secret"));
    expect(forged.status).toBe(401);
    const missing = await call(webhookRoute, "POST", { path: "/api/webhooks/whatsapp", raw, headers: { "content-type": "application/json" } });
    expect(missing.status).toBe(401);
    const tamperedBody = await postWebhook(raw.replace("delivered", "read"), SECRET, sign(raw, SECRET));
    expect(tamperedBody.status).toBe(401);
    expect(await memberStatus(s.memberId)).toBe("pending_notice");
    expect((await waRow(s.wamid)).delivered_at).toBeNull();
    expect(allLogs()).not.toContain(s.wamid.slice(0, 0) + "c0nv3rs4t10n1d");
  });
});

describe("statuses and activation (TASK-064, TASK-065)", () => {
  it("sent, delivered and read update whatsapp_messages with raw JSON; only delivered activates", async () => {
    const s = await enrolled("st", "+919800000302");
    const cursor0 = (await inbox(s.deviceToken)).at(-1)!.id;
    await postWebhook(fixture("status_sent", { wamid: s.wamid, ts: unix("2026-10-18T12:12:20Z"), recipient: s.from }), SECRET);
    let row = await waRow(s.wamid);
    expect(row.status).toBe("sent");
    expect(row.sent_at).not.toBeNull();

    // read without delivered never activates (read receipts can be switched off)
    await postWebhook(fixture("status_read", { wamid: s.wamid, ts: unix("2026-10-18T12:12:50Z"), recipient: s.from }), SECRET);
    expect(await memberStatus(s.memberId)).toBe("pending_notice");
    row = await waRow(s.wamid);
    expect(row.status).toBe("read");

    const delivered = fixture("status_delivered", { wamid: s.wamid, ts: unix("2026-10-18T12:12:40Z"), recipient: s.from });
    const r = await postWebhook(delivered, SECRET);
    expect(r.status).toBe(200);
    expect(await memberStatus(s.memberId)).toBe("active");
    row = await waRow(s.wamid);
    expect(row.status).toBe("read"); // status stays at the furthest receipt
    expect(new Date(row.delivered_at as string).toISOString()).toBe("2026-10-18T12:12:40.000Z");
    // consent ts 17:42:11+05:30 = 12:12:11Z, so 29 seconds to delivery
    expect(row.delivery_seconds).toBe(29);
    expect((row.raw_webhook_json as unknown[]).map((x) => (x as { status: string }).status)).toEqual(["sent", "read", "delivered"]);

    const msgs = await inbox(s.deviceToken, cursor0);
    expect(msgs.map((m) => m.type)).toEqual(["notice_status", "member_status", "notice_status"]);
    expect(msgs[1]!.payload).toEqual({ member_id: s.memberId, status: "active", reason: "notice_delivered", at: "2026-10-18T17:42:40+05:30" });
    expect(msgs[2]!.payload).toEqual({
      member_id: s.memberId,
      consent_record_id: s.consentId,
      wamid: s.wamid,
      whatsapp_status: "delivered",
      status_ts: "2026-10-18T17:42:40+05:30",
      delivered_at: "2026-10-18T17:42:40+05:30",
    });

    // The same webhook twice changes nothing twice.
    const consentCount = (await t.db.query("select 1 from consent_records")).length;
    await postWebhook(delivered, SECRET);
    expect(await inbox(s.deviceToken, msgs.at(-1)!.id)).toEqual([]);
    expect(((await waRow(s.wamid)).raw_webhook_json as unknown[]).length).toBe(3);
    // The cloud never appends a consent record of its own.
    expect((await t.db.query("select 1 from consent_records")).length).toBe(consentCount);
  });

  it("a failed status leaves the member pending_notice and flags the notice", async () => {
    const s = await enrolled("fail", "+919800000303");
    await postWebhook(fixture("status_failed", { wamid: s.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: s.from }), SECRET);
    expect(await memberStatus(s.memberId)).toBe("pending_notice");
    const m = (await t.db.query<{ notice_failed: boolean }>("select notice_failed from members where id = $1", [s.memberId]))[0]!;
    expect(m.notice_failed).toBe(true);
    const msgs = await inbox(s.deviceToken);
    expect(msgs.at(-1)!.payload, JSON.stringify(msgs)).toMatchObject({ whatsapp_status: "failed", delivered_at: null });
  });

  it("hub offline when delivered arrives: the activation waits in the inbox (T-C-08)", async () => {
    const s = await enrolled("off", "+919800000304");
    const first = await inbox(s.deviceToken);
    const cursor = first.at(-1)!.id;
    await postWebhook(fixture("status_delivered", { wamid: s.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: s.from }), SECRET);
    h.clock.advance(2 * 3600_000); // the hub polls again two hours later
    const later = await inbox(s.deviceToken, cursor);
    expect(later.map((m) => m.type)).toContain("member_status");
    // Acknowledging up to the cursor clears it.
    expect(await inbox(s.deviceToken, later.at(-1)!.id)).toEqual([]);
  });

  it("an unknown wamid is ignored", async () => {
    const r = await postWebhook(fixture("status_delivered", { wamid: "wamid.UNKNOWN", ts: unix("2026-10-18T12:13:00Z"), recipient: "919800000999" }), SECRET);
    expect(r.status).toBe(200);
  });
});

describe("thumbs-up and STOP (TASK-066)", () => {
  it("a 👍 button reply is recorded and queues thumbs_up with its timestamp", async () => {
    const s = await enrolled("tu", "+919800000305");
    const raw = fixture("button_thumbs_up", { from: s.from, msg_id: "wamid.IN.TU1", ts: unix("2026-10-18T12:20:00Z"), context_wamid: s.wamid });
    await postWebhook(raw, SECRET);
    await postWebhook(raw, SECRET); // repeated webhook
    expect(new Date((await waRow(s.wamid)).thumbs_up_at as string).toISOString()).toBe("2026-10-18T12:20:00.000Z");
    const inRow = (await t.db.query<Record<string, unknown>>("select kind, member_id, shop_id from whatsapp_messages where wamid = 'wamid.IN.TU1'"))[0];
    expect(inRow).toEqual({ kind: "thumbs_up", member_id: s.memberId, shop_id: s.shopId });
    const tu = (await inbox(s.deviceToken)).filter((m) => m.type === "thumbs_up");
    expect(tu).toEqual([expect.objectContaining({ payload: { member_id: s.memberId, at: "2026-10-18T17:50:00+05:30" } })]);
    expect(await memberStatus(s.memberId)).toBe("pending_notice"); // a thumbs-up never activates
  });

  for (const body of ["STOP", "stop", " Stop "]) {
    it(`a text "${body}" withdraws the sender's members by phone_hash and queues member_status`, async () => {
      const phone = `+9198000004${String(body.length).padStart(2, "0")}${body === "stop" ? "1" : "2"}`.slice(0, 13);
      const s = await enrolled(`stop${body.trim()}${body.length}`, phone);
      const cursor = (await inbox(s.deviceToken)).at(-1)!.id;
      const msgId = `wamid.IN.STOP.${body.length}.${body.trim()}`;
      await postWebhook(fixture("text_message", { from: s.from, msg_id: msgId, ts: unix("2026-10-18T13:00:00Z"), body }), SECRET);
      expect(await memberStatus(s.memberId)).toBe("withdrawn");
      const msgs = await inbox(s.deviceToken, cursor);
      expect(msgs).toEqual([expect.objectContaining({ type: "member_status", payload: { member_id: s.memberId, status: "withdrawn", reason: "stop", at: "2026-10-18T18:30:00+05:30" } })]);
      expect(allLogs()).not.toContain(s.from);
    });
  }

  it("STOP uses the contract phone_hash: lowercase hex sha256 of '+' plus the webhook sender", async () => {
    const s = await setupShop(t, h, "stopvec");
    const memberId = nextId("m");
    // The member arrives from the hub with the contract vector hash, not via our helper.
    const up = memberUpsert(s.shopId, memberId, { phone: "+919876543210" });
    expect(up.phone_hash).toBe("f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e");
    await postEvents(h, s.deviceToken, [ev("member_upsert", up)]);
    await postWebhook(fixture("text_message", { from: "919876543210", msg_id: "wamid.IN.VEC", ts: unix("2026-10-18T13:00:00Z"), body: "STOP" }), SECRET);
    expect(await memberStatus(memberId)).toBe("withdrawn");
    const stored = (await t.db.query<{ phone_hash: string }>("select phone_hash from whatsapp_messages where wamid = 'wamid.IN.VEC'"))[0]!;
    expect(stored.phone_hash).toBe("f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e");
  });

  it("a plain STOP withdraws the number in every shop; a quoted STOP only in the shop of that notice", async () => {
    const phone = "+919800000501";
    const a = await enrolled("multiA", phone);
    const b = await enrolled("multiB", phone);
    // Quoted reply to shop A's notice: only A.
    await postWebhook(fixture("text_reply", { from: a.from, msg_id: "wamid.IN.Q1", ts: unix("2026-10-18T13:00:00Z"), body: "STOP", context_wamid: a.wamid }), SECRET);
    expect(await memberStatus(a.memberId)).toBe("withdrawn");
    expect(await memberStatus(b.memberId)).toBe("pending_notice");
    // Plain STOP: every shop that has the number.
    await postWebhook(fixture("text_message", { from: b.from, msg_id: "wamid.IN.Q2", ts: unix("2026-10-18T13:05:00Z"), body: "STOP" }), SECRET);
    expect(await memberStatus(b.memberId)).toBe("withdrawn");
  });

  it("a STOP before the member row arrives is stored and applied, with its own time, when the row arrives", async () => {
    const s = await setupShop(t, h, "pendstop");
    const phone = "+919800000601";
    await postWebhook(fixture("text_message", { from: phone.slice(1), msg_id: "wamid.IN.P1", ts: unix("2026-10-18T12:30:00Z"), body: "STOP" }), SECRET);
    const pending = (await t.db.query<{ applied_at: unknown }>("select applied_at from whatsapp_messages where wamid = 'wamid.IN.P1'"))[0]!;
    expect(pending.applied_at).toBeNull();
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone, ts: "2026-10-18T17:40:00+05:30" });
    await postEvents(h, s.deviceToken, e.events);
    expect(await memberStatus(e.memberId)).toBe("withdrawn");
    const msgs = await inbox(s.deviceToken);
    expect(msgs.find((m) => m.type === "member_status")?.payload).toEqual({ member_id: e.memberId, status: "withdrawn", reason: "stop", at: "2026-10-18T18:00:00+05:30" });
    const tr = (await t.db.query<{ at: Date }>("select at from member_transitions where member_id = $1", [e.memberId]))[0]!;
    expect(new Date(tr.at).toISOString()).toBe("2026-10-18T12:30:00.000Z");
  });

  it("any other text is stored and ignored; media messages are reduced to type and id", async () => {
    const s = await enrolled("other", "+919800000701");
    await postWebhook(fixture("text_message", { from: s.from, msg_id: "wamid.IN.HELLO", ts: unix("2026-10-18T13:00:00Z"), body: "Please stop by tomorrow" }), SECRET);
    await postWebhook(fixture("image_message", { from: s.from, msg_id: "wamid.IN.IMG", ts: unix("2026-10-18T13:01:00Z") }), SECRET);
    expect(await memberStatus(s.memberId)).toBe("pending_notice");
    const rows = await t.db.query<{ kind: string; raw_webhook_json: unknown }>("select kind, raw_webhook_json from whatsapp_messages where wamid in ('wamid.IN.HELLO', 'wamid.IN.IMG') order by wamid");
    expect(rows.map((r) => r.kind)).toEqual(["text", "other"]);
    expect(rows[1]!.raw_webhook_json).toEqual([{ id: "wamid.IN.IMG", type: "image", timestamp: unix("2026-10-18T13:01:00Z") }]);
    expect(JSON.stringify(rows[1])).not.toContain("image/jpeg");
  });
});
