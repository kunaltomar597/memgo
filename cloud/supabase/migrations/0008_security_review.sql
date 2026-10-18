-- 0008: TASK-112 security review (docs/API_CONTRACT.md section 7).
-- 1. devices.cert_sha256: the hub's TLS certificate fingerprint sent with POST /api/devices/claim
--    (colon-separated uppercase hex of 32 bytes), returned per device by GET /api/shops/{id}/devices.
-- 2. devices.pairing_code holds the lowercase hex SHA-256 of the normalised (trimmed, upper-cased)
--    code, never the code itself. Existing rows are hashed in place.
-- 3. Retention (section 7 item 9): whatsapp_messages.phone_e164 is cleared once a send reached
--    delivered, read or failed, or the member was withdrawn or expired; raw webhook JSON keeps
--    phone_hash in place of sender and recipient numbers and no text body other than a STOP.
--    Existing rows are brought in line here.

alter table public.devices add column cert_sha256 text
  check (cert_sha256 is null or cert_sha256 ~ '^[0-9A-F]{2}(:[0-9A-F]{2}){31}$');

update public.devices set pairing_code = encode(sha256(convert_to(pairing_code, 'UTF8')), 'hex')
  where pairing_code !~ '^[0-9a-f]{64}$';
alter table public.devices add constraint devices_pairing_code_is_hash
  check (pairing_code ~ '^[0-9a-f]{64}$');
comment on column public.devices.pairing_code is
  'lowercase hex SHA-256 of the normalised pairing code; the code itself is never stored';

update public.whatsapp_messages set phone_e164 = null, updated_at = now()
  where phone_e164 is not null
    and (status in ('delivered', 'read', 'failed') or delivered_at is not null or read_at is not null
         or failed_at is not null
         or member_id in (select id from public.members where status in ('withdrawn', 'expired')));

-- One-off backfill helpers, dropped at the end of this migration.
create function pg_temp.remu_hash_number(v jsonb) returns jsonb
language sql immutable as $$
  select case
    when jsonb_typeof(v) = 'string' and (v #>> '{}') ~ '^\+?[0-9]{6,15}$'
      then to_jsonb(encode(sha256(convert_to('+' || ltrim(v #>> '{}', '+'), 'UTF8')), 'hex'))
    else v
  end
$$;

create function pg_temp.remu_redact_raw(e jsonb, p_kind text, p_direction text) returns jsonb
language plpgsql immutable as $$
declare
  k text;
begin
  if jsonb_typeof(e) <> 'object' then
    return e;
  end if;
  foreach k in array array['from', 'wa_id', 'recipient_id', 'display_phone_number', 'phone_number', 'to'] loop
    if e ? k then
      e := jsonb_set(e, array[k], pg_temp.remu_hash_number(e -> k));
    end if;
  end loop;
  if jsonb_typeof(e -> 'context') = 'object' and (e -> 'context') ? 'from' then
    e := jsonb_set(e, '{context,from}', pg_temp.remu_hash_number(e #> '{context,from}'));
  end if;
  if p_direction = 'in' and p_kind <> 'stop' then
    e := jsonb_strip_nulls(jsonb_build_object(
      'id', e -> 'id', 'type', e -> 'type', 'timestamp', e -> 'timestamp', 'from', e -> 'from',
      'context', case when e #> '{context,id}' is null then null else jsonb_build_object('id', e #> '{context,id}') end));
  end if;
  return e;
end;
$$;

update public.whatsapp_messages w set raw_webhook_json = coalesce(
    (select jsonb_agg(pg_temp.remu_redact_raw(x.e, w.kind, w.direction) order by x.n)
     from jsonb_array_elements(w.raw_webhook_json) with ordinality as x(e, n)),
    '[]'::jsonb)
  where jsonb_typeof(w.raw_webhook_json) = 'array' and jsonb_array_length(w.raw_webhook_json) > 0;

drop function pg_temp.remu_redact_raw(jsonb, text, text);
drop function pg_temp.remu_hash_number(jsonb);
