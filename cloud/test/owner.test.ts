import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as devicesRoute from "@/app/api/shops/[id]/devices/route";
import * as inboxRoute from "@/app/api/hub/inbox/route";
import * as withdrawRoute from "@/app/api/members/[id]/withdraw/route";
import * as cardRoute from "@/app/api/visits/[id]/card/route";
import * as feedbackRoute from "@/app/api/visits/[id]/feedback/route";
import { createTestDb, type TestDb } from "./helpers/db";
import { makeHarness, type TestHarness } from "./helpers/deps";
import { ChainBuilder, enrolEvents, ev, healthPayload, nextId, postEvents, setupShop } from "./helpers/factory";
import { call } from "./helpers/http";

let t: TestDb;
let h: TestHarness;
let A: Awaited<ReturnType<typeof setupShop>>;
let B: Awaited<ReturnType<typeof setupShop>>;
let memberId: string;
let v1: string;
let v2: string;
beforeAll(async () => {
  t = await createTestDb();
  h = makeHarness(t.db);
  A = await setupShop(t, h, "ownA");
  B = await setupShop(t, h, "ownB");
  const e = enrolEvents(A.shopId, new ChainBuilder(A.shopId), { phone: "+919800001001", name: "Rajesh Test" });
  memberId = e.memberId;
  const hh = nextId("h");
  v1 = nextId("v");
  v2 = nextId("v");
  const visit = (id: string, ts: string) => ev("visit_summary", { id, member_id: memberId, ts, camera: "gate", score: 0.58, frames_agreed: 3, greeted: false, greeted_by: null, not_them: false });
  await postEvents(h, A.deviceToken, [
    ...e.events,
    ev("household_upsert", { id: hh, shop_id: A.shopId, label: "Test family", payer_member_id: memberId, member_ids: [memberId], created_at: "2026-10-18T17:42:11+05:30" }),
    ev("purchase", { id: nextId("p"), member_id: memberId, bill_no: "INV-2", amount: 1500.5, items_text: "Silver anklet", occasion: null, ts: "2026-10-20T12:00:00+05:30" }),
    visit(v1, "2026-11-01T11:00:00+05:30"),
    visit(v2, "2026-12-01T11:00:00+05:30"),
  ]);
});
afterAll(async () => t.close());

type Msg = { id: string; type: string; payload: Record<string, unknown> };
const inboxAll = async (token: string): Promise<Msg[]> => (((await call(inboxRoute, "GET", { path: "/api/hub/inbox", token })).json) as unknown as { messages: Msg[] }).messages;

describe("GET /api/visits/{id}/card (TASK-069)", () => {
  it("returns the alert payload fields from the mirror", async () => {
    const r = await call(cardRoute, "GET", { path: `/api/visits/${v2}/card`, token: A.owner.token, params: { id: v2 } });
    expect(r.status).toBe(200);
    expect(r.json).toEqual({
      visit_id: v2,
      member_id: memberId,
      display_name: "Rajesh Test",
      household_label: "Test family",
      last_visit: "2026-11-01T11:00:00+05:30",
      last_purchase: { amount: 1500.5, items: "Silver anklet" },
      notes: "Asked about matching earrings",
      score: 0.58,
      frames_agreed: 3,
    });
    const first = await call(cardRoute, "GET", { path: `/api/visits/${v1}/card`, token: A.owner.token, params: { id: v1 } });
    expect((first.json as unknown as { last_visit: string }).last_visit).toBe("2026-10-18T17:42:11+05:30"); // member_since
  });

  it("refuses another shop's owner (403), unknown visits (404) and no session (401)", async () => {
    expect((await call(cardRoute, "GET", { path: `/api/visits/${v2}/card`, token: B.owner.token, params: { id: v2 } })).status).toBe(403);
    expect((await call(cardRoute, "GET", { path: "/api/visits/v_nope/card", token: A.owner.token, params: { id: "v_nope" } })).status).toBe(404);
    expect((await call(cardRoute, "GET", { path: `/api/visits/${v2}/card`, params: { id: v2 } })).status).toBe(401);
  });
});

