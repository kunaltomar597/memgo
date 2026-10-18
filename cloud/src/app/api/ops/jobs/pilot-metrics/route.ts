import { requireOpsKey } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, route } from "@/lib/http";
import { snapshotMetrics } from "@/lib/metrics";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** POST /api/ops/jobs/pilot-metrics: nightly snapshot of the last 60 days, called by pg_cron. */
export const POST = route("ops.jobs.pilot_metrics", async (req) => {
  const deps = getDeps();
  requireOpsKey(req, deps.env);
  const r = await snapshotMetrics(deps.db, deps.now());
  return json({ ok: true, from: r.from, to: r.to });
});
