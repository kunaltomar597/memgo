import { computeRecordHash, orderForChain, type ConsentRecord } from "./consentChain";
import { mirrorHead } from "./db/consent";
import { flagChainBreak } from "./db/devices";
import { queueInbox } from "./db/inbox";
import { getMember, insertMember, updateMemberFields, type MemberUpsertFields } from "./db/members";
import type { Db } from "./db/types";
import type { Deps } from "./deps";
import { ApiError } from "./http";
import { log } from "./log";
import { transition } from "./memberState";
import { prepareNotice, sendPreparedNotice, type NoticeRequest } from "./notice";
import { pushAlert } from "./push";
import { EVENT_SCHEMAS, eventEnvelopeSchema, zodReason, type EventPayload, type EventType } from "./schemas";
import { reportMessage } from "./sentry";
import { toIstIso } from "./time";

export interface EventResult {
  id: string;
  status: "acked" | "rejected";
  reason?: string;
}

interface Envelope {
  id: string;
  type: string;
  created_at: string;
  payload: Record<string, unknown>;
}

interface Device {
  id: string;
  shop_id: string;
}

class Reject extends Error {
  constructor(readonly reason: string) {
    super(reason);
  }
}

type AfterCommit = () => Promise<void>;

/**
 * POST /api/hub/events. Each event is applied once (its ULID is the idempotency key for every side
 * effect) in its own transaction; a rejected event does not block the ones after it. Consent
 * records in the batch are linked in chain order (orderForChain). A 5xx means retry the batch.
 */
export async function ingestBatch(deps: Deps, device: Device, rawEvents: readonly unknown[]): Promise<EventResult[]> {
  const envelopes: Envelope[] = [];
  for (const raw of rawEvents) {
    const parsed = eventEnvelopeSchema.safeParse(raw);
    if (!parsed.success) throw new ApiError(400, "invalid", `event envelope: ${zodReason(parsed.error)}`);
    envelopes.push(parsed.data);
  }
  const requestOrder = [...envelopes];
  envelopes.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const ordered = await orderConsentEvents(deps.db, device.shop_id, envelopes);

  const results = new Map<string, EventResult>();
  for (const ev of ordered) {
    if (results.has(ev.id)) continue;
    results.set(ev.id, await applyOne(deps, device, ev));
  }
  // Results in the order the hub sent them, one per distinct id.
  return requestOrder.filter((e, i, all) => all.findIndex((x) => x.id === e.id) === i).map((e) => results.get(e.id)!);
}

/** Puts the batch's consent records into chain order while keeping every other event in place. */
async function orderConsentEvents(db: Db, shopId: string, events: Envelope[]): Promise<Envelope[]> {
  const consentIdx: number[] = [];
  const consent: (Envelope & { ts: string; prev_hash: string; record_hash: string })[] = [];
  events.forEach((e, i) => {
    const p = e.payload as Partial<ConsentRecord>;
    if (e.type === "consent_record" && typeof p.ts === "string" && typeof p.prev_hash === "string" && typeof p.record_hash === "string") {
      consentIdx.push(i);
      consent.push({ ...e, ts: p.ts, prev_hash: p.prev_hash, record_hash: p.record_hash });
    }
  });
  if (consent.length < 2) return events;
  const head = await mirrorHead(db, shopId);
  const sorted = orderForChain(consent, head);
  const out = [...events];
  consentIdx.forEach((pos, k) => {
    out[pos] = sorted[k]!;
  });
  return out;
}

async function applyOne(deps: Deps, device: Device, ev: Envelope): Promise<EventResult> {
  const known = await deps.db.query("select 1 from hub_events where id = $1", [ev.id]);
  if (known.length > 0) return { id: ev.id, status: "acked" };

  if (!(ev.type in EVENT_SCHEMAS)) return reject(ev, "unknown_type");
  const type = ev.type as EventType;
  const parsed = EVENT_SCHEMAS[type].safeParse(ev.payload);
  if (!parsed.success) return reject(ev, zodReason(parsed.error));

  let after: AfterCommit | undefined;
  try {
    after = await deps.db.tx(async (tx) => {
      const inserted = await tx.query(
        `insert into hub_events (id, device_id, shop_id, type, created_at) values ($1, $2, $3, $4, $5)
         on conflict (id) do nothing returning id`,
        [ev.id, device.id, device.shop_id, type, ev.created_at],
      );
      if (inserted.length === 0) return undefined; // a concurrent batch applied it
      return APPLY[type](tx, deps, device, parsed.data as never, ev);
    });
  } catch (e) {
    if (e instanceof Reject) return reject(ev, e.reason);
    throw e;
  }
  if (after) await after();
  return { id: ev.id, status: "acked" };
}

