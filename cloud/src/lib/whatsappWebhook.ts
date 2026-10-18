import { createHmac, timingSafeEqual } from "node:crypto";
import { flagNoticeFailed, findMembersByPhoneHash } from "./db/members";
import { queueInbox } from "./db/inbox";
import type { Db } from "./db/types";
import { appendRaw, getOutboundByWamid, latestNoticeForPhoneHash } from "./db/whatsappMessages";
import type { Deps } from "./deps";
import { log } from "./log";
import { activateOnDelivery, transition } from "./memberState";
import { e164FromWhatsApp, phoneHash as hashPhone } from "./phone";
import { toIstIso, unixSecondsToDate } from "./time";
import { THUMBS_UP } from "./whatsapp";

/**
 * WhatsApp webhooks (TASK-064 to TASK-066). The signature is checked over the raw body before
 * anything is parsed. Statuses update whatsapp_messages; only `delivered` activates a member.
 * A thumbs-up button reply queues thumbs_up; a text "STOP" withdraws the sender's members.
 */
export function verifySignature(rawBody: string, header: string | null, appSecret: string): boolean {
  if (!header) return false;
  const m = /^sha256=([0-9a-f]{64})$/i.exec(header.trim());
  if (!m) return false;
  const expected = createHmac("sha256", appSecret).update(rawBody, "utf8").digest();
  const given = Buffer.from(m[1]!.toLowerCase(), "hex");
  return given.length === expected.length && timingSafeEqual(given, expected);
}

interface WaStatus {
  id?: string;
  status?: string;
  timestamp?: string;
  recipient_id?: string;
  errors?: { code?: number }[];
}

interface WaMessage {
  id?: string;
  from?: string;
  timestamp?: string;
  type?: string;
  text?: { body?: string };
  button?: { payload?: string; text?: string };
  interactive?: { type?: string; button_reply?: { id?: string; title?: string } };
  context?: { id?: string; from?: string };
}

interface WaBody {
  object?: string;
  entry?: { changes?: { field?: string; value?: { statuses?: WaStatus[]; messages?: WaMessage[] } }[] }[];
}

export async function processWebhook(deps: Deps, body: WaBody): Promise<{ statuses: number; messages: number }> {
  let statuses = 0;
  let messages = 0;
  for (const entry of body.entry ?? []) {
    for (const change of entry.changes ?? []) {
      if (change.field && change.field !== "messages") continue;
      for (const s of change.value?.statuses ?? []) {
        await handleStatus(deps, s);
        statuses++;
      }
      for (const msg of change.value?.messages ?? []) {
        await handleInbound(deps, msg);
        messages++;
      }
    }
  }
  return { statuses, messages };
}

const STATUS_RANK: Record<string, number> = { queued: 0, sent: 1, delivered: 2, read: 3 };

