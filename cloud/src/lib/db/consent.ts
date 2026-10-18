import { GENESIS_HASH, type ConsentRecord } from "../consentChain";
import type { Db } from "./types";

/** Head of the mirrored chain for a shop: the last inserted record's hash, or genesis. */
export async function mirrorHead(db: Db, shopId: string): Promise<string> {
  const rows = await db.query<{ record_hash: string }>(
    "select record_hash from consent_records where shop_id = $1 order by mirror_seq desc limit 1",
    [shopId],
  );
  return rows[0]?.record_hash ?? GENESIS_HASH;
}

/** Every mirrored record of the shop in chain (arrival) order, exactly as the hub sent it. */
export async function listConsentRecords(db: Db, shopId: string): Promise<ConsentRecord[]> {
  return db.query<ConsentRecord>(
    `select id, shop_id, member_id, bill_no, ts, cashier_id, channel, script_version, notice_version,
       purposes_json, notice_delivery_json, adult_check, withdrawn_at, template_deleted_at, prev_hash, record_hash
     from consent_records where shop_id = $1 order by mirror_seq`,
    [shopId],
  );
}
