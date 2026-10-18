import { getDeps } from "@/lib/deps";
import { queueInbox } from "@/lib/db/inbox";
import { getMember } from "@/lib/db/members";
import { requireOwnerUser, requireShopOwner } from "@/lib/auth";
import { ApiError, json, route } from "@/lib/http";
import { transition } from "@/lib/memberState";
import { toIstIso } from "@/lib/time";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * POST /api/members/{id}/withdraw: Remove member off the shop WiFi (TASK-069). The mirror goes to
 * withdrawn and the hub gets member_status (withdrawn, owner_remove), deleting the template at once.
 */
export const POST = route<{ id: string }>("members.withdraw", async (req, { id }) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const member = await getMember(deps.db, id);
  if (!member) throw new ApiError(404, "not_found", "no such member");
  await requireShopOwner(deps.db, userId, member.shop_id);
  if (member.status === "expired") throw new ApiError(409, "conflict", "member already expired");
  const at = deps.now();
  await deps.db.tx(async (tx) => {
    const r = await transition(tx, { memberId: id, to: "withdrawn", by: "owner", reason: "owner_remove", at });
    if (r.changed) {
      await queueInbox(tx, member.shop_id, "member_status", { member_id: id, status: "withdrawn", reason: "owner_remove", at: toIstIso(at) });
    }
  });
  return json({ status: "withdrawn" }, 202);
});
