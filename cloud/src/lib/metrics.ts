import type { Db } from "./db/types";

/**
 * Pilot metrics (TASK-127), per shop and in total, for every success criterion in docs/PRD.md
 * section 11. Computed on demand by GET /api/ops/metrics and nightly into pilot_metrics_snapshots.
 * Days are Asia/Kolkata calendar days; `to` is inclusive.
 */
export interface Metric {
  value: number | null;
  numerator: number | null;
  denominator: number | null;
  target: string;
  pass: boolean | null;
  note?: string;
}

export type MetricName =
  | "face_membership_rate"
  | "notices_delivered_within_2_min"
  | "alerts_within_3_s"
  | "not_them_per_day"
  | "greeted_rate"
  | "stored_photo_columns"
  | "active_members_with_delivered_notice"
  | "stop_deleted_within_1_h"
  | "hub_uptime"
  | "matching_through_outages"
  | "hardware_cost_inr"
  | "setup_minutes";

export interface ScopeMetrics {
  scope: string;
  metrics: Record<MetricName, Metric>;
}

export interface MetricsReport {
  from: string;
  to: string;
  days: number;
  generated_at: string;
  shops: ScopeMetrics[];
  total: ScopeMetrics;
}

const TARGETS: Record<MetricName, { target: string; test: ((v: number) => boolean) | null }> = {
  face_membership_rate: { target: ">= 0.60", test: (v) => v >= 0.6 },
  notices_delivered_within_2_min: { target: ">= 0.90", test: (v) => v >= 0.9 },
  alerts_within_3_s: { target: ">= 0.85", test: (v) => v >= 0.85 },
  not_them_per_day: { target: "<= 1", test: (v) => v <= 1 },
  greeted_rate: { target: ">= 0.50", test: (v) => v >= 0.5 },
  stored_photo_columns: { target: "= 0", test: (v) => v === 0 },
  active_members_with_delivered_notice: { target: "= 1.00", test: (v) => v >= 1 },
  stop_deleted_within_1_h: { target: "= 1.00", test: (v) => v >= 1 },
  hub_uptime: { target: ">= 0.99", test: (v) => v >= 0.99 },
  matching_through_outages: { target: "= 1.00", test: (v) => v >= 1 },
  hardware_cost_inr: { target: "<= 15000", test: (v) => v <= 15000 },
  setup_minutes: { target: "< 60", test: (v) => v < 60 },
};

function ratio(name: MetricName, num: number, den: number, note?: string): Metric {
  const value = den > 0 ? Math.round((num / den) * 10000) / 10000 : null;
  const t = TARGETS[name];
  return { value, numerator: num, denominator: den, target: t.target, pass: value === null || !t.test ? null : t.test(value), ...(note ? { note } : {}) };
}

function plain(name: MetricName, value: number | null, note?: string): Metric {
  const t = TARGETS[name];
  return { value, numerator: null, denominator: null, target: t.target, pass: value === null || !t.test ? null : t.test(value), ...(note ? { note } : {}) };
}

type Counts = Map<string, { num: number; den: number }>;

async function counts(db: Db, sql: string, params: unknown[]): Promise<Counts> {
  const rows = await db.query<{ shop_id: string; num: number; den: number }>(sql, params);
  return new Map(rows.map((r) => [r.shop_id, { num: Number(r.num), den: Number(r.den) }]));
}

export function dayBounds(from: string, to: string): { start: string; end: string; days: number } {
  const start = new Date(`${from}T00:00:00+05:30`);
  const end = new Date(new Date(`${to}T00:00:00+05:30`).getTime() + 86_400_000);
  const days = Math.round((end.getTime() - start.getTime()) / 86_400_000);
  return { start: start.toISOString(), end: end.toISOString(), days };
}

const UPTIME_BUCKET_S = 300; // health is reported every 5 minutes

