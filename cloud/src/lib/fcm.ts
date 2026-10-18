import { importPKCS8, SignJWT } from "jose";
import { required } from "./env";

/**
 * FCM HTTP v1 relay (TASK-068). The data message carries shop_id, visit_id and member_id and
 * nothing else, because Google's push servers are outside India (docs/SECURITY.md 3.2).
 * The service account JSON is decoded in memory from FCM_SERVICE_ACCOUNT_JSON_BASE64.
 */
export interface AlertPush {
  shop_id: string;
  visit_id: string;
  member_id: string;
}

export type PushResult = "sent" | "unregistered" | "failed";

export interface FcmClient {
  send(token: string, data: AlertPush): Promise<PushResult>;
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

/** Exactly the three ids; any other key is a privacy bug. */
export function buildFcmMessage(token: string, data: AlertPush) {
  return {
    message: {
      token,
      data: { shop_id: data.shop_id, visit_id: data.visit_id, member_id: data.member_id },
      android: { priority: "HIGH" },
    },
  };
}

export function parseServiceAccount(b64: string): ServiceAccount {
  const sa = JSON.parse(Buffer.from(b64, "base64").toString("utf8")) as Partial<ServiceAccount>;
  if (!sa.project_id || !sa.client_email || !sa.private_key) throw new Error("FCM service account JSON is incomplete");
  return sa as ServiceAccount;
}

export function createFcmClient(
  serviceAccountB64: string | undefined,
  fetchImpl: typeof fetch = fetch,
  now: () => Date = () => new Date(),
): FcmClient {
  let sa: ServiceAccount | undefined;
  let cached: { token: string; exp: number } | undefined;
  let inflight: Promise<string> | undefined;

  /** One token exchange at a time; parallel sends share it. */
  function accessToken(): Promise<string> {
    const nowS = Math.floor(now().getTime() / 1000);
    if (cached && cached.exp - 60 > nowS) return Promise.resolve(cached.token);
    inflight ??= exchange().finally(() => {
      inflight = undefined;
    });
    return inflight;
  }

  async function exchange(): Promise<string> {
    sa ??= parseServiceAccount(required(serviceAccountB64, "FCM_SERVICE_ACCOUNT_JSON_BASE64"));
    const nowS = Math.floor(now().getTime() / 1000);
    const tokenUri = sa.token_uri ?? DEFAULT_TOKEN_URI;
    const key = await importPKCS8(sa.private_key, "RS256");
    const assertion = await new SignJWT({ scope: SCOPE })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(sa.client_email)
      .setSubject(sa.client_email)
      .setAudience(tokenUri)
      .setIssuedAt(nowS)
      .setExpirationTime(nowS + 3600)
      .sign(key);
    const res = await fetchImpl(tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
    });
    if (!res.ok) throw new Error(`FCM token exchange failed: ${res.status}`);
    const data = (await res.json()) as { access_token: string; expires_in?: number };
    cached = { token: data.access_token, exp: nowS + (data.expires_in ?? 3600) };
    return data.access_token;
  }

  return {
    async send(token, data) {
      const bearer = await accessToken();
      const res = await fetchImpl(`https://fcm.googleapis.com/v1/projects/${sa!.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
        body: JSON.stringify(buildFcmMessage(token, data)),
      });
      if (res.ok) return "sent";
      const body = (await res.json().catch(() => ({}))) as {
        error?: { status?: string; details?: { errorCode?: string }[] };
      };
      const codes = [body.error?.status, ...(body.error?.details ?? []).map((d) => d.errorCode)];
      if (res.status === 404 || codes.includes("UNREGISTERED")) return "unregistered";
      return "failed";
    },
  };
}
