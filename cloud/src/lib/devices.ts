import { createHmac } from "node:crypto";
import { constantTimeEqual, sha256Hex } from "./auth";
import { getDeviceByCode, type DeviceRecord } from "./db/devices";
import { queueInbox } from "./db/inbox";
import { isRateLimited, recordHit } from "./db/rateLimit";
import type { Db } from "./db/types";
import { ApiError } from "./http";
import { prefixedId, randomToken } from "./ids";
import { log } from "./log";
import { configPayload } from "./shops";
import { toIstIso } from "./time";

/** Pairing codes: the hub's 6-digit code or a sticker code like K7QM-2XPA. Upper-cased, trimmed. */
export function normaliseCode(code: string): string {
  return code.trim().toUpperCase();
}
export const PAIRING_CODE_RE = /^[A-Z0-9]{4,12}(-[A-Z0-9]{2,12})?$/;
export const SERIAL_RE = /^[A-Za-z0-9._:-]{4,64}$/;
/** The hub's TLS certificate fingerprint: SHA-256 as colon-separated uppercase hex (32 bytes). */
export const CERT_SHA256_RE = /^[0-9A-F]{2}(:[0-9A-F]{2}){31}$/;

/**
 * devices.pairing_code stores this, never the code (TASK-112): lowercase hex SHA-256 of the
 * normalised code. Every lookup hashes first.
 */
export function pairingCodeHash(code: string): string {
  return sha256Hex(normaliseCode(code));
}

/** POST /api/ops/devices: a provisioned hub, generation 1, not yet linked to a shop. */
export async function registerDevice(db: Db, pairingCode: string, serial: string): Promise<string> {
  const codeHash = pairingCodeHash(pairingCode);
  const id = prefixedId("d");
  const rows = await db.query<{ id: string }>(
    `insert into devices (id, serial, pairing_code, status, generation) values ($1, $2, $3, 'provisioned', 1)
     on conflict (pairing_code) do nothing returning id`,
    [id, serial, codeHash],
  );
  if (rows.length === 0) throw new ApiError(409, "conflict", "pairing code already registered");
  log.info("device_registered", { device_id: id });
  return id;
}

/**
 * POST /api/devices/pair: the owner types the hub's code. Links the device to the shop once.
 * Wrong codes are rate limited per user and per address. The owner app never sees the device token.
 */
export async function pairDevice(
  db: Db,
  args: { shopId: string; code: string; userId: string; ip: string; now: Date },
): Promise<string> {
  const userKey = `pair:user:${args.userId}`;
  const ipKey = `pair:ip:${args.ip}`;
  const shopKey = `pair:shop:${args.shopId}`;
  if (
    (await isRateLimited(db, userKey, 5, 60, args.now)) ||
    (await isRateLimited(db, shopKey, 10, 60, args.now)) ||
    (await isRateLimited(db, ipKey, 20, 60, args.now))
  ) {
    throw new ApiError(429, "rate_limited", "too many wrong pairing codes");
  }
  const codeHash = pairingCodeHash(args.code);
  const result = await db.tx(async (tx) => {
    const d = await getDeviceByCode(tx, codeHash, true);
    if (!d || !constantTimeEqual(d.pairing_code, codeHash)) return { kind: "wrong" as const };
    if (d.status !== "provisioned" || d.shop_id !== null) return { kind: "used" as const };
    await tx.query(
      `update devices set shop_id = $2, status = 'paired', paired_at = $3::timestamptz, paired_by = $4::uuid,
         updated_at = now() where id = $1`,
      [d.id, args.shopId, args.now.toISOString(), args.userId],
    );
    await queueInbox(tx, args.shopId, "config", await configPayload(tx, args.shopId), d.id);
    return { kind: "paired" as const, deviceId: d.id };
  });
  if (result.kind === "wrong") {
    await recordHit(db, userKey, args.now);
    await recordHit(db, shopKey, args.now);
    await recordHit(db, ipKey, args.now);
    throw new ApiError(401, "unauthorized", "wrong pairing code");
  }
  if (result.kind === "used") throw new ApiError(409, "conflict", "pairing code already used");
  log.info("device_paired", { device_id: result.deviceId, shop_id: args.shopId });
  return result.deviceId;
}

export type ClaimResult =
  | { status: "pending" }
  | { status: "paired"; device_token: string; device_id: string; shop_id: string };

/**
 * POST /api/devices/claim: the hub polls with its code and serial. 202 pending until an owner pairs
 * the code, then the device token exactly once. Only its SHA-256 is stored. The hub's certificate
 * fingerprint (cert_sha256, optional) is stored on every claim until the token is issued, so the
 * stored value is the one sent with the claim that received the token (TASK-112).
 */
