import { z } from "zod";
import { recordAnchor } from "@/lib/anchor";
import { requireDevice } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  chain_head_hash: z.string().regex(/^sha256:([0-9a-f]{64}|genesis)$/),
});

/** POST /api/hub/anchor: the hub's nightly chain head (TASK-061). */
export const POST = route("hub.anchor", async (req) => {
  const deps = getDeps();
  const device = await requireDevice(deps.db, req, { now: deps.now() });
  const b = await readJson(req, body);
  const { match } = await recordAnchor(deps.db, device, b.date, b.chain_head_hash, deps.now());
  return json({ ok: true, match });
});
