import { createHash } from "node:crypto";

/**
 * Retention for raw WhatsApp webhook JSON (docs/API_CONTRACT.md section 7 item 9). Before any
 * webhook object is stored, every sender, recipient or business number is replaced by its
 * phone_hash (lowercase hex SHA-256 of "+" and the digits), and an inbound message other than a
 * STOP keeps only its type, id, timestamp, hashed sender and quoted wamid: no text body.
 */
const NUMBER_KEYS = new Set(["from", "wa_id", "recipient_id", "display_phone_number", "phone_number", "to"]);
const NUMBER_RE = /^\+?\d{6,15}$/;

export function hashWaNumber(value: string): string {
  return createHash("sha256").update(`+${value.replace(/^\+/, "")}`, "utf8").digest("hex");
}

/** Replaces phone numbers under the known number keys, at any depth. */
export function redactNumbers(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redactNumbers);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = NUMBER_KEYS.has(k) && typeof v === "string" && NUMBER_RE.test(v.trim()) ? hashWaNumber(v.trim()) : redactNumbers(v);
    }
    return out;
  }
  return value;
}

interface InboundLike {
  id?: unknown;
  type?: unknown;
  timestamp?: unknown;
  from?: unknown;
  context?: { id?: unknown } | null;
}

/** The stored form of an inbound message: a STOP in full (numbers hashed), anything else reduced. */
export function redactInbound(msg: InboundLike, kind: "stop" | "thumbs_up" | "text" | "other"): Record<string, unknown> {
  if (kind === "stop") return redactNumbers(msg) as Record<string, unknown>;
  const out: Record<string, unknown> = { id: msg.id ?? null, type: msg.type ?? null, timestamp: msg.timestamp ?? null };
  if (kind === "other") return out; // media and other types: type and id only, no media references
  if (typeof msg.from === "string" && NUMBER_RE.test(msg.from.trim())) out.from = hashWaNumber(msg.from.trim());
  if (msg.context && typeof msg.context.id === "string") out.context = { id: msg.context.id };
  return out;
}
