import { flagAnchorMismatch } from "./db/devices";
import type { Db } from "./db/types";
import { mirrorHead } from "./db/consent";
import { log } from "./log";
import { reportMessage } from "./sentry";
import { istDate } from "./time";

/** Anchors are accepted for today or yesterday on the Asia/Kolkata calendar only (TASK-112). */
export function anchorDateAllowed(date: string, now: Date): boolean {
  return date === istDate(now) || date === istDate(new Date(now.getTime() - 24 * 3600_000));
}

/**
 * POST /api/hub/anchor (TASK-061 cloud half). Stores the hub's nightly chain head in hash_anchors
 * and compares it with the head of the mirrored chain. Insert-only (TASK-112): the first anchor for a
 * shop and date stands; a later post for that date never overwrites it and gets `match` against the
 * stored row (true only when the stored anchor matched the mirror and the posted head equals it). A
 * mismatch, or a repeat with a different head, is flagged on the device and reported to Sentry.
 */
export async function recordAnchor(
  db: Db,
  device: { id: string; shop_id: string },
  date: string,
  chainHeadHash: string,
  now: Date,
): Promise<{ match: boolean }> {
  return db.tx(async (tx) => {
    const head = await mirrorHead(tx, device.shop_id);
    const inserted = await tx.query<{ match: boolean }>(
      `insert into hash_anchors (shop_id, device_id, date, chain_head_hash, mirror_head_hash, match, received_at)
       values ($1, $2, $3::date, $4, $5, $6, $7::timestamptz)
       on conflict (shop_id, date) do nothing returning match`,
      [device.shop_id, device.id, date, chainHeadHash, head, head === chainHeadHash, now.toISOString()],
    );
    if (inserted[0]) {
      if (!inserted[0].match) await mismatch(tx, device, now, "anchor_mismatch");
      return { match: inserted[0].match };
    }
    const stored = (
      await tx.query<{ chain_head_hash: string; match: boolean }>(
        "select chain_head_hash, match from hash_anchors where shop_id = $1 and date = $2::date",
        [device.shop_id, date],
      )
    )[0]!;
    const sameHead = stored.chain_head_hash === chainHeadHash;
    if (!sameHead) await mismatch(tx, device, now, "anchor_conflict");
    return { match: stored.match && sameHead };
  });
}

async function mismatch(db: Db, device: { id: string; shop_id: string }, now: Date, reason: "anchor_mismatch" | "anchor_conflict") {
  await flagAnchorMismatch(db, device.id, now);
  log.warn("anchor_mismatch", { shop_id: device.shop_id, device_id: device.id, reason });
  reportMessage("hash anchor mismatch", { shop_id: device.shop_id, device_id: device.id, reason });
}

export async function latestAnchor(db: Db, shopId: string) {
  const rows = await db.query<{ date: string; chain_head_hash: string; match: boolean; received_at: Date }>(
    `select to_char(date, 'YYYY-MM-DD') as date, chain_head_hash, match, received_at from hash_anchors
     where shop_id = $1 order by date desc limit 1`,
    [shopId],
  );
  return rows[0];
}