async function handleStatus(deps: Deps, s: WaStatus): Promise<void> {
  if (!s.id || !s.status || !s.timestamp) return;
  const at = unixSecondsToDate(s.timestamp);
  if (Number.isNaN(at.getTime())) return;
  await deps.db.tx(async (tx) => {
    const row = await getOutboundByWamid(tx, s.id!, true);
    if (!row) {
      log.info("wa_status_unknown_wamid", { wamid: s.id!.slice(0, 80), status: s.status });
      return;
    }
    // The same status for the same wamid twice changes nothing twice.
    const seen = await tx.query(
      "select 1 from whatsapp_messages where id = $1::bigint and raw_webhook_json @> jsonb_build_array(jsonb_build_object('status', $2::text))",
      [row.id, s.status],
    );
    if (seen.length > 0) return;
    const raise = (to: string) => ((STATUS_RANK[to] ?? -1) > (STATUS_RANK[row.status] ?? -1) ? to : row.status);
    switch (s.status) {
      case "sent": {
        await tx.query(
          "update whatsapp_messages set sent_at = coalesce(sent_at, $2::timestamptz), status = $3, status_ts = $2::timestamptz where id = $1::bigint",
          [row.id, at.toISOString(), raise("sent")],
        );
        break;
      }
      case "delivered": {
        if (row.delivered_at) return; // the same status twice changes nothing twice
        const seconds = await deliverySeconds(tx, row.consent_record_id, row.created_at, at);
        await tx.query(
          `update whatsapp_messages set delivered_at = $2::timestamptz, status = $3, status_ts = $2::timestamptz,
             delivery_seconds = $4 where id = $1::bigint`,
          [row.id, at.toISOString(), raise("delivered"), seconds],
        );
        if (row.member_id && row.shop_id) {
          const r = await activateOnDelivery(tx, { memberId: row.member_id, at });
          if (r.changed) {
            await queueInbox(tx, row.shop_id, "member_status", {
              member_id: row.member_id,
              status: "active",
              reason: "notice_delivered",
              at: toIstIso(at),
            });
          }
          await queueInbox(tx, row.shop_id, "notice_status", {
            member_id: row.member_id,
            consent_record_id: row.consent_record_id,
            wamid: s.id,
            whatsapp_status: "delivered",
            status_ts: toIstIso(at),
            delivered_at: toIstIso(at),
          });
        }
        log.info("wa_delivered", { wamid: s.id!.slice(0, 80), member_id: row.member_id, duration_ms: seconds === null ? null : seconds * 1000 });
        break;
      }
      case "read": {
        if (row.read_at) return;
        await tx.query(
          "update whatsapp_messages set read_at = $2::timestamptz, status = $3, status_ts = $2::timestamptz where id = $1::bigint",
          [row.id, at.toISOString(), raise("read")],
        );
        if (row.member_id && row.shop_id) {
          await queueInbox(tx, row.shop_id, "notice_status", {
            member_id: row.member_id,
            consent_record_id: row.consent_record_id,
            wamid: s.id,
            whatsapp_status: "read",
            status_ts: toIstIso(at),
            delivered_at: toIstIso(row.delivered_at),
          });
        }
        break;
      }
      case "failed": {
        if (row.failed_at) return;
        const code = s.errors?.[0]?.code;
        await tx.query(
          `update whatsapp_messages set failed_at = $2::timestamptz, status = case when delivered_at is null then 'failed' else status end,
             status_ts = $2::timestamptz, last_error_code = $3 where id = $1::bigint`,
          [row.id, at.toISOString(), code === undefined ? null : String(code)],
        );
        if (row.member_id && row.shop_id && !row.delivered_at) {
          // The member stays pending_notice; the app shows "Notice failed".
          await flagNoticeFailed(tx, row.member_id, at);
          await queueInbox(tx, row.shop_id, "notice_status", {
            member_id: row.member_id,
            consent_record_id: row.consent_record_id,
            wamid: s.id,
            whatsapp_status: "failed",
            status_ts: toIstIso(at),
            delivered_at: null,
          });
        }
        break;
      }
      default:
        return;
    }
    await appendRaw(tx, row.id, s);
  });
}

/** Seconds from the consent record's ts to delivery, for the 2-minute target (TASK-065). */
async function deliverySeconds(tx: Db, consentId: string | null, fallback: Date, at: Date): Promise<number | null> {
  let start: number = new Date(fallback).getTime();
  if (consentId) {
    const rows = await tx.query<{ ts: string }>("select ts from consent_records where id = $1", [consentId]);
    const parsed = rows[0] ? Date.parse(rows[0].ts) : NaN;
    if (!Number.isNaN(parsed)) start = parsed;
  }
  if (Number.isNaN(start)) return null;
  return Math.max(0, Math.round((at.getTime() - start) / 1000));
}

function isThumbsUp(msg: WaMessage): boolean {
  if (msg.type === "button") return msg.button?.payload === THUMBS_UP || msg.button?.text === THUMBS_UP;
  if (msg.type === "interactive") return msg.interactive?.button_reply?.title === THUMBS_UP || msg.interactive?.button_reply?.id === THUMBS_UP;
  return false;
}

