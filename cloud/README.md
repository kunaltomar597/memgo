# cloud

Purpose: the Remu cloud, a Next.js 15 and TypeScript API on Vercel (bom1) with Supabase Postgres and Supabase Auth in Mumbai (ap-south-1). It pairs hubs, mirrors the non-biometric tables, checks the consent chain, sends the WhatsApp notice, turns delivery receipts into activations, relays alerts over FCM with ids only, exports the signed consent log, and watches hub health. It never accepts or stores a face image, template or clip.

## Commands

    cd cloud
    npm ci              # exact versions from package-lock.json (Node 20.19 or later)
    npm run dev         # next dev on http://localhost:3000
    npm run lint        # eslint, zero warnings
    npm run typecheck   # tsc --noEmit, strict
    npm test            # vitest: real migrations on in-process PGlite, Meta and Google mocked
    npm run build       # next build
    npm run db:reset    # applies supabase/migrations to a fresh PGlite and lists the tables

`npm run db:reset` needs no Docker or account. With `DB_RESET_URL=postgres://...` it drops and recreates the `public` schema of that local Postgres (a plain Postgres gets the Supabase roles and `auth` shim first) and applies the migrations there. It refuses any `supabase.co` host: hosted migrations run only with `supabase db push` from CI. With Docker, `supabase start` and `supabase db reset` work too (`supabase/config.toml`).

## How the tests run

