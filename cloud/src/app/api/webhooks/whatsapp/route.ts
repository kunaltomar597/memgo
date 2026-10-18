import { constantTimeEqual } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { required } from "@/lib/env";
import { ApiError, json, readBodyText, route } from "@/lib/http";
import { log } from "@/lib/log";
import { processWebhook, verifySignature } from "@/lib/whatsappWebhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_WEBHOOK_BODY = 1024 * 1024;

/** GET: Meta's verification handshake, answered only for WHATSAPP_VERIFY_TOKEN (TASK-064). */
export const GET = route("webhooks.whatsapp.verify", async (req) => {
  const deps = getDeps();
  const expected = required(deps.env.whatsappVerifyToken, "WHATSAPP_VERIFY_TOKEN");
  const q = new URL(req.url).searchParams;
  const token = q.get("hub.verify_token") ?? "";
  const challenge = q.get("hub.challenge") ?? "";
  if (q.get("hub.mode") !== "subscribe" || !token || !constantTimeEqual(token, expected) || !/^[\w.-]{1,128}$/.test(challenge)) {
    throw new ApiError(403, "forbidden", "verification failed");
  }
  return new Response(challenge, { status: 200, headers: { "content-type": "text/plain" } });
});

/**
 * POST: X-Hub-Signature-256 (HMAC-SHA256 of the raw body with WHATSAPP_APP_SECRET) is checked before
 * the body is parsed; a bad signature gets 401 and the body is not logged.
 */
export const POST = route("webhooks.whatsapp", async (req) => {
  const deps = getDeps();
  const secret = required(deps.env.whatsappAppSecret, "WHATSAPP_APP_SECRET");
  const raw = await readBodyText(req, MAX_WEBHOOK_BODY);
  if (!verifySignature(raw, req.headers.get("x-hub-signature-256"), secret)) {
    log.warn("webhook_bad_signature", { route: "webhooks.whatsapp" });
    throw new ApiError(401, "unauthorized", "bad signature");
  }
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new ApiError(400, "invalid", "body is not valid JSON");
  }
  if (!body || typeof body !== "object") throw new ApiError(400, "invalid", "body is not an object");
  const r = await processWebhook(deps, body as Parameters<typeof processWebhook>[1]);
  return json({ ok: true, ...r });
});
