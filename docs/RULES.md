# Remu Development Rules

The rulebook every person and every AI coding session follows in this repository. docs/PRD.md says what we build; this file says how. Written 2026-10-18.

## General

- Three components, named exactly: the hub (`hub/`, Python 3.11, runs in the shop on a Raspberry Pi 5), the app (`app/`, Kotlin and Jetpack Compose, one Android app with Cashier mode and Owner mode) and the cloud (`cloud/`, Next.js 15, TypeScript, Supabase, Vercel).
- Roles are member (an enrolled customer), owner and cashier. Member status values are `pending_notice`, `active`, `withdrawn` and `expired`.
- The owner is the data fiduciary under the DPDP Act 2023. Remu is the data processor. Code must make the owner's duties easy to prove: consent, notice, deletion, export.
- Reuse what exists. Do not duplicate logic inside a component or across components. Keep functions small and typed.
- Do not modify files unrelated to the task. Do not add a dependency without an ADR in docs/DECISIONS.md.
- Do not widen scope. The later-phase list in docs/PRD.md section 10 (fifteen items, from phone-as-hub to fuzzy commitment or homomorphic matching) stays out until TASKS.md says otherwise.
- Keep the surprise. Nothing at the gate reacts: no screen, no sound, no light, no visible camera behaviour. The customer learns they were recognised from the owner's greeting, not from a machine.
- Target numbers never drift: hardware at or under 15,000 rupees per shop, setup under 60 minutes by a non-engineer, software 500 to 1,500 rupees per shop per month.

## Before coding

- Read docs/PRD.md, docs/ARCHITECTURE.md, docs/DESIGN.md, docs/RULES.md, TASKS.md and docs/MEMORY.md. For anything touching consent, members, templates, clips or webhooks, also read docs/PRIVACY_AND_CONSENT.md and docs/SECURITY.md.
- Inspect the existing code for the component you are changing. Find the module that already does the job before writing a new one.
- Work one TASK id at a time, in the order TASKS.md gives. Do not start the next task in the same session.
- For a change that touches more than three files, or any schema, API, job or member status transition, write the plan first (files, functions, tests, risks) and get it accepted before editing.
- Build the prompt from docs/PROMPTS.md: CONTEXT, TASK, FILES, CONSTRAINTS, ACCEPTANCE CRITERIA, TESTING.
- If the task needs a detail the documents do not give, choose the simplest option and write it down as an assumption in the task notes. If a human must confirm it, start the line with "OPEN:".

## Privacy invariants (code must never violate these)

The DPDP Act 2023 is the legal basis. The consent manager framework starts on 2026-11-13, and the remaining obligations on notice, consent, security, retention and erasure become enforceable on 2027-05-13. We build as if they were enforceable today. These invariants have no feature flag, no debug mode and no temporary bypass. If a task cannot be done without breaking one, stop and raise it.

1. No face photo is ever written to disk, database, log, crash report or cloud. Face crops live in memory until the hub has embedded them, then they are dropped. The only footage kept is the 5-second match clip: encrypted at rest on the hub, deleted after 30 days, viewable over the LAN only, never uploaded.
2. Only the hub computes embeddings. The app sends an aligned face crop with landmarks; the hub embeds it with the same model pack and version it uses at the gate. Templates (`templates.vec_blob`) are scrambled with the per-shop key and never leave the hub.
3. The cloud never accepts or stores image bytes, templates or clips. Its request schemas have no field that could carry them.
4. Members with status `pending_notice` are never matched. Only templates of `active` members are loaded into the matcher. A member becomes `active` only when the cloud confirms the WhatsApp notice was delivered.
5. `consent_records` is append-only. Every row is written through the chain function. There is no update or delete path. Withdrawal, template deletion and expiry are new chained records that reference the member, never edits of old rows.
6. A WhatsApp "STOP" or a "Remove member" tap deletes the template within one hour and logs `template_deleted_at` in the chain. The consent record itself is never deleted, because it proves the deletion.
7. The nightly job deletes templates of members unseen for 1095 days and clips older than 30 days, and logs both.
8. An age estimate under 18 at enrolment refuses enrolment. There is no override, flag, setting or role that bypasses it. Every face in a household goes through the same gate.
9. Unenrolled faces at the gate are discarded within seconds. Nothing is stored about a non-member: no frame, no vector, no count by face. The one exception is incidental: a companion or passer-by may appear in a member's encrypted 5-second clip, which stays on the LAN and is deleted after 30 days, and is never detected, identified or indexed (docs/PRIVACY_AND_CONSENT.md section 12).
10. No PII in logs or error tracking: no names, phone numbers, notes, bill items, message text, crops or vectors. Log ids only (`shop_id`, `member_id`, `visit_id`, `consent_id`).