- `test/helpers/db.ts` starts a fresh PGlite per test file, applies `test/helpers/supabase-shim.sql` (the roles `anon`, `authenticated`, `service_role`, `auth.users`, `auth.uid()` and Supabase's default grants), then every file in `supabase/migrations/` in order. RLS tests switch to the `authenticated` role with a user's JWT claims, so isolation is tested against real policies.
- Route handlers are called directly with `Request` objects. `setDeps()` swaps in PGlite, a fake owner session verifier (`token-<user>` to user id), a fixed clock, and the real WhatsApp and FCM clients on a recording `fetch`, so tests assert the exact Graph API and FCM request bodies.
- Webhooks use recorded-style fixtures in `test/fixtures/webhooks/` signed with a real `X-Hub-Signature-256` (HMAC-SHA256 of the raw body with `WHATSAPP_APP_SECRET`).
- `test/setup.ts` captures every log line so tests can assert that no name, phone number, note, token or message text was logged.
- Test data is synthetic: fake numbers in the `+91980000xxxx` range, invented names.

## Routes

Every route answers JSON (`{"error", "detail"}` on failure), refuses `multipart/*`, `image/*`, `video/*`, `audio/*` and `application/octet-stream` with 415 before the handler runs, and caps bodies at 64 KB (1 MB for `/api/hub/events`, 413 above).

| Route | Auth | Task |
|---|---|---|
| `GET /api/health` | none | TASK-003 |
| `POST /api/shops` | owner session | TASK-056 |
| `GET /api/shops/{id}/devices` | owner of the shop | TASK-070 |
| `POST /api/shops/{id}/consent-export?format=csv\|pdf` | owner of the shop | TASK-092, TASK-093 |
| `POST /api/devices/pair` | owner session | TASK-057 |
| `POST /api/devices/claim` | pairing code and serial | TASK-057 |
| `POST /api/devices/unlock` | device token | TASK-100 |
| `POST /api/devices/fcm-token` | owner of the shop | TASK-068 |
| `POST /api/devices/{id}/revoke` | owner of the shop | TASK-100 (not yet in docs/API_CONTRACT.md) |
| `POST /api/hub/events` | device token | TASK-059, 060, 063, 068, 070 |
| `GET /api/hub/inbox?after=` | device token (revoked devices too) | TASK-065, 066, 069 |
| `POST /api/hub/anchor` | device token | TASK-061 |
| `GET, POST /api/webhooks/whatsapp` | verify token, `X-Hub-Signature-256` | TASK-064, 065, 066 |
| `POST /api/members/{id}/withdraw` | owner of the shop | TASK-069 |
| `GET /api/visits/{id}/card` | owner of the shop | TASK-069 |
| `POST /api/visits/{id}/feedback` | owner of the shop | TASK-069 |
| `POST /api/ops/devices` | `X-Remu-Ops-Key` | ops, TASK-105 |
| `GET /api/ops/hubs` | `X-Remu-Ops-Key` | TASK-119 |
| `GET /api/ops/metrics?from=&to=` (JSON, or CSV with `Accept: text/csv`) | `X-Remu-Ops-Key` | TASK-127 |
| `POST /api/ops/jobs/hub-monitor` | `X-Remu-Ops-Key` (pg_cron, every 5 minutes) | TASK-119 |
| `POST /api/ops/jobs/pilot-metrics` | `X-Remu-Ops-Key` (pg_cron, nightly) | TASK-127 |

Owner sessions: `Authorization: Bearer <Supabase access token>`, verified server-side with `SUPABASE_JWT_SECRET` (HS256, audience `authenticated`) when set, otherwise with `supabase.auth.getUser`, which also covers projects on asymmetric signing keys. The shop check reads the caller's `staff` row with role `owner`. Any Supabase Auth sign-in method works; the pilot choice is still OPEN.

## Layout

    cloud/
    ├── src/app/api/             route handlers (thin: auth, parse, call src/lib)
    ├── src/lib/                 domain logic: ingest.ts, consentChain.ts, canonical.ts, memberState.ts,
    │                            devices.ts, notice.ts, whatsapp.ts, whatsappWebhook.ts, fcm.ts, push.ts,
    │                            anchor.ts, visits.ts, export.ts, exportPdf.ts, signing.ts, metrics.ts,
    │                            monitor.ts, sentry.ts, log.ts, payloadGuard.ts, schemas.ts, phone.ts
    ├── src/lib/db/              repository layer behind the Db interface (types.ts); postgres.ts is the
    │                            production adapter (porsager postgres on DATABASE_URL)
    ├── src/instrumentation.ts   Sentry on the Node.js runtime when SENTRY_DSN is set
    ├── supabase/migrations/     0001 to 0007, RLS policies beside each table, pg_cron schedules in 0007
    ├── supabase/config.toml     Supabase CLI configuration
    ├── assets/fonts/            Noto Sans Devanagari Regular and Bold, with OFL.txt
    ├── scripts/db-reset.mjs     npm run db:reset
    ├── test/                    Vitest suites, helpers, fixtures (consent_vector.json, webhooks/)
    └── vercel.json              functions pinned to bom1

## Data model

Migrations create the brief's mirrored tables (`shops`, `staff`, `households`, `members`, `consent_records`, `purchases`, `visits` without `clip_id`), the cloud tables (`devices`, `whatsapp_messages`, `hash_anchors`, `push_tokens`, `inbox_messages`) and support tables (`hub_events` for event idempotency, `member_transitions`, `device_health_log`, `rate_limit_hits`, `export_log`, `ops_alerts`, `pilot_metrics_snapshots`, `shop_pilot_facts`).

- RLS is on for every table. `anon` has no grant anywhere. An owner (`authenticated`) reads only rows of shops where they have a `staff` row (`is_shop_member()`); writes go through the API routes, except renaming their own shop and deleting their own push tokens. `devices`, `whatsapp_messages`, `inbox_messages` and the support tables have no client policy at all.
- `consent_records` is append-only: UPDATE, DELETE and TRUNCATE are revoked from every client role and `service_role`, and triggers refuse them even for the table owner. Record columns are stored exactly as the hub sent them (timestamps as text) so `record_hash` can be recomputed at any time.
- A trigger on `members` refuses `active` unless the transaction set `remu.activation = 'notice_delivered'`, which only `memberState.activateOnDelivery()` does, and only the WhatsApp delivered webhook calls it. Terminal states are never left.
- Phone numbers: `members.phone_hash` is the contract hash (lowercase hex SHA-256 of the E.164 string with the leading `+`). The plain number is kept only in `whatsapp_messages.phone_e164`, from the `notice_request` event.

## Behaviour notes

- Event ingest: each event is applied once in its own transaction (its ULID in `hub_events` is the idempotency key for every side effect: mirror write, WhatsApp send, FCM push). Results come back in request order; a rejected event never blocks later ones. A payload with a forbidden key or a base64 blob over 1,024 characters rejects the whole request with 400 `payload_forbidden` before anything is written.
- Consent chain: every record's hash is recomputed and its `prev_hash` checked against the shop's mirrored head. A break is stored as received, with `hash_ok` or `link_ok` false, and flags `devices.chain_break` (plus a Sentry warning). Within a batch, records are linked by following `prev_hash` from the head; anything unlinked is taken by `ts` then id.
- Notice: sent in the shop's `settings_json.notice_language` (falling back to the event's `language`), idempotent per consent record, retried 3 times on transient errors, then flagged on the member (`notice_failed`). The cloud never edits a consent record; the hub gets the `wamid` in `notice_status`.
- Webhooks: the signature is checked over the raw body before parsing. The same status for the same `wamid` twice changes nothing twice. `delivered` activates and queues `member_status` (active) and `notice_status` (delivered); `read` never activates; `failed` leaves the member `pending_notice`. A 👍 button reply queues `thumbs_up`. A text `STOP` (trimmed, any case) withdraws the sender's members in every shop, or only in the shop of the quoted notice when it is a reply; a STOP that matches no member yet is kept and applied when the member row arrives, with the STOP's own time. Other inbound messages are stored; media messages are reduced to type and id.
- Consent export CSV: header row with the columns of docs/API_CONTRACT.md section 3, one row per record in chain order (`purposes_json` and `notice_delivery_json` as canonical JSON), then trailing comment lines starting with `# ` (`shop_id`, `generated_at`, `record_count`, `chain_head_hash`, `chain_verified`, `chain_first_break`, `latest_anchor_date`, `latest_anchor_hash`, `anchor_matches_head`). The Ed25519 signature in `X-Remu-Signature` covers every byte, trailer included. `X-Remu-Public-Key` carries the raw 32-byte public key (base64) for verification; every export is logged in `export_log` with the file SHA-256.
- Consent export PDF: A4 landscape, `pdf-lib` with Noto Sans Devanagari embedded (Hindi shop names and the `hi-IN-v1.0` cashier line render), 22 records per page, then a last page with the chain head, chain status, latest anchor, the CSV export's SHA-256 and Ed25519 signature and the public key. The PDF's own detached signature is in `X-Remu-Signature`.
- `CONSENT_EXPORT_SIGNING_KEY`: base64 of the 32-byte Ed25519 seed, or base64 of the PKCS8 DER private key; a PEM `PRIVATE KEY` block also works. Make one with `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`.
- Unlock token: `HMAC-SHA256(HUB_PAIRING_SECRET, device_id + ":" + generation)`, base64url, derived per request; only its SHA-256 is stored. Revoke and unpair bump the generation.
- Hub health: any authenticated hub call updates `last_seen_at`; `health` events also set `hub_version`, `locked` and the health log. Silent for more than 15 minutes is offline.
- Ops alerts (TASK-119): the monitor opens one alert per hub and condition (offline, locked), notifies once, and resolves with a second notice. Notifications go to Sentry as ids-only warnings and, if `OPS_ALERT_WEBHOOK_URL` is set, as a JSON POST there. OPEN: the pilot channel.
- Pilot metrics (TASK-127): per shop and in total, on Asia/Kolkata days. Face membership is face members over every member billed with a phone (phone-only members are the skip-face count). Alerts within 3 seconds come back as null because per-visit timing is not in the sync contract. Hardware cost and setup minutes are entered by hand in `shop_pilot_facts`.