function reject(ev: Envelope, reason: string): EventResult {
  log.warn("event_rejected", { event_id: ev.id, kind: ev.type.slice(0, 40), reason: reason.slice(0, 80) });
  return { id: ev.id, status: "rejected", reason };
}

type Applier<T extends EventType> = (
  tx: Db,
  deps: Deps,
  device: Device,
  payload: EventPayload<T>,
  ev: Envelope,
) => Promise<AfterCommit | undefined>;

function sameShop(device: Device, shopId: string): void {
  if (shopId !== device.shop_id) throw new Reject("shop_mismatch");
}

async function memberOfShop(tx: Db, device: Device, memberId: string) {
  const m = await getMember(tx, memberId);
  if (!m) throw new Reject("unknown_member");
  if (m.shop_id !== device.shop_id) throw new Reject("shop_mismatch");
  return m;
}

const applyConsent: Applier<"consent_record"> = async (tx, deps, device, rec) => {
  sameShop(device, rec.shop_id);
  // Serialise chain writes per shop.
  await tx.query("select 1 from shops where id = $1 for update", [rec.shop_id]);
  const existing = await tx.query("select 1 from consent_records where id = $1", [rec.id]);
  if (existing.length > 0) return undefined; // never update a mirrored record
  const head = await mirrorHead(tx, rec.shop_id);
  const hashOk = computeRecordHash(rec) === rec.record_hash;
  const linkOk = rec.prev_hash === head;
  await tx.query(
    `insert into consent_records (id, shop_id, member_id, bill_no, ts, cashier_id, channel, script_version, notice_version,
       purposes_json, notice_delivery_json, adult_check, withdrawn_at, template_deleted_at, prev_hash, record_hash,
       device_id, hash_ok, link_ok)
     values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::text::jsonb,$11::text::jsonb,$12,$13,$14,$15,$16,$17,$18,$19)`,
    [rec.id, rec.shop_id, rec.member_id, rec.bill_no, rec.ts, rec.cashier_id, rec.channel, rec.script_version,
      rec.notice_version, JSON.stringify(rec.purposes_json), JSON.stringify(rec.notice_delivery_json), rec.adult_check,
      rec.withdrawn_at, rec.template_deleted_at, rec.prev_hash, rec.record_hash, device.id, hashOk, linkOk],
  );
  if (!hashOk || !linkOk) {
    await flagChainBreak(tx, device.id, rec.id, deps.now());
    log.warn("chain_break", { shop_id: rec.shop_id, device_id: device.id, consent_id: rec.id, reason: hashOk ? "link" : "hash" });
    reportMessage("consent chain break", { shop_id: rec.shop_id, device_id: device.id, consent_id: rec.id, reason: hashOk ? "link" : "hash" });
  }
  return undefined;
};

const applyMember: Applier<"member_upsert"> = async (tx, deps, device, m) => {
  sameShop(device, m.shop_id);
  const fields: MemberUpsertFields = {
    id: m.id,
    shop_id: m.shop_id,
    household_id: m.household_id,
    display_name: m.display_name,
    phone_hash: m.phone_hash,
    member_since: m.member_since,
    last_seen_at: m.last_seen_at,
    discount_flag: m.discount_flag,
    notes: m.notes,
    has_template: m.has_template,
    created_at: m.created_at,
    updated_at: m.updated_at,
  };
  const existing = await getMember(tx, m.id, true);
  if (existing && existing.shop_id !== m.shop_id) throw new Reject("shop_mismatch");
  const at = new Date(m.updated_at);
  if (!existing) {
    // A hub never makes a member active; activation is the cloud's, on delivery.
    const status = m.status === "active" ? "pending_notice" : m.status;
    await insertMember(tx, fields, status);
    if (status === "pending_notice") await applyPendingStop(tx, m.id, m.shop_id, m.phone_hash, new Date(m.created_at));
    return undefined;
  }
  await updateMemberFields(tx, fields);
  if (m.status === "withdrawn" || m.status === "expired") {
    await transition(tx, { memberId: m.id, to: m.status, by: "hub", reason: m.status === "withdrawn" ? "hub_withdrawn" : "hub_expired", at });
  } else if (m.status !== existing.status) {
    log.info("member_status_ignored", { member_id: m.id, status: m.status, reason: existing.status });
  }
  return undefined;
};

