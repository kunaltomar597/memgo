import { latestAnchor } from "./anchor";
import { canonicalJson } from "./canonical";
import { CONSENT_COLUMNS, sha256Hex, verifyChain, type ChainCheck, type ConsentRecord } from "./consentChain";
import { listConsentRecords } from "./db/consent";
import type { Db } from "./db/types";
import { ApiError } from "./http";
import { getShop, type ShopRow } from "./shops";
import { toIstIso } from "./time";

/**
 * Consent export data (TASK-092, TASK-093). Records come from the mirror in chain order and the
 * chain is re-verified on every export.
 */
export interface ExportData {
  shop: ShopRow;
  records: ConsentRecord[];
  chain: ChainCheck;
  anchor: { date: string; chain_head_hash: string; match: boolean } | null;
  anchorMatchesHead: boolean | null;
  phoneHashPrefix: Map<string, string>;
  generatedAt: Date;
}

export async function loadExport(db: Db, shopId: string, now: Date): Promise<ExportData> {
  const shop = await getShop(db, shopId);
  if (!shop) throw new ApiError(404, "not_found", "no such shop");
  const records = await listConsentRecords(db, shopId);
  const chain = verifyChain(records);
  const a = await latestAnchor(db, shopId);
  const hashes = await db.query<{ id: string; phone_hash: string }>("select id, phone_hash from members where shop_id = $1", [shopId]);
  const phoneHashPrefix = new Map(hashes.map((h) => [h.id, h.phone_hash.slice(0, 12)]));
  return {
    shop,
    records,
    chain,
    anchor: a ? { date: a.date, chain_head_hash: a.chain_head_hash, match: a.match } : null,
    anchorMatchesHead: a ? a.chain_head_hash === chain.head : null,
    phoneHashPrefix,
    generatedAt: now,
  };
}

/**
 * CSV formula injection (TASK-112): a cell that starts with =, +, -, @, tab or carriage return gets a
 * leading single quote so a spreadsheet shows it as text. A cell that already starts with a quote
 * gets one more, so a reader restores the stored value exactly by dropping one leading quote from
 * any cell that starts with a quote.
 */
export function neutraliseFormula(s: string): string {
  return /^[=+\-@\t\r']/.test(s) ? `'${s}` : s;
}

export function csvCell(v: unknown): string {
  if (v === null || v === undefined) return "";
  const s = neutraliseFormula(typeof v === "object" ? canonicalJson(v) : String(v));
  return /[",\r\n#]/.test(s) || /^\s|\s$/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/**
 * CSV: a header row with the columns of docs/API_CONTRACT.md section 3, one row per record in chain
 * order (JSON columns as canonical JSON), then trailing comment lines that start with "#" giving
 * the chain head and the latest anchor. Cells are neutralised against formula injection
 * (neutraliseFormula) before quoting; the signature covers the final bytes, trailer included.
 */
export function renderCsv(d: ExportData): string {
  const lines = [CONSENT_COLUMNS.join(",")];
  for (const r of d.records) lines.push(CONSENT_COLUMNS.map((c) => csvCell(r[c])).join(","));
  const trailer: [string, string][] = [
    ["shop_id", d.shop.id],
    ["generated_at", toIstIso(d.generatedAt) ?? ""],
    ["record_count", String(d.records.length)],
    ["chain_head_hash", d.chain.head],
    ["chain_verified", String(d.chain.ok)],
    ["chain_first_break", d.chain.firstBreak ? `${d.chain.firstBreak.id}:${d.chain.firstBreak.reason}` : ""],
    ["latest_anchor_date", d.anchor?.date ?? ""],
    ["latest_anchor_hash", d.anchor?.chain_head_hash ?? ""],
    ["anchor_matches_head", d.anchorMatchesHead === null ? "" : String(d.anchorMatchesHead)],
    ["signature", "Ed25519 detached, base64, in the X-Remu-Signature header"],
  ];
  for (const [k, v] of trailer) lines.push(`# ${k}: ${v}`);
  return `${lines.join("\r\n")}\r\n`;
}

export function fileSha256(bytes: Uint8Array): string {
  return sha256Hex(bytes);
}
