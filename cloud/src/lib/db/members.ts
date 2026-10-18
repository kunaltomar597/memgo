import type { Db } from "./types";

export type MemberStatus = "pending_notice" | "active" | "withdrawn" | "expired";

export interface MemberRow {
  id: string;
  shop_id: string;
  household_id: string | null;
  display_name: string;
  phone_hash: string;
  status: MemberStatus;
  member_since: string;
  last_seen_at: Date | null;
  discount_flag: boolean;
  notes: string | null;
  has_template: boolean;
  notice_failed: boolean;
  created_at: Date;
}

export async function getMember(db: Db, id: string, forUpdate = false): Promise<MemberRow | undefined> {
  const rows = await db.query<MemberRow>(`select * from members where id = $1 ${forUpdate ? "for update" : ""}`, [id]);
  return rows[0];
}

export async function findMembersByPhoneHash(db: Db, phoneHash: string, shopId?: string): Promise<MemberRow[]> {
  return db.query<MemberRow>(
    `select * from members where phone_hash = $1 and ($2::text is null or shop_id = $2::text) order by id`,
    [phoneHash, shopId ?? null],
  );
}

export interface MemberUpsertFields {
  id: string;
  shop_id: string;
  household_id: string | null;
  display_name: string;
  phone_hash: string;
  member_since: string;
  last_seen_at: string | null;
  discount_flag: boolean;
  notes: string | null;
  has_template: boolean;
  created_at: string;
  updated_at: string;
}

/** Inserts a new mirrored member; status is chosen by the caller (never active). */
export async function insertMember(db: Db, m: MemberUpsertFields, status: Exclude<MemberStatus, "active">): Promise<void> {
  await db.query(
    `insert into members (id, shop_id, household_id, display_name, phone_hash, status, member_since, last_seen_at,
       discount_flag, notes, has_template, created_at, updated_at)
     values ($1,$2,$3,$4,$5,$6,$7,$8::timestamptz,$9,$10,$11,$12::timestamptz,$13::timestamptz)`,
    [m.id, m.shop_id, m.household_id, m.display_name, m.phone_hash, status, m.member_since, m.last_seen_at,
      m.discount_flag, m.notes, m.has_template, m.created_at, m.updated_at],
  );
}

/** Updates the non-status fields of a mirrored member. Status changes go through memberState.ts. */
export async function updateMemberFields(db: Db, m: MemberUpsertFields): Promise<void> {
  await db.query(
    `update members set household_id = $2, display_name = $3, phone_hash = $4, member_since = $5,
       last_seen_at = greatest(last_seen_at, $6::timestamptz), discount_flag = $7, notes = $8, has_template = $9,
       updated_at = greatest(updated_at, $10::timestamptz)
     where id = $1`,
    [m.id, m.household_id, m.display_name, m.phone_hash, m.member_since, m.last_seen_at, m.discount_flag, m.notes,
      m.has_template, m.updated_at],
  );
}

export async function flagNoticeFailed(db: Db, memberId: string, at: Date): Promise<void> {
  await db.query("update members set notice_failed = true, notice_failed_at = $2::timestamptz where id = $1", [
    memberId,
    at.toISOString(),
  ]);
}

export async function clearNoticeFailed(db: Db, memberId: string): Promise<void> {
  await db.query("update members set notice_failed = false, notice_failed_at = null where id = $1", [memberId]);
}