## Scheduling

`supabase/migrations/0007_cron.sql` creates pg_cron jobs when pg_cron, pg_net and Vault exist (hosted Supabase); elsewhere it does nothing. The jobs call `public.remu_call_ops_job()`, which reads `remu_cloud_url` and `remu_ops_api_key` from Vault and POSTs to:

- `/api/ops/jobs/hub-monitor` every 5 minutes (`*/5 * * * *`);
- `/api/ops/jobs/pilot-metrics` at 04:00 Asia/Kolkata (`30 22 * * *` UTC), after the hubs' 03:30 anchors;
- plus a daily cleanup of `rate_limit_hits`.

Vercel cron is not used, because the Hobby plan allows only daily jobs.

## Environment variables

Copy the Cloud section of the root `.env.example` to `cloud/.env.local` (never committed). Production values live in Vercel.

| Variable | Use |
|---|---|
| `DATABASE_URL` | Postgres for every server query (Supabase pooler, port 6543; prepared statements off) |
| `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY` | owner session check through `auth.getUser` when `SUPABASE_JWT_SECRET` is unset |
| `SUPABASE_JWT_SECRET` | new: verifies owner access tokens locally (HS256) |
| `SUPABASE_SERVICE_ROLE_KEY` | not used by this code; server only if ever needed |
| `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN` | Graph API sends |
| `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET` | webhook handshake and signature |
| `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN`, `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI` | approved template names |
| `WHATSAPP_GRAPH_API_VERSION` | new, optional: Graph API version, default `v21.0` |
| `FCM_SERVICE_ACCOUNT_JSON_BASE64` | FCM HTTP v1 service account |
| `HUB_PAIRING_SECRET` | unlock token derivation |
| `CONSENT_EXPORT_SIGNING_KEY` | Ed25519 export signing key |
| `OPS_API_KEY` | new: `X-Remu-Ops-Key` for the ops routes and the pg_cron jobs |
| `OPS_ALERT_WEBHOOK_URL` | new, optional: where ops alerts are POSTed |
| `SENTRY_DSN` | Sentry; empty disables it |

