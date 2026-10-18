import { readFileSync } from "node:fs";
import path from "node:path";
import { PGlite } from "@electric-sql/pglite";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as claimRoute from "@/app/api/devices/claim/route";
import * as pairRoute from "@/app/api/devices/pair/route";
import * as anchorRoute from "@/app/api/hub/anchor/route";
import * as eventsRoute from "@/app/api/hub/events/route";
import * as withdrawRoute from "@/app/api/members/[id]/withdraw/route";
import * as opsDevicesRoute from "@/app/api/ops/devices/route";
import * as exportRoute from "@/app/api/shops/[id]/consent-export/route";
import * as devicesRoute from "@/app/api/shops/[id]/devices/route";
import * as cardRoute from "@/app/api/visits/[id]/card/route";
import * as webhookRoute from "@/app/api/webhooks/whatsapp/route";
import { sha256Hex } from "@/lib/auth";
import { csvCell, neutraliseFormula } from "@/lib/export";
import { WEBHOOK_RATE_LIMIT, WindowLimiter } from "@/lib/memoryRateLimit";
import { verifyDetached } from "@/lib/signing";
import { hashWaNumber, redactInbound, redactNumbers } from "@/lib/waRedact";
import { createTestDb, migrationFiles, MIGRATIONS_DIR, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, createOwner, createShop, enrolEvents, ev, healthPayload, memberUpsert, nextId, postEvents, provision, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";
import { allLogs } from "./helpers/logs";
import { fixture, postWebhook, sign, unix } from "./helpers/whatsapp";

// TASK-112 security review fixes (docs/API_CONTRACT.md section 7).
let t: TestDb;
let h: TestHarness;
const SECRET = "test-app-secret";
const NOON = new Date("2026-10-18T12:00:00.000Z"); // 17:30 IST, 2026-10-18
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

type Results = { results: { id: string; status: string; reason?: string }[] };
const results = (r: { json: unknown }) => (r.json as Results).results;

/** A shop with one enrolled member whose notice was sent. */
async function enrolled(label: string, phone: string) {
  const s = await setupShop(t, h, label);
  const chain = new ChainBuilder(s.shopId);
  const e = enrolEvents(s.shopId, chain, { phone });
  await postEvents(h, s.deviceToken, e.events);
  const row = (await t.db.query<{ wamid: string }>("select wamid from whatsapp_messages where member_id = $1 and direction = 'out'", [e.memberId]))[0]!;
  return { ...s, chain, memberId: e.memberId, consentId: e.record.id, wamid: row.wamid, phone, from: phone.slice(1) };
}
const phoneOf = async (memberId: string) =>
  (await t.db.query<{ phone_e164: string | null }>("select phone_e164 from whatsapp_messages where member_id = $1 and direction = 'out'", [memberId]))[0]!.phone_e164;

describe("1. ingest rejects per event (section 7 item 8)", () => {
  it("a batch with one forbidden payload and two good events acks two and rejects one", async () => {
    const s = await setupShop(t, h, "perev");
    const good1 = ev("member_upsert", memberUpsert(s.shopId, nextId("m"), { phone: "+919800002001" }));
    const badId = nextId("m");
    const bad = ev("member_upsert", { ...memberUpsert(s.shopId, badId), crop_jpeg_b64: "abc" });
    const good2 = ev("health", healthPayload({ version: "0.2.0" }));
    const r = await postEvents(h, s.deviceToken, [good1, bad, good2]);
    expect(r.status).toBe(200);
    expect(results(r)).toEqual([
      { id: good1.id, status: "acked" },
      { id: bad.id, status: "rejected", reason: "payload_forbidden" },
      { id: good2.id, status: "acked" },
    ]);
    expect(await t.db.query("select 1 from members where id = $1", [badId])).toHaveLength(0);
    expect(await t.db.query("select 1 from members where id = $1", [(good1.payload as { id: string }).id])).toHaveLength(1);
    expect((await t.db.query<{ hub_version: string }>("select hub_version from devices where id = $1", [s.deviceId]))[0]!.hub_version).toBe("0.2.0");
    expect(allLogs()).toContain('"event":"payload_forbidden"');
  });

  it("forbidden content outside an event payload still refuses the request with 400, and binary types get 415", async () => {
    const s = await setupShop(t, h, "perev2");
    const outside = { ...ev("health", healthPayload()), image: "abc" };
    const r = await postEvents(h, s.deviceToken, [outside]);
    expect(r.status).toBe(400);
    expect(r.json).toMatchObject({ error: "payload_forbidden" });
    for (const ct of ["image/jpeg", "application/octet-stream", "multipart/form-data; boundary=x"]) {
      const bin = await call(eventsRoute, "POST", { path: "/api/hub/events", token: s.deviceToken, raw: "xx", headers: { "content-type": ct } });
      expect(bin.status, ct).toBe(415);
    }
  });
});

describe("2. device certificate fingerprint (section 7 item 4)", () => {
  const FP = Array.from({ length: 32 }, (_, i) => (i * 7 + 16).toString(16).toUpperCase().padStart(2, "0")).join(":");

  it("claim stores cert_sha256 and GET /api/shops/{id}/devices returns it per device", async () => {
    const owner = await createOwner(t, h, "cert");
    const shopId = await createShop(h, owner.token);
    const p = await provision(h);
    const pending = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial, cert_sha256: FP.toLowerCase() } });
    expect(pending.status).toBe(202);
    await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: p.code } });
    const claimed = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial, cert_sha256: FP } });
    expect(claimed.status).toBe(200);
    expect((await t.db.query<{ cert_sha256: string }>("select cert_sha256 from devices where id = $1", [p.deviceId]))[0]!.cert_sha256).toBe(FP);
    const list = await call(devicesRoute, "GET", { path: `/api/shops/${shopId}/devices`, token: owner.token, params: { id: shopId } });
    expect(list.status).toBe(200);
    expect(list.json).toEqual([expect.objectContaining({ id: p.deviceId, cert_sha256: FP })]);
    // After the token was issued a claim (409) can no longer change it.
    const other = FP.replace(/^../, "00");
    expect((await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial, cert_sha256: other } })).status).toBe(409);
    expect((await t.db.query<{ cert_sha256: string }>("select cert_sha256 from devices where id = $1", [p.deviceId]))[0]!.cert_sha256).toBe(FP);
  });

  it("cert_sha256 is optional, and a malformed one is refused with 400", async () => {
    const p = await provision(h);
    expect((await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial } })).status).toBe(202);
    for (const bad of ["AB:CD", FP.replace(/:/g, ""), `${FP}:00`, FP.replace(/^../, "ZZ"), 42]) {
      const r = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial, cert_sha256: bad } });
      expect(r.status, String(bad)).toBe(400);
    }
    expect((await t.db.query<{ cert_sha256: string | null }>("select cert_sha256 from devices where id = $1", [p.deviceId]))[0]!.cert_sha256).toBeNull();
  });
});

