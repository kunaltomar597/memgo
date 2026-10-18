import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import "regenerator-runtime/runtime";
import fontkit from "@pdf-lib/fontkit";
import { PDFDocument } from "pdf-lib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as anchorRoute from "@/app/api/hub/anchor/route";
import * as exportRoute from "@/app/api/shops/[id]/consent-export/route";
import { CONSENT_COLUMNS, computeRecordHash, verifyChain, type ConsentRecord } from "@/lib/consentChain";
import { CASHIER_LINE_HI, FONT_DIR, FONT_REGULAR, pageCountFor, ROWS_PER_PAGE, signatureLines, tableRows } from "@/lib/exportPdf";
import { loadExport } from "@/lib/export";
import { loadSigningKey, publicKeyRawBase64, verifyDetached } from "@/lib/signing";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

let t: TestDb;
let h: TestHarness;
let S: Awaited<ReturnType<typeof setupShop>>;
let chain: ChainBuilder;
const N = 50;

beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
  S = await setupShop(t, h, "exp", { name: "शर्मा ज्वेलर्स" });
  chain = new ChainBuilder(S.shopId);
  const events: ReturnType<typeof ev>[] = [];
  for (let i = 0; i < N / 2; i++) {
    const e = enrolEvents(S.shopId, chain, { phone: `+9198000${String(20000 + i)}` });
    events.push(e.events[0]!, e.events[1]!);
    const del = chain.next({
      member_id: e.memberId,
      channel: "whatsapp_webhook",
      cashier_id: null,
      notice_delivery_json: { printed_on_bill: false, whatsapp_msg_id: `wamid.X${i}`, whatsapp_status: "delivered", delivered_at: "2026-10-18T17:42:40+05:30", thumbs_up_at: null },
    });
    events.push(ev("consent_record", del));
  }
  await postEvents(h, S.deviceToken, events);
  await call(anchorRoute, "POST", { path: "/api/hub/anchor", token: S.deviceToken, body: { date: "2026-10-18", chain_head_hash: chain.head } });
});
afterAll(async () => t.close());

function parseCsv(text: string): { rows: string[][]; trailer: Record<string, string> } {
  const lines = text.split("\r\n").filter((l) => l !== "");
  const trailer: Record<string, string> = {};
  const rows: string[][] = [];
  for (const line of lines) {
    if (line.startsWith("# ")) {
      const i = line.indexOf(": ");
      trailer[line.slice(2, i)] = line.slice(i + 2);
      continue;
    }
    const cells: string[] = [];
    let cur = "";
    let q = false;
    for (let i = 0; i < line.length; i++) {
      const c = line[i]!;
      if (q) {
        if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; }
        else if (c === '"') q = false;
        else cur += c;
      } else if (c === '"') q = true;
      else if (c === ",") { cells.push(cur); cur = ""; }
      else cur += c;
    }
    cells.push(cur);
    rows.push(cells);
  }
  return { rows, trailer };
}

function toRecord(header: string[], cells: string[]): ConsentRecord {
  const o: Record<string, unknown> = {};
  header.forEach((k, i) => {
    const v = cells[i] ?? "";
    o[k] = k.endsWith("_json") ? JSON.parse(v) : v === "" ? null : v;
  });
  return o as unknown as ConsentRecord;
}

const exportReq = (format: string, token = S.owner.token, shopId = S.shopId) =>
  call(exportRoute, "POST", { path: `/api/shops/${shopId}/consent-export?format=${format}`, token, params: { id: shopId } });

describe("consent export CSV (TASK-092)", () => {
  it("has every column in chain order, one row per record, chain head and anchor, and a valid Ed25519 signature", async () => {
    const r = await exportReq("csv");
    expect(r.status).toBe(200);
    expect(r.res.headers.get("content-type")).toBe("text/csv; charset=utf-8");
    const bytes = new Uint8Array(await r.res.arrayBuffer());
    const text = new TextDecoder().decode(bytes);
    const { rows, trailer } = parseCsv(text);
    expect(rows[0]).toEqual([...CONSENT_COLUMNS]);
    expect(rows.length - 1).toBe(N); // 25 enrolment + 25 delivery records
    const recs = rows.slice(1).map((c) => toRecord(rows[0]!, c));
    expect(recs.map((x) => x.id)).toEqual(chain.records.map((x) => x.id));
    for (const rec of recs) expect(computeRecordHash(rec)).toBe(rec.record_hash);
    expect(verifyChain(recs)).toMatchObject({ ok: true, head: chain.head });
    expect(trailer).toMatchObject({
      shop_id: S.shopId,
      record_count: String(N),
      chain_head_hash: chain.head,
      chain_verified: "true",
      latest_anchor_date: "2026-10-18",
      latest_anchor_hash: chain.head,
      anchor_matches_head: "true",
    });

    const sig = r.res.headers.get("x-remu-signature")!;
    const pub = r.res.headers.get("x-remu-public-key")!;
    expect(pub).toBe(publicKeyRawBase64(loadSigningKey(h.deps.env.consentExportSigningKey!)));
    expect(verifyDetached(pub, bytes, sig)).toBe(true);
    const changed = Uint8Array.from(bytes);
    changed[100] = changed[100]! ^ 1;
    expect(verifyDetached(pub, changed, sig)).toBe(false);
    const log = await t.db.query("select format, record_count, chain_verified from export_log where shop_id = $1", [S.shopId]);
    expect(log).toContainEqual({ format: "csv", record_count: N, chain_verified: true });
  });

  it("a changed row breaks the re-verification", async () => {
    const d = await loadExport(t.db, S.shopId, new Date());
    const tampered = d.records.map((x) => ({ ...x }));
    tampered[7]!.channel = "sweep";
    expect(verifyChain(tampered).firstBreak).toEqual({ id: tampered[7]!.id, reason: "hash" });
  });

  it("refuses another shop's owner (403), no session (401) and an unknown format (400)", async () => {
    const other = await setupShop(t, h, "exp2");
    expect((await exportReq("csv", other.owner.token)).status).toBe(403);
    expect((await call(exportRoute, "POST", { path: `/api/shops/${S.shopId}/consent-export?format=csv`, params: { id: S.shopId } })).status).toBe(401);
    expect((await exportReq("xlsx")).status).toBe(400);
  });

  it("accepts the signing key as a raw seed, PKCS8 DER base64 or PEM, all the same key", () => {
    const seed = Buffer.alloc(32, 7);
    const fromSeed = loadSigningKey(seed.toString("base64"));
    const der = fromSeed.export({ format: "der", type: "pkcs8" });
    const pem = fromSeed.export({ format: "pem", type: "pkcs8" }).toString();
    expect(publicKeyRawBase64(loadSigningKey(Buffer.from(der).toString("base64")))).toBe(publicKeyRawBase64(fromSeed));
    expect(publicKeyRawBase64(loadSigningKey(pem))).toBe(publicKeyRawBase64(fromSeed));
  });
});