## Fonts

`assets/fonts/NotoSansDevanagari-Regular.ttf` and `-Bold.ttf` come from the notofonts release `NotoSansDevanagari-v2.006` (https://github.com/notofonts/devanagari/releases/tag/NotoSansDevanagari-v2.006, zip SHA-256 `4c582c103f0a42836338df07148b23a0aa080cce8393ddc4364af87eb22ebd85`), folder `googlefonts/ttf`, which covers Latin and Devanagari. Licence: SIL Open Font License 1.1, `assets/fonts/OFL.txt`. File SHA-256: Regular `da2d2135e978c6f68852cfd8201c3a067df1acf73232fe80c58f89e6302ee6e8`, Bold `ee8bb4df2203759d8d302a5e05aa2206b98067bd24a10e8f8c3d930f4b23135a`.

## For the hub team

- Consent hash vector: `test/fixtures/consent_vector.json` holds two linked records, their canonical JSON and the expected `record_hash` values (computed with Node and checked with Python `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`), plus the phone hash vector `+919876543210` to `f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e`.
- The cloud exempts the key `template_deleted_at` (null or a timestamp) from the payload_forbidden key rule, like `has_template`.
- The cloud never sets a member `active` from a `member_upsert`; it ignores an `active` status from the hub.

## Needs a real account

These steps cannot be done from the repository; the code and config are ready for them.

1. Create the Supabase project in Mumbai (AWS ap-south-1); link it with `supabase link` and run `supabase db push` from CI.
2. In that project: enable pg_cron and pg_net, then add the Vault secrets `remu_cloud_url` and `remu_ops_api_key` (see Scheduling). Choose the owner sign-in method (OPEN) and copy the JWT secret to `SUPABASE_JWT_SECRET` if the project uses the legacy HS256 secret.
3. Create the Vercel project from `cloud/`, keep functions in bom1 (`vercel.json`), and set every variable above.
4. Meta: Business account, WhatsApp Business Account, dedicated number, and approval of the two utility templates (named parameters `shop_name`, `first_name`, `date`, quick-reply button with payload `👍`) after the lawyer signs off `wa-v1.0` (TASK-071, TASK-072). Point the webhook at `/api/webhooks/whatsapp`, subscribe to `messages`, set the verify token.
5. Firebase: a project with an FCM-only service account; base64 its JSON into `FCM_SERVICE_ACCOUNT_JSON_BASE64`.
6. Sentry: a project and DSN, then send one test event and note it in docs/MEMORY.md (TASK-118). OPEN: Sentry stores events outside India.
7. Generate and store `HUB_PAIRING_SECRET`, `CONSENT_EXPORT_SIGNING_KEY` and `OPS_API_KEY`; keep old export public keys so old exports still verify.
