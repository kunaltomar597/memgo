import { json, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** GET /api/health: liveness. */
export const GET = route("health", async () => json({ ok: true }));
