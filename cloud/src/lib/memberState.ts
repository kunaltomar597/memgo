import type { Db } from "./db/types";
import type { MemberStatus } from "./db/members";
import { log } from "./log";

/**
 * The cloud's member state machine (docs/ARCHITECTURE.md section 5). Every member status write in
 * the cloud goes through this file. `transition()` can never set `active`; only
 * `activateOnDelivery()` can, and only the WhatsApp delivered webhook calls it. A database trigger
 * (0002_members.sql) enforces the same rules, so a stray UPDATE fails too.
 */
export const ALLOWED: Readonly<Record<MemberStatus, readonly MemberStatus[]>> = {
  pending_notice: ["active", "withdrawn"],
  active: ["withdrawn", "expired"],
  withdrawn: [],
  expired: [],
};

export function canTransition(from: MemberStatus, to: MemberStatus): boolean {
  return ALLOWED[from].includes(to);
}

export type Actor = "cloud" | "hub" | "owner";

export interface TransitionResult {
  changed: boolean;
  from: MemberStatus | null;
  shopId: string | null;
}

async function apply(
  db: Db,
  memberId: string,
  to: MemberStatus,
  by: Actor,
  reason: string,
  at: Date,
): Promise<TransitionResult> {
  return db.tx(async (tx) => {
    const rows = await tx.query<{ status: MemberStatus; shop_id: string }>(
      "select status, shop_id from members where id = $1 for update",
      [memberId],
    );
    const cur = rows[0];
    if (!cur) return { changed: false, from: null, shopId: null };
    if (cur.status === to || !canTransition(cur.status, to)) {
      if (cur.status !== to) log.info("member_transition_refused", { member_id: memberId, status: cur.status, reason: to });
      return { changed: false, from: cur.status, shopId: cur.shop_id };
    }
    if (to === "active") {
      await tx.query("select set_config('remu.activation', 'notice_delivered', true)");
    }
    await tx.query("update members set status = $2, updated_at = now() where id = $1", [memberId, to]);
    if (to === "active") {
      await tx.query("select set_config('remu.activation', '', true)");
    }
    await tx.query(
      `insert into member_transitions (shop_id, member_id, from_status, to_status, by_actor, reason, at)
       values ($1, $2, $3, $4, $5, $6, $7::timestamptz)`,
      [cur.shop_id, memberId, cur.status, to, by, reason, at.toISOString()],
    );
    log.info("member_transition", { member_id: memberId, shop_id: cur.shop_id, status: to, reason });
    return { changed: true, from: cur.status, shopId: cur.shop_id };
  });
}

/** Withdrawal or expiry. Refuses `active`, which only notice delivery may set. */
export async function transition(
  db: Db,
  args: { memberId: string; to: Exclude<MemberStatus, "active" | "pending_notice">; by: Actor; reason: string; at: Date },
): Promise<TransitionResult> {
  if ((args.to as MemberStatus) === "active" || (args.to as MemberStatus) === "pending_notice") {
    throw new Error("transition() cannot set active or pending_notice");
  }
  return apply(db, args.memberId, args.to, args.by, args.reason, args.at);
}

/** pending_notice to active on a WhatsApp `delivered` receipt. The only path to active. */
export async function activateOnDelivery(db: Db, args: { memberId: string; at: Date }): Promise<TransitionResult> {
  return apply(db, args.memberId, "active", "cloud", "notice_delivered", args.at);
}