describe("3. cloud retention of phone numbers (section 7 item 9)", () => {
  it("after delivered, phone_e164 is null and the raw JSON holds no 10-digit number", async () => {
    const s = await enrolled("ret1", "+919800003001");
    expect(await phoneOf(s.memberId)).toBe(s.phone);
    await postWebhook(fixture("status_delivered", { wamid: s.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: s.from }), SECRET);
    expect(await phoneOf(s.memberId)).toBeNull();
    const raw = (await t.db.query<{ raw_webhook_json: Record<string, unknown>[] }>("select raw_webhook_json from whatsapp_messages where wamid = $1", [s.wamid]))[0]!.raw_webhook_json;
    expect(raw).toHaveLength(1);
    expect(raw[0]).toMatchObject({ status: "delivered", recipient_id: hashWaNumber(s.from) });
    expect(raw[0]!.recipient_id).toBe(sha256Hex(s.phone)); // the contract phone_hash
    const withoutTimestamps = JSON.stringify(raw, (k, v: unknown) => (k === "timestamp" ? undefined : v));
    expect(withoutTimestamps).not.toMatch(/\b\+?\d{10,}\b/);
    expect(withoutTimestamps).not.toContain(s.from);
  });

  it("read and failed clear phone_e164 too", async () => {
    const r = await enrolled("ret2", "+919800003002");
    await postWebhook(fixture("status_read", { wamid: r.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: r.from }), SECRET);
    expect(await phoneOf(r.memberId)).toBeNull();
    const f = await enrolled("ret3", "+919800003003");
    await postWebhook(fixture("status_failed", { wamid: f.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: f.from }), SECRET);
    expect(await phoneOf(f.memberId)).toBeNull();
    const all = JSON.stringify(await t.db.query("select * from whatsapp_messages where member_id = any($1::text[])", [[r.memberId, f.memberId]]));
    expect(all).not.toContain(r.from);
    expect(all).not.toContain(f.from);
  });

  it("withdrawal clears phone_e164 on every path: owner remove, STOP and the hub", async () => {
    const o = await enrolled("ret4", "+919800003004");
    await call(withdrawRoute, "POST", { path: `/api/members/${o.memberId}/withdraw`, token: o.owner.token, params: { id: o.memberId } });
    expect(await phoneOf(o.memberId)).toBeNull();

    const st = await enrolled("ret5", "+919800003005");
    await postWebhook(fixture("text_message", { from: st.from, msg_id: "wamid.IN.RET5", ts: unix("2026-10-18T13:00:00Z"), body: "STOP" }), SECRET);
    expect(await phoneOf(st.memberId)).toBeNull();

    const hb = await enrolled("ret6", "+919800003006");
    await postEvents(h, hb.deviceToken, [ev("member_upsert", memberUpsert(hb.shopId, hb.memberId, { phone: hb.phone, status: "withdrawn" }))]);
    expect((await t.db.query<{ status: string }>("select status from members where id = $1", [hb.memberId]))[0]!.status).toBe("withdrawn");
    expect(await phoneOf(hb.memberId)).toBeNull();
  });

  it("inbound text bodies are dropped except a STOP; numbers are stored as phone_hash", async () => {
    const s = await enrolled("ret7", "+919800003007");
    await postWebhook(fixture("text_message", { from: s.from, msg_id: "wamid.IN.RET7A", ts: unix("2026-10-18T13:00:00Z"), body: "My new number is 9812345678" }), SECRET);
    await postWebhook(fixture("button_thumbs_up", { from: s.from, msg_id: "wamid.IN.RET7B", ts: unix("2026-10-18T13:01:00Z"), context_wamid: s.wamid }), SECRET);
    await postWebhook(fixture("text_reply", { from: s.from, msg_id: "wamid.IN.RET7C", ts: unix("2026-10-18T13:02:00Z"), body: "STOP", context_wamid: s.wamid }), SECRET);
    const rows = await t.db.query<{ wamid: string; kind: string; raw_webhook_json: Record<string, unknown>[] }>(
      "select wamid, kind, raw_webhook_json from whatsapp_messages where wamid like 'wamid.IN.RET7%' order by wamid",
    );
    expect(rows.map((r) => r.kind)).toEqual(["text", "thumbs_up", "stop"]);
    const hash = hashWaNumber(s.from);
    expect(rows[0]!.raw_webhook_json).toEqual([{ id: "wamid.IN.RET7A", type: "text", timestamp: unix("2026-10-18T13:00:00Z"), from: hash }]);
    expect(rows[1]!.raw_webhook_json).toEqual([{ id: "wamid.IN.RET7B", type: "button", timestamp: unix("2026-10-18T13:01:00Z"), from: hash, context: { id: s.wamid } }]);
    expect(rows[2]!.raw_webhook_json[0]).toMatchObject({ from: hash, type: "text", text: { body: "STOP" }, context: { id: s.wamid, from: hashWaNumber("919800000099") } });
    const dump = JSON.stringify(rows, (k, v: unknown) => (k === "timestamp" ? undefined : v));
    expect(dump).not.toContain("9812345678");
    expect(dump).not.toMatch(/\b\+?\d{10,}\b/);
    expect(dump).not.toContain(s.from);
  });

  it("redactNumbers and redactInbound replace sender, recipient and business numbers at any depth", () => {
    const out = redactNumbers({ recipient_id: "919800000001", contacts: [{ wa_id: "919800000002" }], metadata: { display_phone_number: "+919800000099" }, id: "wamid.X", timestamp: "1760789580" });
    expect(out).toEqual({
      recipient_id: hashWaNumber("919800000001"),
      contacts: [{ wa_id: hashWaNumber("919800000002") }],
      metadata: { display_phone_number: hashWaNumber("919800000099") },
      id: "wamid.X",
      timestamp: "1760789580",
    });
    expect(hashWaNumber("+919876543210")).toBe("f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e");
    expect(redactInbound({ id: "w", type: "image", timestamp: "1", from: "919800000001" }, "other")).toEqual({ id: "w", type: "image", timestamp: "1" });
  });
});

