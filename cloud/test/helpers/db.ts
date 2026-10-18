import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { PGlite, type Transaction } from "@electric-sql/pglite";
import type { Db } from "@/lib/db/types";

const ROOT = path.resolve(__dirname, "..", "..");
export const MIGRATIONS_DIR = path.join(ROOT, "supabase", "migrations");

export function migrationFiles(): string[] {
  return readdirSync(MIGRATIONS_DIR)
    .filter((f) => f.endsWith(".sql"))
    .sort();
}

type Queryable = PGlite | Transaction;

function wrap(q: Queryable, inTx: boolean): Db {
  const db: Db = {
    async query<T>(text: string, params: readonly unknown[] = []): Promise<T[]> {
      const r = await q.query<T>(text, params as unknown[]);
      return r.rows;
    },
    async tx<T>(fn: (d: Db) => Promise<T>): Promise<T> {
      if (inTx) return fn(db);
      return (q as PGlite).transaction(async (t) => fn(wrap(t, true)));
    },
  };
  return db;
}

export interface TestDb {
  pg: PGlite;
  db: Db;
  /** Runs fn as Supabase's `authenticated` role with the given user's JWT claims (RLS applies). */
  asUser<T>(userId: string, fn: (q: Transaction) => Promise<T>): Promise<T>;
  asRole<T>(role: "anon" | "service_role" | "authenticated", fn: (q: Transaction) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

/** A fresh in-process Postgres with the Supabase shim and every real migration applied. */
export async function createTestDb(): Promise<TestDb> {
  const pg = new PGlite();
  await pg.exec("set timezone = 'UTC'");
  await pg.exec(readFileSync(path.join(__dirname, "supabase-shim.sql"), "utf8"));
  for (const f of migrationFiles()) {
    await pg.exec(readFileSync(path.join(MIGRATIONS_DIR, f), "utf8"));
  }
  return {
    pg,
    db: wrap(pg, false),
    asUser: (userId, fn) =>
      pg.transaction(async (t) => {
        await t.query("select set_config('request.jwt.claims', $1, true)", [JSON.stringify({ sub: userId, role: "authenticated" })]);
        await t.exec("set local role authenticated");
        return fn(t);
      }),
    asRole: (role, fn) =>
      pg.transaction(async (t) => {
        await t.exec(`set local role ${role}`);
        return fn(t);
      }),
    close: () => pg.close(),
  };
}
