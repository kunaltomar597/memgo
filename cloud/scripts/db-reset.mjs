#!/usr/bin/env node
// npm run db:reset: applies supabase/migrations/*.sql to a fresh database and lists the tables.
//
// Default: an in-process PGlite (no Docker, no account) with the Supabase roles and auth shim from
// test/helpers/supabase-shim.sql, which proves the migrations apply cleanly.
// With DB_RESET_URL=postgres://... it drops and recreates the public schema of that local Postgres
// and applies the migrations there. It refuses any supabase.co host: hosted migrations run only
// with `supabase db push` from CI (docs/ARCHITECTURE.md section 12).
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dir = path.join(root, "supabase", "migrations");
const files = readdirSync(dir).filter((f) => f.endsWith(".sql")).sort();
const listSql =
  "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1";

const url = process.env.DB_RESET_URL;
if (url) {
  if (/supabase\.(co|com)/i.test(url)) {
    console.error("db:reset refuses a hosted Supabase database; use `supabase db push` from CI.");
    process.exit(1);
  }
  const { default: postgres } = await import("postgres");
  const sql = postgres(url, { max: 1, onnotice: () => undefined });
  const hasAuth = await sql.unsafe("select 1 from pg_namespace where nspname = 'auth'");
  if (hasAuth.length === 0) {
    // Plain Postgres: add what Supabase would provide (roles, auth.users, auth.uid()).
    await sql.unsafe(readFileSync(path.join(root, "test", "helpers", "supabase-shim.sql"), "utf8"));
  }
  await sql.unsafe("drop schema if exists public cascade; create schema public;");
  await sql.unsafe("grant usage on schema public to anon, authenticated, service_role;");
  for (const f of files) {
    await sql.unsafe(readFileSync(path.join(dir, f), "utf8"));
    console.log(`applied ${f}`);
  }
  const rows = await sql.unsafe(listSql);
  console.log(`tables: ${rows.map((r) => r.table_name).join(", ")}`);
  await sql.end();
} else {
  const { PGlite } = await import("@electric-sql/pglite");
  const pg = new PGlite();
  await pg.exec(readFileSync(path.join(root, "test", "helpers", "supabase-shim.sql"), "utf8"));
  for (const f of files) {
    await pg.exec(readFileSync(path.join(dir, f), "utf8"));
    console.log(`applied ${f}`);
  }
  const { rows } = await pg.query(listSql);
  console.log(`tables: ${rows.map((r) => r.table_name).join(", ")}`);
  await pg.close();
}
