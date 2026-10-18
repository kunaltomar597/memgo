-- 0002: households, members, purchases and visits (mirrored from the hub, no biometrics),
-- plus the member transition log. visits never carries clip_id; members never carries phone_enc.

create table public.households (
  id text primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  label text not null,
  payer_member_id text,
  member_ids jsonb not null default '[]'::jsonb,
  created_at timestamptz not null,
  updated_at timestamptz not null default now()
);
create index households_shop_idx on public.households(shop_id);

create table public.members (
  id text primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  household_id text,
  display_name text not null,
  phone_hash text not null,
  status text not null check (status in ('pending_notice', 'active', 'withdrawn', 'expired')),
  member_since text not null,
  last_seen_at timestamptz,
  discount_flag boolean not null default false,
  notes text,
  has_template boolean not null default false,
  notice_failed boolean not null default false,
  notice_failed_at timestamptz,
  status_changed_at timestamptz not null default now(),
  created_at timestamptz not null,
  updated_at timestamptz not null
);
create index members_shop_idx on public.members(shop_id);
create index members_phone_hash_idx on public.members(phone_hash);

-- Status guard. Terminal states are never left; a member becomes active only inside a
-- transaction that set remu.activation = 'notice_delivered' (src/lib/memberState.ts does this
-- on the WhatsApp delivered webhook and nowhere else).
create function public.members_status_guard()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'INSERT' then
    if new.status = 'active' then
      raise exception 'members.status active is set only by notice delivery' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.status is distinct from old.status then
    if old.status in ('withdrawn', 'expired') then
      raise exception 'member % is in a terminal state', old.id using errcode = 'check_violation';
    end if;
    if new.status = 'pending_notice' then
      raise exception 'no transition back to pending_notice' using errcode = 'check_violation';
    end if;
    if new.status = 'active' and (old.status <> 'pending_notice'
        or coalesce(current_setting('remu.activation', true), '') <> 'notice_delivered') then
      raise exception 'members.status active is set only by notice delivery' using errcode = 'check_violation';
    end if;
    if new.status = 'expired' and old.status <> 'active' then
      raise exception 'only an active member can expire' using errcode = 'check_violation';
    end if;
    new.status_changed_at := now();
  end if;
  return new;
end;
$$;
create trigger members_status_guard before insert or update on public.members
  for each row execute function public.members_status_guard();

create table public.member_transitions (
  id bigserial primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  member_id text not null references public.members(id) on delete cascade,
  from_status text not null,
  to_status text not null,
  by_actor text not null check (by_actor in ('cloud', 'hub', 'owner')),
  reason text not null,
  at timestamptz not null,
  created_at timestamptz not null default now()
);
create index member_transitions_member_idx on public.member_transitions(member_id);

create table public.purchases (
  id text primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  member_id text not null references public.members(id) on delete cascade,
  bill_no text not null,
  amount numeric(12, 2) not null check (amount >= 0),
  items_text text,
  occasion text,
  ts timestamptz not null,
  created_at timestamptz not null default now()
);
create index purchases_member_idx on public.purchases(member_id, ts desc);

create table public.visits (
  id text primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  member_id text not null references public.members(id) on delete cascade,
  ts timestamptz not null,
  camera text not null,
  score double precision not null,
  frames_agreed integer not null,
  greeted boolean not null default false,
  greeted_by text,
  not_them boolean not null default false,
  alerted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index visits_member_idx on public.visits(member_id, ts desc);
create index visits_shop_ts_idx on public.visits(shop_id, ts);

alter table public.households enable row level security;
alter table public.members enable row level security;
alter table public.member_transitions enable row level security;
alter table public.purchases enable row level security;
alter table public.visits enable row level security;

revoke all on public.households, public.members, public.member_transitions, public.purchases, public.visits
  from anon, authenticated;
revoke all on sequence public.member_transitions_id_seq from anon, authenticated;

-- Owner sessions read their own shop's rows. Writes go through the API routes, which keep the
-- member state machine and the hub inbox consistent, so authenticated has no write grant here.
grant select on public.households, public.members, public.purchases, public.visits to authenticated;
create policy households_select on public.households for select to authenticated
  using (public.is_shop_member(shop_id));
create policy members_select on public.members for select to authenticated
  using (public.is_shop_member(shop_id));
create policy purchases_select on public.purchases for select to authenticated
  using (public.is_shop_member(shop_id));
create policy visits_select on public.visits for select to authenticated
  using (public.is_shop_member(shop_id));
