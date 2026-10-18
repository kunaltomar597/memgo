import { requireOpsKey } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, route } from "@/lib/http";
import { runHubMonitor } from "@/lib/monitor";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** POST /api/ops/jobs/hub-monitor: called every 5 minutes by pg_cron (0007_cron.sql). */
export const POST = route("ops.jobs.hub_monitor", async (req) => {
  const deps = getDeps();
  requireOpsKey(req, deps.env);
  return json(await runHubMonitor(deps));
});
