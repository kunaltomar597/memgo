import postgres from "postgres";
import type { Db } from "./types";

type Sql = postgres.Sql | postgres.TransactionSql;

function wrap(sql: Sql, inTx: boolean): Db {
  return {
    async query<T>(text: string, params: readonly unknown[] = []): Promise<T[]> {
      const rows = await sql.unsafe(text, params as postgres.ParameterOrJSON<never>[]);
      return rows as unknown as T[];
    },
    async tx<T>(fn: (db: Db) => Promise<T>): Promise<T> {
      if (inTx) return fn(this);
      const root = sql as postgres.Sql;
      return (await root.begin(async (t) => fn(wrap(t, true)))) as T;
    },
  };
}

let cached: Db | undefined;

/** Production database from DATABASE_URL (Supabase Postgres in ap-south-1, table owner role). */
export function postgresDb(url: string): Db {
  if (cached) return cached;
  const sql = postgres(url, {
    max: 5,
    idle_timeout: 20,
    prepare: false, // Supabase's transaction pooler (port 6543) does not support prepared statements.
    transform: { undefined: null },
    onnotice: () => undefined,
  });
  cached = wrap(sql, false);
  return cached;
}
