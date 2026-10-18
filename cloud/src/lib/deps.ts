import { createOwnerVerifier, type OwnerVerifier } from "./auth";
import { postgresDb } from "./db/postgres";
import type { Db } from "./db/types";
import { readEnv, required, type Env } from "./env";
import { createFcmClient, type FcmClient } from "./fcm";
import { createOpsNotifier, type OpsNotifier } from "./opsNotifier";
import { createWhatsAppClient, type WhatsAppClient } from "./whatsapp";

/**
 * Everything a route handler needs, built lazily from the environment. Tests replace the whole
 * set with setDeps(): PGlite for the database, a fake owner verifier, and the real WhatsApp and
 * FCM clients on a recording fetch.
 */
export interface Deps {
  env: Env;
  db: Db;
  owners: OwnerVerifier;
  whatsapp: WhatsAppClient;
  fcm: FcmClient;
  ops: OpsNotifier;
  now: () => Date;
  sleep: (ms: number) => Promise<void>;
}

let current: Deps | undefined;

export function getDeps(): Deps {
  if (current) return current;
  const env = readEnv();
  let db: Db | undefined;
  current = {
    env,
    get db() {
      db ??= postgresDb(required(env.databaseUrl, "DATABASE_URL"));
      return db;
    },
    owners: createOwnerVerifier(env),
    whatsapp: createWhatsAppClient({
      phoneNumberId: env.whatsappPhoneNumberId,
      accessToken: env.whatsappAccessToken,
      templateEn: env.whatsappTemplateEn,
      templateHi: env.whatsappTemplateHi,
      graphVersion: env.whatsappGraphVersion,
    }),
    fcm: createFcmClient(env.fcmServiceAccountJsonBase64),
    ops: createOpsNotifier(env.opsAlertWebhookUrl),
    now: () => new Date(),
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  };
  return current;
}

export function setDeps(d: Deps | undefined): void {
  current = d;
}
