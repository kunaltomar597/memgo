import { z } from "zod";
import { requireOwnerUser, requireShopOwner } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { noContent, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  shop_id: z.string().min(1).max(80),
  fcm_token: z.string().min(10).max(4096).regex(/^[A-Za-z0-9_:\-.]+$/),
});

/** POST /api/devices/fcm-token: the signed-in owner's phone registers for alert pushes (TASK-068). */
export const POST = route("devices.fcm_token", async (req) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const b = await readJson(req, body);
  await requireShopOwner(deps.db, userId, b.shop_id);
  await deps.db.query(
    `insert into push_tokens (shop_id, user_id, fcm_token, updated_at) values ($1, $2::uuid, $3, now())
     on conflict (fcm_token) do update set shop_id = excluded.shop_id, user_id = excluded.user_id, updated_at = now()`,
    [b.shop_id, userId, b.fcm_token],
  );
  return noContent();
});
