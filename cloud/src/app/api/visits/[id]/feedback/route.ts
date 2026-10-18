import { z } from "zod";
import { requireOwnerUser, requireShopOwner } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { noContent, readJson, route } from "@/lib/http";
import { staffId } from "@/lib/schemas";
import { recordFeedback, visitShop } from "@/lib/visits";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z
  .strictObject({
    greeted: z.boolean().optional(),
    greeted_by: staffId.nullable().optional(),
    not_them: z.boolean().optional(),
  })
  .refine((b) => b.greeted !== undefined || b.greeted_by !== undefined || b.not_them !== undefined, "no change");

/** POST /api/visits/{id}/feedback: Greeted or Not them off the shop WiFi (TASK-069). */
export const POST = route<{ id: string }>("visits.feedback", async (req, { id }) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const b = await readJson(req, body);
  await requireShopOwner(deps.db, userId, await visitShop(deps.db, id));
  await recordFeedback(deps.db, id, b);
  return noContent();
});
