import { z } from "zod";
import { requireOwnerUser, requireShopOwner } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { pairDevice } from "@/lib/devices";
import { clientIp, json, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  shop_id: z.string().min(1).max(80),
  pairing_code: z.string().min(4).max(32),
});

/** POST /api/devices/pair: owner session plus the hub's code links the hub to the shop (TASK-057). */
export const POST = route("devices.pair", async (req) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const b = await readJson(req, body);
  await requireShopOwner(deps.db, userId, b.shop_id);
  const deviceId = await pairDevice(deps.db, { shopId: b.shop_id, code: b.pairing_code, userId, ip: clientIp(req), now: deps.now() });
  return json({ device_id: deviceId });
});