describe("4. anchors are today or yesterday (IST) and insert-only", () => {
  it("accepts only today's and yesterday's Asia/Kolkata date", async () => {
    h.clock.set(new Date("2026-10-18T18:45:00.000Z")); // 00:15 IST on 2026-10-19
    try {
      const s = await setupShop(t, h, "anc1");
      const post = (date: string) => call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date, chain_head_hash: "sha256:genesis" } });
      expect((await post("2026-10-19")).status).toBe(200); // today in IST (still 2026-10-18 in UTC)
      expect((await post("2026-10-18")).status).toBe(200); // yesterday
      for (const d of ["2026-10-17", "2026-10-20", "2025-10-19", "2026-02-30"]) {
        const r = await post(d);
        expect(r.status, d).toBe(400);
        expect(r.json, d).toMatchObject({ error: "invalid" });
      }
      expect(await t.db.query("select 1 from hash_anchors where shop_id = $1", [s.shopId])).toHaveLength(2);
    } finally {
      h.clock.set(NOON);
    }
  });

  it("a second post for the same shop and date never overwrites; match is against the stored row", async () => {
    h.clock.set(NOON);
    const s = await setupShop(t, h, "anc2");
    const chain = new ChainBuilder(s.shopId);
    await postEvents(h, s.deviceToken, [ev("consent_record", chain.next({ member_id: nextId("m") }))]);
    const first = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-18", chain_head_hash: chain.head } });
    expect(first.json).toEqual({ ok: true, match: true });
    const stored = async () =>
      (await t.db.query<{ chain_head_hash: string; match: boolean }>("select chain_head_hash, match from hash_anchors where shop_id = $1 and date = '2026-10-18'", [s.shopId]))[0];
    // The same anchor again: no change, still a match.
    const same = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-18", chain_head_hash: chain.head } });
    expect(same.json).toEqual({ ok: true, match: true });
    expect((await t.db.query<{ anchor_mismatch: boolean }>("select anchor_mismatch from devices where id = $1", [s.deviceId]))[0]!.anchor_mismatch).toBe(false);
    // An attempt to overwrite with another head is refused and flagged.
    const forged = `sha256:${"1".repeat(64)}`;
    const over = await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: s.deviceToken, body: { date: "2026-10-18", chain_head_hash: forged } });
    expect(over.status).toBe(200);
    expect(over.json).toEqual({ ok: true, match: false });
    expect(await stored()).toEqual({ chain_head_hash: chain.head, match: true });
    expect(await t.db.query("select 1 from hash_anchors where shop_id = $1", [s.shopId])).toHaveLength(1);
    expect((await t.db.query<{ anchor_mismatch: boolean }>("select anchor_mismatch from devices where id = $1", [s.deviceId]))[0]!.anchor_mismatch).toBe(true);
  });
});

