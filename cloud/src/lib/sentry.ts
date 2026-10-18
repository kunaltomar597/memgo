/**
 * Sentry for the cloud (TASK-118 cloud half). SENTRY_DSN empty disables it. send_default_pii is
 * off and every event passes through scrubEvent, which drops phone numbers, names and any binary
 * before it leaves the function (docs/ARCHITECTURE.md section 15).
 */
const DROP_TOKENS = new Set([
  "phone",
  "name",
  "names",
  "notes",
  "note",
  "items",
  "vec",
  "landmarks",
  "image",
  "crop",
  "token",
  "key",
  "secret",
  "password",
  "pin",
  "jpeg",
  "embedding",
  "template",
  "body",
  "cookie",
  "cookies",
  "authorization",
  "e164",
  "text",
]);

function keyTokens(key: string): string[] {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
}

export function sensitiveKey(key: string): boolean {
  return keyTokens(key).some((t) => DROP_TOKENS.has(t));
}

const PHONE_PATTERNS = [
  /\+\d[\d\s-]{7,16}\d/g, // E.164 and spaced international numbers
  /(?<!\d)(?:91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)/g, // Indian mobiles with or without 91
  /(?<!\d)\d{10,15}(?!\d)/g, // any other long digit run
];
const BASE64_BLOB = /[A-Za-z0-9+/_-]{256,}={0,2}/g;

export function scrubString(s: string): string {
  let out = s.replace(BASE64_BLOB, "[binary]");
  for (const re of PHONE_PATTERNS) out = out.replace(re, "[phone]");
  out = out.replace(/(https?:\/\/[^\s?#]+)\?[^\s#]*/g, "$1?[query]");
  return out;
}

function isBinary(v: unknown): boolean {
  return (
    v instanceof ArrayBuffer ||
    ArrayBuffer.isView(v) ||
    (typeof Blob !== "undefined" && v instanceof Blob)
  );
}

function scrubValue(v: unknown, depth: number): unknown {
  if (depth > 12) return "[depth]";
  if (typeof v === "string") return scrubString(v);
  if (isBinary(v)) return undefined;
  if (Array.isArray(v)) return v.map((x) => scrubValue(x, depth + 1)).filter((x) => x !== undefined);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      if (sensitiveKey(k)) continue;
      if (k === "vars") continue; // stack frame locals
      const s = scrubValue(val, depth + 1);
      if (s !== undefined) out[k] = s;
    }
    return out;
  }
  return v;
}

const SAFE_HEADERS = new Set(["user-agent", "content-type", "content-length", "x-request-id"]);

// Structural Sentry fields that must survive key-based dropping.
const KEEP_TOP = new Set(["event_id", "timestamp", "platform", "level", "release", "environment", "sdk", "server_name", "transaction", "fingerprint", "type", "start_timestamp"]);

export type SentryLikeEvent = Record<string, unknown>;

export function scrubEvent<T extends SentryLikeEvent>(event: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(event)) {
    if (KEEP_TOP.has(k)) {
      out[k] = v;
      continue;
    }
    if (k === "user") {
      const id = (v as { id?: unknown } | null)?.id;
      if (id !== undefined) out.user = { id: scrubValue(id, 0) };
      continue;
    }
    if (k === "request" && v && typeof v === "object") {
      const r = v as Record<string, unknown>;
      const headers: Record<string, unknown> = {};
      for (const [hk, hv] of Object.entries((r.headers as Record<string, unknown>) ?? {})) {
        if (SAFE_HEADERS.has(hk.toLowerCase())) headers[hk] = hv;
      }
      out.request = {
        method: r.method,
        url: typeof r.url === "string" ? scrubString(r.url) : undefined,
        headers,
      };
      continue;
    }
    if (sensitiveKey(k)) continue;
    const s = scrubValue(v, 0);
    if (s !== undefined) out[k] = s;
  }
  return out as T;
}

export function scrubBreadcrumb<T extends Record<string, unknown>>(crumb: T): T {
  return scrubValue(crumb, 0) as T;
}

/** Sentry init options shared by instrumentation.ts. */
export function sentryOptions(dsn: string, release: string) {
  return {
    dsn,
    release,
    environment: process.env.VERCEL_ENV ?? process.env.NODE_ENV ?? "development",
    sendDefaultPii: false,
    tracesSampleRate: 0,
    maxBreadcrumbs: 30,
    beforeSend: (event: SentryLikeEvent) => scrubEvent(event),
    beforeBreadcrumb: (crumb: Record<string, unknown>) => scrubBreadcrumb(crumb),
  };
}

function enabled(): boolean {
  return Boolean(process.env.SENTRY_DSN && process.env.SENTRY_DSN.trim() !== "");
}

export function reportError(e: unknown): void {
  if (!enabled()) return;
  void import("@sentry/nextjs").then((S) => S.captureException(e)).catch(() => undefined);
}

/** Ids-only alert to Sentry, for chain breaks, anchor mismatches and ops alerts. */
export function reportMessage(message: string, tags: Record<string, string>): void {
  if (!enabled()) return;
  void import("@sentry/nextjs")
    .then((S) => S.captureMessage(message, { level: "warning", tags }))
    .catch(() => undefined);
}
