import type { Db } from "./types";

export interface WaMessageRow {
  id: string;
  direction: "out" | "in";
  kind: "notice" | "stop" | "thumbs_up" | "text" | "other";
  shop_id: string | null;
  member_id: string | null;
  consent_record_id: string | null;
  template_name: string | null;
  language: string | null;
  wamid: string | null;
  context_wamid: string | null;
  phone_e164: string | null;
  phone_hash: string | null;
  status: string;
  status_ts: Date | null;
  sent_at: Date | null;
  delivered_at: Date | null;
  read_at: Date | null;
  failed_at: Date | null;
  thumbs_up_at: Date | null;
  delivery_seconds: number | null;
  send_attempts: number;
  last_error_code: string | null;
  applied_at: Date | null;
  received_ts: Date | null;
  raw_webhook_json: unknown[];
  created_at: Date;
}

const COLS = `id::text as id, direction, kind, shop_id, member_id, consent_record_id, template_name, language, wamid,
  context_wamid, phone_e164, phone_hash, status, status_ts, sent_at, delivered_at, read_at, failed_at, thumbs_up_at,
  delivery_seconds, send_attempts, last_error_code, applied_at, received_ts, raw_webhook_json, created_at`;

export async function getNoticeByConsent(db: Db, consentRecordId: string, forUpdate = false): Promise<WaMessageRow | undefined> {
  const rows = await db.query<WaMessageRow>(
    `select ${COLS} from whatsapp_messages where direction = 'out' and consent_record_id = $1 ${forUpdate ? "for update" : ""}`,
    [consentRecordId],
  );
  return rows[0];
}

export async function getOutboundByWamid(db: Db, wamid: string, forUpdate = false): Promise<WaMessageRow | undefined> {
  const rows = await db.query<WaMessageRow>(
    `select ${COLS} from whatsapp_messages where direction = 'out' and wamid = $1 ${forUpdate ? "for update" : ""}`,
    [wamid],
  );
  return rows[0];
}

export async function latestNoticeForPhoneHash(db: Db, hash: string): Promise<WaMessageRow | undefined> {
  const rows = await db.query<WaMessageRow>(
    `select ${COLS} from whatsapp_messages where direction = 'out' and phone_hash = $1
     and wamid is not null order by created_at desc limit 1`,
    [hash],
  );
  return rows[0];
}

export async function appendRaw(db: Db, id: string, raw: unknown): Promise<void> {
  await db.query(
    "update whatsapp_messages set raw_webhook_json = raw_webhook_json || jsonb_build_array($2::text::jsonb), updated_at = now() where id = $1::bigint",
    [id, JSON.stringify(raw)],
  );
}