export async function computeMetrics(db: Db, from: string, to: string, now: Date): Promise<MetricsReport> {
  const { start, end, days } = dayBounds(from, to);
  if (days < 1 || days > 400) throw new Error("metrics range must be 1 to 400 days");
  const p = [start, end];
  const shops = await db.query<{ id: string }>("select id from shops order by id");

  const membership = await counts(
    db,
    `select shop_id, count(*) filter (where has_template)::int as num, count(*)::int as den
     from members where created_at >= $1::timestamptz and created_at < $2::timestamptz group by shop_id`,
    p,
  );
  const notices = await counts(
    db,
    `select shop_id, count(*) filter (where delivery_seconds is not null and delivery_seconds <= 120)::int as num, count(*)::int as den
     from whatsapp_messages where direction = 'out' and kind = 'notice'
       and created_at >= $1::timestamptz and created_at < $2::timestamptz group by shop_id`,
    p,
  );
  const visits = await db.query<{ shop_id: string; visits: number; greeted: number; not_them: number }>(
    `select shop_id, count(*)::int as visits, count(*) filter (where greeted)::int as greeted,
       count(*) filter (where not_them)::int as not_them
     from visits where ts >= $1::timestamptz and ts < $2::timestamptz group by shop_id`,
    p,
  );
  const visitBy = new Map(visits.map((v) => [v.shop_id, v]));
  const delivered = await counts(
    db,
    `select m.shop_id,
       count(*) filter (where exists (select 1 from whatsapp_messages w where w.direction = 'out' and w.member_id = m.id and w.delivered_at is not null)
                          or exists (select 1 from consent_records c where c.member_id = m.id and c.notice_delivery_json->>'whatsapp_status' = 'delivered'))::int as num,
       count(*)::int as den
     from members m where m.status = 'active' group by m.shop_id`,
    [],
  );
  const stops = await counts(
    db,
    `select t.shop_id,
       count(*) filter (where d.deleted_at is not null and d.deleted_at <= t.at + interval '1 hour')::int as num,
       count(*)::int as den
     from member_transitions t
     left join lateral (
       select min(c.template_deleted_at::timestamptz) as deleted_at from consent_records c
       where c.member_id = t.member_id and c.template_deleted_at is not null
     ) d on true
     where t.to_status = 'withdrawn' and t.reason = 'stop' and t.at >= $1::timestamptz and t.at < $2::timestamptz
     group by t.shop_id`,
    p,
  );
  const uptime = await db.query<{ shop_id: string; up: number; first_report: Date | null }>(
    `select shop_id, count(distinct floor(extract(epoch from hub_created_at) / ${UPTIME_BUCKET_S}))::int as up,
       min(hub_created_at) as first_report
     from device_health_log where hub_created_at >= $1::timestamptz and hub_created_at < $2::timestamptz group by shop_id`,
    p,
  );
  const uptimeBy = new Map(uptime.map((u) => [u.shop_id, u]));
  const outages = await counts(
    db,
    `select shop_id, count(*) filter (where fps > 0 and stream = 'ok' and not locked)::int as num, count(*)::int as den
     from device_health_log where not internet and hub_created_at >= $1::timestamptz and hub_created_at < $2::timestamptz
     group by shop_id`,
    p,
  );
  const facts = await db.query<{ shop_id: string; hardware_cost_inr: string | null; setup_minutes: number | null }>(
    "select shop_id, hardware_cost_inr::text as hardware_cost_inr, setup_minutes from shop_pilot_facts",
  );
  const factBy = new Map(facts.map((f) => [f.shop_id, f]));
  const photoCols = await db.query<{ n: number }>(
    `select count(*)::int as n from information_schema.columns
     where table_schema = 'public' and (data_type = 'bytea'
       or column_name ~* '(vec_blob|template_blob|image|crop|jpeg|embedding|clip_id|clip_bytes)')`,
  );
  const storedPhotoColumns = photoCols[0]?.n ?? 0;

  const periodEnd = Math.min(new Date(end).getTime(), now.getTime());
  const bucketsFrom = (startIso: string) =>
    Math.max(0, Math.ceil((periodEnd - new Date(startIso).getTime()) / (UPTIME_BUCKET_S * 1000)));

  const zero = { num: 0, den: 0 };
  const tot = {
    membership: { ...zero }, notices: { ...zero }, visits: 0, greeted: 0, notThem: 0,
    delivered: { ...zero }, stops: { ...zero }, up: 0, buckets: 0, outages: { ...zero },
  };
  const ALERT_NOTE = "measured on the hub (per-visit timing is not in the sync contract); OPEN";

  const perShop: ScopeMetrics[] = shops.map(({ id }) => {
    const mem = membership.get(id) ?? zero;
    const ntc = notices.get(id) ?? zero;
    const vis = visitBy.get(id) ?? { visits: 0, greeted: 0, not_them: 0 };
    const del = delivered.get(id) ?? zero;
    const stp = stops.get(id) ?? zero;
    const up = uptimeBy.get(id);
    const out = outages.get(id) ?? zero;
    const buckets = up?.first_report
      ? bucketsFrom(new Date(Math.max(new Date(start).getTime(), new Date(up.first_report).getTime())).toISOString())
      : 0;
    const f = factBy.get(id);
    tot.membership.num += mem.num; tot.membership.den += mem.den;
    tot.notices.num += ntc.num; tot.notices.den += ntc.den;
    tot.visits += vis.visits; tot.greeted += vis.greeted; tot.notThem += vis.not_them;
    tot.delivered.num += del.num; tot.delivered.den += del.den;
    tot.stops.num += stp.num; tot.stops.den += stp.den;
    tot.up += Math.min(up?.up ?? 0, buckets); tot.buckets += buckets;
    tot.outages.num += out.num; tot.outages.den += out.den;
    return {
      scope: id,
      metrics: {
        face_membership_rate: ratio("face_membership_rate", mem.num, mem.den, "face members over all members billed with a phone; phone-only members are the skip-face count"),
        notices_delivered_within_2_min: ratio("notices_delivered_within_2_min", ntc.num, ntc.den),
        alerts_within_3_s: plain("alerts_within_3_s", null, ALERT_NOTE),
        not_them_per_day: { ...plain("not_them_per_day", Math.round((vis.not_them / days) * 100) / 100), numerator: vis.not_them, denominator: days },
        greeted_rate: ratio("greeted_rate", vis.greeted, vis.visits),
        stored_photo_columns: plain("stored_photo_columns", storedPhotoColumns, "cloud schema check; hub scans come from scripts/privacy_scan.py"),
        active_members_with_delivered_notice: ratio("active_members_with_delivered_notice", del.num, del.den),
        stop_deleted_within_1_h: ratio("stop_deleted_within_1_h", stp.num, stp.den),
        hub_uptime: ratio("hub_uptime", Math.min(up?.up ?? 0, buckets), buckets, "5-minute buckets with a health report"),
        matching_through_outages: ratio("matching_through_outages", out.num, out.den, "health reports taken offline that show the stream up and matching"),
        hardware_cost_inr: plain("hardware_cost_inr", f?.hardware_cost_inr ? Number(f.hardware_cost_inr) : null, "entered by hand in shop_pilot_facts"),
        setup_minutes: plain("setup_minutes", f?.setup_minutes ?? null, "entered by hand in shop_pilot_facts"),
      },
    };
  });

  const shopCount = Math.max(1, shops.length);
  const costs = facts.map((f) => (f.hardware_cost_inr ? Number(f.hardware_cost_inr) : null)).filter((v): v is number => v !== null);
  const setups = facts.map((f) => f.setup_minutes).filter((v): v is number => v !== null);
  const total: ScopeMetrics = {
    scope: "total",
    metrics: {
      face_membership_rate: ratio("face_membership_rate", tot.membership.num, tot.membership.den),
      notices_delivered_within_2_min: ratio("notices_delivered_within_2_min", tot.notices.num, tot.notices.den),
      alerts_within_3_s: plain("alerts_within_3_s", null, ALERT_NOTE),
      not_them_per_day: { ...plain("not_them_per_day", Math.round((tot.notThem / (days * shopCount)) * 100) / 100, "per shop per day"), numerator: tot.notThem, denominator: days * shopCount },
      greeted_rate: ratio("greeted_rate", tot.greeted, tot.visits),
      stored_photo_columns: plain("stored_photo_columns", storedPhotoColumns),
      active_members_with_delivered_notice: ratio("active_members_with_delivered_notice", tot.delivered.num, tot.delivered.den),
      stop_deleted_within_1_h: ratio("stop_deleted_within_1_h", tot.stops.num, tot.stops.den),
      hub_uptime: ratio("hub_uptime", tot.up, tot.buckets),
      matching_through_outages: ratio("matching_through_outages", tot.outages.num, tot.outages.den),
      hardware_cost_inr: plain("hardware_cost_inr", costs.length ? Math.max(...costs) : null, "highest shop"),
      setup_minutes: plain("setup_minutes", setups.length ? Math.max(...setups) : null, "slowest shop"),
    },
  };
  return { from, to, days, generated_at: now.toISOString(), shops: perShop, total };
}

