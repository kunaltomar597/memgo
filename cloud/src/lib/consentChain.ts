import { createHash } from "node:crypto";
import { canonicalJson } from "./canonical";

/** The consent record of docs/API_CONTRACT.md section 3, in CSV column order. */
export const CONSENT_COLUMNS = [
  "id",
  "shop_id",
  "member_id",
  "bill_no",
  "ts",
  "cashier_id",
  "channel",
  "script_version",
  "notice_version",
  "purposes_json",
  "notice_delivery_json",
  "adult_check",
  "withdrawn_at",
  "template_deleted_at",
  "prev_hash",
  "record_hash",
] as const;

export const GENESIS_HASH = "sha256:genesis";

export interface ConsentRecord {
  id: string;
  shop_id: string;
  member_id: string;
  bill_no: string | null;
  ts: string;
  cashier_id: string | null;
  channel: string;
  script_version: string;
  notice_version: string;
  purposes_json: { recognise_at_entry: boolean; whatsapp_offers: boolean };
  notice_delivery_json: {
    printed_on_bill: boolean;
    whatsapp_msg_id: string | null;
    whatsapp_status: string | null;
    delivered_at: string | null;
    thumbs_up_at: string | null;
  };
  adult_check: string | null;
  withdrawn_at: string | null;
  template_deleted_at: string | null;
  prev_hash: string;
  record_hash: string;
}

export function sha256Hex(data: string | Uint8Array): string {
  return createHash("sha256").update(data).digest("hex");
}

/** "sha256:" + hex(sha256(canonical(record without record_hash))). */
export function computeRecordHash(record: Omit<ConsentRecord, "record_hash"> | ConsentRecord): string {
  const rest: Record<string, unknown> = { ...record };
  delete rest.record_hash;
  return `sha256:${sha256Hex(canonicalJson(rest))}`;
}

export interface ChainCheck {
  ok: boolean;
  head: string;
  count: number;
  firstBreak: { id: string; reason: "hash" | "link" } | null;
}

/** Walks records in chain order from genesis, checking every hash and link. */
export function verifyChain(records: readonly ConsentRecord[]): ChainCheck {
  let head = GENESIS_HASH;
  let firstBreak: ChainCheck["firstBreak"] = null;
  for (const r of records) {
    if (!firstBreak && computeRecordHash(r) !== r.record_hash) firstBreak = { id: r.id, reason: "hash" };
    if (!firstBreak && r.prev_hash !== head) firstBreak = { id: r.id, reason: "link" };
    head = r.record_hash;
  }
  return { ok: firstBreak === null, head, count: records.length, firstBreak };
}

/**
 * Orders the consent records of one ingest batch for linking. Records that link from the current
 * head (by prev_hash) are taken first, following the chain; anything left over is taken by ts then
 * id. This handles out-of-order arrival without trusting the hub clock for linked records.
 */
export function orderForChain<T extends Pick<ConsentRecord, "id" | "ts" | "prev_hash" | "record_hash">>(
  records: readonly T[],
  currentHead: string,
): T[] {
  const pending = [...records];
  const out: T[] = [];
  let head = currentHead;
  const byTsThenId = (a: T, b: T) => {
    const ta = Date.parse(a.ts);
    const tb = Date.parse(b.ts);
    if (ta !== tb) return ta - tb;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  };
  while (pending.length > 0) {
    let idx = pending.findIndex((r) => r.prev_hash === head);
    if (idx === -1) {
      pending.sort(byTsThenId);
      idx = 0;
    }
    const [next] = pending.splice(idx, 1);
    out.push(next!);
    head = next!.record_hash;
  }
  return out;
}
