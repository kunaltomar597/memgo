import { log } from "./log";
import { reportMessage } from "./sentry";

/**
 * Ops notifications for hub monitoring (TASK-119). OPEN: the pilot channel (email, or a WhatsApp
 * message to the Remu team number). Until it is chosen, every alert goes to Sentry as an ids-only
 * warning and, when OPS_ALERT_WEBHOOK_URL is set, as a JSON POST to that URL (an email relay or
 * chat webhook). Payloads carry ids and the alert kind only.
 */
export interface OpsAlert {
  kind: "offline" | "locked";
  state: "opened" | "resolved";
  device_id: string;
  shop_id: string | null;
  since: string;
}

export interface OpsNotifier {
  notify(alert: OpsAlert): Promise<void>;
}

export function createOpsNotifier(webhookUrl: string | undefined, fetchImpl: typeof fetch = fetch): OpsNotifier {
  return {
    async notify(alert) {
      log.warn("ops_alert", { kind: alert.kind, status: alert.state, device_id: alert.device_id, shop_id: alert.shop_id });
      reportMessage(`hub ${alert.kind} ${alert.state}`, {
        device_id: alert.device_id,
        shop_id: alert.shop_id ?? "none",
        kind: alert.kind,
      });
      if (!webhookUrl) return;
      try {
        await fetchImpl(webhookUrl, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ source: "remu-cloud", ...alert }),
        });
      } catch {
        log.error("ops_alert_webhook_failed", { device_id: alert.device_id });
      }
    },
  };
}