## Hub rules (`hub/`)

- Python 3.11, pinned. The Pi image is Raspberry Pi OS Lite 64-bit (Bookworm, the legacy image, which ships 3.11); on any other base, 3.11 is installed with `uv`. One package, `remu_hub`. All tool configuration lives in `pyproject.toml`. Tests live in `hub/tests/`.
- `ruff` for lint and format, `mypy` in strict mode for types, `pytest` for tests, run by `make lint`, `make typecheck` and `make test`. All three pass before a task is complete.
- Every function has type hints. No `Any` in public signatures. No bare `except`. No global mutable state outside the settings object and the loaded model handles.
- Configuration comes from environment variables only: the `REMU_*` names and `SENTRY_DSN` listed in `.env.example`, loaded once at startup into one typed settings object (assumed library: pydantic-settings). No tunable is hardcoded. Defaults match docs/PRD.md: `REMU_MODEL_PACK=buffalo_l`, `REMU_MATCH_THRESHOLD=0.45`, `REMU_FRAMES_REQUIRED=3`, `REMU_FRAMES_WINDOW=5`, `REMU_ALERT_COOLDOWN_MINUTES=10`, `REMU_CLIP_RETENTION_DAYS=30`, `REMU_MEMBER_EXPIRY_DAYS=1095`.
- The face pipeline is InsightFace SCRFD for detection and ArcFace from the `buffalo_l` pack for embedding (fallback `buffalo_sc` on weak CPUs), on ONNX Runtime CPU. Model packs are downloaded by `make setup`, pinned by version, never committed. Changing the detector, the embedding model or the pack needs the evaluation harness and an ADR.
- Decode the RTSP stream with OpenCV's FFmpeg backend at reduced resolution (for example 640 by 360 at 5 to 8 frames per second). Target on a Pi 5: a match in under a second and an alert on the owner's phone within 3 seconds of entry.
- The matching loop (decode, detect, embed, scramble, match, alert) makes no network calls other than reading the RTSP stream. It writes visits, clips and alert events to SQLite and `events_outbox`. Separate workers push WebSocket events to the app on the LAN and flush the outbox to the cloud.
- Matching is cosine similarity against the scrambled templates of `active` members only, threshold 0.45, agreement in 3 of 5 consecutive frames before an alert, and at most one alert per member per 10 minutes. Every template row carries `model_version`; a template from a different model version than the running pack is not matched and is flagged for re-enrolment.
- The scrambling matrix is derived from the shop key, which lives in a root-only file on the hub. The key is never logged, synced, exported or shown in the app.
- `consent_records` rows are written only by the chain function (assumed name `append_consent_record`). It sets `prev_hash` from the previous record and `record_hash` as sha256 over the record including `prev_hash`. No code path and no migration updates or deletes a row. After any schema migration, chain verification over all existing records must still pass, and a test proves it.
- SQLite access goes through one data layer module. No SQL strings in API handlers, jobs or the pipeline.
- The hub opens no inbound port except the LAN FastAPI service (HTTPS, self-signed certificate pinned in the app at pairing) and its WebSocket. Cloud sync is outbound HTTPS only, through `events_outbox`, so matching and enrolment keep working with no internet.
- Jobs (nightly expiry at 1095 days unseen, clip cleanup at 30 days, hash anchor, outbox flush, health report) are idempotent and safe to run twice.
- The hub fails closed. Without the shop key or the data unlock token it serves `health` only and matches nothing.
- Logs are structured, with ids only. `REMU_LOG_LEVEL` controls verbosity; DEBUG is never on in a shop.
- Deletion when the hub is offline: a "Remove member" tap reaches the hub on the LAN, so the hub sets `withdrawn`, deletes at once and syncs the withdrawal up through `events_outbox`. A "STOP" reaches the cloud first and reaches an offline hub when it reconnects. The one-hour clock starts when the withdrawal reaches the hub (docs/SECURITY.md section 8, ADR-016).

