-- 0005: WhatsApp messages. One row per wamid: outbound notices (direction out) and inbound
-- replies (direction in: STOP, thumbs-up, other text). phone_e164 is the only place the cloud keeps
-- a plain number (TASK-030). Cloud only; no policy for any client role.

create table public.whatsapp_messages (
  id bigserial primary key,
  direction text not null check (direction in ('out', 'in')),
  kind text not null check (kind in ('notice', 'stop', 'thumbs_up', 'text', 'other')),
  shop_id text references public.shops(id),
  member_id text,
  consent_record_id text,
  template_name text,
  language text,
  wamid text unique,
  context_wamid text,
  phone_e164 text,
  phone_hash text,
  status text not null check (status in
    ('queued', 'sent', 'delivered', 'read', 'failed', 'send_failed', 'received')),
  status_ts timestamptz,
  sent_at timestamptz,
  delivered_at timestamptz,
  read_at timestamptz,
  failed_at timestamptz,
  thumbs_up_at timestamptz,
  delivery_seconds integer,
  send_attempts integer not null default 0,
  last_error_code text,
  -- For inbound STOP: when it was applied to member rows (null while no member matched yet).
  applied_at timestamptz,
  received_ts timestamptz,
  raw_webhook_json jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index whatsapp_messages_notice_uidx on public.whatsapp_messages(consent_record_id)
  where direction = 'out';
create index whatsapp_messages_member_idx on public.whatsapp_messages(member_id);
create index whatsapp_messages_phone_hash_idx on public.whatsapp_messages(phone_hash);

alter table public.whatsapp_messages enable row level security;
revoke all on public.whatsapp_messages from anon, authenticated;
revoke all on sequence public.whatsapp_messages_id_seq from anon, authenticated;
