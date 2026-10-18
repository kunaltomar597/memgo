import { requireDevice } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { HUB_EVENTS_MAX_BODY, ApiError, json, readRawJson, route } from "@/lib/http";
import { ingestBatch } from "@/lib/ingest";
import { eventBatchSchema, zodReason } from "@/lib/schemas";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

/** POST /api/hub/events: an outbox batch of up to 200 events (TASK-059). */
export const POST = route("hub.events", async (req) => {
  const deps = getDeps();
  const device = await requireDevice(deps.db, req, { now: deps.now() });
  const raw = await readRawJson(req, HUB_EVENTS_MAX_BODY);
  const parsed = eventBatchSchema.safeParse(raw);
  if (!parsed.success) throw new ApiError(400, "invalid", zodReason(parsed.error));
  const results = await ingestBatch(deps, device, parsed.data.events);
  return json({ results });
});