describe("5. CSV formula injection", () => {
  it("prefixes cells starting with =, +, -, @, tab, CR (and a quote) with a single quote", () => {
    expect(["=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "'x", "a=b", "1-2", ""].map(neutraliseFormula)).toEqual([
      "'=1+1", "'+1", "'-1", "'@SUM(A1)", "'\tx", "'\rx", "''x", "a=b", "1-2", "",
    ]);
    expect(csvCell('=HYPERLINK("http://evil.test","x")')).toBe(`"'=HYPERLINK(""http://evil.test"",""x"")"`);
    expect(csvCell("\rcmd")).toBe(`"'\rcmd"`);
    expect(csvCell(null)).toBe("");
  });

  it("the export neutralises hub-supplied cells and the signature covers the final bytes", async () => {
    const s = await setupShop(t, h, "csvinj");
    const chain = new ChainBuilder(s.shopId);
    const bills = ["=cmd|' /C calc'!A0", "@SUM(1+1)", "+919800000000", "-2+3", "\tTAB"];
    await postEvents(h, s.deviceToken, bills.map((bill_no) => ev("consent_record", chain.next({ member_id: nextId("m"), bill_no }))));
    const r = await call(exportRoute, "POST", { path: `/api/shops/${s.shopId}/consent-export?format=csv`, token: s.owner.token, params: { id: s.shopId } });
    expect(r.status).toBe(200);
    const bytes = new Uint8Array(await r.res.arrayBuffer());
    const text = new TextDecoder().decode(bytes);
    const rows = text.split("\r\n").filter((l) => l && !l.startsWith("# ")).slice(1);
    expect(rows).toHaveLength(bills.length);
    const billCells = rows.map((l) => l.split(",")[3]);
    expect(billCells).toEqual(["'=cmd|' /C calc'!A0", "'@SUM(1+1)", "'+919800000000", "'-2+3", "'\tTAB"]);
    for (const l of rows) for (const cell of l.split(",")) expect(cell).not.toMatch(/^"?[=+\-@\t\r]/);
    expect(verifyDetached(r.res.headers.get("x-remu-public-key")!, bytes, r.res.headers.get("x-remu-signature")!)).toBe(true);
    expect(r.res.headers.get("x-remu-sha256")).toBe(sha256Hex(text));
  });
});

describe("6. staff_upsert never overwrites a cloud-authored staff row", () => {
  it("skips and acks a hub event for the owner row; role and user_id stay", async () => {
    const s = await setupShop(t, h, "stf");
    const ownerRow = (await t.db.query<{ id: string; name: string; role: string; user_id: string }>("select id, name, role, user_id::text as user_id from staff where shop_id = $1 and user_id is not null", [s.shopId]))[0]!;
    expect(ownerRow).toMatchObject({ role: "owner", user_id: s.owner.userId });
    const hijack = ev("staff_upsert", { id: ownerRow.id, shop_id: s.shopId, name: "Hub Rename", role: "cashier", created_at: "2026-10-18T10:00:00+05:30" });
    const r = await postEvents(h, s.deviceToken, [hijack]);
    expect(results(r)).toEqual([{ id: hijack.id, status: "acked" }]);
    const after = (await t.db.query<{ name: string; role: string; user_id: string }>("select name, role, user_id::text as user_id from staff where id = $1", [ownerRow.id]))[0]!;
    expect(after).toEqual({ name: ownerRow.name, role: "owner", user_id: s.owner.userId });
    // The owner keeps access.
    const list = await call(devicesRoute, "GET", { path: `/api/shops/${s.shopId}/devices`, token: s.owner.token, params: { id: s.shopId } });
    expect(list.status).toBe(200);
    // Hub-authored rows still mirror as before.
    const cashierId = nextId("s");
    await postEvents(h, s.deviceToken, [ev("staff_upsert", { id: cashierId, shop_id: s.shopId, name: "Ravi", role: "cashier", created_at: "2026-10-18T10:00:00+05:30" })]);
    await postEvents(h, s.deviceToken, [ev("staff_upsert", { id: cashierId, shop_id: s.shopId, name: "Ravi K", role: "cashier", created_at: "2026-10-18T10:00:00+05:30" })]);
    expect((await t.db.query("select name, role, user_id from staff where id = $1", [cashierId]))[0]).toEqual({ name: "Ravi K", role: "cashier", user_id: null });
  });
});

describe("7. consent_record member must belong to the device's shop", () => {
  it("rejects (per event) a record for a member mirrored under another shop", async () => {
    const a = await setupShop(t, h, "cra");
    const b = await setupShop(t, h, "crb");
    const memberB = nextId("m");
    await postEvents(h, b.deviceToken, [ev("member_upsert", memberUpsert(b.shopId, memberB))]);
    const chain = new ChainBuilder(a.shopId);
    const foreign = ev("consent_record", chain.next({ member_id: memberB }));
    const chainOk = new ChainBuilder(a.shopId);
    const own = ev("consent_record", chainOk.next({ member_id: nextId("m") })); // no mirror row yet: accepted
    const r = await postEvents(h, a.deviceToken, [foreign]);
    expect(results(r)).toEqual([{ id: foreign.id, status: "rejected", reason: "shop_mismatch" }]);
    expect(await t.db.query("select 1 from consent_records where member_id = $1", [memberB])).toHaveLength(0);
    const r2 = await postEvents(h, a.deviceToken, [own]);
    expect(results(r2)).toEqual([{ id: own.id, status: "acked" }]);
  });
});

describe("8. card for withdrawn or expired members", () => {
  it("GET /api/visits/{id}/card returns 410 gone once the member is withdrawn or expired", async () => {
    const visitFor = async (s: { deviceToken: string }, memberId: string) => {
      const v = nextId("v");
      await postEvents(h, s.deviceToken, [ev("visit_summary", { id: v, member_id: memberId, ts: "2026-10-19T11:00:00+05:30", camera: "gate", score: 0.6, frames_agreed: 3, greeted: false, greeted_by: null, not_them: false })]);
      return v;
    };
    const card = (token: string, v: string) => call(cardRoute, "GET", { path: `/api/visits/${v}/card`, token, params: { id: v } });

    const w = await enrolled("card1", "+919800008001");
    const vw = await visitFor(w, w.memberId);
    expect((await card(w.owner.token, vw)).status).toBe(200);
    await call(withdrawRoute, "POST", { path: `/api/members/${w.memberId}/withdraw`, token: w.owner.token, params: { id: w.memberId } });
    const gone = await card(w.owner.token, vw);
    expect(gone.status).toBe(410);
    expect(gone.json).toMatchObject({ error: "gone" });
    expect(JSON.stringify(gone.json)).not.toContain("Test Member");

    const x = await enrolled("card2", "+919800008002");
    await postWebhook(fixture("status_delivered", { wamid: x.wamid, ts: unix("2026-10-18T12:13:00Z"), recipient: x.from }), SECRET);
    await postEvents(h, x.deviceToken, [ev("member_upsert", memberUpsert(x.shopId, x.memberId, { phone: x.phone, status: "expired" }))]);
    expect((await t.db.query<{ status: string }>("select status from members where id = $1", [x.memberId]))[0]!.status).toBe("expired");
    const vx = await visitFor(x, x.memberId);
    expect((await card(x.owner.token, vx)).status).toBe(410);
    // Another shop's owner still gets 403, not a hint that the member left.
    expect((await card(w.owner.token, vx)).status).toBe(403);
  });
});

describe("9. pairing codes are stored as SHA-256", () => {
  it("ops register hashes before insert; pair and claim look up by hash", async () => {
    const owner = await createOwner(t, h, "pch");
    const shopId = await createShop(h, owner.token);
    const p = await provision(h, "q7zx-4mna", "pi5-serial-hash01");
    const row = (await t.db.query<{ pairing_code: string }>("select pairing_code from devices where id = $1", [p.deviceId]))[0]!;
    expect(row.pairing_code).toBe(sha256Hex("Q7ZX-4MNA"));
    expect(row.pairing_code).toMatch(/^[0-9a-f]{64}$/);
    // The stored hash is not a pairing code.
    expect((await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: row.pairing_code.slice(0, 12) } })).status).toBe(401);
    expect((await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: " q7zx-4mna ", serial: p.serial } })).status).toBe(202);
    const pr = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: "Q7ZX-4MNA" } });
    expect(pr.json).toEqual({ device_id: p.deviceId });
    const cr = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: "q7zx-4mna", serial: p.serial } });
    expect(cr.status).toBe(200);
    expect(JSON.stringify(await t.db.query("select * from devices"))).not.toMatch(/Q7ZX-4MNA/i);
    // A duplicate registration is still refused, case-insensitively.
    const dup = await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers: { "x-remu-ops-key": h.deps.env.opsApiKey! }, body: { pairing_code: "Q7ZX-4mna", serial: "pi5-serial-hash02" } });
    expect(dup.status).toBe(409);
  });

  it("migration 0008 hashes codes stored in plain text and clears retained numbers", async () => {
    const pg = new PGlite();
    try {
      await pg.exec("set timezone = 'UTC'");
      await pg.exec(readFileSync(path.join(__dirname, "helpers", "supabase-shim.sql"), "utf8"));
      const files = migrationFiles();
      const at = files.indexOf("0008_security_review.sql");
      expect(at).toBeGreaterThan(0);
      for (const f of files.slice(0, at)) await pg.exec(readFileSync(path.join(MIGRATIONS_DIR, f), "utf8"));
      await pg.exec(`
        insert into shops (id, name, city) values ('mig-shop', 'Mig', 'Jaipur');
        insert into devices (id, serial, pairing_code) values ('d_MIG1', 'pi5-mig1', 'K7QM-2XPA');
        insert into members (id, shop_id, display_name, phone_hash, status, member_since, created_at, updated_at)
          values ('m_MIG1', 'mig-shop', 'Mig', '${sha256Hex("+919800009001")}', 'pending_notice', '2026-10-18', now(), now());
        insert into whatsapp_messages (direction, kind, shop_id, member_id, consent_record_id, wamid, phone_e164, phone_hash, status, delivered_at, raw_webhook_json)
          values ('out', 'notice', 'mig-shop', 'm_MIG1', 'c_MIG1', 'wamid.MIG1', '+919800009001', '${sha256Hex("+919800009001")}', 'delivered', now(),
                  '[{"id": "wamid.MIG1", "status": "delivered", "timestamp": "1760789580", "recipient_id": "919800009001"}]'::jsonb);
        insert into whatsapp_messages (direction, kind, wamid, phone_hash, status, raw_webhook_json)
          values ('in', 'text', 'wamid.MIG2', 'x', 'received',
                  '[{"id": "wamid.MIG2", "type": "text", "timestamp": "1760789580", "from": "919800009001", "text": {"body": "call me on 9812345678"}}]'::jsonb);`);
      await pg.exec(readFileSync(path.join(MIGRATIONS_DIR, files[at]!), "utf8"));
      const d = (await pg.query<{ pairing_code: string }>("select pairing_code from devices where id = 'd_MIG1'")).rows[0]!;
      expect(d.pairing_code).toBe(sha256Hex("K7QM-2XPA"));
      const wa = (await pg.query<{ wamid: string; phone_e164: string | null; raw_webhook_json: unknown }>("select wamid, phone_e164, raw_webhook_json from whatsapp_messages order by wamid")).rows;
      expect(wa[0]).toEqual({ wamid: "wamid.MIG1", phone_e164: null, raw_webhook_json: [{ id: "wamid.MIG1", status: "delivered", timestamp: "1760789580", recipient_id: sha256Hex("+919800009001") }] });
      expect(wa[1]!.raw_webhook_json).toEqual([{ id: "wamid.MIG2", type: "text", timestamp: "1760789580", from: sha256Hex("+919800009001") }]);
      await expect(pg.query("insert into devices (id, serial, pairing_code) values ('d_MIG2', 'pi5-mig2', 'PLAIN-CODE')")).rejects.toThrow();
    } finally {
      await pg.close();
    }
  });
});

