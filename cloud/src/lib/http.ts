import type { z } from "zod";
import { ConfigError } from "./env";
import { log } from "./log";
import { findForbidden, type Violation } from "./payloadGuard";
import { zodReason } from "./schemas";

/** Error codes of docs/API_CONTRACT.md conventions, plus internal for server faults. */
export type ErrorCode =
  | "unauthorized"
  | "forbidden"
  | "not_found"
  | "invalid"
  | "conflict"
  | "gone"
  | "rate_limited"
  | "locked"
  | "payload_forbidden"
  | "unsupported_media_type"
  | "payload_too_large"
  | "internal";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ErrorCode,
    readonly detail: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function noContent(status = 204): Response {
  return new Response(null, { status, headers: { "cache-control": "no-store" } });
}

export function errorResponse(e: ApiError): Response {
  return json({ error: e.code, detail: e.detail, ...e.extra }, e.status);
}

export const DEFAULT_MAX_BODY = 64 * 1024;
export const HUB_EVENTS_MAX_BODY = 1024 * 1024;

const REFUSED_MEDIA = [/^multipart\//i, /^image\//i, /^video\//i, /^audio\//i, /^application\/octet-stream/i];

/** 415 for multipart and binary content types, on every route, before any handler runs. */
export function refuseBinaryContentType(req: Request): void {
  const ct = req.headers.get("content-type") ?? "";
  if (REFUSED_MEDIA.some((re) => re.test(ct.trim()))) {
    throw new ApiError(415, "unsupported_media_type", "only application/json is accepted");
  }
}

/** Reads the body as text with a hard byte cap (413 over the cap). */
export async function readBodyText(req: Request, maxBytes: number): Promise<string> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) {
    throw new ApiError(413, "payload_too_large", `body over ${maxBytes} bytes`);
  }
  if (!req.body) return "";
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new ApiError(413, "payload_too_large", `body over ${maxBytes} bytes`);
    }
    chunks.push(value);
  }
  return new TextDecoder("utf-8", { fatal: false }).decode(Buffer.concat(chunks));
}

/**
 * Parses a JSON body: JSON content type only, size capped, the payload_forbidden scan, then a
 * strict zod schema.
 */
export async function readJson<S extends z.ZodType>(
  req: Request,
  schema: S,
  maxBytes = DEFAULT_MAX_BODY,
): Promise<z.infer<S>> {
  const raw = await readRawJson(req, maxBytes);
  const parsed = schema.safeParse(raw);
  if (!parsed.success) throw new ApiError(400, "invalid", zodReason(parsed.error));
  return parsed.data;
}

/**
 * Reads and parses a JSON body, then runs the payload_forbidden scan (400 on a hit). POST
 * /api/hub/events passes a scan that skips event payloads, which ingest checks per event instead.
 */
export async function readRawJson(
  req: Request,
  maxBytes = DEFAULT_MAX_BODY,
  scan: (raw: unknown) => Violation | null = findForbidden,
): Promise<unknown> {
  const ct = req.headers.get("content-type") ?? "";
  if (!/^application\/json\b/i.test(ct.trim())) {
    throw new ApiError(415, "unsupported_media_type", "only application/json is accepted");
  }
  const text = await readBodyText(req, maxBytes);
  let raw: unknown;
  try {
    raw = text === "" ? {} : JSON.parse(text);
  } catch {
    throw new ApiError(400, "invalid", "body is not valid JSON");
  }
  const violation = scan(raw);
  if (violation) {
    log.warn("payload_forbidden", { reason: violation.reason });
    throw new ApiError(400, "payload_forbidden", `forbidden content at ${violation.path}`);
  }
  return raw;
}

export interface RouteContext<P> {
  params: Promise<P>;
}

export type Handler<P> = (req: Request, params: P) => Promise<Response>;

/** Wraps a route handler: media type guard, error mapping, no PII in logs. */
export function route<P = Record<string, never>>(name: string, handler: Handler<P>) {
  return async (req: Request, ctx: RouteContext<P>): Promise<Response> => {
    const started = Date.now();
    try {
      refuseBinaryContentType(req);
      const params = ctx?.params ? await ctx.params : ({} as P);
      const res = await handler(req, params);
      log.info("request", { route: name, http_status: res.status, duration_ms: Date.now() - started });
      return res;
    } catch (e) {
      if (e instanceof ApiError) {
        log.info("request", { route: name, http_status: e.status, reason: e.code, duration_ms: Date.now() - started });
        return errorResponse(e);
      }
      if (e instanceof ConfigError) {
        log.error("config_missing", { route: name });
        return json({ error: "internal", detail: "server not configured" }, 500);
      }
      // Error class and Postgres SQLSTATE only: never the message, which can carry row values.
      const err = e as { name?: unknown; code?: unknown; constraint_name?: unknown; routine?: unknown };
      log.error("unhandled", {
        route: name,
        duration_ms: Date.now() - started,
        error_class: typeof err?.name === "string" ? err.name : "unknown",
        sqlstate: typeof err?.code === "string" ? err.code : undefined,
        constraint: typeof err?.constraint_name === "string" ? err.constraint_name : undefined,
        routine: typeof err?.routine === "string" ? err.routine : undefined,
      });
      const { reportError } = await import("./sentry");
      reportError(e);
      return json({ error: "internal", detail: "internal error" }, 500);
    }
  };
}

export function bearer(req: Request): string | null {
  const h = req.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(\S+)$/i.exec(h.trim());
  return m ? m[1]! : null;
}

export function clientIp(req: Request): string {
  const xff = req.headers.get("x-forwarded-for");
  if (xff) return xff.split(",")[0]!.trim();
  return req.headers.get("x-real-ip") ?? "unknown";
}
