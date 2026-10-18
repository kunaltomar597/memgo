import { z } from "zod";
import { getDeps } from "@/lib/deps";
import { claimDevice, SERIAL_RE } from "@/lib/devices";
import { clientIp, json, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  pairing_code: z.string().min(4).max(32),
  serial: z.string().regex(SERIAL_RE),
});

/** POST /api/devices/claim: the hub's poll; 202 pending, then 200 with the device token once. */
export const POST = route("devices.claim", async (req) => {
  const deps = getDeps();
  const b = await readJson(req, body);
  const r = await claimDevice(deps.db, { code: b.pairing_code, serial: b.serial, ip: clientIp(req), now: deps.now() });
  return r.status === "pending" ? json({ status: "pending" }, 202) : json(r, 200);
});