## App rules (`app/`)

- Kotlin, Jetpack Compose, minimum SDK 26, Gradle. `./gradlew lint`, `./gradlew test` and `./gradlew connectedAndroidTest` pass before a task is complete.
- One app, two modes chosen at login: Cashier mode and Owner mode. Shared code lives once. Mode-specific screens live in their own package. Never fork the app.
- No direct SQLite. The app has no database of its own: no Room, no SQLiteOpenHelper. The hub is the source of truth over the LAN; the cloud relays alerts. Keep only what a screen needs, in memory.
- Network goes only through two clients: `HubClient` (LAN HTTPS to the hub with the certificate pinned at pairing, plus the WebSocket for events) and `CloudClient` (HTTPS to `REMU_CLOUD_URL`). No other class opens a socket or calls an HTTP library.
- CameraX gives the live preview. ML Kit detects faces and landmarks on the phone and draws a box on every face. The phone never computes an embedding. A face crop is sent to the hub and dropped; it is never written to disk, cache, logs or a crash report.
- Every user-facing string lives in `res/values/strings.xml` (English) and `res/values-hi/strings.xml` (Hindi). No string literals in composables. A task that adds a string adds both languages.
- Secrets (device token, pinned certificate fingerprint, session token) are stored encrypted with an Android Keystore key. Never in plain SharedPreferences, never in logs. `google-services.json` is never committed.
- The app never stores a staff PIN. The hub checks the PIN against `staff.pin_hash` and the app keeps only the session.
- Owner mode receives alerts by Firebase Cloud Messaging push and by the LAN WebSocket when on the shop WiFi. Deduplicate by `visit_id` so one match shows one card.
- Clips play over the LAN only. The app never saves a clip to storage and never shares one.
- Build configuration carries `REMU_CLOUD_URL`. Nothing else about the backend is hardcoded.

## Cloud rules (`cloud/`)

- Next.js 15 with the app router, TypeScript with `"strict": true`, Supabase Postgres and Supabase Auth, deployed on Vercel. `npm run lint`, `npm run typecheck`, `npm test` and `npm run build` pass before a task is complete.
- India region only. The Supabase project is in Mumbai (AWS ap-south-1). Vercel functions run in `bom1`. No service, queue, cache or log sink outside India without an ADR and a line in docs/PRIVACY_AND_CONSENT.md.
- Row Level Security is enabled on every table, with policies in the migration that creates the table. A shop's users see only rows with their `shop_id`. A test proves that a user from another shop gets nothing.
- `SUPABASE_SERVICE_ROLE_KEY` is used only in server code: route handlers, server actions and jobs. It is never imported into a client component and never has a `NEXT_PUBLIC_` name.
- Every webhook verifies its signature before parsing the body. WhatsApp: `X-Hub-Signature-256` (HMAC-SHA256 of the raw body with `WHATSAPP_APP_SECRET`) and the `WHATSAPP_VERIFY_TOKEN` handshake. Hub calls present the device token, checked against `devices.token_hash`. A bad signature gets 401 and the body is not logged.
- Route handlers accept JSON only, validated with a strict schema that rejects unknown fields (assumed library: zod). No schema has a field for image data, vectors or clips.
- Only the cloud moves a member to `active` (on the WhatsApp `delivered` webhook). `withdrawn` is set by the cloud on a "STOP" reply and by the hub on a "Remove member" tap; the hub's transition is synced up through `events_outbox` as a `member_upsert` event with status `withdrawn`. The cloud records every WhatsApp status in `whatsapp_messages` with the raw webhook JSON. Webhooks are idempotent: the same `wamid` status twice changes nothing twice.
- The mirrored `consent_records` table is insert-only at the policy level. `hash_anchors` holds the nightly chain head per shop so the hub's chain can be checked against the cloud.
- FCM is a relay. The push carries `shop_id` and `visit_id` only; the app fetches the card from the cloud or, on the shop WiFi, from the hub. OPEN: confirm this with the lawyer, since Google's push servers are outside India and a full card in the push would carry a name and a purchase out of the country.
- OPEN: how the cloud gets the plain phone number for the WhatsApp send and matches a "STOP" reply to a member, given `members.phone_hash` and `members.phone_enc`. Decide who holds the key for `phone_enc` and what salts `phone_hash`.
- Razorpay billing and the owner web dashboard are later phases. No billing code in MVP.