describe("10. WhatsApp webhook rate limit", () => {
  it("allows 50 requests a second per address, answers 429 beyond, per address and per window", async () => {
    h.clock.set(NOON);
    const raw = JSON.stringify({ object: "whatsapp_business_account", entry: [] });
    const post = (ip: string, signature = sign(raw, SECRET)) =>
      call(webhookRoute, "POST", {
        path: "/api/webhooks/whatsapp",
        raw,
        headers: { "content-type": "application/json", "x-hub-signature-256": signature, "x-forwarded-for": ip },
      });
    const statuses: number[] = [];
    for (let i = 0; i < WEBHOOK_RATE_LIMIT.limit; i++) statuses.push((await post("203.0.113.50", i % 2 ? "sha256=00" : undefined)).status);
    expect(statuses.every((s) => s === 200 || s === 401)).toBe(true);
    const limited = await post("203.0.113.50");
    expect(limited.status).toBe(429);
    expect(limited.json).toMatchObject({ error: "rate_limited" });
    expect((await post("198.51.100.50")).status).toBe(200); // another address
    h.clock.advance(1000); // next window
    expect((await post("203.0.113.50")).status).toBe(200);
    // Over the size cap is refused on the declared length first.
    const big = await call(webhookRoute, "POST", {
      path: "/api/webhooks/whatsapp",
      raw: "{}",
      headers: { "content-type": "application/json", "content-length": String(2 * 1024 * 1024), "x-forwarded-for": "192.0.2.50" },
    });
    expect(big.status).toBe(413);
  });

  it("the limiter keeps bounded memory", () => {
    const l = new WindowLimiter(2, 1000, 3);
    expect([l.hit("a", 0), l.hit("a", 10), l.hit("a", 20)]).toEqual([true, true, false]);
    for (const k of ["b", "c", "d", "e"]) l.hit(k, 30);
    expect(l.hit("a", 1000)).toBe(true);
  });
});
