-- 0004: devices (hubs), hash anchors, push tokens, the hub inbox, ingested event ids and the
-- health log. All cloud only. Only hashes of the device token and unlock token are stored.

create table public.devices (
  id text primary key,
  shop_id text references public.shops(id),
  serial text not null,
  pairing_code text not null unique,
  status text not null default 'provisioned' check (status in ('provisioned', 'paired', 'unpaired', 'revoked')),
  generation integer not null default 1 check (generation >= 1),
  token_hash text unique,
  token_issued_at timestamptz,
  data_unlock_token_hash text,
  hub_version text,
  last_seen_at timestamptz,
  last_health_at timestamptz,
  last_health_json jsonb,
  locked boolean not null default false,
  chain_break boolean not null default false,
  chain_break_record_id text,
  chain_break_at timestamptz,
  anchor_mismatch boolean not null default false,
  anchor_mismatch_at timestamptz,
  paired_at timestamptz,
  paired_by uuid,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index devices_shop_idx on public.devices(shop_id);

create table public.hash_anchors (
  id bigserial primary key,
  shop_id text not null references public.shops(id),
  device_id text references public.devices(id),
  date date not null,
  chain_head_hash text not null,
  mirror_head_hash text,
  match boolean not null,
  received_at timestamptz not null default now(),
  unique (shop_id, date)
);

create table public.push_tokens (
  id bigserial primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  fcm_token text not null unique check (char_length(fcm_token) between 10 and 4096),
  updated_at timestamptz not null default now()
);
create index push_tokens_shop_idx on public.push_tokens(shop_id);

-- Messages from the cloud to one hub, read by GET /api/hub/inbox. Cursor = id.
create table public.inbox_messages (
  id bigserial primary key,
  shop_id text not null references public.shops(id),
  device_id text not null references public.devices(id),
  type text not null check (type in
    ('member_status', 'notice_status', 'thumbs_up', 'visit_feedback', 'config', 'device_revoked')),
  payload jsonb not null,
  created_at timestamptz not null default now(),
  acked_at timestamptz
);
create index inbox_messages_device_idx on public.inbox_messages(device_id, id) where acked_at is null;

-- Every hub event id the cloud has applied. The ULID is the idempotency key for every side effect.
create table public.hub_events (
  id text primary key,
  device_id text not null references public.devices(id),
  shop_id text not null references public.shops(id),
  type text not null,
  created_at text not null,
  received_at timestamptz not null default now()
);

create table public.device_health_log (
  id bigserial primary key,
  device_id text not null references public.devices(id),
  shop_id text not null references public.shops(id),
  hub_created_at timestamptz not null,
  received_at timestamptz not null default now(),
  locked boolean not null,
  internet boolean not null,
  stream text not null,
  fps double precision not null,
  outbox_pending integer not null,
  outbox_failed integer not null
);
create index device_health_log_device_idx on public.device_health_log(device_id, hub_created_at);

alter table public.devices enable row level security;
alter table public.hash_anchors enable row level security;
alter table public.push_tokens enable row level security;
alter table public.inbox_messages enable row level security;
alter table public.hub_events enable row level security;
alter table public.device_health_log enable row level security;

revoke all on public.devices, public.hash_anchors, public.push_tokens, public.inbox_messages,
  public.hub_events, public.device_health_log from anon, authenticated;
revoke all on sequence public.hash_anchors_id_seq, public.push_tokens_id_seq,
  public.inbox_messages_id_seq, public.device_health_log_id_seq from anon, authenticated;

-- devices, inbox_messages, hub_events and device_health_log hold token hashes, pairing codes and
-- sync state: no policy, so only the server reads them. Owners see device status through
-- GET /api/shops/{id}/devices.
grant select on public.hash_anchors to authenticated;
create policy hash_anchors_select on public.hash_anchors for select to authenticated
  using (public.is_shop_member(shop_id));

-- An owner may see and remove the push tokens they registered, only for their shops.
grant select, delete on public.push_tokens to authenticated;
create policy push_tokens_select on public.push_tokens for select to authenticated
  using (user_id = auth.uid() and public.is_shop_member(shop_id));
create policy push_tokens_delete on public.push_tokens for delete to authenticated
  using (user_id = auth.uid() and public.is_shop_member(shop_id));
