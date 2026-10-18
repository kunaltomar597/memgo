import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as inboxRoute from "@/app/api/hub/inbox/route";
import { createWhatsAppClient, THUMBS_UP } from "@/lib/whatsapp";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, recordingFetch, TEST_ENV, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";

describe("WhatsApp Cloud API client (TASK-062)", () => {
  it("sends the approved template by name with shop_name, first_name, date and the 👍 quick reply", async () => {
    const { impl, calls } = recordingFetch({
      "graph.facebook.com": () => Response.json({ messaging_product: "whatsapp", messages: [{ id: "wamid.ABC" }] }),
    });
    const wa = createWhatsAppClient(
      { phoneNumberId: "100000000000001", accessToken: "tok", templateEn: "remu_member_notice_en_v1", templateHi: "remu_member_notice_hi_v1", graphVersion: "v21.0" },
      impl,
    );
    const r = await wa.sendNotice({ to: "+919800000001", language: "en", shopName: "Test Jewellers", firstName: "Asha", date: "2026-10-18" });
    expect(r).toEqual({ wamid: "wamid.ABC" });
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe("https://graph.facebook.com/v21.0/100000000000001/messages");
    expect(calls[0]!.method).toBe("POST");
    expect(calls[0]!.headers.authorization).toBe("Bearer tok");
    expect(JSON.parse(calls[0]!.body)).toEqual({
      messaging_product: "whatsapp",
      recipient_type: "individual",
      to: "919800000001",
      type: "template",
      template: {
        name: "remu_member_notice_en_v1",
        language: { code: "en" },
        components: [
          {
            type: "body",
            parameters: [
              { type: "text", parameter_name: "shop_name", text: "Test Jewellers" },
              { type: "text", parameter_name: "first_name", text: "Asha" },
              { type: "text", parameter_name: "date", text: "2026-10-18" },
            ],
          },
          { type: "button", sub_type: "quick_reply", index: "0", parameters: [{ type: "payload", payload: THUMBS_UP }] },
        ],
      },
    });
    expect(THUMBS_UP).toBe("👍");

    await wa.sendNotice({ to: "+919800000001", language: "hi", shopName: "शर्मा ज्वेलर्स", firstName: "आशा", date: "2026-10-18" });
    const hi = JSON.parse(calls[1]!.body);
    expect(hi.template.name).toBe("remu_member_notice_hi_v1");
    expect(hi.template.language).toEqual({ code: "hi" });
    expect(hi.template.components[0].parameters[0].text).toBe("शर्मा ज्वेलर्स");
  });

  it("maps Graph errors to retryable and permanent", async () => {
    const statuses = [500, 400];
    const { impl } = recordingFetch({
      "graph.facebook.com": () => new Response(JSON.stringify({ error: { code: 131026 } }), { status: statuses.shift()! }),
    });
    const wa = createWhatsAppClient({ phoneNumberId: "1", accessToken: "t", templateEn: "a", templateHi: "b", graphVersion: "v21.0" }, impl);
    const p = { to: "+919800000001", language: "en" as const, shopName: "S", firstName: "F", date: "2026-10-18" };
    await expect(wa.sendNotice(p)).rejects.toMatchObject({ retryable: true, httpStatus: 500 });
    await expect(wa.sendNotice(p)).rejects.toMatchObject({ retryable: false, code: "131026" });
  });
});

