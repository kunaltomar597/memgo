import { getDeps } from "@/lib/deps";
import { getDevice } from "@/lib/db/devices";
import { revokeDevice } from "@/lib/devices";
import { ApiError, noContent, route } from "@/lib/http";
import { requireOwnerUser, requireShopOwner } from "@/lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * POST /api/devices/{id}/revoke: the "Revoke hub" kill switch (docs/SECURITY.md section 7).
 * Not yet in docs/API_CONTRACT.md; proposed as a contract addition.
 */
export const POST = route<{ id: string }>("devices.revoke", async (req, { id }) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const device = await getDevice(deps.db, id);
  if (!device || !device.shop_id) throw new ApiError(404, "not_found", "no such device");
  await requireShopOwner(deps.db, userId, device.shop_id);
  await revokeDevice(deps.db, id, deps.now());
  return noContent();
});
