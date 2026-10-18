import { generateKeyPairSync } from "node:crypto";
import type { OwnerVerifier } from "@/lib/auth";
import type { Db } from "@/lib/db/types";
import { setDeps, type Deps } from "@/lib/deps";
import type { Env } from "@/lib/env";
import { createFcmClient } from "@/lib/fcm";
import type { OpsAlert, OpsNotifier } from "@/lib/opsNotifier";
import { createWhatsAppClient } from "@/lib/whatsapp";

export interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string;
}

type Responder = (r: RecordedRequest) => Response | Promise<Response>;

/** A fetch that records every request and answers from a per-host responder. */
export function recordingFetch(responders: Record<string, Responder>) {
  const calls: RecordedRequest[] = [];
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => (headers[k] = v));
    const rec: RecordedRequest = { url, method: init?.method ?? "GET", headers, body: typeof init?.body === "string" ? init.body : "" };
    calls.push(rec);
    const host = new URL(url).host;
    const responder = responders[host];
    if (!responder) throw new Error(`unexpected fetch to ${host}`);
    return responder(rec);
  }) as typeof fetch;
  return { impl, calls };
}

export const TEST_WAMID_PREFIX = "wamid.TEST";

export function fcmServiceAccount() {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const sa = {
    type: "service_account",
    project_id: "remu-test",
    client_email: "fcm-sender@remu-test.iam.gserviceaccount.com",
    private_key: privateKey.export({ format: "pem", type: "pkcs8" }).toString(),
    token_uri: "https://oauth2.googleapis.com/token",
  };
  return { sa, b64: Buffer.from(JSON.stringify(sa)).toString("base64"), publicKey };
}

export interface TestHarness {
  deps: Deps;
  calls: RecordedRequest[];
  whatsappCalls: () => RecordedRequest[];
  fcmCalls: () => RecordedRequest[];
  opsAlerts: OpsAlert[];
  clock: { now: Date; advance(ms: number): void; set(d: Date): void };
  users: Map<string, string>;
  /** Controls the Graph API mock: a queue of statuses to return before succeeding. */
  graph: { failures: number[]; nextId: number };
  fcm: { unregistered: Set<string>; publicKey: ReturnType<typeof fcmServiceAccount>["publicKey"] };
}

export const TEST_ENV: Env = {
  whatsappPhoneNumberId: "100000000000001",
  whatsappAccessToken: "test-wa-access-token",
  whatsappVerifyToken: "test-verify-token",
  whatsappAppSecret: "test-app-secret",
  whatsappTemplateEn: "remu_member_notice_en_v1",
  whatsappTemplateHi: "remu_member_notice_hi_v1",
  whatsappGraphVersion: "v21.0",
  hubPairingSecret: "test-hub-pairing-secret-0123456789abcdef",
  consentExportSigningKey: Buffer.alloc(32, 7).toString("base64"),
  opsApiKey: "test-ops-key",
  release: "remu-cloud@test",
};

/** Deps for route tests: PGlite, fake owner sessions ("token-<user>"), mocked Meta and Google. */
export function makeHarness(db: Db, envOverrides: Partial<Env> = {}): TestHarness {
  const env: Env = { ...TEST_ENV, ...envOverrides };
  const clock = {
    now: new Date("2026-10-18T12:00:00.000Z"),
    advance(ms: number) {
      this.now = new Date(this.now.getTime() + ms);
    },
    set(d: Date) {
      this.now = d;
    },
  };
  const graph = { failures: [] as number[], nextId: 1 };
  const fcmState = fcmServiceAccount();
  const unregistered = new Set<string>();
  const { impl, calls } = recordingFetch({
    "graph.facebook.com": () => {
      const fail = graph.failures.shift();
      if (fail !== undefined) {
        return new Response(JSON.stringify({ error: { code: fail === 400 ? 131026 : 1, message: "x" } }), { status: fail });
      }
      const id = `${TEST_WAMID_PREFIX}${String(graph.nextId++).padStart(4, "0")}`;
      return Response.json({ messaging_product: "whatsapp", contacts: [{ input: "x", wa_id: "x" }], messages: [{ id }] });
    },
    "oauth2.googleapis.com": () => Response.json({ access_token: "ya29.test-access", expires_in: 3600, token_type: "Bearer" }),
    "fcm.googleapis.com": (r) => {
      const token = (JSON.parse(r.body) as { message: { token: string } }).message.token;
      if (unregistered.has(token)) {
        return new Response(JSON.stringify({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }), { status: 404 });
      }
      return Response.json({ name: `projects/remu-test/messages/${token.slice(0, 6)}` });
    },
    "ops-alerts.example.test": () => new Response(null, { status: 204 }),
  });
  const users = new Map<string, string>(); // token -> user id
  const owners: OwnerVerifier = { verify: async (t) => (users.has(t) ? { userId: users.get(t)! } : null) };
  const opsAlerts: OpsAlert[] = [];
  const ops: OpsNotifier = { notify: async (a) => void opsAlerts.push(a) };
  const deps: Deps = {
    env,
    db,
    owners,
    whatsapp: createWhatsAppClient(
      {
        phoneNumberId: env.whatsappPhoneNumberId,
        accessToken: env.whatsappAccessToken,
        templateEn: env.whatsappTemplateEn,
        templateHi: env.whatsappTemplateHi,
        graphVersion: env.whatsappGraphVersion,
      },
      impl,
    ),
    fcm: createFcmClient(fcmState.b64, impl, () => clock.now),
    ops,
    now: () => clock.now,
    sleep: async () => undefined,
  };
  setDeps(deps);
  return {
    deps,
    calls,
    whatsappCalls: () => calls.filter((c) => c.url.includes("graph.facebook.com")),
    fcmCalls: () => calls.filter((c) => c.url.includes("fcm.googleapis.com")),
    opsAlerts,
    clock,
    users,
    graph,
    fcm: { unregistered, publicKey: fcmState.publicKey },
  };
}
