/**
 * Structured JSON logs with an allow-list of fields. Values that are not on the list are dropped,
 * so a name, phone number, note, message text, token or raw webhook body cannot reach the logs
 * even by mistake (docs/RULES.md privacy invariant 10). Ids are fine; values are not.
 */
const ALLOWED = new Set([
  "error_class",
  "sqlstate",
  "constraint",
  "routine",
  "component",
  "event",
  "shop_id",
  "device_id",
  "member_id",
  "visit_id",
  "consent_id",
  "event_id",
  "request_id",
  "wamid",
  "status",
  "outcome",
  "reason",
  "count",
  "duration_ms",
  "http_status",
  "route",
  "kind",
]);

export type LogFields = Record<string, string | number | boolean | null | undefined>;
type Level = "debug" | "info" | "warn" | "error";

export type LogSink = (line: string) => void;
let sink: LogSink = (line) => console.log(line);

/** Tests capture log lines through this. */
export function setLogSink(s: LogSink): void {
  sink = s;
}

export function resetLogSink(): void {
  sink = (line) => console.log(line);
}

function emit(level: Level, event: string, fields: LogFields = {}): void {
  const out: Record<string, unknown> = { ts: new Date().toISOString(), level, component: "cloud", event };
  for (const [k, v] of Object.entries(fields)) {
    if (!ALLOWED.has(k) || v === undefined) continue;
    // Strings are capped: ids and codes are short; a long value is not an id.
    out[k] = typeof v === "string" ? v.slice(0, 80) : v;
  }
  sink(JSON.stringify(out));
}

export const log = {
  debug: (event: string, f?: LogFields) => emit("debug", event, f),
  info: (event: string, f?: LogFields) => emit("info", event, f),
  warn: (event: string, f?: LogFields) => emit("warn", event, f),
  error: (event: string, f?: LogFields) => emit("error", event, f),
};