describe("member notice on enrolment (TASK-063)", () => {
  let t: TestDb;
  let h: TestHarness;
  beforeAll(async () => {
    t = await createTestDb();
    h = makeHarness(t.db);
  });
  afterAll(async () => t.close());

  it("first send: the shop's notice language template, a whatsapp_messages row with wamid, number and status sent, and notice_status for the hub", async () => {
    const s = await setupShop(t, h, "n1", { language: "hi" });
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000201", name: "Asha Verma", language: "en" });
    const before = h.whatsappCalls().length;
    const consentBefore = await t.db.query("select * from consent_records where shop_id = $1", [s.shopId]);
    await postEvents(h, s.deviceToken, e.events);
    const sent = h.whatsappCalls().slice(before);
    expect(sent).toHaveLength(1);
    const body = JSON.parse(sent[0]!.body);
    expect(body.to).toBe("919800000201");
    expect(body.template.name).toBe(TEST_ENV.whatsappTemplateHi); // shop setting wins over the event's language
    expect(body.template.components[0].parameters.map((p: { text: string }) => p.text)).toEqual(["Test Jewellers", "Asha", "2026-10-18"]);
    const row = (await t.db.query<Record<string, unknown>>("select wamid, phone_e164, status, consent_record_id, member_id, send_attempts from whatsapp_messages where member_id = $1", [e.memberId]))[0]!;
    expect(row).toMatchObject({ phone_e164: "+919800000201", status: "sent", consent_record_id: e.record.id, send_attempts: 1 });
    expect(String(row.wamid)).toMatch(/^wamid\.TEST/);
    // The cloud edits no consent record; the wamid reaches the hub through the inbox.
    const consentAfter = await t.db.query("select * from consent_records where shop_id = $1", [s.shopId]);
    expect(consentAfter).toHaveLength(consentBefore.length + 1);
    expect((consentAfter.at(-1) as { notice_delivery_json: { whatsapp_msg_id: null } }).notice_delivery_json.whatsapp_msg_id).toBeNull();
    const inbox = await call(inboxRoute, "GET", { path: "/api/hub/inbox?after=0", token: s.deviceToken });
    const msgs = (inbox.json as unknown as { messages: { type: string; payload: Record<string, unknown> }[] }).messages;
    expect(msgs.find((m) => m.type === "notice_status")?.payload).toMatchObject({ member_id: e.memberId, consent_record_id: e.record.id, wamid: row.wamid, whatsapp_status: "sent", delivered_at: null });
    // The number is stored only in whatsapp_messages and never logged.
    expect(JSON.stringify(await t.db.query("select * from members"))).not.toContain("9800000201");
    expect(allLogs()).not.toContain("9800000201");
    expect(allLogs()).not.toContain("Asha");
  });

  it("a retry: a transient failure is retried and then sent", async () => {
    const s = await setupShop(t, h, "n2");
    h.graph.failures.push(500, 503);
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000202" });
    await postEvents(h, s.deviceToken, e.events);
    const row = (await t.db.query<Record<string, unknown>>("select status, send_attempts from whatsapp_messages where member_id = $1", [e.memberId]))[0]!;
    expect(row).toEqual({ status: "sent", send_attempts: 3 });
    const m = (await t.db.query<{ notice_failed: boolean }>("select notice_failed from members where id = $1", [e.memberId]))[0]!;
    expect(m.notice_failed).toBe(false);
  });

  it("after 3 retries the failure is flagged on the member, who stays pending_notice", async () => {
    const s = await setupShop(t, h, "n3");
    const before = h.whatsappCalls().length;
    h.graph.failures.push(500, 500, 500, 500);
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000203" });
    const r = await postEvents(h, s.deviceToken, e.events);
    expect((r.json as unknown as { results: { status: string }[] }).results.every((x) => x.status === "acked")).toBe(true);
    expect(h.whatsappCalls().length - before).toBe(4); // first try plus 3 retries
    const row = (await t.db.query<Record<string, unknown>>("select status, send_attempts, wamid from whatsapp_messages where member_id = $1", [e.memberId]))[0]!;
    expect(row).toEqual({ status: "send_failed", send_attempts: 4, wamid: null });
    const m = (await t.db.query<Record<string, unknown>>("select status, notice_failed from members where id = $1", [e.memberId]))[0]!;
    expect(m).toEqual({ status: "pending_notice", notice_failed: true });
  });

  it("a permanent error is not retried", async () => {
    const s = await setupShop(t, h, "n4");
    const before = h.whatsappCalls().length;
    h.graph.failures.push(400);
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000204" });
    await postEvents(h, s.deviceToken, e.events);
    expect(h.whatsappCalls().length - before).toBe(1);
    const row = (await t.db.query<Record<string, unknown>>("select status, last_error_code from whatsapp_messages where member_id = $1", [e.memberId]))[0]!;
    expect(row).toEqual({ status: "send_failed", last_error_code: "131026" });
  });

  it("duplicate suppression: a second notice_request for the same consent record (new event id) sends nothing", async () => {
    const s = await setupShop(t, h, "n5");
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000205" });
    await postEvents(h, s.deviceToken, e.events);
    const before = h.whatsappCalls().length;
    const again = ev("notice_request", e.events[3]!.payload);
    const r = await postEvents(h, s.deviceToken, [again]);
    expect((r.json as unknown as { results: { status: string }[] }).results[0]!.status).toBe("acked");
    expect(h.whatsappCalls().length).toBe(before);
    expect(await t.db.query("select 1 from whatsapp_messages where member_id = $1", [e.memberId])).toHaveLength(1);
  });

  it("no notice for a member who is no longer pending_notice", async () => {
    const s = await setupShop(t, h, "n6");
    const chain = new ChainBuilder(s.shopId);
    const e = enrolEvents(s.shopId, chain, { phone: "+919800000206" });
    const [c, m, p, n] = e.events;
    const withdrawn = ev("member_upsert", { ...(m!.payload as object), status: "withdrawn", updated_at: "2026-10-18T17:43:00+05:30" });
    const before = h.whatsappCalls().length;
    const lateNotice = ev("notice_request", n!.payload); // id after the withdrawal
    await postEvents(h, s.deviceToken, [c!, m!, p!, withdrawn, lateNotice]);
    expect(h.whatsappCalls().length).toBe(before);
  });
});
