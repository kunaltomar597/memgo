import "regenerator-runtime/runtime"; // @pdf-lib/fontkit's Indic shaper needs it as a global
import { readFile } from "node:fs/promises";
import path from "node:path";
import fontkit from "@pdf-lib/fontkit";
import { PDFDocument, rgb, type PDFFont, type PDFPage } from "pdf-lib";
import type { ExportData } from "./export";
import { toIstIso } from "./time";

/**
 * Consent export PDF (TASK-093): shop name, generation date, script and notice versions, one row
 * per consent record, and on the last page the chain head hash and the signature block. Text is set
 * in Noto Sans Devanagari (OFL, assets/fonts/), which covers Latin and Devanagari, so Hindi shop
 * names and the hi-IN-v1.0 cashier line render. The PDF's own detached signature travels in the
 * X-Remu-Signature header; the signature printed on the last page is the one over the CSV export of
 * the same records, so the printed copy can be checked against the CSV.
 */
export const FONT_DIR = path.join(process.cwd(), "assets", "fonts");
export const FONT_REGULAR = "NotoSansDevanagari-Regular.ttf";
export const FONT_BOLD = "NotoSansDevanagari-Bold.ttf";

export const CASHIER_LINE_HI = "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।";
export const CASHIER_LINE_EN =
  "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off.";

export const ROWS_PER_PAGE = 22;
const PAGE: [number, number] = [841.89, 595.28]; // A4 landscape
const MARGIN = 36;

export const TABLE_COLUMNS = [
  { key: "member_id", title: "Member", width: 142 },
  { key: "phone_hash", title: "Phone hash", width: 66 },
  { key: "bill_no", title: "Bill no", width: 84 },
  { key: "ts", title: "Timestamp", width: 108 },
  { key: "cashier_id", title: "Cashier", width: 66 },
  { key: "channel", title: "Channel", width: 80 },
  { key: "delivery", title: "Delivery", width: 54 },
  { key: "thumbs_up", title: "Thumbs-up", width: 50 },
  { key: "withdrawn", title: "Withdrawn", width: 50 },
  { key: "template_deleted", title: "Template deleted", width: 70 },
] as const;

export interface SignatureBlock {
  chainHeadHash: string;
  chainVerified: boolean;
  firstBreak: string | null;
  latestAnchor: string;
  anchorMatchesHead: string;
  csvSha256: string;
  csvSignature: string;
  publicKey: string;
}

export type TableRow = Record<(typeof TABLE_COLUMNS)[number]["key"], string>;

export function tableRows(d: ExportData): TableRow[] {
  return d.records.map((r) => ({
    member_id: r.member_id,
    phone_hash: d.phoneHashPrefix.get(r.member_id) ?? "",
    bill_no: r.bill_no ?? "",
    ts: r.ts,
    cashier_id: r.cashier_id ?? "",
    channel: r.channel,
    delivery: r.notice_delivery_json.whatsapp_status ?? "",
    thumbs_up: r.notice_delivery_json.thumbs_up_at ? "yes" : "",
    withdrawn: r.withdrawn_at ? "yes" : "",
    template_deleted: r.template_deleted_at ? "yes" : "",
  }));
}

export function pageCountFor(records: number): number {
  return Math.max(1, Math.ceil(records / ROWS_PER_PAGE)) + 1; // table pages plus the signature page
}

export function signatureLines(s: SignatureBlock): string[] {
  return [
    `Chain head hash: ${s.chainHeadHash}`,
    `Chain verified: ${s.chainVerified ? "yes" : `NO (first break ${s.firstBreak ?? "unknown"})`}`,
    `Latest hash anchor: ${s.latestAnchor}`,
    `Anchor matches chain head: ${s.anchorMatchesHead}`,
    `CSV export SHA-256: ${s.csvSha256}`,
    `CSV export signature (Ed25519, base64): ${s.csvSignature}`,
    `Signing public key (Ed25519 raw, base64): ${s.publicKey}`,
    "This PDF's own detached Ed25519 signature is delivered in the X-Remu-Signature header.",
  ];
}

function fit(font: PDFFont, text: string, size: number, width: number): string {
  if (font.widthOfTextAtSize(text, size) <= width) return text;
  let t = text;
  while (t.length > 1 && font.widthOfTextAtSize(`${t}…`, size) > width) t = t.slice(0, -1);
  return `${t}…`;
}

export async function loadFonts(): Promise<{ regular: Uint8Array; bold: Uint8Array }> {
  const [regular, bold] = await Promise.all([
    readFile(path.join(FONT_DIR, FONT_REGULAR)),
    readFile(path.join(FONT_DIR, FONT_BOLD)),
  ]);
  return { regular, bold };
}

