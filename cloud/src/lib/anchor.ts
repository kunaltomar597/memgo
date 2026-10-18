import { flagAnchorMismatch } from "./db/devices";
import type { Db } from "./db/types";
import { mirrorHead } from "./db/consent";
import { log } from "./log";
import { reportMessage } from "./sentry";

/**
 * POST /api/hub/anchor (TASK-061 cloud half). Stores the hub's nightly chain head in hash_anchors
 * (one row per shop per day; a repeat for the same day replaces it) and compares it with the head
 * of the mirrored chain. A mismatch is flagged on the device record and reported to Sentry.
 */
export async function recordAnchor(
  db: Db,
  device: { id: string; shop_id: string },
  date: string,
  chainHeadHash: string,
  now: Date,
): Promise<{ match: boolean }> {
  const head = await mirrorHead(db, device.shop_id);
  const match = head === chainHeadHash;
  await db.query(
    `insert into hash_anchors (shop_id, device_id, date, chain_head_hash, mirror_head_hash, match, received_at)
     values ($1, $2, $3::date, $4, $5, $6, $7::timestamptz)
     on conflict (shop_id, date) do update set chain_head_hash = excluded.chain_head_hash,
       mirror_head_hash = excluded.mirror_head_hash, match = excluded.match, received_at = excluded.received_at,
       device_id = excluded.device_id`,
    [device.shop_id, device.id, date, chainHeadHash, head, match, now.toISOString()],
  );
  if (!match) {
    await flagAnchorMismatch(db, device.id, now);
    log.warn("anchor_mismatch", { shop_id: device.shop_id, device_id: device.id });
    reportMessage("hash anchor mismatch", { shop_id: device.shop_id, device_id: device.id });
  }
  return { match };
}

export async function latestAnchor(db: Db, shopId: string) {
  const rows = await db.query<{ date: string; chain_head_hash: string; match: boolean; received_at: Date }>(
    `select to_char(date, 'YYYY-MM-DD') as date, chain_head_hash, match, received_at from hash_anchors
     where shop_id = $1 order by date desc limit 1`,
    [shopId],
  );
  return rows[0];
}