export function metricsCsv(r: MetricsReport): string {
  const lines = ["scope,metric,value,numerator,denominator,target,pass"];
  for (const s of [...r.shops, r.total]) {
    for (const [name, m] of Object.entries(s.metrics)) {
      const cells = [s.scope, name, m.value ?? "", m.numerator ?? "", m.denominator ?? "", m.target, m.pass === null ? "" : String(m.pass)];
      lines.push(cells.map((c) => (/[",\n]/.test(String(c)) ? `"${String(c).replace(/"/g, '""')}"` : String(c))).join(","));
    }
  }
  return `${lines.join("\r\n")}\r\n`;
}

/** Nightly job: the last 60 days (the pilot length) ending yesterday, stored as a snapshot. */
export async function snapshotMetrics(db: Db, now: Date): Promise<MetricsReport> {
  const istToday = new Date(now.getTime() + 330 * 60_000).toISOString().slice(0, 10);
  const toDate = new Date(new Date(`${istToday}T00:00:00Z`).getTime() - 86_400_000);
  const fromDate = new Date(toDate.getTime() - 59 * 86_400_000);
  const from = fromDate.toISOString().slice(0, 10);
  const to = toDate.toISOString().slice(0, 10);
  const report = await computeMetrics(db, from, to, now);
  await db.query(
    "insert into pilot_metrics_snapshots (period_from, period_to, metrics_json, computed_at) values ($1::date, $2::date, $3::jsonb, $4::timestamptz)",
    [from, to, JSON.stringify(report), now.toISOString()],
  );
  return report;
}
