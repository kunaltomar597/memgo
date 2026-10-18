import { requireDevice } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { readInbox } from "@/lib/db/inbox";
import { ApiError, json, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** GET /api/hub/inbox?after=<cursor>: acknowledges up to the cursor and returns what is next. */
export const GET = route("hub.inbox", async (req) => {
  const deps = getDeps();
  const device = await requireDevice(deps.db, req, { allowRevoked: true, now: deps.now() });
  const after = new URL(req.url).searchParams.get("after") ?? "0";
  if (!/^\d{1,18}$/.test(after)) throw new ApiError(400, "invalid", "after must be a cursor");
  return json(await readInbox(deps.db, device.id, BigInt(after)));
});
