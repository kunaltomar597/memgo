-- 0007: schedules (TASK-119 hub monitor every 5 minutes, TASK-127 pilot metrics nightly).
-- Runs as Supabase pg_cron jobs that call the cloud's ops job routes over HTTP with pg_net,
-- because Vercel's Hobby plan allows only daily cron. The cloud URL and the ops key are read from
-- Supabase Vault at run time, never stored in this file:
--   select vault.create_secret('https://<your-vercel-domain>', 'remu_cloud_url');
--   select vault.create_secret('<OPS_API_KEY value>', 'remu_ops_api_key');
-- Where pg_cron, pg_net or Vault is missing (local PGlite tests, plain Postgres) this migration
-- does nothing, so the same migration set applies everywhere.

create function public.remu_call_ops_job(p_path text)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
  v_url text;
  v_key text;
  v_request_id bigint;
begin
  execute 'select decrypted_secret from vault.decrypted_secrets where name = $1' into v_url using 'remu_cloud_url';
  execute 'select decrypted_secret from vault.decrypted_secrets where name = $1' into v_key using 'remu_ops_api_key';
  if v_url is null or v_key is null then
    raise warning 'remu_call_ops_job: vault secrets remu_cloud_url or remu_ops_api_key missing';
    return null;
  end if;
  execute 'select net.http_post(url := $1, headers := $2, body := $3, timeout_milliseconds := 20000)'
    into v_request_id
    using v_url || p_path,
          jsonb_build_object('Content-Type', 'application/json', 'X-Remu-Ops-Key', v_key),
          '{}'::jsonb;
  return v_request_id;
end;
$$;
-- Only the database owner (pg_cron runs as postgres) may call it; never a client role.
revoke all on function public.remu_call_ops_job(text) from public, anon, authenticated;

do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron')
     and exists (select 1 from pg_available_extensions where name = 'pg_net')
     and exists (select 1 from pg_namespace where nspname = 'vault') then
    create extension if not exists pg_net with schema extensions;
    create extension if not exists pg_cron;
    -- Every 5 minutes: offline (silent 15 minutes) and locked hubs.
    perform cron.schedule('remu-hub-monitor', '*/5 * * * *',
      $job$ select public.remu_call_ops_job('/api/ops/jobs/hub-monitor') $job$);
    -- 04:00 Asia/Kolkata (22:30 UTC), after the hubs' 03:30 anchors: pilot metrics snapshot.
    perform cron.schedule('remu-pilot-metrics', '30 22 * * *',
      $job$ select public.remu_call_ops_job('/api/ops/jobs/pilot-metrics') $job$);
    -- Daily: drop rate-limit hits older than one day.
    perform cron.schedule('remu-rate-limit-cleanup', '15 22 * * *',
      $job$ delete from public.rate_limit_hits where at < now() - interval '1 day' $job$);
  else
    raise notice 'pg_cron, pg_net or vault not available: Remu schedules not created';
  end if;
end;
$$;
