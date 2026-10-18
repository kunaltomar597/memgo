-- 0001: shops and staff, plus the shop membership helper used by every RLS policy.
-- Supabase provides the roles anon, authenticated and service_role, the auth schema and auth.uid().
-- Server code connects with DATABASE_URL as the table owner and authorises in code; RLS guards
-- every direct Supabase client read (anon key plus a user session).

create table public.shops (
  id text primary key,
  name text not null check (char_length(name) between 1 and 120),
  city text not null check (char_length(city) between 1 and 80),
  shop_key_ref text,
  script_version text not null default 'hi-IN-v1.0',
  notice_version text not null default 'wa-v1.0',
  settings_json jsonb not null default '{"notice_language": "hi"}'::jsonb,
  created_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.staff (
  id text primary key,
  shop_id text not null references public.shops(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 120),
  role text not null check (role in ('owner', 'cashier')),
  -- Cloud only: links an owner's Supabase Auth user to the shop. Hub-authored rows have no user.
  user_id uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index staff_user_idx on public.staff(user_id) where user_id is not null;
create index staff_shop_idx on public.staff(shop_id);

-- True when the signed-in user is staff of the shop. Security definer so policies on staff
-- itself do not recurse.
create function public.is_shop_member(p_shop_id text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.staff s
    where s.shop_id = p_shop_id and s.user_id = auth.uid()
  );
$$;
revoke all on function public.is_shop_member(text) from public, anon;
grant execute on function public.is_shop_member(text) to authenticated;

alter table public.shops enable row level security;
alter table public.staff enable row level security;

revoke all on public.shops from anon, authenticated;
revoke all on public.staff from anon, authenticated;

grant select on public.shops to authenticated;
grant update (name, city) on public.shops to authenticated;
create policy shops_select on public.shops for select to authenticated
  using (public.is_shop_member(id));
create policy shops_update on public.shops for update to authenticated
  using (public.is_shop_member(id)) with check (public.is_shop_member(id));

grant select on public.staff to authenticated;
create policy staff_select on public.staff for select to authenticated
  using (public.is_shop_member(shop_id));
