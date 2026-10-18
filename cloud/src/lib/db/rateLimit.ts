import type { Db } from "./types";

/** Database-backed rate limit (Vercel functions share no memory). */
export async function isRateLimited(db: Db, key: string, limit: number, windowSec: number, now: Date): Promise<boolean> {
  const rows = await db.query<{ n: number }>(
    "select count(*)::int as n from rate_limit_hits where key = $1 and at > $2::timestamptz - make_interval(secs => $3)",
    [key, now.toISOString(), windowSec],
  );
  return (rows[0]?.n ?? 0) >= limit;
}

export async function recordHit(db: Db, key: string, now: Date): Promise<void> {
  await db.query("insert into rate_limit_hits (key, at) values ($1, $2::timestamptz)", [key, now.toISOString()]);
}