## UI rules

- Follow docs/DESIGN.md for colour, type, spacing, components and both modes. Do not invent a second button style or card style.
- Every screen has four states: loading, empty, error and offline. Cashier mode shows the offline indicator whenever the hub is unreachable on the LAN, and shows a member as notice pending until the cloud confirms delivery.
- Cashier mode is used standing and one-handed between bills: large tap targets, one primary action per screen, the single "Make member" button at the bottom. The largest, closest face is pre-selected; a tap changes the selection or adds an adult to a household.
- Skipping the face tap is a normal path, not an error. The bill completes and nothing about the face is kept.
- The script line is shown on the Cashier screen in Hindi and English, word for word, versioned `hi-IN-v1.0`:
  - Hindi: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.)
  - English: "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off."
- The alert card reads exactly "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}" with two buttons, "Greeted" and "Not them". "Not them" sets `visits.not_them` and feeds threshold tuning.
- The WhatsApp notice, the printed bill line and the counter standee use the exact wording and versions in docs/PRD.md (`wa-v1.0` for the notice). Changing a word of any consent text means a new version, lawyer sign-off and an ADR.
- Each phone has its own language setting (Hindi or English). It is chosen on the login screen, can be changed in Settings in either mode, and persists on that phone. Cashier mode and Owner mode run on different phones, so one setting never changes the other phone.
- Nothing is ever shown to the customer. The counter phone screen faces the cashier. Nothing reacts at the gate.

## Security rules

- Never commit a secret. `.env.example` lists names only. Real values live in local `.env` files, Vercel environment settings and root-only files on the hub. `.gitignore` covers `.env*`, `google-services.json`, model packs, shop keys, device tokens, data directories and clips.
- The shop key is used only to derive the scrambling matrix and the data directory encryption. It is never sent to the cloud, never shown in the app, never logged.
- The hub data directory is encrypted and unlocked at boot with a device-bound token from the cloud. A hub that is unpaired or has no token serves `health` only. No unlock token is ever written to disk: a running hub matches through any internet outage, and only a reboot needs the cloud (docs/SECURITY.md section 3.1).
- All transport is HTTPS. Hub to cloud: outbound only, device token in a header, no inbound ports. App to hub: HTTPS on the LAN with the self-signed certificate pinned at pairing; the app refuses a hub whose certificate changed until it is paired again.
- Pairing uses a single-use code the hub generates at `remu-hub init`, typed into Owner mode (OPEN: the founder confirms this direction, docs/ARCHITECTURE.md flow A), and `HUB_PAIRING_SECRET` on the cloud.
- Validate every input at every boundary: phone numbers, bill numbers, amounts, RTSP URLs, pairing codes, webhook bodies. Reject bad input; do not clean it up silently.
- Authorisation is checked on the hub and in the cloud from `staff.role`, never only in the app. Cashier mode can enrol, search, edit households and remove members. Owner mode can also change settings, staff PINs, device status and run the consent export.
- Clips are encrypted at rest on the hub, served over the LAN only, and never leave the shop network.
- Error tracking is Sentry (`SENTRY_DSN` in all three components) with default PII sending off, request bodies stripped and a scrubber for phone numbers. OPEN: Sentry stores events outside India. Confirm with the lawyer that scrubbed error data with no personal data may leave India, or self-host Sentry in India.
- Consent exports (PDF and CSV) are signed with `CONSENT_EXPORT_SIGNING_KEY` so a tampered export can be detected.
- The security review checklist in docs/SECURITY.md runs in Phase 8 and before every pilot release.

## Testing rules

