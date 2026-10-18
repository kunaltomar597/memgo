import { z } from "zod";
import { requireOpsKey } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { PAIRING_CODE_RE, normaliseCode, registerDevice, SERIAL_RE } from "@/lib/devices";
import { json, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  pairing_code: z.string().transform(normaliseCode).pipe(z.string().regex(PAIRING_CODE_RE)),
  serial: z.string().regex(SERIAL_RE),
});

/** POST /api/ops/devices: register a provisioned hub (scripts/provision_hub.py, TASK-105). */
export const POST = route("ops.devices", async (req) => {
  const deps = getDeps();
  requireOpsKey(req, deps.env);
  const b = await readJson(req, body);
  return json({ device_id: await registerDevice(deps.db, b.pairing_code, b.serial) }, 201);
});