/** A STOP that arrived before its member row (hub was offline): applied now, with the STOP's time. */
async function applyPendingStop(tx: Db, memberId: string, shopId: string, phoneHash: string, createdAt: Date): Promise<void> {
  const rows = await tx.query<{ id: string; received_ts: Date }>(
    `select id::text as id, received_ts from whatsapp_messages
     where direction = 'in' and kind = 'stop' and applied_at is null and phone_hash = $1
       and received_ts >= $2::timestamptz order by received_ts limit 1`,
    [phoneHash, createdAt.toISOString()],
  );
  const stop = rows[0];
  if (!stop) return;
  const at = new Date(stop.received_ts);
  const r = await transition(tx, { memberId, to: "withdrawn", by: "cloud", reason: "stop", at });
  if (r.changed) {
    await queueInbox(tx, shopId, "member_status", { member_id: memberId, status: "withdrawn", reason: "stop", at: toIstIso(at) });
  }
  await tx.query("update whatsapp_messages set applied_at = now() where id = $1::bigint", [stop.id]);
}

const applyHousehold: Applier<"household_upsert"> = async (tx, _deps, device, h) => {
  sameShop(device, h.shop_id);
  const existing = await tx.query<{ shop_id: string }>("select shop_id from households where id = $1", [h.id]);
  if (existing[0] && existing[0].shop_id !== h.shop_id) throw new Reject("shop_mismatch");
  await tx.query(
    `insert into households (id, shop_id, label, payer_member_id, member_ids, created_at)
     values ($1, $2, $3, $4, $5::text::jsonb, $6::timestamptz)
     on conflict (id) do update set label = excluded.label, payer_member_id = excluded.payer_member_id,
       member_ids = excluded.member_ids, updated_at = now()`,
    [h.id, h.shop_id, h.label, h.payer_member_id, JSON.stringify(h.member_ids), h.created_at],
  );
  await tx.query("update members set household_id = null where household_id = $1 and shop_id = $2 and not (id = any($3::text[]))", [
    h.id,
    h.shop_id,
    h.member_ids,
  ]);
  await tx.query("update members set household_id = $1 where shop_id = $2 and id = any($3::text[])", [h.id, h.shop_id, h.member_ids]);
  return undefined;
};

const applyStaff: Applier<"staff_upsert"> = async (tx, _deps, device, s) => {
  sameShop(device, s.shop_id);
  const existing = await tx.query<{ shop_id: string }>("select shop_id from staff where id = $1", [s.id]);
  if (existing[0] && existing[0].shop_id !== s.shop_id) throw new Reject("shop_mismatch");
  await tx.query(
    `insert into staff (id, shop_id, name, role, created_at) values ($1, $2, $3, $4, $5::timestamptz)
     on conflict (id) do update set name = excluded.name, role = excluded.role, updated_at = now()`,
    [s.id, s.shop_id, s.name, s.role, s.created_at],
  );
  return undefined;
};

const applyPurchase: Applier<"purchase"> = async (tx, _deps, device, p) => {
  await memberOfShop(tx, device, p.member_id);
  const existing = await tx.query<{ shop_id: string }>("select shop_id from purchases where id = $1", [p.id]);
  if (existing[0]) {
    if (existing[0].shop_id !== device.shop_id) throw new Reject("shop_mismatch");
    return undefined;
  }
  await tx.query(
    `insert into purchases (id, shop_id, member_id, bill_no, amount, items_text, occasion, ts)
     values ($1, $2, $3, $4, $5, $6, $7, $8::timestamptz)`,
    [p.id, device.shop_id, p.member_id, p.bill_no, p.amount.toFixed(2), p.items_text, p.occasion, p.ts],
  );
  return undefined;
};

