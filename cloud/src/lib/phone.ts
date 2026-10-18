import { createHash } from "node:crypto";

/**
 * phone_hash (docs/API_CONTRACT.md conventions): lowercase hex SHA-256 of the UTF-8 E.164 string
 * including the leading "+", no prefix, no salt. "+919876543210" ->
 * f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e.
 */
export function phoneHash(e164: string): string {
  if (!/^\+[1-9]\d{7,14}$/.test(e164)) throw new Error("phone must be E.164 with a leading +");
  return createHash("sha256").update(e164, "utf8").digest("hex");
}

/** WhatsApp webhooks give the sender as digits without "+" ("919876543210"). */
export function e164FromWhatsApp(from: string): string {
  return `+${from.replace(/^\+/, "")}`;
}

export const PHONE_HASH_RE = /^[0-9a-f]{64}$/;
