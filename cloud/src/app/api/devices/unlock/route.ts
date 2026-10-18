import { requireDevice } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { issueUnlockToken } from "@/lib/devices";
import { required } from "@/lib/env";
import { json, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** POST /api/devices/unlock: the data unlock token for a paired, unrevoked device (TASK-100). */
export const POST = route("devices.unlock", async (req) => {
  const deps = getDeps();
  const secret = required(deps.env.hubPairingSecret, "HUB_PAIRING_SECRET");
  const device = await requireDevice(deps.db, req, { now: deps.now() });
  const unlock_token = await issueUnlockToken(deps.db, device, secret);
  return json({ unlock_token });
});
