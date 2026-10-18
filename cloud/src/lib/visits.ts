import { queueInbox } from "./db/inbox";
import type { Db } from "./db/types";
import { ApiError } from "./http";
import { toIstIso } from "./time";

export async function visitShop(db: Db, visitId: string): Promise<string> {
  const rows = await db.query<{ shop_id: string }>("select shop_id from visits where id = $1", [visitId]);
  if (!rows[0]) throw new ApiError(404, "not_found", "no such visit");
  return rows[0].shop_id;
}

/**
 * The `alert` payload of docs/API_CONTRACT.md section 1, built from the mirror (TASK-069). A member
 * who has withdrawn or expired gets 410 gone: their card is not shown again (TASK-112).
 */
export async function buildCard(db: Db, visitId: string) {
  const rows = await db.query<{
    visit_id: string;
    member_id: string;
    ts: Date;
    score: number;
    frames_agreed: number;
    display_name: string;
    status: string;
    notes: string | null;
    member_since: string;
    household_label: string | null;
  }>(
    `select v.id as visit_id, v.member_id, v.ts, v.score, v.frames_agreed, m.display_name, m.status, m.notes, m.member_since,
       h.label as household_label
     from visits v join members m on m.id = v.member_id left join households h on h.id = m.household_id
     where v.id = $1`,
    [visitId],
  );
  const v = rows[0];
  if (!v) throw new ApiError(404, "not_found", "no such visit");
  if (v.status === "withdrawn" || v.status === "expired") throw new ApiError(410, "gone", "member withdrawn or expired");
  const prev = await db.query<{ ts: Date }>(
    "select ts from visits where member_id = $1 and (ts < $2::timestamptz or (ts = $2::timestamptz and id < $3)) order by ts desc, id desc limit 1",
    [v.member_id, new Date(v.ts).toISOString(), v.visit_id],
  );
  const purchase = await db.query<{ amount: string; items_text: string | null }>(
    "select amount::text as amount, items_text from purchases where member_id = $1 order by ts desc, id desc limit 1",
    [v.member_id],
  );
  const p = purchase[0];
  return {
    visit_id: v.visit_id,
    member_id: v.member_id,
    display_name: v.display_name,
    household_label: v.household_label,
    last_visit: prev[0] ? toIstIso(prev[0].ts) : v.member_since,
    last_purchase: p ? { amount: Number(p.amount), items: p.items_text } : null,
    notes: v.notes,
    score: v.score,
    frames_agreed: v.frames_agreed,
  };
}

/** Greeted or Not them off the shop WiFi: mirror first, then the hub learns through its inbox. */
export async function recordFeedback(
  db: Db,
  visitId: string,
  change: { greeted?: boolean; greeted_by?: string | null; not_them?: boolean },
): Promise<void> {
  await db.tx(async (tx) => {
    const rows = await tx.query<{ shop_id: string; greeted: boolean; greeted_by: string | null; not_them: boolean }>(
      "select shop_id, greeted, greeted_by, not_them from visits where id = $1 for update",
      [visitId],
    );
    const cur = rows[0];
    if (!cur) throw new ApiError(404, "not_found", "no such visit");
    const next = {
      greeted: change.greeted ?? cur.greeted,
      greeted_by: change.greeted_by !== undefined ? change.greeted_by : cur.greeted_by,
      not_them: change.not_them ?? cur.not_them,
    };
    await tx.query("update visits set greeted = $2, greeted_by = $3, not_them = $4, updated_at = now() where id = $1", [
      visitId,
      next.greeted,
      next.greeted_by,
      next.not_them,
    ]);
    await queueInbox(tx, cur.shop_id, "visit_feedback", { visit_id: visitId, ...next });
  });
}
