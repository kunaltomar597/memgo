-- 0006: operational tables: rate limits, the export log, ops alerts, pilot metrics snapshots and
-- the hand-entered pilot facts (hardware cost, setup time). Cloud only.

create table public.rate_limit_hits (
  id bigserial primary key,
  key text not null,
  at timestamptz not null default now()
);
create index rate_limit_hits_key_idx on public.rate_limit_hits(key, at);

-- docs/SECURITY.md 3.3: every export is logged with requester, shop, time and file hash.
create table public.export_log (
  id bigserial primary key,
  shop_id text not null references public.shops(id),
  requested_by uuid not null,
  format text not null check (format in ('csv', 'pdf')),
  record_count integer not null,
  file_sha256 text not null,
  chain_head_hash text,
  chain_verified boolean not null,
  created_at timestamptz not null default now()
);

create table public.ops_alerts (
  id bigserial primary key,
  device_id text not null references public.devices(id),
  shop_id text references public.shops(id),
  kind text not null check (kind in ('offline', 'locked')),
  opened_at timestamptz not null default now(),
  resolved_at timestamptz,
  notified boolean not null default false
);
create unique index ops_alerts_open_uidx on public.ops_alerts(device_id, kind) where resolved_at is null;

create table public.pilot_metrics_snapshots (
  id bigserial primary key,
  period_from date not null,
  period_to date not null,
  metrics_json jsonb not null,
  computed_at timestamptz not null default now()
);

create table public.shop_pilot_facts (
  shop_id text primary key references public.shops(id),
  hardware_cost_inr numeric(10, 2),
  setup_minutes integer,
  updated_at timestamptz not null default now()
);

alter table public.rate_limit_hits enable row level security;
alter table public.export_log enable row level security;
alter table public.ops_alerts enable row level security;
alter table public.pilot_metrics_snapshots enable row level security;
alter table public.shop_pilot_facts enable row level security;

revoke all on public.rate_limit_hits, public.export_log, public.ops_alerts,
  public.pilot_metrics_snapshots, public.shop_pilot_facts from anon, authenticated;
revoke all on sequence public.rate_limit_hits_id_seq, public.export_log_id_seq,
  public.ops_alerts_id_seq, public.pilot_metrics_snapshots_id_seq from anon, authenticated;