describe("consent export PDF (TASK-093)", () => {
  it("renders the expected page count with an embedded Devanagari font and a signed signature page", async () => {
    const r = await exportReq("pdf");
    expect(r.status).toBe(200);
    expect(r.res.headers.get("content-type")).toBe("application/pdf");
    const bytes = new Uint8Array(await r.res.arrayBuffer());
    if (process.env.REMU_PDF_OUT) writeFileSync(process.env.REMU_PDF_OUT, bytes); // manual visual check
    expect(S.shopId).toMatch(/^shop-[0-9a-f]{6}-jaipur$/); // Devanagari-only name, readable unique id
    expect(Buffer.from(bytes.subarray(0, 5)).toString()).toBe("%PDF-");
    const doc = await PDFDocument.load(bytes);
    expect(doc.getPageCount()).toBe(pageCountFor(N));
    expect(pageCountFor(N)).toBe(Math.ceil(N / ROWS_PER_PAGE) + 1);
    expect(doc.getSubject()).toBe(`Chain head ${chain.head}`);
    const raw = Buffer.from(bytes).toString("latin1");
    expect(raw).toMatch(/\/BaseFont \/NotoSansDevanagari-Regular/);
    expect(raw).toMatch(/\/FontFile2/);
    const sig = r.res.headers.get("x-remu-signature")!;
    expect(verifyDetached(r.res.headers.get("x-remu-public-key")!, bytes, sig)).toBe(true);
  });

  it("the table rows carry the required fields and the last page carries the chain head and the CSV signature", async () => {
    const d = await loadExport(t.db, S.shopId, new Date());
    const rows = tableRows(d);
    expect(rows).toHaveLength(N);
    expect(rows[0]).toMatchObject({ member_id: chain.records[0]!.member_id, bill_no: "INV-2026-10-18-0417", channel: "verbal_at_counter", ts: "2026-10-18T17:42:11+05:30" });
    expect(rows[0]!.phone_hash).toMatch(/^[0-9a-f]{12}$/);
    expect(rows[1]).toMatchObject({ channel: "whatsapp_webhook", delivery: "delivered" });
    const lines = signatureLines({ chainHeadHash: chain.head, chainVerified: true, firstBreak: null, latestAnchor: "2026-10-18 x", anchorMatchesHead: "yes", csvSha256: "abc", csvSignature: "SIG==", publicKey: "PUB=" });
    expect(lines[0]).toBe(`Chain head hash: ${chain.head}`);
    expect(lines.some((l) => l.includes("SIG=="))).toBe(true);
    expect(lines.some((l) => l.includes("PUB="))).toBe(true);
  });

  it("the embedded font shapes Devanagari without missing glyphs (shop name, cashier line, conjuncts and nukta)", () => {
    const font = fontkit.create(readFileSync(path.join(FONT_DIR, FONT_REGULAR)));
    for (const text of ["शर्मा ज्वेलर्स", CASHIER_LINE_HI, "पहचान ज़रूरत", "Remu consent export 0417"]) {
      const run = font.layout(text);
      expect(run.glyphs.length).toBeGreaterThan(0);
      expect(run.glyphs.filter((g) => g.id === 0)).toHaveLength(0);
    }
    // "र्मा" forms a conjunct: fewer glyphs than code points.
    expect(font.layout("शर्मा").glyphs.length).toBeLessThan(Array.from("शर्मा").length);
  });

  it("ships the OFL licence beside the fonts", () => {
    expect(readFileSync(path.join(FONT_DIR, "OFL.txt"), "utf8")).toContain("SIL Open Font License, Version 1.1");
  });

  it("an empty shop still exports a valid PDF and CSV", async () => {
    const e = await setupShop(t, h, "exp3");
    const csv = await exportReq("csv", e.owner.token, e.shopId);
    expect(csv.status).toBe(200);
    const pdf = await exportReq("pdf", e.owner.token, e.shopId);
    const doc = await PDFDocument.load(new Uint8Array(await pdf.res.arrayBuffer()));
    expect(doc.getPageCount()).toBe(2);
    void nextId;
  });
});