const applyNotice: Applier<"notice_request"> = async (tx, deps, device, n) => {
  await memberOfShop(tx, device, n.member_id);
  const send = await prepareNotice(tx, device.shop_id, n);
  if (!send) return undefined;
  return async () => {
    await sendPreparedNotice(deps, n as NoticeRequest);
  };
};

const applyVisit: Applier<"visit_summary"> = async (tx, _deps, device, v) => {
  await memberOfShop(tx, device, v.member_id);
  const existing = await tx.query<{ shop_id: string }>("select shop_id from visits where id = $1", [v.id]);
  if (existing[0] && existing[0].shop_id !== device.shop_id) throw new Reject("shop_mismatch");
  await tx.query(
    `insert into visits (id, shop_id, member_id, ts, camera, score, frames_agreed, greeted, greeted_by, not_them)
     values ($1, $2, $3, $4::timestamptz, $5, $6, $7, $8, $9, $10)
     on conflict (id) do update set ts = excluded.ts, camera = excluded.camera, score = excluded.score,
       frames_agreed = excluded.frames_agreed, greeted = excluded.greeted, greeted_by = excluded.greeted_by,
       not_them = excluded.not_them, updated_at = now()`,
    [v.id, device.shop_id, v.member_id, v.ts, v.camera, v.score, v.frames_agreed, v.greeted, v.greeted_by, v.not_them],
  );
  await tx.query(
    "update members set last_seen_at = greatest(coalesce(last_seen_at, $2::timestamptz), $2::timestamptz) where id = $1",
    [v.member_id, v.ts],
  );
  return undefined;
};

const applyFeedback: Applier<"visit_feedback"> = async (tx, _deps, device, f) => {
  const rows = await tx.query<{ shop_id: string }>("select shop_id from visits where id = $1", [f.visit_id]);
  if (!rows[0]) throw new Reject("unknown_visit");
  if (rows[0].shop_id !== device.shop_id) throw new Reject("shop_mismatch");
  await tx.query("update visits set greeted = $2, greeted_by = $3, not_them = $4, updated_at = now() where id = $1", [
    f.visit_id,
    f.greeted,
    f.greeted_by,
    f.not_them,
  ]);
  return undefined;
};

const applyAlert: Applier<"alert"> = async (tx, deps, device, a) => {
  await memberOfShop(tx, device, a.member_id);
  await tx.query("update visits set alerted_at = $2::timestamptz where id = $1 and shop_id = $3", [a.visit_id, a.ts, device.shop_id]);
  return async () => {
    await pushAlert(deps, { shop_id: device.shop_id, visit_id: a.visit_id, member_id: a.member_id });
  };
};

const applyHealth: Applier<"health"> = async (tx, deps, device, h, ev) => {
  await tx.query(
    `update devices set hub_version = $2, last_health_at = $3::timestamptz, last_health_json = $4::text::jsonb, locked = $5,
       last_seen_at = greatest(coalesce(last_seen_at, $3::timestamptz), $3::timestamptz), updated_at = now()
     where id = $1`,
    [device.id, h.version, deps.now().toISOString(), JSON.stringify(h), h.locked],
  );
  await tx.query(
    `insert into device_health_log (device_id, shop_id, hub_created_at, locked, internet, stream, fps, outbox_pending, outbox_failed)
     values ($1, $2, $3::timestamptz, $4, $5, $6, $7, $8, $9)`,
    [device.id, device.shop_id, ev.created_at, h.locked, h.internet, h.stream, h.fps, h.outbox_pending, h.outbox_failed],
  );
  return undefined;
};

const APPLY: { [T in EventType]: Applier<T> } = {
  consent_record: applyConsent,
  member_upsert: applyMember,
  household_upsert: applyHousehold,
  staff_upsert: applyStaff,
  purchase: applyPurchase,
  notice_request: applyNotice,
  visit_summary: applyVisit,
  visit_feedback: applyFeedback,
  alert: applyAlert,
  health: applyHealth,
};
