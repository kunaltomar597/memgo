import { z } from "zod";
import { getDeps } from "@/lib/deps";
import { CERT_SHA256_RE, claimDevice, SERIAL_RE } from "@/lib/devices";
import { clientIp, json, readJson, route } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  pairing_code: z.string().min(4).max(32),
  serial: z.string().regex(SERIAL_RE),
  // docs/API_CONTRACT.md section 7 item 4: the hub's certificate fingerprint, colon-separated hex.
  cert_sha256: z
    .string()
    .max(95)
    .transform((v) => v.trim().toUpperCase())
    .pipe(z.string().regex(CERT_SHA256_RE, "colon-separated uppercase hex of 32 bytes"))
    .optional(),
});

/**
 * POST /api/devices/claim: the hub's poll; 202 pending, then 200 with the device token once. An
 * optional cert_sha256 is stored on the device and shown to the owner (TASK-112).
 */
export const POST = route("devices.claim", async (req) => {
  const deps = getDeps();
  const b = await readJson(req, body);
  const r = await claimDevice(deps.db, {
    code: b.pairing_code,
    serial: b.serial,
    certSha256: b.cert_sha256,
    ip: clientIp(req),
    now: deps.now(),
  });
  return r.status === "pending" ? json({ status: "pending" }, 202) : json(r, 200);
});
