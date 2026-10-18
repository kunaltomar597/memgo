import { randomUUID } from "node:crypto";
import { computeRecordHash, GENESIS_HASH, type ConsentRecord } from "@/lib/consentChain";
import { ulid } from "@/lib/ids";
import { phoneHash } from "@/lib/phone";
import * as claimRoute from "@/app/api/devices/claim/route";
import * as pairRoute from "@/app/api/devices/pair/route";
import * as eventsRoute from "@/app/api/hub/events/route";
import * as opsDevicesRoute from "@/app/api/ops/devices/route";
import * as shopsRoute from "@/app/api/shops/route";
import type { TestDb } from "./db";
import type { TestHarness } from "./deps";
import { call } from "./http";

let seq = Date.UTC(2026, 9, 18, 6, 0, 0);
/** Monotonic ULID-based ids so batch order equals creation order. */
export function nextId(prefix: string): string {
  seq += 1000;
  return `${prefix}_${ulid(seq)}`;
}

export async function createOwner(t: TestDb, h: TestHarness, label: string) {
  const userId = randomUUID();
  await t.db.query("insert into auth.users (id, email) values ($1, $2)", [userId, `${label}@example.test`]);
  const token = `token-${label}-${userId.slice(0, 8)}`;
  h.users.set(token, userId);
  return { userId, token };
}

export async function createShop(h: TestHarness, token: string, name = "Test Jewellers", city = "Jaipur", notice_language: "en" | "hi" = "en") {
  const r = await call(shopsRoute, "POST", { path: "/api/shops", token, body: { name, city, notice_language } });
  if (r.status !== 201) throw new Error(`create shop failed: ${r.status} ${JSON.stringify(r.json)}`);
  return (r.json as unknown as { id: string }).id;
}

let codeSeq = 100000;
export async function provision(h: TestHarness, code?: string, serial?: string) {
  const pairing_code = code ?? String(codeSeq++);
  const s = serial ?? `pi5-serial-${pairing_code}`;
  const r = await call(opsDevicesRoute, "POST", { path: "/api/ops/devices", headers: { "x-remu-ops-key": h.deps.env.opsApiKey! }, body: { pairing_code, serial: s } });
  if (r.status !== 201) throw new Error(`provision failed ${r.status}`);
  return { deviceId: (r.json as unknown as { device_id: string }).device_id, code: pairing_code, serial: s };
}

/** A shop with an owner and one paired, claimed hub. */
export async function setupShop(t: TestDb, h: TestHarness, label = "a", opts: { language?: "en" | "hi"; name?: string } = {}) {
  const owner = await createOwner(t, h, label);
  const shopId = await createShop(h, owner.token, opts.name ?? `Shop ${label.toUpperCase()} Jewellers`, "Jaipur", opts.language ?? "en");
  const p = await provision(h);
  const pr = await call(pairRoute, "POST", { path: "/api/devices/pair", token: owner.token, body: { shop_id: shopId, pairing_code: p.code } });
  if (pr.status !== 200) throw new Error(`pair failed ${pr.status} ${JSON.stringify(pr.json)}`);
  const cr = await call(claimRoute, "POST", { path: "/api/devices/claim", body: { pairing_code: p.code, serial: p.serial } });
  if (cr.status !== 200) throw new Error(`claim failed ${cr.status}`);
  const deviceToken = (cr.json as unknown as { device_token: string }).device_token;
  return { owner, shopId, deviceId: p.deviceId, deviceToken, code: p.code, serial: p.serial };
}

export { phoneHash } from "@/lib/phone";

/** Builds a valid per-shop hash chain the way the hub's append_consent_record() does. */
export class ChainBuilder {
  head = GENESIS_HASH;
  readonly records: ConsentRecord[] = [];
  constructor(readonly shopId: string) {}

