import type { Deps } from "./deps";
import type { AlertPush } from "./fcm";
import { log } from "./log";

/** Fans an alert out to every push_tokens row of the shop. Ids only (TASK-068). */
export async function pushAlert(deps: Deps, data: AlertPush): Promise<{ sent: number; removed: number; failed: number }> {
  const tokens = await deps.db.query<{ id: string; fcm_token: string }>(
    "select id::text as id, fcm_token from push_tokens where shop_id = $1 order by push_tokens.id",
    [data.shop_id],
  );
  let sent = 0;
  let removed = 0;
  let failed = 0;
  await Promise.all(
    tokens.map(async (t) => {
      try {
        const r = await deps.fcm.send(t.fcm_token, data);
        if (r === "sent") sent++;
        else if (r === "unregistered") {
          removed++;
          await deps.db.query("delete from push_tokens where id = $1::bigint", [t.id]);
        } else failed++;
      } catch {
        failed++;
      }
    }),
  );
  log.info("alert_pushed", { shop_id: data.shop_id, visit_id: data.visit_id, count: sent, outcome: failed ? "partial" : "ok" });
  return { sent, removed, failed };
}
