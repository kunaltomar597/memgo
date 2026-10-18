/** Timestamps travel as ISO 8601 with an offset; the cloud renders them in Asia/Kolkata (+05:30). */
const IST_OFFSET_MIN = 330;

export function toIstIso(d: Date | string | null | undefined): string | null {
  if (d === null || d === undefined) return null;
  const date = typeof d === "string" ? new Date(d) : d;
  if (Number.isNaN(date.getTime())) return null;
  const shifted = new Date(date.getTime() + IST_OFFSET_MIN * 60_000);
  const iso = shifted.toISOString(); // e.g. 2026-10-18T12:12:11.000Z (shifted clock)
  return `${iso.slice(0, 19)}+05:30`;
}

export function istDate(d: Date): string {
  return (toIstIso(d) ?? "").slice(0, 10);
}

export const ISO_TS_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$/;
export const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export function unixSecondsToDate(s: string | number): Date {
  return new Date(Number(s) * 1000);
}