  next(over: Partial<Omit<ConsentRecord, "prev_hash" | "record_hash">> & { member_id: string }): ConsentRecord {
    const base: Omit<ConsentRecord, "record_hash"> = {
      id: over.id ?? nextId("c"),
      shop_id: this.shopId,
      member_id: over.member_id,
      bill_no: over.bill_no ?? "INV-2026-10-18-0417",
      ts: over.ts ?? "2026-10-18T17:42:11+05:30",
      cashier_id: over.cashier_id ?? "s_01JTESTCASHIER0000000000",
      channel: over.channel ?? "verbal_at_counter",
      script_version: over.script_version ?? "hi-IN-v1.0",
      notice_version: over.notice_version ?? "wa-v1.0",
      purposes_json: over.purposes_json ?? { recognise_at_entry: true, whatsapp_offers: true },
      notice_delivery_json: over.notice_delivery_json ?? {
        printed_on_bill: false,
        whatsapp_msg_id: null,
        whatsapp_status: null,
        delivered_at: null,
        thumbs_up_at: null,
      },
      adult_check: over.adult_check === undefined ? "passed" : over.adult_check,
      withdrawn_at: over.withdrawn_at ?? null,
      template_deleted_at: over.template_deleted_at ?? null,
      prev_hash: this.head,
    };
    const rec: ConsentRecord = { ...base, record_hash: computeRecordHash(base) };
    this.head = rec.record_hash;
    this.records.push(rec);
    return rec;
  }
}

export function ev(type: string, payload: unknown, created_at = "2026-10-18T17:42:12+05:30") {
  return { id: nextId("e"), type, created_at, payload };
}

export interface EnrolOptions {
  memberId?: string;
  phone?: string;
  name?: string;
  hasTemplate?: boolean;
  language?: "en" | "hi";
  ts?: string;
}

/** The events the hub queues for one enrolment: consent_record, member_upsert, purchase, notice_request. */
export function enrolEvents(shopId: string, chain: ChainBuilder, o: EnrolOptions = {}) {
  const memberId = o.memberId ?? nextId("m");
  const phone = o.phone ?? "+919800000001";
  const ts = o.ts ?? "2026-10-18T17:42:11+05:30";
  const rec = chain.next({ member_id: memberId, ts });
  const member = memberUpsert(shopId, memberId, { phone, name: o.name, hasTemplate: o.hasTemplate ?? true, ts });
  const purchase = {
    id: nextId("p"),
    member_id: memberId,
    bill_no: "INV-2026-10-18-0417",
    amount: 20000,
    items_text: "Gold necklace",
    occasion: "Engagement",
    ts,
  };
  const notice = {
    member_id: memberId,
    consent_record_id: rec.id,
    phone_e164: phone,
    first_name: (o.name ?? "Test Member").split(" ")[0]!,
    shop_name: "Test Jewellers",
    language: o.language ?? "en",
    date: ts.slice(0, 10),
  };
  return {
    memberId,
    record: rec,
    events: [ev("consent_record", rec), ev("member_upsert", member), ev("purchase", purchase), ev("notice_request", notice)],
  };
}

export function memberUpsert(
  shopId: string,
  memberId: string,
  o: { phone?: string; name?: string; hasTemplate?: boolean; status?: string; ts?: string; householdId?: string | null } = {},
) {
  const ts = o.ts ?? "2026-10-18T17:42:11+05:30";
  return {
    id: memberId,
    shop_id: shopId,
    household_id: o.householdId ?? null,
    display_name: o.name ?? "Test Member",
    phone_hash: phoneHash(o.phone ?? "+919800000001"),
    status: o.status ?? "pending_notice",
    member_since: ts,
    last_seen_at: null,
    discount_flag: true,
    notes: "Asked about matching earrings",
    has_template: o.hasTemplate ?? true,
    created_at: ts,
    updated_at: ts,
  };
}

export async function postEvents(h: TestHarness, deviceToken: string, events: unknown[]) {
  return call(eventsRoute, "POST", { path: "/api/hub/events", token: deviceToken, body: { events } });
}

export function healthPayload(over: Record<string, unknown> = {}) {
  return {
    ok: true,
    version: "0.1.0",
    model_version: "buffalo_l:3f9a1c2d",
    uptime_s: 3600,
    gallery_size: 42,
    locked: false,
    data_dir_encrypted: true,
    internet: true,
    outbox_pending: 0,
    outbox_failed: 0,
    clock_synced: true,
    fps: 6.8,
    stream: "ok",
    ...over,
  };
}