export async function renderPdf(d: ExportData, sig: SignatureBlock): Promise<Uint8Array> {
  const fonts = await loadFonts();
  const doc = await PDFDocument.create();
  doc.registerFontkit(fontkit);
  doc.setTitle(`Remu consent export ${d.shop.id}`);
  doc.setAuthor("Remu cloud");
  doc.setSubject(`Chain head ${sig.chainHeadHash}`);
  doc.setKeywords(["remu", "consent", d.shop.id]);
  doc.setCreationDate(d.generatedAt);
  doc.setModificationDate(d.generatedAt);
  doc.setProducer("Remu cloud (pdf-lib)");
  doc.setCreator("Remu cloud");
  const regular = await doc.embedFont(fonts.regular, { subset: false });
  const bold = await doc.embedFont(fonts.bold, { subset: false });
  const ink = rgb(0.1, 0.1, 0.12);
  const muted = rgb(0.4, 0.4, 0.45);

  const rows = tableRows(d);
  const tablePages = Math.max(1, Math.ceil(rows.length / ROWS_PER_PAGE));
  const total = tablePages + 1;
  const footer = (page: PDFPage, n: number) =>
    page.drawText(`${d.shop.id} · page ${n} of ${total}`, { x: MARGIN, y: 20, size: 8, font: regular, color: muted });

  for (let p = 0; p < tablePages; p++) {
    const page = doc.addPage(PAGE);
    let y = PAGE[1] - MARGIN;
    if (p === 0) {
      page.drawText("Remu consent export", { x: MARGIN, y: y - 14, size: 18, font: bold, color: ink });
      y -= 38;
      const header = [
        `Shop: ${d.shop.name} (${d.shop.city}) · ${d.shop.id}`,
        `Generated: ${toIstIso(d.generatedAt)} · Records: ${d.records.length}`,
        `Script version: ${d.shop.script_version} · Notice version: ${d.shop.notice_version}`,
      ];
      for (const line of header) {
        page.drawText(fit(regular, line, 10, PAGE[0] - 2 * MARGIN), { x: MARGIN, y, size: 10, font: regular, color: ink });
        y -= 15;
      }
      page.drawText(fit(regular, `Cashier line ${d.shop.script_version}: ${CASHIER_LINE_HI}`, 9, PAGE[0] - 2 * MARGIN), {
        x: MARGIN, y, size: 9, font: regular, color: ink,
      });
      y -= 13;
      page.drawText(fit(regular, CASHIER_LINE_EN, 9, PAGE[0] - 2 * MARGIN), { x: MARGIN, y, size: 9, font: regular, color: muted });
      y -= 22;
    } else {
      page.drawText(`Remu consent export · ${d.shop.id} (continued)`, { x: MARGIN, y: y - 10, size: 11, font: bold, color: ink });
      y -= 30;
    }
    let x = MARGIN;
    for (const c of TABLE_COLUMNS) {
      page.drawText(fit(bold, c.title, 8, c.width - 4), { x, y, size: 8, font: bold, color: ink });
      x += c.width;
    }
    y -= 4;
    page.drawLine({ start: { x: MARGIN, y }, end: { x: PAGE[0] - MARGIN, y }, thickness: 0.5, color: muted });
    y -= 12;
    for (const r of rows.slice(p * ROWS_PER_PAGE, (p + 1) * ROWS_PER_PAGE)) {
      x = MARGIN;
      for (const c of TABLE_COLUMNS) {
        page.drawText(fit(regular, r[c.key], 7.5, c.width - 4), { x, y, size: 7.5, font: regular, color: ink });
        x += c.width;
      }
      y -= 17;
    }
    if (rows.length === 0) page.drawText("No consent records.", { x: MARGIN, y, size: 9, font: regular, color: muted });
    footer(page, p + 1);
  }

  const last = doc.addPage(PAGE);
  let y = PAGE[1] - MARGIN - 14;
  last.drawText("Chain and signature", { x: MARGIN, y, size: 16, font: bold, color: ink });
  y -= 28;
  for (const line of signatureLines(sig)) {
    last.drawText(fit(regular, line, 9, PAGE[0] - 2 * MARGIN), { x: MARGIN, y, size: 9, font: regular, color: ink });
    y -= 16;
  }
  footer(last, total);
  // Plain (non object-stream) output keeps the font dictionaries readable for audits and tests.
  return doc.save({ useObjectStreams: false });
}