describe("POST /api/visits/{id}/feedback and /api/members/{id}/withdraw (TASK-069)", () => {
  it("feedback updates the mirror and queues visit_feedback for the hub", async () => {
    expect((await call(feedbackRoute, "POST", { path: `/api/visits/${v2}/feedback`, token: B.owner.token, params: { id: v2 }, body: { not_them: true } })).status).toBe(403);
    const r = await call(feedbackRoute, "POST", { path: `/api/visits/${v2}/feedback`, token: A.owner.token, params: { id: v2 }, body: { greeted: true, greeted_by: "s_01JOWNERSTAFF00000000000" } });
    expect(r.status).toBe(204);
    const row = (await t.db.query("select greeted, greeted_by, not_them from visits where id = $1", [v2]))[0];
    expect(row).toEqual({ greeted: true, greeted_by: "s_01JOWNERSTAFF00000000000", not_them: false });
    const msgs = await inboxAll(A.deviceToken);
    expect(msgs.filter((m) => m.type === "visit_feedback").at(-1)!.payload).toEqual({ visit_id: v2, greeted: true, greeted_by: "s_01JOWNERSTAFF00000000000", not_them: false });
    expect((await inboxAll(B.deviceToken)).some((m) => m.type === "visit_feedback")).toBe(false);
    expect((await call(feedbackRoute, "POST", { path: `/api/visits/${v1}/feedback`, token: A.owner.token, params: { id: v1 }, body: {} })).status).toBe(400);
    expect((await call(feedbackRoute, "POST", { path: `/api/visits/${v1}/feedback`, token: A.owner.token, params: { id: v1 }, body: { greeted: "yes" } })).status).toBe(400);
  });

  it("withdraw moves the mirror to withdrawn and queues member_status (owner_remove)", async () => {
    expect((await call(withdrawRoute, "POST", { path: `/api/members/${memberId}/withdraw`, token: B.owner.token, params: { id: memberId } })).status).toBe(403);
    expect((await t.db.query<{ status: string }>("select status from members where id = $1", [memberId]))[0]!.status).toBe("pending_notice");
    const r = await call(withdrawRoute, "POST", { path: `/api/members/${memberId}/withdraw`, token: A.owner.token, params: { id: memberId } });
    expect(r.status).toBe(202);
    expect((await t.db.query<{ status: string }>("select status from members where id = $1", [memberId]))[0]!.status).toBe("withdrawn");
    const ms = (await inboxAll(A.deviceToken)).filter((m) => m.type === "member_status");
    expect(ms).toHaveLength(1);
    expect(ms[0]!.payload).toEqual({ member_id: memberId, status: "withdrawn", reason: "owner_remove", at: "2026-10-18T17:30:00+05:30" });
    // Idempotent: a second tap queues nothing new.
    await call(withdrawRoute, "POST", { path: `/api/members/${memberId}/withdraw`, token: A.owner.token, params: { id: memberId } });
    expect((await inboxAll(A.deviceToken)).filter((m) => m.type === "member_status")).toHaveLength(1);
    expect((await call(withdrawRoute, "POST", { path: "/api/members/m_nope/withdraw", token: A.owner.token, params: { id: "m_nope" } })).status).toBe(404);
  });
});

describe("hub health and last seen (TASK-070)", () => {
  it("health events set last_seen_at and hub_version; silent for over 15 minutes is offline", async () => {
    h.clock.set(new Date("2026-10-18T12:00:00.000Z"));
    await postEvents(h, A.deviceToken, [ev("health", healthPayload({ version: "0.1.3", locked: false }))]);
    let r = await call(devicesRoute, "GET", { path: `/api/shops/${A.shopId}/devices`, token: A.owner.token, params: { id: A.shopId } });
    expect(r.status).toBe(200);
    expect(r.json).toEqual([{ id: A.deviceId, hub_version: "0.1.3", last_seen_at: "2026-10-18T17:30:00+05:30", online: true, locked: false, chain_break: false }]);
    h.clock.advance(15 * 60_000);
    r = await call(devicesRoute, "GET", { path: `/api/shops/${A.shopId}/devices`, token: A.owner.token, params: { id: A.shopId } });
    expect((r.json as unknown as { online: boolean }[])[0]!.online).toBe(true);
    h.clock.advance(1000);
    r = await call(devicesRoute, "GET", { path: `/api/shops/${A.shopId}/devices`, token: A.owner.token, params: { id: A.shopId } });
    expect((r.json as unknown as { online: boolean }[])[0]!.online).toBe(false);
    // A locked hub reports it.
    await postEvents(h, A.deviceToken, [ev("health", healthPayload({ locked: true }))]);
    r = await call(devicesRoute, "GET", { path: `/api/shops/${A.shopId}/devices`, token: A.owner.token, params: { id: A.shopId } });
    expect((r.json as unknown as { online: boolean; locked: boolean }[])[0]).toMatchObject({ online: true, locked: true });
    expect((await call(devicesRoute, "GET", { path: `/api/shops/${A.shopId}/devices`, token: B.owner.token, params: { id: A.shopId } })).status).toBe(403);
  });
});

describe("hub inbox cursor", () => {
  it("after=n acknowledges up to n and returns only later messages; bad cursors are 400", async () => {
    const all = await inboxAll(A.deviceToken);
    expect(all.length).toBeGreaterThan(2);
    const ids = all.map((m) => Number(m.id));
    expect([...ids].sort((x, y) => x - y)).toEqual(ids);
    const mid = all[1]!.id;
    const rest = (await call(inboxRoute, "GET", { path: `/api/hub/inbox?after=${mid}`, token: A.deviceToken })).json as unknown as { messages: Msg[]; cursor: string };
    expect(rest.messages.map((m) => m.id)).toEqual(all.slice(2).map((m) => m.id));
    expect(rest.cursor).toBe(all.at(-1)!.id);
    const again = (await call(inboxRoute, "GET", { path: "/api/hub/inbox?after=0", token: A.deviceToken })).json as unknown as { messages: Msg[] };
    expect(again.messages.map((m) => m.id)).toEqual(all.slice(2).map((m) => m.id));
    const empty = (await call(inboxRoute, "GET", { path: `/api/hub/inbox?after=${rest.cursor}`, token: A.deviceToken })).json as unknown as { messages: Msg[]; cursor: string };
    expect(empty).toEqual({ messages: [], cursor: rest.cursor });
    expect((await call(inboxRoute, "GET", { path: "/api/hub/inbox?after=abc", token: A.deviceToken })).status).toBe(400);
  });
});
