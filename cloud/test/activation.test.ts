import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as withdrawRoute from "@/app/api/members/[id]/withdraw/route";
import { ALLOWED, activateOnDelivery, canTransition, transition } from "@/lib/memberState";
import type { MemberStatus } from "@/lib/db/members";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, memberUpsert, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

// "Only the delivered webhook path sets a member active" (TASK-065, docs/TEST_PLAN.md section 17).
let t: TestDb;
let h: TestHarness;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
});
afterAll(async () => t.close());

const status = async (id: string) => (await t.db.query<{ status: string }>("select status from members where id = $1", [id]))[0]!.status;

describe("state machine", () => {
  it("allows exactly the four transitions and rejects the rest", () => {
    const all: MemberStatus[] = ["pending_notice", "active", "withdrawn", "expired"];
    const allowed = all.flatMap((f) => all.filter((to) => canTransition(f, to)).map((to) => `${f}->${to}`));
    expect(allowed.sort()).toEqual(["active->expired", "active->withdrawn", "pending_notice->active", "pending_notice->withdrawn"]);
    for (const bad of ["pending_notice->expired", "withdrawn->active", "expired->active", "expired->withdrawn"]) expect(allowed).not.toContain(bad);
    expect(Object.keys(ALLOWED)).toHaveLength(4);
  });

  it("transition() refuses active", async () => {
    await expect(transition(t.db, { memberId: "m_x", to: "active" as never, by: "hub", reason: "x", at: new Date() })).rejects.toThrow();
  });

  it("activateOnDelivery is the one path, and only from pending_notice", async () => {
    const s = await setupShop(t, h, "sm");
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000801" });
    await postEvents(h, s.deviceToken, e.events);
    expect((await activateOnDelivery(t.db, { memberId: e.memberId, at: new Date() })).changed).toBe(true);
    expect(await status(e.memberId)).toBe("active");
    expect((await transition(t.db, { memberId: e.memberId, to: "withdrawn", by: "owner", reason: "owner_remove", at: new Date() })).changed).toBe(true);
    expect((await activateOnDelivery(t.db, { memberId: e.memberId, at: new Date() })).changed).toBe(false);
    expect(await status(e.memberId)).toBe("withdrawn");
  });
});

describe("no other path sets active", () => {
  it("a hub member_upsert with status active does not activate (insert or update)", async () => {
    const s = await setupShop(t, h, "hubact");
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000802" });
    await postEvents(h, s.deviceToken, e.events);
    const up = memberUpsert(s.shopId, e.memberId, { phone: "+919800000802", status: "active", ts: "2026-10-18T17:50:00+05:30" });
    const r = await postEvents(h, s.deviceToken, [ev("member_upsert", up)]);
    expect((r.json as unknown as { results: { status: string }[] }).results[0]!.status).toBe("acked");
    expect(await status(e.memberId)).toBe("pending_notice");
    const fresh = memberUpsert(s.shopId, "m_01JFRESHACTIVE000000000000", { phone: "+919800000803", status: "active" });
    await postEvents(h, s.deviceToken, [ev("member_upsert", fresh)]);
    expect(await status(fresh.id)).toBe("pending_notice");
  });

  it("owner withdraw cannot activate and a direct UPDATE fails at the database", async () => {
    const s = await setupShop(t, h, "ownact");
    const e = enrolEvents(s.shopId, new ChainBuilder(s.shopId), { phone: "+919800000804" });
    await postEvents(h, s.deviceToken, e.events);
    await expect(t.db.query("update members set status = 'active' where id = $1", [e.memberId])).rejects.toThrow(/notice delivery/);
    const r = await call(withdrawRoute, "POST", { path: `/api/members/${e.memberId}/withdraw`, token: s.owner.token, params: { id: e.memberId } });
    expect(r.status).toBe(202);
    expect(await status(e.memberId)).toBe("withdrawn");
  });

  it("the source code: only memberState.ts writes members.status, and only one function sets remu.activation", () => {
    const root = path.resolve(__dirname, "..", "src");
    const files: string[] = [];
    const walk = (d: string) => {
      for (const f of readdirSync(d)) {
        const p = path.join(d, f);
        if (statSync(p).isDirectory()) walk(p);
        else if (/\.tsx?$/.test(f)) files.push(p);
      }
    };
    walk(root);
    const statusWriters = files.filter((f) => /update\s+members\s+set\s+[^`"]*\bstatus\s*=/i.test(readFileSync(f, "utf8")));
    expect(statusWriters.map((f) => path.relative(root, f))).toEqual(["lib/memberState.ts"]);
    const activators = files.filter((f) => readFileSync(f, "utf8").includes("'notice_delivered', true"));
    expect(activators.map((f) => path.relative(root, f))).toEqual(["lib/memberState.ts"]);
    const callers = files.filter((f) => /activateOnDelivery\(/.test(readFileSync(f, "utf8")) && !f.endsWith("memberState.ts"));
    expect(callers.map((f) => path.relative(root, f))).toEqual(["lib/whatsappWebhook.ts"]);
  });
});
