/**
 * The one database interface every repository function takes. Production wraps porsager
 * `postgres` on DATABASE_URL (src/lib/db/postgres.ts); tests wrap an in-process PGlite running
 * the real migrations (test/helpers/db.ts). Queries use $1-style parameters.
 */
export interface Db {
  query<T = Record<string, unknown>>(text: string, params?: readonly unknown[]): Promise<T[]>;
  /** Runs fn in one transaction. Nested calls reuse the outer transaction. */
  tx<T>(fn: (db: Db) => Promise<T>): Promise<T>;
}

export async function one<T>(db: Db, text: string, params?: readonly unknown[]): Promise<T | undefined> {
  const rows = await db.query<T>(text, params);
  return rows[0];
}
