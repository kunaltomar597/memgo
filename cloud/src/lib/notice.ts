import { clearNoticeFailed, flagNoticeFailed, getMember } from "./db/members";
import { queueInbox } from "./db/inbox";
import type { Db } from "./db/types";
import { getNoticeByConsent } from "./db/whatsappMessages";
import type { Deps } from "./deps";
import { ConfigError } from "./env";
import { log } from "./log";
import { phoneHash as hashPhone } from "./phone";
import { getShop, noticeLanguageOf } from "./shops";
import type { EventPayload } from "./schemas";
import { toIstIso } from "./time";
import { WhatsAppSendError } from "./whatsapp";

/** A failed send is retried this many times before it is flagged on the member (TASK-063). */
export const NOTICE_RETRIES = 3;
const BACKOFF_MS = [500, 2000, 5000];

export type NoticeRequest = EventPayload<"notice_request">;

/**
 * Inside the ingest transaction: records the intended send, keyed by consent record, so a resent
 * batch or a duplicate notice_request never sends twice. Returns true when a send should follow.
 */
export async function prepareNotice(db: Db, shopId: string, req: NoticeRequest): Promise<boolean> {
  const member = await getMember(db, req.member_id);
  if (!member || member.shop_id !== shopId) return false;
  if (member.status !== "pending_notice") {
    log.info("notice_skipped", { member_id: member.id, status: member.status });
    return false;
  }
  const shop = await getShop(db, shopId);
  const language = noticeLanguageOf(shop, req.language);
  const phoneHash = hashPhone(req.phone_e164);
  const inserted = await db.query<{ id: string }>(
    `insert into whatsapp_messages (direction, kind, shop_id, member_id, consent_record_id, language, phone_e164, phone_hash, status)
     values ('out', 'notice', $1, $2, $3, $4, $5, $6, 'queued')
     on conflict (consent_record_id) where direction = 'out' do nothing returning id::text`,
    [shopId, req.member_id, req.consent_record_id, language, req.phone_e164, phoneHash],
  );
  if (inserted.length === 1) return true;
  const existing = await getNoticeByConsent(db, req.consent_record_id, true);
  if (existing?.status === "send_failed") {
    await db.query("update whatsapp_messages set status = 'queued', updated_at = now() where id = $1::bigint", [existing.id]);
    return true;
  }
  log.info("notice_duplicate_suppressed", { member_id: req.member_id, consent_id: req.consent_record_id });
  return false;
}

/** After commit: sends the template, retrying, then records the wamid or flags the member. */
export async function sendPreparedNotice(deps: Deps, req: NoticeRequest): Promise<"sent" | "failed"> {
  const { db } = deps;
  const row = await getNoticeByConsent(db, req.consent_record_id);
  if (!row || row.status !== "queued") return row?.status === "sent" ? "sent" : "failed";
  const language = (row.language === "en" ? "en" : "hi") as "en" | "hi";
  let attempts = row.send_attempts;
  let lastCode = "unknown";
  let templateName: string | null = null;
  for (let i = 0; i <= NOTICE_RETRIES; i++) {
    attempts++;
    try {
      templateName = deps.whatsapp.templateName(language);
      const { wamid } = await deps.whatsapp.sendNotice({
        to: req.phone_e164,
        language,
        shopName: req.shop_name,
        firstName: req.first_name,
        date: req.date,
      });
      const now = deps.now();
      await db.tx(async (tx) => {
        await tx.query(
          `update whatsapp_messages set status = 'sent', wamid = $2, template_name = $3, sent_at = $4::timestamptz,
             status_ts = $4::timestamptz, send_attempts = $5, last_error_code = null, updated_at = now() where id = $1::bigint`,
          [row.id, wamid, templateName, now.toISOString(), attempts],
        );
        await clearNoticeFailed(tx, req.member_id);
        if (row.shop_id) {
          await queueInbox(tx, row.shop_id, "notice_status", {
            member_id: req.member_id,
            consent_record_id: req.consent_record_id,
            wamid,
            whatsapp_status: "sent",
            status_ts: toIstIso(now),
            delivered_at: null,
          });
        }
      });
      log.info("notice_sent", { member_id: req.member_id, consent_id: req.consent_record_id, count: attempts });
      return "sent";
    } catch (e) {
      if (e instanceof ConfigError) {
        lastCode = "config";
        break;
      }
      if (!(e instanceof WhatsAppSendError)) throw e;
      lastCode = e.code;
      if (!e.retryable) break;
      if (i < NOTICE_RETRIES) await deps.sleep(BACKOFF_MS[i] ?? 5000);
    }
  }
  const now = deps.now();
  await db.tx(async (tx) => {
    await tx.query(
      `update whatsapp_messages set status = 'send_failed', send_attempts = $2, last_error_code = $3,
         template_name = coalesce($4, template_name), updated_at = now() where id = $1::bigint`,
      [row.id, attempts, lastCode, templateName],
    );
    await flagNoticeFailed(tx, req.member_id, now);
  });
  log.warn("notice_send_failed", { member_id: req.member_id, consent_id: req.consent_record_id, reason: lastCode, count: attempts });
  return "failed";
}