export function isStop(msg: WaMessage): boolean {
  return msg.type === "text" && (msg.text?.body ?? "").trim().toUpperCase() === "STOP";
}

async function handleInbound(deps: Deps, msg: WaMessage): Promise<void> {
  if (!msg.id || !msg.from || !msg.timestamp) return;
  const at = unixSecondsToDate(msg.timestamp);
  if (Number.isNaN(at.getTime())) return;
  if (!/^\+?[1-9]\d{7,14}$/.test(msg.from)) return;
  const phoneHash = hashPhone(e164FromWhatsApp(msg.from));
  const kind = isThumbsUp(msg) ? "thumbs_up" : isStop(msg) ? "stop" : msg.type === "text" ? "text" : "other";
  // Media and other message types are reduced to type and id: the cloud keeps no media references.
  const raw = kind === "other" ? { id: msg.id, type: msg.type ?? null, timestamp: msg.timestamp } : msg;

  await deps.db.tx(async (tx) => {
    const inserted = await tx.query<{ id: string }>(
      `insert into whatsapp_messages (direction, kind, wamid, context_wamid, phone_hash, status, received_ts, raw_webhook_json)
       values ('in', $1, $2, $3, $4, 'received', $5::timestamptz, jsonb_build_array($6::jsonb))
       on conflict (wamid) do nothing returning id::text`,
      [kind, msg.id, msg.context?.id ?? null, phoneHash, at.toISOString(), JSON.stringify(raw)],
    );
    if (inserted.length === 0) return; // repeated webhook
    const inboundId = inserted[0]!.id;

    if (kind === "thumbs_up") {
      const notice = msg.context?.id
        ? await getOutboundByWamid(tx, msg.context.id, true)
        : await latestNoticeForPhoneHash(tx, phoneHash);
      if (!notice || !notice.member_id || !notice.shop_id) return;
      await tx.query("update whatsapp_messages set shop_id = $2, member_id = $3 where id = $1::bigint", [
        inboundId,
        notice.shop_id,
        notice.member_id,
      ]);
      if (notice.thumbs_up_at) return;
      await tx.query("update whatsapp_messages set thumbs_up_at = $2::timestamptz where id = $1::bigint", [notice.id, at.toISOString()]);
      await queueInbox(tx, notice.shop_id, "thumbs_up", { member_id: notice.member_id, at: toIstIso(at) });
      log.info("wa_thumbs_up", { member_id: notice.member_id, shop_id: notice.shop_id });
      return;
    }

    if (kind === "stop") {
      // Every shop's notice goes out from one Remu number, so a plain STOP withdraws the number from
      // every shop; a quoted reply (context.id) narrows it to the shop of that notice.
      let shopId: string | undefined;
      if (msg.context?.id) shopId = (await getOutboundByWamid(tx, msg.context.id))?.shop_id ?? undefined;
      const known = await findMembersByPhoneHash(tx, phoneHash, shopId);
      const members = known.filter((m) => m.status === "pending_notice" || m.status === "active");
      let applied = 0;
      for (const m of members) {
        const r = await transition(tx, { memberId: m.id, to: "withdrawn", by: "cloud", reason: "stop", at });
        if (r.changed) {
          applied++;
          await queueInbox(tx, m.shop_id, "member_status", { member_id: m.id, status: "withdrawn", reason: "stop", at: toIstIso(at) });
        }
      }
      if (known.length > 0) {
        // Matched (or already terminal): nothing left to apply later. With no member row yet the STOP
        // stays pending and is applied when the member arrives (ingest applyPendingStop).
        await tx.query("update whatsapp_messages set applied_at = now(), shop_id = $2 where id = $1::bigint", [
          inboundId,
          known.length === 1 ? known[0]!.shop_id : (shopId ?? null),
        ]);
      }
      log.info("wa_stop", { count: applied, outcome: applied > 0 ? "applied" : "pending" });
      return;
    }
    log.info("wa_inbound_ignored", { kind });
  });
}