export async function claimDevice(
  db: Db,
  args: { code: string; serial: string; certSha256?: string; ip: string; now: Date },
): Promise<ClaimResult> {
  const ipKey = `claim:ip:${args.ip}`;
  if (await isRateLimited(db, ipKey, 10, 60, args.now)) throw new ApiError(429, "rate_limited", "too many wrong claims");
  const codeHash = pairingCodeHash(args.code);
  const out = await db.tx(async (tx): Promise<ClaimResult | "wrong" | "claimed" | "revoked"> => {
    const d = await getDeviceByCode(tx, codeHash, true);
    if (!d || !constantTimeEqual(d.serial, args.serial)) return "wrong";
    const issuable = d.status === "provisioned" || (d.status === "paired" && d.shop_id !== null && !d.token_hash);
    if (args.certSha256 && issuable) {
      await tx.query("update devices set cert_sha256 = $2, updated_at = now() where id = $1", [d.id, args.certSha256]);
    }
    if (d.status === "provisioned") return { status: "pending" };
    if (d.status !== "paired" || !d.shop_id) return "revoked";
    if (d.token_hash) return "claimed";
    const token = randomToken(32);
    await tx.query(
      "update devices set token_hash = $2, token_issued_at = $3::timestamptz, last_seen_at = $3::timestamptz, updated_at = now() where id = $1",
      [d.id, sha256Hex(token), args.now.toISOString()],
    );
    return { status: "paired", device_token: token, device_id: d.id, shop_id: d.shop_id };
  });
  if (out === "wrong") {
    await recordHit(db, ipKey, args.now);
    throw new ApiError(401, "unauthorized", "unknown pairing code or serial");
  }
  if (out === "claimed") throw new ApiError(409, "conflict", "device token already issued");
  if (out === "revoked") throw new ApiError(403, "forbidden", "device unpaired or revoked");
  if (out.status === "paired") log.info("device_claimed", { device_id: out.device_id, shop_id: out.shop_id });
  return out;
}

/** HMAC-SHA256(HUB_PAIRING_SECRET, device_id ":" generation), base64url of 32 bytes. Never stored. */
export function deriveUnlockToken(secret: string, deviceId: string, generation: number): string {
  return createHmac("sha256", secret).update(`${deviceId}:${generation}`, "utf8").digest("base64url");
}

/** POST /api/devices/unlock for an authenticated device (requireDevice already refused revoked). */
export async function issueUnlockToken(db: Db, device: Pick<DeviceRecord, "id" | "status" | "generation">, secret: string): Promise<string> {
  if (device.status !== "paired") throw new ApiError(403, "forbidden", "device unpaired or revoked");
  const token = deriveUnlockToken(secret, device.id, device.generation);
  await db.query("update devices set data_unlock_token_hash = $2, updated_at = now() where id = $1", [
    device.id,
    sha256Hex(token),
  ]);
  return token;
}

/** Revoke (kill switch): stops unlock tokens, bumps the generation, tells an online hub. */
export async function revokeDevice(db: Db, deviceId: string, now: Date): Promise<void> {
  await db.tx(async (tx) => {
    const rows = await tx.query<{ shop_id: string | null; status: string }>(
      "select shop_id, status from devices where id = $1 for update",
      [deviceId],
    );
    const d = rows[0];
    if (!d) throw new ApiError(404, "not_found", "no such device");
    if (d.status === "revoked") return;
    await tx.query(
      `update devices set status = 'revoked', generation = generation + 1, data_unlock_token_hash = null,
         revoked_at = $2::timestamptz, updated_at = now() where id = $1`,
      [deviceId, now.toISOString()],
    );
    if (d.shop_id) await queueInbox(tx, d.shop_id, "device_revoked", {}, deviceId);
  });
  log.info("device_revoked", { device_id: deviceId });
}

/** Unpair: the device token stops working, the generation is bumped, the code stays used. */
export async function unpairDevice(db: Db, deviceId: string): Promise<void> {
  await db.query(
    `update devices set status = 'unpaired', generation = generation + 1, token_hash = null,
       data_unlock_token_hash = null, updated_at = now() where id = $1`,
    [deviceId],
  );
  log.info("device_unpaired", { device_id: deviceId });
}

export const OFFLINE_AFTER_MS = 15 * 60 * 1000;

export function isOnline(lastSeen: Date | null, now: Date): boolean {
  return lastSeen !== null && now.getTime() - new Date(lastSeen).getTime() <= OFFLINE_AFTER_MS;
}

export function deviceStatusView(d: DeviceRecord, now: Date) {
  const online = d.status === "paired" && isOnline(d.last_seen_at, now);
  return {
    id: d.id,
    hub_version: d.hub_version,
    last_seen_at: toIstIso(d.last_seen_at),
    online,
    locked: d.locked,
    chain_break: d.chain_break || d.anchor_mismatch,
    cert_sha256: d.cert_sha256 ?? null,
  };
}
