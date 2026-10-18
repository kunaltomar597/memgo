import { sentryOptions } from "./lib/sentry";

/** Next.js instrumentation hook: Sentry on the Node.js runtime when SENTRY_DSN is set. */
export async function register(): Promise<void> {
  const dsn = process.env.SENTRY_DSN?.trim();
  if (!dsn || process.env.NEXT_RUNTIME !== "nodejs") return;
  const Sentry = await import("@sentry/nextjs");
  const release = process.env.VERCEL_GIT_COMMIT_SHA ?? "remu-cloud@0.1.0";
  // The scrubber works on plain objects; Sentry's types are wider than what it needs.
  Sentry.init(sentryOptions(dsn, release) as unknown as Parameters<typeof Sentry.init>[0]);
}

export async function onRequestError(...args: unknown[]): Promise<void> {
  if (!process.env.SENTRY_DSN?.trim()) return;
  const Sentry = await import("@sentry/nextjs");
  (Sentry.captureRequestError as (...a: unknown[]) => void)(...args);
}
