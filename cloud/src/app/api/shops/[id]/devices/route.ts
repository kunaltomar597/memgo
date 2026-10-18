import { getDeps } from "@/lib/deps";
import { listDevicesForShop } from "@/lib/db/devices";
import { deviceStatusView } from "@/lib/devices";
import { json, route } from "@/lib/http";
import { ownerOfShop } from "@/lib/owner";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** GET /api/shops/{id}/devices: hub status for Owner mode (TASK-070). */
export const GET = route<{ id: string }>("shops.devices", async (req, { id }) => {
  const deps = getDeps();
  await ownerOfShop(req, deps, id);
  const now = deps.now();
  const devices = await listDevicesForShop(deps.db, id);
  return json(devices.map((d) => deviceStatusView(d, now)));
});
