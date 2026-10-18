import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ev, healthPayload, postEvents, setupShop } from "./helpers/factory";
import { call, type RouteModule } from "./helpers/http";

// TASK-113 and docs/ARCHITECTURE.md section 13: no route accepts image bytes; negative tests for
// every authentication path; the service role key only in server code.
const SRC = path.resolve(__dirname, "..", "src");
const API = path.join(SRC, "app", "api");

function walk(dir: string, out: string[] = []): string[] {
  for (const f of readdirSync(dir)) {
    const p = path.join(dir, f);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const routeFiles = walk(API).filter((f) => f.endsWith("route.ts"));
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"] as const;

let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

describe("binary content types are refused on every route (T-P-03)", () => {
  it("found the routes", () => {
    expect(routeFiles.length).toBeGreaterThanOrEqual(21);
  });

  for (const file of routeFiles) {
    const rel = path.relative(API, path.dirname(file));
    it(`/api/${rel}: multipart and image bodies get 415 before any handler runs`, async () => {
      const mod = (await import(file)) as RouteModule;
      const methods = METHODS.filter((m) => typeof mod[m] === "function");
      expect(methods.length).toBeGreaterThan(0);
      for (const m of methods) {
        for (const ct of ["multipart/form-data; boundary=x", "image/jpeg", "application/octet-stream", "video/mp4"]) {
          const r = await call(mod, m, { path: `/api/${rel}`, raw: m === "GET" ? undefined : "\xff\xd8\xff\xe0", headers: { "content-type": ct }, params: { id: "x" } });
          expect(r.status, `${m} ${ct}`).toBe(415);
        }
      }
    });
  }

  it("a non-JSON content type on a JSON route is 415 and an oversized body is 413", async () => {
    const shops = await import("@/app/api/shops/route");
    expect((await call(shops, "POST", { path: "/api/shops", raw: "name=x", headers: { "content-type": "application/x-www-form-urlencoded" }, token: "t" })).status).toBe(401);
    const s = await setupShop(t, h, "sec");
    expect((await call(shops, "POST", { path: "/api/shops", raw: "name=x", headers: { "content-type": "text/plain" }, token: s.owner.token })).status).toBe(415);
    const big = JSON.stringify({ name: "x".repeat(70_000), city: "c", notice_language: "en" });
    expect((await call(shops, "POST", { path: "/api/shops", raw: big, token: s.owner.token })).status).toBe(413);
  });
});

describe("negative authentication tests (TASK-113)", () => {
  it("device-token routes refuse missing, malformed and unknown tokens", async () => {
    const mods = await Promise.all([
      import("@/app/api/hub/events/route"),
      import("@/app/api/hub/inbox/route"),
      import("@/app/api/hub/anchor/route"),
      import("@/app/api/devices/unlock/route"),
    ]);
    for (const mod of mods) {
      const m = typeof (mod as RouteModule).POST === "function" ? "POST" : "GET";
      for (const headers of [{}, { authorization: "Basic abc" }, { authorization: "Bearer nope" }, { authorization: "Bearer " }] as Record<string, string>[]) {
        const r = await call(mod as RouteModule, m, { path: "/api/hub/x", headers, body: m === "POST" ? {} : undefined });
        expect(r.status).toBe(401);
      }
    }
  });

  it("an owner session token is not a device token and a device token is not an owner session", async () => {
    const s = await setupShop(t, h, "sec2");
    const events = await import("@/app/api/hub/events/route");
    expect((await call(events, "POST", { path: "/api/hub/events", token: s.owner.token, body: { events: [] } })).status).toBe(401);
    const shops = await import("@/app/api/shops/route");
    expect((await call(shops, "POST", { path: "/api/shops", token: s.deviceToken, body: { name: "x", city: "y", notice_language: "en" } })).status).toBe(401);
    // A device token from shop A cannot write rows for shop B.
    const b = await setupShop(t, h, "sec3");
    const r = await postEvents(h, s.deviceToken, [ev("staff_upsert", { id: "s_01JSECSTAFF0000000000000", shop_id: b.shopId, name: "X", role: "owner", created_at: "2026-10-18T10:00:00+05:30" })]);
    expect((r.json as unknown as { results: { reason: string }[] }).results[0]!.reason).toBe("shop_mismatch");
    await postEvents(h, s.deviceToken, [ev("health", healthPayload())]);
  });

  it("ops routes need the ops key", async () => {
    for (const p of ["ops/hubs", "ops/metrics", "ops/jobs/hub-monitor", "ops/jobs/pilot-metrics", "ops/devices"]) {
      const mod = (await import(path.join(API, p, "route.ts"))) as RouteModule;
      const m = typeof mod.GET === "function" ? "GET" : "POST";
      for (const headers of [{}, { "x-remu-ops-key": "wrong" }] as Record<string, string>[]) {
        expect((await call(mod, m, { path: `/api/${p}`, headers, body: m === "POST" ? {} : undefined })).status, p).toBe(401);
      }
    }
  });
});

describe("secrets in code (TASK-113)", () => {
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f));

  it("SUPABASE_SERVICE_ROLE_KEY and other secrets never appear in client code or under a NEXT_PUBLIC_ name", () => {
    for (const f of files) {
      const text = readFileSync(f, "utf8");
      expect(text, f).not.toMatch(/NEXT_PUBLIC_[A-Z_]*(SERVICE|SECRET|TOKEN|PRIVATE)/);
      if (/^["']use client["']/m.test(text)) {
        expect(text, f).not.toMatch(/SUPABASE_SERVICE_ROLE_KEY|process\.env\.(?!NEXT_PUBLIC_)/);
      }
    }
    const users = files.filter((f) => readFileSync(f, "utf8").includes("SUPABASE_SERVICE_ROLE_KEY"));
    for (const f of users) expect(path.relative(SRC, f).startsWith("lib/") || path.relative(SRC, f).startsWith("app/api/"), f).toBe(true);
    expect(files.some((f) => /^["']use client["']/m.test(readFileSync(f, "utf8")))).toBe(false);
  });

  it("no ONNX, InsightFace or ArcFace code in the cloud", () => {
    for (const f of files) expect(readFileSync(f, "utf8"), f).not.toMatch(/onnx|insightface|arcface/i);
  });

  it("vercel.json pins functions to bom1", () => {
    const v = JSON.parse(readFileSync(path.resolve(__dirname, "..", "vercel.json"), "utf8")) as { regions: string[] };
    expect(v.regions).toEqual(["bom1"]);
  });
});
