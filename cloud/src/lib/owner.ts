import { requireOwnerUser, requireShopOwner } from "./auth";
import type { Deps } from "./deps";

/** Owner session for a shop: 401 without a valid Supabase session, 403 when not that shop's owner. */
export async function ownerOfShop(req: Request, deps: Deps, shopId: string): Promise<string> {
  const userId = await requireOwnerUser(req, deps.owners);
  await requireShopOwner(deps.db, userId, shopId);
  return userId;
}