- Every task adds or updates tests for what it changed. No task is complete until the component's lint, typecheck and tests pass and `make check` passes at the root.
- Component tests live with the component: `hub/tests/` (pytest, unit and integration), `app/app/src/test/` (JUnit) and `app/app/src/androidTest/` (Compose UI tests), `cloud/**/*.test.ts` (Vitest against a local Supabase). Maestro flows live in `app/maestro/`. The root `tests/` holds `integration/` (hub against local cloud), `e2e/` (fake camera to app), `eval/` (the evaluation harness) and `unit/` (shared fixtures and the privacy scanner in `scripts/`).
- Hub tests cover: the chain function (append, verify, tamper detection, no update or delete path), the age gate (under 18 refused, no override), status gating (a `pending_notice` template is never loaded into the matcher), the 3 of 5 frame rule, the 10-minute cooldown, outbox flush and retry, the 1095-day expiry and 30-day clip cleanup jobs, and offline enrolment.
- App tests cover each screen's loading, empty, error and offline states, both languages, and the end-to-end flows: enrol a member, add a household, receive an alert, tap "Greeted", tap "Not them", remove a member. A test proves no crop is written to app storage after an enrolment.
- Cloud tests use signed sample webhook payloads for sent, delivered, read, "STOP" and thumbs-up, prove that a bad signature gets 401 and a repeated `wamid` is idempotent, prove RLS by querying as a user from another shop, and prove that a request carrying image bytes, a vector or a clip field is rejected.
- Unit tests never touch the internet. Mock the WhatsApp Cloud API, FCM and Supabase. Integration tests that need a real service are marked and skipped with a reason when it is not configured.
- Test data is synthetic. No real customer name, phone number or face appears in a fixture. Fake embeddings are random unit vectors. The fixture video for `make fake-camera` shows only volunteers who signed a consent form; it is stored outside the repo and fetched by `make setup`. Benchmark images for the evaluation harness stay on the developer machine and never go on a hub.
- The evaluation harness in `tests/eval/` must pass before any change to `REMU_MODEL_PACK`, the model version, `REMU_MATCH_THRESHOLD`, `REMU_FRAMES_REQUIRED` or `REMU_FRAMES_WINDOW` is merged. It reports true accept rate at a false accept rate of 0.1 percent, plus the age-gap, low-light and angle subsets. The report goes in the pull request.
- The privacy scanner runs in CI: after an enrolment and a match against the fake camera, no image file exists under `REMU_DATA_DIR` except an encrypted clip, and the log contains no phone number.
- Fix a failing test before writing new code. Never skip, weaken or delete a test to make a build green.

## Git rules

- Small commits, one logical change each, never more than one task. Commit after the checks pass.
- Conventional messages: `feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:`, `ci:`, with the component in parentheses and the task id at the end, for example `feat(hub): consent hash chain (TASK-031)`.
- Feature branches off `main`: `feature/<phase>-<short-name>` (for example `feature/p2-consent-chain`) and `fix/<short-name>`. `main` is always green. No direct commits to `main`, no force-push, no rewriting of published history.
- One pull request per task. CI runs `make check` and must pass before merge.
- Never commit `.env` files, `google-services.json`, model packs, shop keys, device tokens, data directories, clips, the fixture video, or any image or video that shows a real customer.

## Documentation rules

- A task is not complete until the documents match the code. As part of every task: tick the task in TASKS.md; update docs/MEMORY.md (current status, completed, current task, known issues, next step); add an ADR to docs/DECISIONS.md for any decision on a library, schema, API, threshold, wording or region; update docs/ARCHITECTURE.md and README.md when structure, commands or flows change.
- docs/DECISIONS.md holds permanent decisions. docs/MEMORY.md holds the current state. Do not mix them.
- Every document has one H1 title, a one-line purpose statement, then sections. Plain English, short sentences, no em dashes, no marketing. Exact component names (hub, app with Cashier mode and Owner mode, cloud) and the roles member, owner and cashier. Hindi in Devanagari with the romanised version beside it. Dates absolute, for example 2026-10-18. Commands, environment variables, tables, files and phases use the names in docs/PRD.md and docs/ARCHITECTURE.md exactly.
- Numbers stay consistent everywhere: threshold 0.45, 3 of 5 frames, 10-minute cooldown, 30-day clips, 1095-day expiry, 1-hour deletion, 5 percent discount, 60-day pilot with 5 to 10 shops, hardware at or under 15,000 rupees, software 500 to 1,500 rupees per shop per month.
- Consent wording (cashier line, WhatsApp notice, printed bill line, standee) is versioned: `hi-IN-v1.0` for the script, `wa-v1.0` for the notice. A change needs lawyer sign-off, a version bump, an ADR and a new WhatsApp template approval. OPEN: name the lawyer who signs off the v1.0 script and notice before the pilot, and the date by which it is done.
- Anything that still needs a human decision starts with "OPEN:" so one search finds it all.
