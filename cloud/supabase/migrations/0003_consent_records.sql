-- 0003: the consent log mirror. Append-only: no role may UPDATE, DELETE or TRUNCATE, and
-- triggers refuse it even for the table owner. The cloud inserts only records it received from
-- the hub (consent_record events); it never appends a record of its own.
-- Record columns are stored exactly as the hub sent them (timestamps as text) so record_hash can
-- be recomputed byte for byte at any time (docs/API_CONTRACT.md section 3).

create table public.consent_records (
  id text primary key,
  shop_id text not null references public.shops(id),
  member_id text not null,
  bill_no text,
  ts text not null,
  cashier_id text,
  channel text not null check (channel in
    ('verbal_at_counter', 'whatsapp_webhook', 'owner_remove', 'whatsapp_stop', 'nightly_expiry', 'sweep')),
  script_version text not null,
  notice_version text not null,
  purposes_json jsonb not null,
  notice_delivery_json jsonb not null,
  adult_check text,
  withdrawn_at text,
  template_deleted_at text,
  prev_hash text not null,
  record_hash text not null,
  -- Cloud metadata, written once at insert.
  mirror_seq bigserial not null unique,
  device_id text,
  hash_ok boolean not null,
  link_ok boolean not null,
  received_at timestamptz not null default now()
);
create index consent_records_shop_seq_idx on public.consent_records(shop_id, mirror_seq);
create index consent_records_member_idx on public.consent_records(member_id);

create function public.consent_records_append_only()
returns trigger
language plpgsql
as $$
begin
  raise exception 'consent_records is append-only (% refused)', tg_op using errcode = 'insufficient_privilege';
end;
$$;
create trigger consent_records_no_update before update or delete on public.consent_records
  for each row execute function public.consent_records_append_only();
create trigger consent_records_no_truncate before truncate on public.consent_records
  for each statement execute function public.consent_records_append_only();

alter table public.consent_records enable row level security;
revoke all on public.consent_records from anon, authenticated;
revoke update, delete, truncate on public.consent_records from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke update, delete, truncate on public.consent_records from service_role';
  end if;
end;
$$;
revoke all on sequence public.consent_records_mirror_seq_seq from anon, authenticated;

-- Owners read their shop's log; nobody but the server inserts.
grant select on public.consent_records to authenticated;
create policy consent_records_select on public.consent_records for select to authenticated
  using (public.is_shop_member(shop_id));
