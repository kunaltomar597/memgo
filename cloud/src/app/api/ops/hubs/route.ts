import { requireOpsKey } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, route } from "@/lib/http";
import { listHubs } from "@/lib/monitor";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** GET /api/ops/hubs: every device with its status (TASK-119). */
export const GET = route("ops.hubs", async (req) => {
  const deps = getDeps();
  requireOpsKey(req, deps.env);
  return json(await listHubs(deps));
});
