import type { Db } from "./types";

/** Hub inbox (GET /api/hub/inbox). Cursors are increasing integers as strings. */
export type InboxType = "member_status" | "notice_status" | "thumbs_up" | "visit_feedback" | "config" | "device_revoked";

export interface InboxMessage {
  id: string;
  type: InboxType;
  payload: Record<string, unknown>;
}

/** Queues one message for every paired device of the shop (or one named device). */
export async function queueInbox(
  db: Db,
  shopId: string,
  type: InboxType,
  payload: Record<string, unknown>,
  deviceId?: string,
): Promise<number> {
  const rows = await db.query<{ id: string }>(
    `insert into inbox_messages (shop_id, device_id, type, payload)
     select $1, d.id, $2, $3::jsonb from devices d
     where d.shop_id = $1 and ($4::text is null and d.status = 'paired' or d.id = $4::text)
     returning id::text`,
    [shopId, type, JSON.stringify(payload), deviceId ?? null],
  );
  return rows.length;
}

/** Acknowledges every message up to `after`, then returns the next unacknowledged ones. */
export async function readInbox(
  db: Db,
  deviceId: string,
  after: bigint,
  limit = 100,
): Promise<{ messages: InboxMessage[]; cursor: string }> {
  const afterText = after.toString();
  await db.query(
    "update inbox_messages set acked_at = now() where device_id = $1 and id <= $2::bigint and acked_at is null",
    [deviceId, afterText],
  );
  const rows = await db.query<InboxMessage>(
    `select id::text as id, type, payload from inbox_messages
     where device_id = $1 and id > $2::bigint and acked_at is null order by inbox_messages.id limit $3`,
    [deviceId, afterText, limit],
  );
  const cursor = rows.length > 0 ? rows[rows.length - 1]!.id : afterText;
  return { messages: rows, cursor };
}

export async function listInboxForShop(db: Db, shopId: string): Promise<(InboxMessage & { device_id: string })[]> {
  return db.query(
    "select id::text as id, device_id, type, payload from inbox_messages where shop_id = $1 order by inbox_messages.id",
    [shopId],
  );
}
