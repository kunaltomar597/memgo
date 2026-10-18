import { listAllDevices } from "./db/devices";
import type { Deps } from "./deps";
import { deviceStatusView, isOnline } from "./devices";
import { toIstIso } from "./time";

/**
 * Hub monitoring (TASK-119): run every 5 minutes by pg_cron (supabase/migrations/0007_cron.sql).
 * Opens an ops alert when a paired hub is silent for 15 minutes or reports `locked`, notifies once,
 * and resolves (and notifies) when the condition clears.
 */
export async function runHubMonitor(deps: Deps): Promise<{ checked: number; opened: number; resolved: number }> {
  const now = deps.now();
  const devices = (await listAllDevices(deps.db)).filter((d) => d.status === "paired");
  let opened = 0;
  let resolved = 0;
  for (const d of devices) {
    const conditions = { offline: !isOnline(d.last_seen_at, now), locked: d.locked } as const;
    for (const kind of ["offline", "locked"] as const) {
      const open = await deps.db.query<{ id: string; opened_at: Date }>(
        "select id::text as id, opened_at from ops_alerts where device_id = $1 and kind = $2 and resolved_at is null",
        [d.id, kind],
      );
      if (conditions[kind] && open.length === 0) {
        const since = kind === "offline" ? (d.last_seen_at ?? now) : (d.last_health_at ?? now);
        const ins = await deps.db.query<{ id: string }>(
          `insert into ops_alerts (device_id, shop_id, kind, opened_at) values ($1, $2, $3, $4::timestamptz)
           on conflict (device_id, kind) where resolved_at is null do nothing returning id::text`,
          [d.id, d.shop_id, kind, now.toISOString()],
        );
        if (ins.length === 1) {
          await deps.ops.notify({ kind, state: "opened", device_id: d.id, shop_id: d.shop_id, since: toIstIso(since) ?? "" });
          await deps.db.query("update ops_alerts set notified = true where id = $1::bigint", [ins[0]!.id]);
          opened++;
        }
      } else if (!conditions[kind] && open.length > 0) {
        await deps.db.query("update ops_alerts set resolved_at = $2::timestamptz where id = $1::bigint", [open[0]!.id, now.toISOString()]);
        await deps.ops.notify({ kind, state: "resolved", device_id: d.id, shop_id: d.shop_id, since: toIstIso(open[0]!.opened_at) ?? "" });
        resolved++;
      }
    }
  }
  return { checked: devices.length, opened, resolved };
}

/** GET /api/ops/hubs: every device with its status for the Remu team. */
export async function listHubs(deps: Deps) {
  const now = deps.now();
  const devices = await listAllDevices(deps.db);
  const alerts = await deps.db.query<{ device_id: string; kind: string }>(
    "select device_id, kind from ops_alerts where resolved_at is null",
  );
  return devices.map((d) => ({
    ...deviceStatusView(d, now),
    shop_id: d.shop_id,
    serial: d.serial,
    status: d.status,
    generation: d.generation,
    anchor_mismatch: d.anchor_mismatch,
    chain_break_record_id: d.chain_break_record_id,
    paired_at: toIstIso(d.paired_at),
    revoked_at: toIstIso(d.revoked_at),
    open_alerts: alerts.filter((a) => a.device_id === d.id).map((a) => a.kind),
  }));
}
