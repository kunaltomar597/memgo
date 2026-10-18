/**
 * Server configuration, read lazily from process.env so `next build` needs no secrets.
 * Server code only: nothing here may be imported by a client component, and no secret has a
 * NEXT_PUBLIC_ name.
 */
export interface Env {
  databaseUrl?: string;
  supabaseUrl?: string;
  supabaseAnonKey?: string;
  supabaseJwtSecret?: string;
  whatsappPhoneNumberId?: string;
  whatsappAccessToken?: string;
  whatsappVerifyToken?: string;
  whatsappAppSecret?: string;
  whatsappTemplateEn?: string;
  whatsappTemplateHi?: string;
  whatsappGraphVersion: string;
  fcmServiceAccountJsonBase64?: string;
  hubPairingSecret?: string;
  consentExportSigningKey?: string;
  opsApiKey?: string;
  opsAlertWebhookUrl?: string;
  sentryDsn?: string;
  release: string;
}

function val(name: string): string | undefined {
  const v = process.env[name];
  return v === undefined || v.trim() === "" ? undefined : v.trim();
}

export function readEnv(): Env {
  return {
    databaseUrl: val("DATABASE_URL"),
    supabaseUrl: val("NEXT_PUBLIC_SUPABASE_URL"),
    supabaseAnonKey: val("NEXT_PUBLIC_SUPABASE_ANON_KEY"),
    supabaseJwtSecret: val("SUPABASE_JWT_SECRET"),
    whatsappPhoneNumberId: val("WHATSAPP_PHONE_NUMBER_ID"),
    whatsappAccessToken: val("WHATSAPP_ACCESS_TOKEN"),
    whatsappVerifyToken: val("WHATSAPP_VERIFY_TOKEN"),
    whatsappAppSecret: val("WHATSAPP_APP_SECRET"),
    whatsappTemplateEn: val("WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN"),
    whatsappTemplateHi: val("WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI"),
    whatsappGraphVersion: val("WHATSAPP_GRAPH_API_VERSION") ?? "v21.0",
    fcmServiceAccountJsonBase64: val("FCM_SERVICE_ACCOUNT_JSON_BASE64"),
    hubPairingSecret: val("HUB_PAIRING_SECRET"),
    consentExportSigningKey: val("CONSENT_EXPORT_SIGNING_KEY"),
    opsApiKey: val("OPS_API_KEY"),
    opsAlertWebhookUrl: val("OPS_ALERT_WEBHOOK_URL"),
    sentryDsn: val("SENTRY_DSN"),
    release: val("VERCEL_GIT_COMMIT_SHA") ?? "remu-cloud@0.1.0",
  };
}

export class ConfigError extends Error {
  constructor(name: string) {
    super(`missing configuration: ${name}`);
    this.name = "ConfigError";
  }
}

export function required<T>(value: T | undefined, name: string): T {
  if (value === undefined) throw new ConfigError(name);
  return value;
}
