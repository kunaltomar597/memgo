import { z } from "zod";
import { PHONE_HASH_RE } from "./phone";
import { ISO_DATE_RE, ISO_TS_RE } from "./time";

/**
 * Strict zod schemas for every hub event payload (docs/API_CONTRACT.md section 2). Unknown keys
 * are rejected, and no schema has a field that could carry image data, vectors or clips.
 */
const ts = z.string().regex(ISO_TS_RE, "ISO 8601 timestamp with offset");
const tsOrNull = ts.nullable();
const dateOrTs = z.string().refine((s) => ISO_DATE_RE.test(s) || ISO_TS_RE.test(s), "date or timestamp");
const id = (prefix: string) => z.string().regex(new RegExp(`^${prefix}_[0-9A-Za-z]{1,40}$`));
const shopId = z.string().min(1).max(80).regex(/^[a-z0-9][a-z0-9_-]*$/);
const text = (max: number) => z.string().max(max);
const sha256Ref = z.string().regex(/^sha256:([0-9a-f]{64}|genesis)$/);
const phoneHash = z.string().regex(PHONE_HASH_RE, "lowercase hex sha256 of the E.164 number");

export const memberId = id("m");
export const visitId = id("v");
export const staffId = id("s");
export const householdId = id("h");
export const consentId = id("c");
export const purchaseId = id("p");
export const eventId = z.string().regex(/^e_[0-9A-Za-z]{1,40}$/);

export const consentRecordSchema = z.strictObject({
  id: consentId,
  shop_id: shopId,
  member_id: memberId,
  bill_no: text(64).nullable(),
  ts,
  cashier_id: text(64).nullable(),
  channel: z.enum(["verbal_at_counter", "whatsapp_webhook", "owner_remove", "whatsapp_stop", "nightly_expiry", "sweep"]),
  script_version: text(32),
  notice_version: text(32),
  purposes_json: z.strictObject({ recognise_at_entry: z.boolean(), whatsapp_offers: z.boolean() }),
  notice_delivery_json: z.strictObject({
    printed_on_bill: z.boolean(),
    whatsapp_msg_id: text(256).nullable(),
    whatsapp_status: z.enum(["sent", "delivered", "read", "failed"]).nullable(),
    delivered_at: tsOrNull,
    thumbs_up_at: tsOrNull,
  }),
  adult_check: text(32).nullable(),
  withdrawn_at: tsOrNull,
  template_deleted_at: tsOrNull,
  prev_hash: sha256Ref,
  record_hash: z.string().regex(/^sha256:[0-9a-f]{64}$/),
});

export const memberUpsertSchema = z.strictObject({
  id: memberId,
  shop_id: shopId,
  household_id: householdId.nullable(),
  display_name: text(120).min(1),
  phone_hash: phoneHash,
  status: z.enum(["pending_notice", "active", "withdrawn", "expired"]),
  member_since: dateOrTs,
  last_seen_at: tsOrNull,
  discount_flag: z.boolean(),
  notes: text(1000).nullable(),
  has_template: z.boolean(),
  created_at: ts,
  updated_at: ts,
});

export const householdUpsertSchema = z.strictObject({
  id: householdId,
  shop_id: shopId,
  label: text(120).min(1),
  payer_member_id: memberId.nullable(),
  member_ids: z.array(memberId).max(20),
  created_at: ts,
});

export const staffUpsertSchema = z.strictObject({
  id: staffId,
  shop_id: shopId,
  name: text(120).min(1),
  role: z.enum(["owner", "cashier"]),
  created_at: ts,
});

export const purchaseSchema = z.strictObject({
  id: purchaseId,
  member_id: memberId,
  bill_no: text(64).min(1),
  amount: z.number().nonnegative().max(1e10).refine((n) => Math.abs(n * 100 - Math.round(n * 100)) < 1e-6, "at most two decimals"),
  items_text: text(500).nullable(),
  occasion: text(200).nullable(),
  ts,
});

export const noticeRequestSchema = z.strictObject({
  member_id: memberId,
  consent_record_id: consentId,
  phone_e164: z.string().regex(/^\+[1-9]\d{7,14}$/),
  first_name: text(60).min(1),
  shop_name: text(120).min(1),
  language: z.enum(["en", "hi"]),
  date: dateOrTs,
});

export const visitSummarySchema = z.strictObject({
  id: visitId,
  member_id: memberId,
  ts,
  camera: text(32).min(1),
  score: z.number().min(-1).max(1),
  frames_agreed: z.number().int().min(0).max(100),
  greeted: z.boolean(),
  greeted_by: staffId.nullable(),
  not_them: z.boolean(),
});

export const visitFeedbackSchema = z.strictObject({
  visit_id: visitId,
  greeted: z.boolean(),
  greeted_by: staffId.nullable(),
  not_them: z.boolean(),
});

export const alertSchema = z.strictObject({
  visit_id: visitId,
  member_id: memberId,
  ts,
});

export const healthSchema = z.strictObject({
  ok: z.boolean(),
  version: text(40),
  model_version: text(80),
  uptime_s: z.number().int().nonnegative(),
  gallery_size: z.number().int().nonnegative(),
  locked: z.boolean(),
  data_dir_encrypted: z.boolean(),
  internet: z.boolean(),
  outbox_pending: z.number().int().nonnegative(),
  outbox_failed: z.number().int().nonnegative(),
  clock_synced: z.boolean(),
  fps: z.number().nonnegative().max(240),
  stream: z.enum(["ok", "connecting", "down"]),
});

export const EVENT_SCHEMAS = {
  consent_record: consentRecordSchema,
  member_upsert: memberUpsertSchema,
  household_upsert: householdUpsertSchema,
  staff_upsert: staffUpsertSchema,
  purchase: purchaseSchema,
  notice_request: noticeRequestSchema,
  visit_summary: visitSummarySchema,
  visit_feedback: visitFeedbackSchema,
  alert: alertSchema,
  health: healthSchema,
} as const;

export type EventType = keyof typeof EVENT_SCHEMAS;
export type EventPayload<T extends EventType> = z.infer<(typeof EVENT_SCHEMAS)[T]>;

export const eventEnvelopeSchema = z.strictObject({
  id: eventId,
  type: z.string().min(1).max(40),
  created_at: ts,
  payload: z.record(z.string(), z.unknown()),
});

export const eventBatchSchema = z.strictObject({
  events: z.array(z.unknown()).max(200),
});

export function zodReason(err: z.ZodError): string {
  const first = err.issues[0];
  if (!first) return "invalid";
  return `${first.path.join(".") || "$"}: ${first.message}`.slice(0, 200);
}
