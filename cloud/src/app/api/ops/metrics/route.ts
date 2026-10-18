import { requireOpsKey } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { ApiError, json, route } from "@/lib/http";
import { computeMetrics, metricsCsv } from "@/lib/metrics";
import { istDate } from "@/lib/time";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** GET /api/ops/metrics?from=&to=: pilot metrics as JSON, or CSV with Accept: text/csv (TASK-127). */
export const GET = route("ops.metrics", async (req) => {
  const deps = getDeps();
  requireOpsKey(req, deps.env);
  const now = deps.now();
  const q = new URL(req.url).searchParams;
  const to = q.get("to") ?? istDate(now);
  const from = q.get("from") ?? istDate(new Date(new Date(`${to}T00:00:00+05:30`).getTime() - 59 * 86_400_000));
  if (!DATE.test(from) || !DATE.test(to) || from > to) throw new ApiError(400, "invalid", "from and to are YYYY-MM-DD, from <= to");
  let report;
  try {
    report = await computeMetrics(deps.db, from, to, now);
  } catch (e) {
    if (e instanceof Error && e.message.startsWith("metrics range")) throw new ApiError(400, "invalid", e.message);
    throw e;
  }
  if ((req.headers.get("accept") ?? "").includes("text/csv")) {
    return new Response(metricsCsv(report), {
      status: 200,
      headers: { "content-type": "text/csv; charset=utf-8", "cache-control": "no-store" },
    });
  }
  return json(report);
});
