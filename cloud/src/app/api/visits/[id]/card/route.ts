import { requireOwnerUser, requireShopOwner } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, route } from "@/lib/http";
import { buildCard, visitShop } from "@/lib/visits";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** GET /api/visits/{id}/card: the alert card fields from the mirror, after an FCM push (TASK-069). */
export const GET = route<{ id: string }>("visits.card", async (req, { id }) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  await requireShopOwner(deps.db, userId, await visitShop(deps.db, id));
  return json(await buildCard(deps.db, id));
});
