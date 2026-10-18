# Remu Tasks

The ordered build list for Remu v0.1, from an empty repository to the end of the 60-day pilot. Last updated 2026-10-18 (build pass 1).

## How to work this list

Every task goes through the same loop:

1. Take the next unchecked task. The next task is the lowest unticked id.
2. Read docs/PROMPTS.md and build the session prompt from it. Read every document the task names.
3. Implement that task only. Do not touch unrelated files.
4. Test. Run the component's lint, typecheck and tests (`make lint`, `make typecheck`, `make test` for the hub; `./gradlew lint`, `./gradlew test` for the app; `npm run lint`, `npm run typecheck`, `npm test` for the cloud), then `make check` at the root, and check each acceptance criterion by hand.
5. Review the change against docs/RULES.md, docs/ARCHITECTURE.md and docs/SECURITY.md. Fix what the review finds.
6. Commit on a feature branch (`feature/pN-short-name`) with a conventional message that ends with the task id, for example `feat(hub): cosine matcher (TASK-016)`. Open one pull request per task; CI must be green before merge.
7. Tick the task here.
8. Update docs/MEMORY.md (current task, completed, known issues, next step). If the task settled a choice, add an entry to docs/DECISIONS.md.

Rules for the list:

- Tasks are done strictly in order unless a task carries an "Independent" bullet. An independent task may run in parallel with the tasks it names, nothing else.
- Each task is a vertical slice sized for one AI coding session of under two hours. If a task turns out larger, split it into sub-bullets under the same id. Do not renumber. A task marked "Elapsed time" needs a soak, a measurement window or field days; its session sets it up and later tasks run while it completes.
- "Depends on" is listed only when the dependency is not simply the previous task.
- Component tags: hub (Python, `hub/`), app (Android, `app/`), cloud (Next.js and Supabase, `cloud/`), all (two or more components), docs (`docs/`, README.md, CLAUDE.md), infra (CI, scripts, hardware, images).
- The numbers are fixed by the brief: threshold 0.45, 3 of 5 frames, 10-minute cooldown, 30-day clips, 1095-day expiry, deletion within 1 hour, 5 percent discount. A task that changes one of them needs an ADR in docs/DECISIONS.md first.
- Nothing from the Later section is built, stubbed, or left as a hook in MVP code.
- Lines starting with OPEN: are decisions a human still has to make. Make the decision, record it in docs/DECISIONS.md, then remove the OPEN line.
- Module paths, endpoint paths, cloud routes, script names and event type names in this file are the canonical names. docs/ARCHITECTURE.md and the component READMEs are rewritten to match them in the documentation task of each phase.

Each phase ends with an exit check. A phase is done when every task is ticked and the exit check passes.

## Phase 0: Project setup (repo, tooling, CI, docs in place)

- [x] TASK-001 [all] Initialise the monorepo
  - The layout from docs/ARCHITECTURE.md exists: `hub/`, `app/`, `cloud/`, `tests/unit/`, `tests/integration/`, `tests/e2e/`, `tests/eval/`, `scripts/`, `docs/`, `.cursor/rules/`.
  - `.gitignore` excludes `.env`, `.env.local`, `google-services.json`, hub data directories, downloaded model packs, sample videos, `node_modules/`, `.venv/`, Gradle and Next.js build output.
  - `git init` done on `main`, first commit made, GitHub repository created and pushed.
- [x] TASK-002 [hub] Hub Python project skeleton
  - `hub/pyproject.toml` declares Python 3.11, the `remu_hub` package, `ruff`, `mypy` (strict) and `pytest`.
  - `hub/Makefile` has the targets `setup`, `dev`, `fake-camera`, `test`, `lint`, `typecheck`. `dev` and `fake-camera` may print "not implemented until TASK-007 / TASK-024" for now. `setup` is the single fetch point for the model pack and the fixture video (TASK-007).
  - `cd hub && make setup && make lint && make typecheck && make test` pass with one placeholder test.
  - Independent: may run in parallel with TASK-003 and TASK-004.
- [x] TASK-003 [cloud] Cloud Next.js project skeleton
  - `cloud/` is a Next.js 15 TypeScript project with Vitest; `npm run dev`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run build` all pass.
  - `@supabase/supabase-js` is installed; `cloud/.env.local` is ignored; one placeholder route `GET /api/health` returns `{ "ok": true }`.
  - Independent: may run in parallel with TASK-002 and TASK-004.
- [x] TASK-004 [app] Android project skeleton
  - `app/` is a Gradle project, Kotlin, Jetpack Compose, minimum SDK 26, with one screen that shows "Remu".
  - `./gradlew assembleDebug`, `./gradlew test` and `./gradlew lint` pass; `REMU_CLOUD_URL` is read from BuildConfig with a development default.
  - `google-services.json` is ignored by git and README.md says where it goes.
  - Independent: may run in parallel with TASK-002 and TASK-003.
- [x] TASK-005 [all] Root `make check` and `.env.example`
  - The root `Makefile` target `check` runs lint, typecheck and tests for hub, app and cloud and fails if any of them fails.
  - `.env.example` already exists; confirm it lists the hub variables `REMU_SHOP_ID`, `REMU_DEVICE_TOKEN`, `REMU_CLOUD_URL`, `REMU_RTSP_GATE_URL`, `REMU_RTSP_COUNTER_URL`, `REMU_DATA_DIR`, `REMU_MODEL_PACK=buffalo_l`, `REMU_MATCH_THRESHOLD=0.45`, `REMU_FRAMES_REQUIRED=3`, `REMU_FRAMES_WINDOW=5`, `REMU_ALERT_COOLDOWN_MINUTES=10`, `REMU_CLIP_RETENTION_DAYS=30`, `REMU_MEMBER_EXPIRY_DAYS=1095`, `REMU_LOG_LEVEL`, `SENTRY_DSN`, and fix any gap.
  - Confirm it also lists the cloud variables `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `DATABASE_URL`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_BUSINESS_ACCOUNT_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN`, `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI`, `FCM_SERVICE_ACCOUNT_JSON_BASE64`, `HUB_PAIRING_SECRET`, `CONSENT_EXPORT_SIGNING_KEY`, `SENTRY_DSN`, and `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` commented as later phase and empty, each with a comment and no real value.
  - Depends on: TASK-002, TASK-003, TASK-004.
- [x] TASK-006 [infra] GitHub Actions CI
  - `.github/workflows/ci.yml` runs on every push and pull request: `cd hub && make lint typecheck test`, `cd cloud && npm run lint && npm run typecheck && npm test && npm run build`, `cd app && ./gradlew lint test`.
  - pip, npm and Gradle caches are used and the full run finishes in under 15 minutes.
  - CI is green on `main`, and a deliberate failing test turns it red (then reverted).
- [x] TASK-007 [infra] Fake camera script
  - `scripts/fake_camera.py` downloads a pinned mediamtx release for macOS and Linux and loops the fixture video on `rtsp://127.0.0.1:8554/gate`.
  - The fixture video shows only volunteers who signed the consent form in docs/TEST_PLAN.md section 1, never a real customer. It is never committed: `cd hub && make setup` fetches it into the gitignored `tests/eval/samples/` directory, and `tests/eval/samples/README.md` says so. OPEN: where the fixture video is hosted for `make setup` to fetch.
  - `cd hub && make fake-camera` starts the stream; a smoke test opens it with OpenCV's FFmpeg backend and reads one frame.
- [x] TASK-008 [docs] Project documents in place
  - README.md, CLAUDE.md, TASKS.md, every file under `docs/` and every `.cursor/rules/*.mdc` from the repository layout already exist; confirm they are all present and agree with each other, and fix any gap found.
  - CLAUDE.md names the standard commands and tells the agent to read docs/PROMPTS.md before each task.
  - docs/MEMORY.md names TASK-009 as the current task.
- [x] TASK-009 [docs] Clean machine setup check
  - A fresh clone on a second machine (or a clean user account) follows README.md only and reaches a green `make check` within 30 minutes.
  - Every missing step found on the way is added to README.md.
  - docs/MEMORY.md marks Phase 0 complete.
  - Status 2026-10-18: a fresh clone into an empty folder passed hub, cloud and app checks with the README commands in under 5 minutes (download caches were warm). The missing ANDROID_HOME step was added to README.md.

Phase 0 exit check: CI green, `make check` green locally, `make fake-camera` serves a stream, every document present.

## Phase 1: Hub core pipeline on the dev machine plus the eval harness

The hub runs on the developer laptop against the fake camera. Nothing is written to disk in this phase except model packs and eval reports. The phase ends with the console demo: a face in the fixture video is enrolled, then matched, and an alert prints.

- [x] TASK-010 [hub] Hub settings module
  - `remu_hub/settings.py` loads every `REMU_*` variable and `SENTRY_DSN` from the environment with the brief's defaults and types (threshold 0.45, frames required 3, window 5, cooldown 10 minutes, clip retention 30 days, expiry 1095 days).
  - A missing required value (`REMU_SHOP_ID`, `REMU_RTSP_GATE_URL`) fails fast with a clear message.
  - Unit tests cover defaults, overrides and the failure.
- [x] TASK-011 [hub] RTSP frame source
  - `remu_hub/video/source.py` opens an RTSP URL with OpenCV's FFmpeg backend, resizes frames to 640 by 360 and throttles to between 5 and 8 frames per second.
  - It reconnects with backoff when the stream drops and yields (timestamp, frame) pairs.
  - Works against `make fake-camera`; a unit test uses a file-backed fake source.
- [x] TASK-012 [hub] Model pack download and load
  - `remu-hub models download` fetches the InsightFace `buffalo_l` pack into `REMU_DATA_DIR/models/` and verifies checksums; `REMU_MODEL_PACK=buffalo_sc` selects the fallback pack.
  - `remu_hub/faces/models.py` loads the pack on ONNX Runtime CPU once and reports `model_version` (pack name plus file hash).
  - A test asserts the loader is a singleton and that the version string is stable.
- [x] TASK-013 [hub] Face detection stage
  - `remu_hub/faces/detect.py` runs the SCRFD detector and returns boxes, detection scores and 5 landmarks per face.
  - Faces smaller than a configurable minimum (default 40 pixels) are dropped.
  - A test on a committed synthetic test image finds the expected number of faces. OPEN: pick or generate a licence-clean face image set for unit tests.
- [x] TASK-014 [hub] Alignment and embedding
  - `remu_hub/faces/embed.py` aligns a crop by its 5 landmarks and returns a 512-dimensional L2-normalised ArcFace embedding.
  - This module is the only place in the codebase that computes embeddings; a test searches for the model call outside this module and fails if it finds one.
  - The same crop embedded twice gives identical vectors (test).
- [x] TASK-015 [hub] Template scrambler
  - `remu_hub/faces/scramble.py` builds a 512 by 512 random orthogonal matrix seeded from the shop key and multiplies embeddings by it.
  - Test: cosine similarity between two vectors is the same before and after scrambling, within 1e-6.
  - Test: the same embedding scrambled with two different keys has near-zero similarity; the matrix is never written to disk.
- [x] TASK-016 [hub] Gallery and cosine matcher
  - `remu_hub/match/gallery.py` holds active scrambled templates in memory keyed by member id, with add, remove and replace.
  - `match(vec)` returns the best member id and score, or nothing when the score is below `REMU_MATCH_THRESHOLD` (0.45).
  - Tests cover an empty gallery, an exact match, a below-threshold score, and a 5,000-template timing check under 5 ms per probe (the budget in docs/ARCHITECTURE.md section 14).
- [x] TASK-017 [hub] Frame agreement and alert cooldown
  - `remu_hub/match/decide.py` keeps a sliding window of the last 5 frame results per face track and emits a match only when 3 of 5 agree on the same member.
  - A member alerted in the last 10 minutes (`REMU_ALERT_COOLDOWN_MINUTES`) is not alerted again.
  - Tests use synthetic sequences: 3 of 5 passes, 2 of 5 does not, cooldown blocks, cooldown expires.
- [x] TASK-018 [hub] Age gate
  - `remu_hub/faces/age.py` uses the pack's age and gender model and returns an estimated age.
  - `check_adult(crop)` returns passed or refused; under 18 is refused and there is no override parameter.
  - Tests cover both paths; the docstring states that the estimate is approximate and the policy is to refuse when in doubt.
- [x] TASK-019 [hub] Non-member discard
  - Frames, crops and embeddings of faces that do not match are released within one processing cycle; nothing about them is written to disk or kept in any list.
  - A test runs 1,000 non-matching frames and asserts that process memory and the data directory size stay flat.
- [x] TASK-020 [hub] Pipeline runner
  - `remu_hub/pipeline.py` wires source, detect, embed, scramble, match and decide into one loop with a simple per-face tracker (box overlap across frames).
  - Each decided match goes to an alert sink; the console sink prints one line with member id, score and frames agreed.
  - An integration test feeds a short synthetic frame sequence through the loop.
- [ ] TASK-021 [hub] Eval harness: pair set and TAR at FAR 0.1 percent
  - `tests/eval/run_eval.py` loads a labelled pair list (same person or different) from a local, uncommitted directory and reports the true accept rate at a false accept rate of 0.1 percent for the current model pack, plus the true accept rate and false accept rate at the deployed threshold 0.45.
  - It prints the threshold that gives FAR 0.1 percent and writes `tests/eval/reports/<date>.json`.
  - The run passes when, at the deployed threshold 0.45, the false accept rate is at or under 0.1 percent, the true accept rate is at least 95 percent overall, and a scrambled-template run matches the raw-embedding run within 0.1 points (the bars in docs/TEST_PLAN.md section 10); `cd hub && make eval` runs it and exits non-zero otherwise. OPEN: choose the public face-pair benchmark subset for development and confirm its licence allows this use.
  - Status 2026-10-18: harness and LFW preparation built; on LFW with buffalo_l at 0.45: TAR 97.2 percent, FAR 0, scrambled equal to raw, so every TASK-021 bar passes (docs/RESEARCH.md section 7). Not ticked only because the LFW licence OPEN is still open.
- [ ] TASK-022 [hub] Eval harness: robustness subsets
  - The harness accepts subset tags (age_gap, low_light, angle) and reports each one separately; the age_gap subset must reach a true accept rate of at least 85 percent at threshold 0.45, and no subset may drop more than 0.5 points against the last merged run.
  - A smoke subset of under 100 pairs runs in CI in under 2 minutes; the full run is manual.
  - docs/TEST_PLAN.md states the gate: no change to the model pack or the threshold merges without a harness report attached.
  - Status 2026-10-18: low_light and angle subsets are synthetic and reported; age_gap has no licence-clean source, so the harness reports it missing and exits 3 (incomplete).
- [ ] TASK-023 [docs] Record the 0.45 threshold
  - A full harness run is attached to an ADR in docs/DECISIONS.md that keeps `REMU_MATCH_THRESHOLD` at 0.45 (or justifies a change) and records TAR at FAR 0.1 percent, the TAR and FAR at 0.45, and the pass bars from TASK-021 and TASK-022.
  - docs/RESEARCH.md gets the measured numbers; the pipeline section of docs/ARCHITECTURE.md matches the modules built in TASK-010 to TASK-020.
  - Depends on: TASK-021, TASK-022.
  - Status 2026-10-18: ADR-031 keeps 0.45 with the measured numbers; it closes fully once the age_gap subset exists.
- [x] TASK-024 [hub] Console demo
  - `remu-hub demo` enrols the face found at a chosen second of the fixture video (detect, embed, scramble, add to the in-memory gallery), then runs the gate loop against `rtsp://127.0.0.1:8554/gate` and prints `ALERT member=<id> score=<s> frames=3/5` when the face reappears.
  - `cd hub && make dev` runs the demo against `make fake-camera`.
  - A transcript of one successful run is pasted into docs/MEMORY.md and Phase 1 is marked complete.

Phase 1 exit check: `make fake-camera` in one terminal, `make dev` in another, an ALERT line prints within 3 seconds of the enrolled face appearing; `make eval` runs; the 0.45 threshold ADR exists.

## Phase 2: Hub local API, enrolment endpoint, SQLite, consent records and hash chain

- [x] TASK-025 [hub] SQLite schema and migrations
  - `remu_hub/db/schema.sql` creates the tables `shops`, `staff`, `households`, `members`, `templates`, `consent_records`, `purchases`, `visits`, `clips`, `events_outbox` with exactly the columns of the data model in docs/ARCHITECTURE.md.
  - `remu-hub db migrate` applies numbered migrations and is idempotent; `members.status` is constrained to `pending_notice`, `active`, `withdrawn`, `expired`.
  - Tests create a fresh database, migrate twice and insert one row per table.
- [x] TASK-026 [hub] Shop key and data directory layout
  - `remu-hub init` creates `REMU_DATA_DIR` with `db/`, `clips/`, `models/`, `certs/` and generates a 32-byte shop key in a root-only file (mode 0600, owned by root on Linux; on macOS development the current user, with a logged warning).
  - The scrambler from TASK-015 reads the key from this file; the key is never logged or sent anywhere (a test asserts no key bytes appear in logs or outbox rows).
  - Directory encryption is deferred to Phase 7 and the warning says so.
- [x] TASK-027 [hub] FastAPI app with HTTPS, tokens and staff PINs
  - `remu_hub/api/app.py` serves FastAPI over HTTPS on the LAN with a self-signed certificate generated on first run into `certs/` with a 10-year validity (the app pins its fingerprint, so it is replaced only by re-pairing); `GET /health` returns version, `model_version`, uptime and gallery size.
  - Every other route needs a bearer token; unknown tokens get 401. `POST /staff` creates a staff row (name, role owner or cashier, `pin_hash`) and `POST /staff/verify-pin` returns the staff id and role for a correct PIN. The hub is the author of `staff` rows in MVP; they reach the cloud through the outbox (TASK-035).
  - `remu-hub staff add --name --role` prompts for a PIN and creates the first owner or cashier row from the console, for development and for the Phase 3 tests.
  - Tests use the FastAPI test client; `make dev` now starts the API and the pipeline together.
  - Sessions: (1) HTTPS, certificate and `GET /health`; (2) bearer tokens, `POST /staff`, `POST /staff/verify-pin` and the CLI.
- [x] TASK-028 [hub] Hub pairing code and app token issue
  - The hub holds a pairing code generated at `remu-hub init`, printed to the console and to `pairing.txt` in the data directory for the sticker.
  - `POST /pair` with the correct code returns an app token for the owner phone and the certificate SHA-256 fingerprint for pinning; the code is then marked used. Wrong codes are rate limited to 5 tries per minute.
  - Each phone gets its own app token. `POST /pair/invite` (owner token) returns a one-time invite code valid 10 minutes that the counter phone exchanges at `POST /pair` for its own token and the fingerprint; TASK-041 and TASK-106 use it.
  - Tests cover success, wrong code, reuse, the rate limit and the invite. OPEN: confirm the pairing direction (hub code typed into the app) with the founder, since the brief describes it both ways.
- [x] TASK-029 [hub] Enrol endpoint
  - `POST /enrol` accepts zero or more aligned face crops with 5 landmarks each, a phone number, bill number, amount, items, occasion, notes, the staff id, and, when more than one face is sent, an optional `household_label` and the index of the payer face.
  - For each face: age gate (under 18 is refused with HTTP 422 and reason `under_18`, no override), embed, scramble, then insert `members` (status `pending_notice`, `member_since` now, `discount_flag` true), `templates` (with `model_version`) and `purchases`. With two or more accepted faces the hub creates the `households` row with the label and `payer_member_id` in the same transaction.
  - With zero faces the request records a phone member: a `members` row with no `templates` row, a `purchases` row, no consent record; such a row never enters the gallery.
  - A phone that already belongs to a member with no template adds the template, the consent record and the purchase to that member; a phone that belongs to a member who already has a template returns 409 with the member id so the app can add the bill through `POST /members/{id}/purchases` (TASK-032).
  - Tests cover one adult, a refused minor, an invalid crop, a missing phone, zero faces, a household of two, and both existing-phone cases.
- [x] TASK-030 [hub] Phone hashing and encryption
  - `phone_hash` is SHA-256 of the normalised number (E.164) with no salt, as docs/ARCHITECTURE.md section 4 and docs/SECURITY.md section 1 describe, so the cloud can compute the same hash from a STOP sender number; `phone_enc` is the number encrypted with a key derived from the shop key and is readable only on the hub.
  - The cloud gets the plain number for the WhatsApp send only inside the `notice_request` outbox event (TASK-035); it keeps it in `whatsapp_messages` (assumed column `phone_e164`) and nowhere else. TASK-063 and TASK-066 rely on this. OPEN: docs/RULES.md Cloud rules carry the salt and key question; this is the simplest option until the founder and lawyer close it.
  - `GET /members?phone=` finds a member by hash without decrypting; the number is decrypted only for the member card.
  - Tests cover normalisation (10-digit Indian mobiles, +91 prefix), lookup and the round trip.
- [x] TASK-031 [hub] Consent record writer with hash chain
  - Every enrol writes one `consent_records` row with `channel=verbal_at_counter`, `script_version=hi-IN-v1.0`, `notice_version=wa-v1.0`, `purposes_json`, `adult_check=passed`, `prev_hash` of the shop's last record and `record_hash` over the canonical JSON.
  - The JSON matches the example in docs/PRIVACY_AND_CONSENT.md field for field; rows can be inserted but never updated or deleted (SQLite triggers enforce it). Delivery, thumbs-up, withdrawal and template deletion are new chained rows for the same member, written by the same function.
  - `remu-hub consent verify` walks the chain and reports the first break; a test tampers with one row and sees the break.
- [x] TASK-032 [hub] Members and households endpoints
  - `GET /members` (search by name or phone, filter by status), `GET /members/{id}`, `PATCH /members/{id}` (`display_name` and `notes` only), and `POST /members/{id}/remove`, which sets `withdrawn`, deletes the `templates` row at once and overwrites the blob, writes one chained consent record with `withdrawn_at` and `template_deleted_at`, and drops the member from the gallery, all in one transaction.
  - `POST /members/{id}/purchases` adds a bill (bill number, amount, items, occasion) to an existing member.
  - `POST /households` and `PATCH /households/{id}` (label, `payer_member_id`, add or remove members); a member belongs to at most one household.
  - Tests cover search, the patch limits, remove (template gone, record chained), the purchase, and the household rules.
- [x] TASK-033 [hub] Gallery from the database
  - On start the gallery loads templates of `active` members only; `pending_notice`, `withdrawn` and `expired` members are never loaded (test).
  - A status change in `members` refreshes the gallery entry within 5 seconds.
  - The demo from TASK-024 now enrols through `POST /enrol` and reads from SQLite. There is no cloud yet, so the demo's test fixture sets `members.status` to `active` directly in the test database; no hub code path, flag or switch activates a member.
- [x] TASK-034 [hub] Visits and encrypted clips
  - Each decided match writes a `visits` row (`ts`, `camera`, `score`, `frames_agreed`) and saves a 5-second H.264 clip, the 5 seconds starting at the first agreeing frame, encoded from the decoded 640 by 360 frames (no pre-roll buffer and no second decoder), encrypted at rest with a key derived from the shop key, with `expires_at` 30 days ahead.
  - `GET /visits?member_id=`, `PATCH /visits/{id}` (`greeted`, `greeted_by`, `not_them`) and `GET /clips/{id}` (decrypts and streams) answer only on the LAN interface.
  - Tests cover the row, the file, the decrypt round trip and the `expires_at` value.
- [x] TASK-035 [hub] Events outbox
  - `remu_hub/sync/outbox.py` appends typed events (`consent_record`, `member_upsert`, `staff_upsert`, `purchase`, `notice_request`, `visit_summary`, `visit_feedback`, `alert`, `health`) to `events_outbox` with `synced_at` null.
  - Payload builders strip `vec_blob`, `clip_id` and any image bytes; the plain phone number appears only in `notice_request`; an allowlist test fails if a forbidden key appears.
  - No network in this task; flushing arrives in Phase 4.
- [x] TASK-036 [hub] WebSocket alert events
  - `GET /ws` (token in the first message) pushes each alert as JSON keyed by `visit_id`: visit id, member id, display name, household label, last visit, last purchase amount and items, notes.
  - Every alert also goes to the outbox as an `alert` event.
  - A test connects, triggers a synthetic match and receives the message within 1 second.
- [x] TASK-037 [hub] Phase 2 integration test
  - `tests/integration/test_enrol_then_match.py`: start the API and pipeline against the fake camera, `POST /pair`, `POST /enrol` from a frame, set `members.status` to `active` in the test database, then see the alert on `/ws`, the `visits` row and the clip on disk.
  - Runs under `make test` when `REMU_INTEGRATION=1`, skipped otherwise.
- [x] TASK-038 [hub] Privacy scanner
  - `scripts/privacy_scan.py` walks `REMU_DATA_DIR` and the hub log after an enrolment and a match against the fake camera and fails on any JPEG, PNG, WebP or BMP magic bytes, any raw H.264 start code outside an encrypted clip, any enrol request body, or any phone number (docs/TEST_PLAN.md T-P-01); SQLite files are scanned too.
  - It runs under the root `make check` and in CI on every push.
- [ ] TASK-039 [docs] Phase 2 documentation update
  - docs/ARCHITECTURE.md lists every hub endpoint with method, auth and payload and the module tree as built; the consent JSON in docs/PRIVACY_AND_CONSENT.md matches the writer.
  - docs/SECURITY.md records the shop key file, certificate pinning and the outbox allowlist as built.
  - docs/MEMORY.md marks Phase 2 complete.
  - Status 2026-10-18: endpoint list is in docs/API_CONTRACT.md; docs/ARCHITECTURE.md section 3.1 lists the modules as built.

Phase 2 exit check: the integration test passes, the privacy scanner is green, and `remu-hub consent verify` reports an intact chain after 10 enrolments.

## Phase 3: Android app, Cashier mode

- [x] TASK-040 [app] App shell, login and mode choice
  - A login screen with staff PIN entry and a choice of Cashier mode or Owner mode; the PIN is checked against the hub's `POST /staff/verify-pin`.
  - Navigation, theme and components follow docs/DESIGN.md; strings live in `values/` (English) and `values-hi/` (Hindi in Devanagari).
  - Compose UI test: a wrong PIN shows an error, a right PIN opens the chosen mode. The test hub gets its staff rows from `remu-hub staff add` (TASK-027).
- [x] TASK-041 [app] Hub pairing client and pinned HTTPS
  - A pairing screen takes the hub's code (owner phone) or the invite code from Owner mode (counter phone, TASK-028), calls `POST /pair`, and stores the token and certificate fingerprint in Android Keystore-backed encrypted storage (DataStore encrypted with a Keystore key; not EncryptedSharedPreferences, which is deprecated).
  - The HTTP client pins the stored fingerprint; a changed certificate is refused and the user is told to pair again.
  - A status chip shows hub connected or not, using `GET /health` every 30 seconds.
- [x] TASK-042 [app] Live camera preview with face boxes
  - CameraX preview on the camera facing the customer; ML Kit face detection draws a box on every face at 10 or more frames per second.
  - The largest, closest face is pre-selected and drawn in the primary colour.
  - The camera permission flow and a "no face in view" hint are handled.
- [x] TASK-043 [app] Face selection and household multi-select
  - Tap another face to move the selection; a toggle adds faces, up to 6, for a household.
  - Selected faces show as numbered chips; a chip can be removed.
  - UI test on recorded detection results.
- [x] TASK-044 [app] Face crop and landmark payload
  - For each selected face the app captures a still, crops the face with a margin, and packages the crop (JPEG) with its 5 landmarks in crop coordinates.
  - The payload matches the hub's `POST /enrol` contract; a unit test checks the shape and the size limit (under 300 KB per face).
  - No image stays on the phone after the request completes (a test checks the cache directory).
- [x] TASK-045 [app] Billing form and the script prompt
  - Fields: phone number (10-digit Indian mobile validation), bill number, amount, items, occasion, notes.
  - The cashier line is shown above the button in Devanagari, "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.), with the English line "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off." below it.
  - Form state survives rotation; validation errors show inline.
- [x] TASK-046 [app] Make member
  - One "Make member" button sends the crops, phone, bill details and staff id to `POST /enrol`; the tap is the consent time and there is no separate consent control anywhere in the UI.
  - Success shows the docs/DESIGN.md section 6 string "Member added. Notice pending on WhatsApp." / "मेंबर बन गए। WhatsApp पर सूचना बाकी।" (Member ban gaye. WhatsApp par soochna baaki.) with the pending chip and clears the form; an under-18 refusal shows the docs/DESIGN.md section 5 face-box label "18 से कम (18 se kam) / Under 18" with no override; hub errors show a retry. All strings come from docs/DESIGN.md, in both languages.
  - UI test against a mock hub covers all three outcomes.
- [x] TASK-047 [app] Household enrolment
  - With more than one face selected the form asks for a household label (default "<surname> family") and who pays; all faces go in one `POST /enrol` call with `household_label` and the payer index, and the hub creates the household (TASK-029).
  - Each face passes the age gate on its own; one refusal removes that face and the rest proceed, with the docs/DESIGN.md card "One face was refused (under 18). The others are members."
  - UI test with two faces.
- [x] TASK-048 [app] Phone-member path
  - A "Skip face, save bill only" action sends `POST /enrol` with zero faces (TASK-029), so the purchase is recorded against the phone number with no crop and no consent record for recognition; the app shows "Saved without face membership".
  - A counter of skipped taps is kept for the pilot enrolment denominator (TASK-127).
  - OPEN: the brief's member status list has no value for a phone-only member. Proposed: a row with no template is the marker; confirm, or add a status.
- [x] TASK-049 [app] Member search, add bill and remove member
  - Search by phone or name calls `GET /members`; rows show name, status, member since and household. A 409 from `POST /enrol` (TASK-029) opens the matching member.
  - "Add bill" on a member row calls `POST /members/{id}/purchases`.
  - "Remove member" asks for confirmation with the docs/DESIGN.md section 7 wording, calls `POST /members/{id}/remove` and shows "Removed. Face data is deleted within 1 hour."
  - UI test for search, add bill and remove.
- [x] TASK-050 [app] Household editing
  - From a member row the cashier opens the household, renames the label, changes the payer, and adds (through a new enrolment) or removes a member.
  - Changes call `PATCH /households/{id}` and the list refreshes.
  - UI test for rename and payer change.
- [x] TASK-051 [app] Hub-offline handling in Cashier mode
  - When `GET /health` fails, a top banner says "Hub not reachable" in the current language, "Make member" is disabled and the camera preview stays live.
  - Reconnection is automatic and the banner clears within 30 seconds.
  - UI test toggles a mock hub off and on.
- [ ] TASK-052 [app] Cashier mode in Hindi and English
  - Every Cashier mode string exists in both languages; the language switch sits on the login screen, can be changed in Settings, and persists on that phone (docs/RULES.md UI rules).
  - Devanagari renders correctly on the stand phone at the chosen sizes (checked on a real device, screenshots in the pull request).
  - A lint rule fails the build on a missing Hindi string.
  - Status 2026-10-18: strings in both languages with a build check, and Devanagari checked on an emulator; screenshots on the real stand phone still need a person.
- [ ] TASK-053 [app] Cashier mode end-to-end test
  - A Maestro flow (or Compose UI test) on a device or emulator: pair with a local hub, select a face shown to the camera from a test image, fill the form, tap Make member, and assert the hub has one `pending_notice` member.
  - The flow is documented in docs/TEST_PLAN.md and runs with `./gradlew connectedAndroidTest` or `maestro test`.
  - Depends on: TASK-037, TASK-052.
  - Status 2026-10-18: Maestro flow app/maestro/cashier_enrol.yaml and an instrumented test exist; they need a running hub on a device to execute.
- [x] TASK-054 [docs] Phase 3 documentation update
  - The Cashier mode screens in docs/DESIGN.md match what was built (screenshots linked); the app module layout in docs/ARCHITECTURE.md matches the code.
  - docs/MEMORY.md marks Phase 3 complete.

Phase 3 exit check: on a phone on a stand, a cashier can pair, enrol one adult and a two-person household, search and remove, in Hindi and in English.

## Phase 4: Cloud: Supabase schema, pairing, consent mirror, WhatsApp send and webhooks, activation

- [x] TASK-055 [cloud] Supabase project and mirror schema
  - A Supabase project in the Mumbai region (AWS ap-south-1); `cloud/supabase/migrations/` creates `shops`, `staff`, `households`, `members`, `consent_records`, `purchases`, `visits` (without `clip_id`), `devices`, `whatsapp_messages`, `hash_anchors` and `push_tokens` (`id`, `shop_id`, `user_id`, `fcm_token`, `updated_at`; assumed, not in the brief) with the columns of the data model in docs/ARCHITECTURE.md.
  - Row Level Security is on for every table with no policies yet (deny by default); no role has UPDATE or DELETE on `consent_records`.
  - `npm run db:reset` applies the migrations locally; a Vitest test checks the table list.
- [x] TASK-056 [cloud] Owner accounts and shop creation
  - Supabase Auth sign-in for owners; `POST /api/shops` creates a shop with name, city, `script_version=hi-IN-v1.0`, `notice_version=wa-v1.0` and a default `settings_json` (notice language), plus a `staff` row with role `owner`.
  - RLS policies: a user reads and writes only rows of shops where they are staff; a test with two users proves the isolation.
  - OPEN: owner sign-in method for the pilot (email and password, or phone OTP).
- [x] TASK-057 [cloud] Device pairing
  - `POST /api/devices/pair` takes the hub pairing code and the owner session, checks the code against `devices.pairing_code` (registered at provisioning, TASK-105) and links the device to the shop. The owner app never receives the device token.
  - The hub, which polls `POST /api/devices/claim` with its pairing code and serial every 30 seconds from `remu-hub init` until paired, receives the device token on its next poll and stores it root-only; only the SHA-256 of the token is stored in `token_hash`.
  - A used code cannot be reused; wrong codes are rate limited.
  - Tests cover success, reuse, a code from another shop, and the claim poll before and after pairing.
- [x] TASK-058 [hub] Cloud client and outbox flush
  - `remu_hub/sync/client.py` sends outbox events to `POST /api/hub/events` over HTTPS with the device token, in id order, in batches of up to 200, every 30 seconds and at once after an `alert` or `notice_request` event, and sets `synced_at` on acknowledgement.
  - Failures retry with exponential backoff capped at 5 minutes; the hub never opens an inbound port (a test asserts no listener beyond the LAN API).
  - Tests use a local mock server, including a partial-batch failure.
- [x] TASK-059 [cloud] Hub event ingest
  - `POST /api/hub/events` authenticates by device token, validates each event with zod against the mirrored columns, inserts `consent_records` ignoring duplicate ids (never updating a row), and upserts `members`, `staff`, `households`, `purchases`, `visits` and device health.
  - Any payload with a key named like `vec_blob`, `template`, `image`, `crop` or `clip`, or a base64 blob over 1 KB, is rejected with 400 and logged; a test proves it.
  - Replaying the same event id writes once.
- [x] TASK-060 [cloud] Consent chain check on ingest
  - For each `consent_records` event the cloud recomputes `record_hash` and checks `prev_hash` against the shop's previous mirrored record; a break is stored with a `chain_break` flag on the device record.
  - Out-of-order arrival inside a batch is handled by sorting on `ts` then id.
  - Tests cover an intact chain, a tampered record and an out-of-order batch.
- [x] TASK-061 [all] Nightly hash anchor
  - The hub posts `{date, chain_head_hash}` to `POST /api/hub/anchor`; the cloud stores it in `hash_anchors` and compares it with its own mirrored head.
  - A mismatch is flagged on the device record.
  - Tests on both sides; the hub job runs by hand until the scheduler in TASK-091.
- [x] TASK-062 [cloud] WhatsApp Cloud API client
  - `cloud/src/lib/whatsapp.ts` sends an approved template message by name with the body variables `shop_name`, `first_name` and `date` and the "👍" quick-reply button, using `WHATSAPP_PHONE_NUMBER_ID` and `WHATSAPP_ACCESS_TOKEN`.
  - Template names come from `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN` and `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI`.
  - Tests mock the Graph API and assert the exact request body.
- [x] TASK-063 [cloud] Send the member notice on enrolment
  - When a `notice_request` event for a new `pending_notice` member arrives, the cloud sends the notice in the shop's notice language to the number in the event and writes a `whatsapp_messages` row with the `wamid`, the number and status `sent`. The cloud edits no consent record; the `wamid` reaches the hub inside the `notice_status` inbox message (TASK-065).
  - Sending is idempotent per consent record; a failed send is retried 3 times and then flagged on the member.
  - Tests cover the first send, a retry and duplicate suppression.
- [x] TASK-064 [cloud] WhatsApp webhook: verification and status events
  - `GET /api/webhooks/whatsapp` answers the Meta verification challenge with `WHATSAPP_VERIFY_TOKEN`; `POST` checks the `X-Hub-Signature-256` header with `WHATSAPP_APP_SECRET` and rejects bad signatures.
  - `sent`, `delivered` and `read` statuses update `whatsapp_messages.status` and `status_ts`; the raw body is stored in `raw_webhook_json`.
  - Tests use recorded webhook payloads.
- [x] TASK-065 [cloud] Activation on delivery
  - A `delivered` status sets the member to `active` in the mirror and queues two inbox messages for the hub: `member_status` (active) and `notice_status` (`wamid`, `whatsapp_status=delivered`, `delivered_at`). The hub appends the chained consent record carrying `notice_delivery_json` (TASK-067); the cloud never updates a mirrored consent record.
  - No other path sets a member `active` (test); `read` without `delivered` does not activate.
  - A metric records the seconds from consent `ts` to `delivered_at` for the 2-minute target.
- [x] TASK-066 [cloud] Thumbs-up and STOP messages
  - A button reply with payload "👍" is recorded in `whatsapp_messages` and queues a `thumbs_up` inbox message with the timestamp; the hub appends the chained record with `thumbs_up_at` (TASK-067). A text message whose trimmed content is `STOP` (any case) is matched to members by `phone_hash` of the sender number, sets them `withdrawn` in the mirror and queues a `member_status` (withdrawn) message for the hub.
  - Any other inbound text is stored and ignored. OPEN: whether the cloud sends a short confirmation after STOP, and its wording.
  - Tests use recorded payloads for both.
- [x] TASK-067 [hub] Hub inbox polling
  - `remu_hub/sync/inbox.py` polls `GET /api/hub/inbox` every 30 seconds (outbound only), applies `member_status` (active, withdrawn), `notice_status`, `thumbs_up`, `visit_feedback` and `config` messages to SQLite, acknowledges them and refreshes the gallery.
  - `notice_status` (delivered) and `thumbs_up` each append a chained consent record through the writer from TASK-031 and queue it to the outbox so the mirror receives the same row; `member_status` (withdrawn) runs the same deletion as `POST /members/{id}/remove` (TASK-032) at once.
  - A member activated by the cloud is in the gallery within one poll; a withdrawn member leaves the gallery at once.
  - Tests with a mock inbox cover every message type.
- [x] TASK-068 [cloud] FCM relay for alerts
  - On an `alert` event the cloud sends a Firebase Cloud Messaging data message, high priority, to every `push_tokens` row of the shop using `FCM_SERVICE_ACCOUNT_JSON_BASE64`; the message carries `shop_id`, `visit_id` and `member_id` only, never a name, purchase or note, because Google's push servers are outside India (docs/SECURITY.md 3.2).
  - `POST /api/devices/fcm-token` writes `push_tokens` for the signed-in owner.
  - Tests mock FCM, assert the fan-out, and fail if the payload contains any key other than the three ids.
- [x] TASK-069 [cloud] Owner actions off the shop WiFi
  - `GET /api/visits/{id}/card` returns the alert card fields (display name, household label, last visit, last purchase amount and items, notes) from the mirror to an owner session of that shop, so the app can build the card after an FCM push.
  - `POST /api/visits/{id}/feedback` (`greeted`, `greeted_by`, `not_them`) and `POST /api/members/{id}/withdraw` record the change in the mirror and queue `visit_feedback` and `member_status` (withdrawn) inbox messages for the hub (TASK-067). The app uses the hub on the LAN and these routes off it.
  - Tests cover a user of another shop (403), the queued messages and the card fields.
- [x] TASK-070 [cloud] Hub health and last seen
  - `health` events update `devices.last_seen_at` and `hub_version`; `GET /api/shops/{id}/devices` returns the status for the app.
  - A device silent for more than 15 minutes is reported as offline.
  - Tests cover both states.
- [ ] TASK-071 [docs] Lawyer sign-off of the v1.0 wording
  - The lawyer receives the cashier line `hi-IN-v1.0` (Hindi and its English rendering), the WhatsApp notice `wa-v1.0` (English and Hindi), the standee text, the staff FAQ answers in docs/PRIVACY_AND_CONSENT.md section 16 and the consent record format, and signs off or asks for changes.
  - The sign-off, or the required changes, is recorded in docs/DECISIONS.md; any wording change bumps `script_version` and `notice_version` and reaches the hub, the cloud, the app strings and the templates before TASK-072.
  - OPEN: name the lawyer and the review date; no template is submitted to Meta and no shop goes live before sign-off.
  - Independent: may run in parallel with TASK-055 to TASK-070.
  - Status 2026-10-18: needs the lawyer. No template goes to Meta and no shop goes live before sign-off.
- [ ] TASK-072 [cloud] WhatsApp template submission
  - The EN and HI member notice templates use the exact wording from docs/PRIVACY_AND_CONSENT.md, category utility, with the "👍" quick-reply button, and are submitted through the Meta Business account; submission ids and approval state go in docs/MEMORY.md.
  - The Meta Business account, the WhatsApp Business Account and the dedicated number exist and are listed in docs/ARCHITECTURE.md (ids only, no secrets).
  - OPEN: which Indian number the pilot uses.
  - Depends on: TASK-071. Independent of TASK-062 to TASK-070.
  - Status 2026-10-18: needs the Meta Business account, the WhatsApp Business Account and a number.
- [x] TASK-073 [all] Phase 4 integration test
  - `tests/integration/test_notice_to_active.py`: the hub enrols, the outbox flushes to a local cloud, the mocked WhatsApp API accepts the send, a replayed `delivered` webhook activates the member, the hub inbox picks it up, the hub chain gains the delivery record, and the gallery contains the member.
  - It also replays a STOP webhook and asserts the member leaves the gallery and the template is gone.
  - Depends on: TASK-067, TASK-072.
- [x] TASK-074 [docs] Phase 4 documentation update
  - docs/ARCHITECTURE.md documents every cloud route, the inbox and outbox message types and the status machine `pending_notice` to `active` to `withdrawn` or `expired`, with the names as built; cloud/README.md matches.
  - docs/SECURITY.md records RLS, token hashing and the webhook signature check.
  - docs/MEMORY.md marks Phase 4 complete.

Phase 4 exit check: a real WhatsApp message with the approved template reaches a test number, the delivered webhook activates the member and the hub matches them.

## Phase 5: Android app, Owner mode: alerts, member card, feedback, timeline and clips

- [x] TASK-075 [app] Owner sign-in and shop session
  - Owner mode signs in with Supabase Auth, loads the owner's shop and shows a home screen with today's alert count and the hub status from `GET /api/shops/{id}/devices`.
  - The session survives restarts; sign-out clears it.
  - UI test with a mock cloud.
- [ ] TASK-076 [app] FCM registration
  - Firebase Messaging is wired with `google-services.json` kept out of git; on sign-in the FCM token goes to `POST /api/devices/fcm-token` and is refreshed on rotation.
  - A data message (`shop_id`, `visit_id`, `member_id` only) makes the app fetch the card from the hub on the shop WiFi or from `GET /api/visits/{id}/card` otherwise, then build a heads-up notification locally that opens the alert card. OPEN: whether the lock-screen text shows the first name or only "Member at the gate" (docs/SECURITY.md 3.2).
  - Manual test with a message sent from the cloud, documented in docs/TEST_PLAN.md.
  - Status 2026-10-18: code done (ids-only payload, card fetch, local notification, lock screen shows 'Member at the gate', ADR-036); needs a Firebase project for the manual push test.
- [x] TASK-077 [app] LAN WebSocket alerts
  - On the shop WiFi and paired, Owner mode keeps a connection to the hub's `/ws`, shows its alerts at once and reconnects with backoff.
  - Alerts are keyed by `visit_id`; the same visit from FCM and from the WebSocket shows once.
  - Tests cover de-duplication and reconnection.
- [x] TASK-078 [app] Alert card
  - The card renders "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}" exactly, with empty parts dropped cleanly, plus the two buttons "Greeted" and "Not them".
  - Alerts stack newest first; a card older than 10 minutes greys out.
  - Compose test with a fixture alert.
- [x] TASK-079 [all] Greeted and Not them feedback
  - "Greeted" sends `greeted=true` and `greeted_by=<owner staff id>`, "Not them" sends `not_them=true`; on the shop WiFi to the hub's `PATCH /visits/{id}`, off it to the cloud's `POST /api/visits/{id}/feedback` (TASK-069). The hub mirrors its change through the outbox; the cloud's reaches the hub through the inbox.
  - "Not them" also dismisses the card, and the pair (visit id, member id) is kept for threshold tuning with the eval harness.
  - Tests on the app, hub and cloud sides.
- [x] TASK-080 [app] Member list and member card
  - A member list from the hub with search and status filter; the member card shows display name, household, status, member since, last seen, purchases (bill number, amount, items, occasion, date) and editable notes.
  - Notes save through `PATCH /members/{id}` and reach the cloud mirror.
  - UI test for the note edit.
- [x] TASK-081 [app] Visit timeline and clips over LAN
  - The member card has a timeline of visits (date, score, greeted or not them); tapping a visit plays its 5-second clip from `GET /clips/{id}` when on the shop WiFi.
  - Off the shop WiFi the row says "Clips play only on shop WiFi" and no clip request leaves the LAN.
  - Tests cover both network states.
- [x] TASK-082 [all] Owner settings
  - A settings screen with the staff list and cashier PIN creation (4 to 6 digits, stored as `pin_hash` on the hub through `POST /staff` and mirrored to the cloud through the `staff_upsert` outbox event; the hub is the author of staff rows), device status (hub online, last seen, version, gallery size), the language switch, and sign-out.
  - PIN creation needs the shop WiFi; off it the screen says so.
  - UI tests for PIN creation and the language switch.
  - Sessions: (1) staff list and PIN creation; (2) device status, language and sign-out.
- [ ] TASK-083 [app] Owner mode in Hindi and English
  - Every Owner mode string exists in both languages, including notification text; the Hindi lint rule from TASK-052 covers Owner mode.
  - Screenshots of each screen in both languages are attached to the pull request.
  - Status 2026-10-18: every Owner mode string in both languages under the same build check; per-screen screenshots for the pull request still need a person.
- [ ] TASK-084 [app] Owner mode end-to-end test
  - Maestro flow: sign in, receive a synthetic alert over the WebSocket from a local hub, open the card, tap Greeted, and see `greeted=true` on the hub's `visits` row.
  - Documented in docs/TEST_PLAN.md and runnable with `maestro test`.
  - Depends on: TASK-079, TASK-083.
  - Status 2026-10-18: Maestro flow app/maestro/owner_alert_greeted.yaml written; needs a hub, the cloud and an owner account on a device.
- [x] TASK-085 [docs] Phase 5 documentation update
  - The Owner mode screens in docs/DESIGN.md match the build; docs/ARCHITECTURE.md draws the alert path (hub to cloud to FCM to card fetch, and hub to WebSocket) with timings.
  - docs/MEMORY.md marks Phase 5 complete.

Phase 5 exit check: a member passes the fake camera, the owner phone buzzes within 3 seconds on the LAN and shows the card, and Greeted lands in the database.

## Phase 6: Withdrawal, deletion, expiry, consent export

- [x] TASK-086 [hub] Template deletion sweep
  - The normal path deletes a template at once inside the withdrawal (TASK-032, TASK-067). `remu_hub/jobs/delete_templates.py` is the safety net: it finds members with status `withdrawn` or `expired` that still have a `templates` row (a crash between transition and commit, or a restored copy), deletes the row and overwrites the blob, writes a new chained `consent_records` row with `template_deleted_at` (and `withdrawn_at` when not yet set), and queues it to the outbox.
  - The sweep runs every 5 minutes so any miss is closed well inside 1 hour of the withdrawal; a test with a frozen clock proves the bound. Its counts go into the health report as the nightly self-check in docs/SECURITY.md section 8.
  - `remu-hub jobs delete-templates` runs it by hand.
- [x] TASK-087 [all] Remove member from the app, end to end
  - "Remove member" in Cashier mode or Owner mode on the shop WiFi calls `POST /members/{id}/remove`; Owner mode off the shop WiFi calls `POST /api/members/{id}/withdraw` (TASK-069) and the hub applies it from the inbox. Either way the hub sets `withdrawn`, deletes the template at once and drops the member from the gallery.
  - The cloud mirror shows `withdrawn` with `template_deleted_at` once the chained record syncs; the member's `whatsapp_messages` rows are kept.
  - An integration test measures the time from tap to `template_deleted_at` on both paths.
- [x] TASK-088 [all] STOP flow, end to end
  - A STOP text at the WhatsApp webhook sets `withdrawn` in the cloud, the hub inbox applies it within one poll, the gallery drops the member, the template is deleted and the chained record returns to the cloud.
  - An integration test with a replayed STOP webhook asserts `template_deleted_at` within 1 hour on a simulated clock and that no match fires after the status change.
  - Depends on: TASK-067, TASK-086.
- [x] TASK-089 [hub] Nightly member expiry
  - `remu_hub/jobs/expire_members.py` sets `expired` on members whose `last_seen_at` (or `member_since` when never seen) is older than `REMU_MEMBER_EXPIRY_DAYS` (1095), deletes their templates and writes a chained consent record with `template_deleted_at`.
  - Tests with a frozen clock cover 1094 days (kept), 1095 days (expired) and members with no visits.
- [x] TASK-090 [hub] Clip cleanup
  - `remu_hub/jobs/cleanup_clips.py` deletes clip files and `clips` rows past `expires_at` (30 days) and sets `visits.clip_id` to null.
  - A test creates clips at 29, 30 and 31 days and checks which survive; orphan files on disk are removed too.
- [x] TASK-091 [hub] Job scheduler
  - `remu-hub jobs run` runs the schedule: outbox flush continuously, template deletion sweep every 5 minutes, health report every 5 minutes, the expiry and clip cleanup nightly at 03:00 Asia/Kolkata and the hash anchor at 03:30 Asia/Kolkata (docs/ARCHITECTURE.md sections 6 and 10). Nightly jobs run only when the hub clock is NTP-synchronised.
  - A failing job is logged and retried on the next tick without stopping the others.
  - Tests drive one simulated day with a fake clock.
- [x] TASK-092 [cloud] Consent export CSV
  - `POST /api/shops/{id}/consent-export?format=csv` streams every mirrored consent record of the shop with every column of the data model, plus a detached signature made with `CONSENT_EXPORT_SIGNING_KEY` (Ed25519) over the CSV bytes.
  - The export includes the chain head hash and the latest `hash_anchors` row.
  - Tests verify the signature and the row count.
- [x] TASK-093 [cloud] Consent export PDF
  - `format=pdf` produces a PDF with shop name, generation date, script and notice versions, one row per consent record (member id, phone hash prefix, bill number, timestamp, cashier, channel, delivery status, thumbs-up, withdrawn, template deleted) and the chain head hash and signature on the last page.
  - Devanagari renders correctly in the PDF (embedded font).
  - Tests check the page count and the signature block.
- [x] TASK-094 [app] Export from Owner mode
  - Owner settings has "Export consent records"; one tap calls the cloud, shows progress and opens the share sheet with the PDF and the CSV.
  - A failure shows a retry; the date of the last export is shown.
  - UI test with a mock cloud.
- [x] TASK-095 [hub] Local consent export
  - `remu-hub consent export --csv <path>` writes the hub's own chain as CSV with the same columns as TASK-092, for audits while offline.
  - After a full sync the hub CSV and the cloud CSV hold the same records byte for byte (a test syncs, exports both and compares them); before a sync the hub CSV may hold records the mirror has not received yet.
- [x] TASK-096 [all] Phase 6 integration tests
  - `tests/integration/test_withdraw_expire_export.py`: enrol, activate, STOP, assert deletion inside 1 hour; age a member to 1095 days, run expiry, assert deletion and the record; export and verify the chain from the CSV.
  - Runs in CI on the simulated clock.
- [x] TASK-097 [docs] Phase 6 documentation update
  - docs/PRIVACY_AND_CONSENT.md records the measured deletion time, the expiry rule and the export format with a sample row.
  - docs/MEMORY.md marks Phase 6 complete.

Phase 6 exit check: STOP and Remove member both delete the template within 1 hour and leave a chained record; the export verifies.

## Phase 7: Hub on the Pi 5: image, systemd, encrypted data directory, real DVR RTSP, pairing

- [ ] TASK-098 [infra] Pi 5 image build script
  - `scripts/build_pi_image.sh` takes Raspberry Pi OS Lite 64-bit, installs the system packages, creates the Python 3.11 virtual environment, installs `remu_hub`, downloads the model pack and writes a first-boot script.
  - The result boots on a Pi 5 (4 GB) and `remu-hub --version` works on the console.
  - OPEN: build the image on a Mac in a container, or on a Pi; pick one and document it. OPEN: whether SSH stays enabled on pilot hubs and what the hub update channel is (signed wheel pulled by the hub, or manual SSH); docs/SECURITY.md 3.1 and docs/ARCHITECTURE.md section 12 carry the same question, and one ADR closes all of them.
  - Status 2026-10-18: scripts/build_pi_image.sh written (built on a Pi, ADR-034); needs a Pi 5 to boot it. SSH and update channel stay OPEN.
- [ ] TASK-099 [hub] systemd services
  - `remu-hub.service` runs the API and pipeline as a dedicated `remu` user with `Restart=always`, a 10-second restart delay and a systemd watchdog ping; `remu-hub-jobs.service` runs the scheduler from TASK-091.
  - Logs go to journald; `remu-hub status` shows both units and the last 20 log lines.
  - A kill test shows the service back within 30 seconds.
  - Status 2026-10-18: hub/deploy/remu-hub.service and remu-hub-jobs.service with Restart=always, RestartSec=10, a watchdog and LoadCredential; the kill test needs a Pi.
- [x] TASK-100 [cloud] Data unlock token service
  - `POST /api/devices/unlock` with a valid device token returns the device's unlock token only while the device is paired and not revoked; unpairing or a revoke flag makes it refuse.
  - The token is derived on each request from `HUB_PAIRING_SECRET`, the device id and a generation counter (docs/ARCHITECTURE.md section 8, docs/SECURITY.md 3.1, ADR-006); nothing but its hash is stored, in `devices.data_unlock_token_hash`; unpairing or revoking bumps the generation; it is never logged.
  - Tests cover paired, unpaired and revoked.
- [ ] TASK-101 [hub] Encrypted data directory
  - On the Pi, `REMU_DATA_DIR` lives on a LUKS volume (file-backed container, `cryptsetup`) keyed by HKDF over the shop key and the unlock token from TASK-100; the hub fetches the token at boot, unlocks, mounts, and wipes the token from memory. No unlock token is ever written to disk.
  - A hub that cannot reach the cloud at boot stays locked, reports `locked` on `GET /health` and retries every 60 seconds; once unlocked it keeps running through later internet loss.
  - The threat model and limits (a powered, unlocked hub is readable with root; the shop key file sits on the unencrypted root) are written in docs/SECURITY.md. OPEN: LUKS container or fscrypt, decide after measuring boot time.
  - Status 2026-10-18: remu_hub/unlock.py (LUKS, HKDF over shop key and unlock token, 60-second retry, never on disk) tested with a fake cryptsetup; needs a Pi to run for real. The LUKS versus fscrypt OPEN stays until boot time is measured.
- [ ] TASK-102 [hub] RTSP discovery on the shop LAN
  - `remu-hub discover` scans the LAN for devices answering on port 554 and tries the known URL patterns for CP Plus, Hikvision and Dahua with the credentials the owner enters; it returns candidate streams with a thumbnail each.
  - `GET /discover` exposes the same list to the app.
  - Tests run the URL pattern logic against recorded responses; a manual test against one real DVR is recorded in docs/TEST_PLAN.md.
  - Status 2026-10-18: discovery with Hikvision and Dahua/CP Plus patterns, sub-stream first, tested on recorded responses; the manual test against a real DVR is pending.
- [ ] TASK-103 [hub] Real DVR stream hardening
  - The frame source handles H.264 and H.265 substreams, picks the substream when the main stream is above 1080p, survives DVR reboots and timestamp jumps, and logs decode errors at most once a minute.
  - Elapsed time: a 24-hour soak on the Pi against a real DVR shows no memory growth and no stall (memory and frame rate logged hourly); it runs while the next tasks proceed.
  - Status 2026-10-18: reconnect with backoff and once-a-minute decode error logging done; the 24-hour soak needs a Pi and a real DVR.
- [ ] TASK-104 [hub] Pi performance tuning
  - On the Pi 5 with `buffalo_l` at 640 by 360 and 5 to 8 frames per second, the time from a face entering the frame to the alert event is under 1 second at the hub, measured over 20 runs.
  - When the hub cannot sustain 5 frames per second it logs it and reports it in health; it never switches the model pack by itself. Choosing `buffalo_sc` for a shop is a provisioning-time decision made before the first enrolment, with a harness report (docs/TEST_PLAN.md T-X-02) and an ADR, because templates from one pack do not match the other and every member of that shop would re-enrol.
  - The numbers go in docs/RESEARCH.md and docs/DECISIONS.md.
  - Status 2026-10-18: the hub logs and reports fps under 5 and never switches packs itself; the latency measurement needs a Pi 5.
- [ ] TASK-105 [all] Hub provisioning and pairing, end to end
  - `scripts/provision_hub.py` reads the pairing code the hub generated at `remu-hub init` (TASK-028) from `pairing.txt`, registers the device in the cloud (pairing code, device record, generation 1), and prints a sticker label.
  - On the Pi: first boot, the owner enters the code in the app, the cloud links the device, the hub receives its device token on its next claim poll (TASK-057), fetches the unlock token, unlocks the data directory and reports healthy.
  - Depends on: TASK-057, TASK-101.
  - Status 2026-10-18: scripts/provision_hub.py and the claim poll work end to end against a local cloud (tests/integration/test_full_stack.py); first boot on a Pi is pending.
- [x] TASK-106 [app] Setup wizard in Owner mode
  - Steps in order: create shop, pair hub (code), pick the gate stream from the discovered list, aim the camera, add the counter phone (the invite code from TASK-028 shown as a QR code the counter phone scans), print the standee.
  - Each step can be resumed; the wizard shows the elapsed time so the 60-minute target can be checked.
  - UI test walks every step against mocks.
  - Sessions: one per wizard step, under the same id.
- [ ] TASK-107 [all] Camera aim preview
  - During the aim step the hub streams low-resolution JPEG frames with detection boxes to the app over the LAN (`GET /preview` as MJPEG, token protected) so the owner can set the camera face-on at eye level.
  - The preview stops by itself after 10 minutes and nothing from it is stored.
  - Manual test on a real gate camera.
  - Status 2026-10-18: GET /preview MJPEG with face boxes, 10-minute stop, LAN only, nothing stored; the manual test on a real gate camera is pending.
- [x] TASK-108 [hub] Optional counter camera stream
  - When `REMU_RTSP_COUNTER_URL` is set the hub decodes a second stream that feeds only the clip recorder (a second 5-second clip for the same visit, same encryption and 30-day expiry) and the aim preview. No face in it is detected, embedded or matched: the notice promises recognition at the door only, and `purposes_json` holds `recognise_at_entry`.
  - Unset means no second stream and no error.
  - OPEN: whether the pilot needs the counter stream at all; matching at the counter would need a new notice version and purpose and stays in Later.
- [x] TASK-109 [infra] Counter standee print file
  - `scripts/standee/standee.pdf` (A5 and A4) carries "Members get 5% off. No card, no app." and "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।" (Members ko 5% off. Na card, na app.) with the Remu mark; the Devanagari font is embedded.
  - The wizard's print step opens this file.
  - Independent: may run in parallel with TASK-098 to TASK-108.
- [ ] TASK-110 [all] First shop-floor test
  - In a real shop or a mock-up, a non-engineer follows the wizard with a Pi, a DVR and the counter phone; the time from unboxing to the first alert is recorded (target under 60 minutes).
  - Every blocker is logged as a sub-bullet here; fixes are small and land before the phase closes.
  - Depends on: TASK-106, TASK-107, TASK-109.
  - Status 2026-10-18: needs a shop or a mock-up, a Pi, a DVR and a non-engineer.
- [ ] TASK-111 [docs] Phase 7 documentation update
  - The hub deployment section of docs/ARCHITECTURE.md (image, services, unlock flow, script names) matches what runs; README.md has the hub install and pairing guide; scripts/README.md matches.
  - docs/MEMORY.md marks Phase 7 complete.

Phase 7 exit check: a Pi from the image pairs, unlocks, discovers the DVR, matches a member at the real gate and alerts the owner phone.

## Phase 8: Hardening: security review, offline mode, monitoring, QA

- [ ] TASK-112 [all] Security review
  - The checklist in docs/SECURITY.md is run item by item on hub, app and cloud; each finding gets a severity; high findings are fixed in this task and the rest are listed as sub-bullets here.
  - The threat model table (stolen hub, stolen counter phone, rogue cashier, cloud breach, WhatsApp number takeover) is updated with what the build does today.
  - Status 2026-10-18: review run on 2026-10-18; findings and fixes are listed under this task.
- [x] TASK-113 [cloud] RLS and secrets audit
  - Automated tests prove a shop's user cannot read, insert or update another shop's rows in any mirrored table; `SUPABASE_SERVICE_ROLE_KEY` appears only in server code (a search test).
  - Webhook signature, device token and owner session checks are covered by negative tests.
- [x] TASK-114 [hub] Hub API hardening
  - Input limits on `POST /enrol` (crop size, count, content type), per-token rate limits, token rotation on re-pair, and a standard security header set. The certificate is not renewed in MVP: it is issued for 10 years at first boot (TASK-027) and replaced only by re-pairing, because the app pins its fingerprint.
  - `GET /clips/{id}` and `GET /preview` refuse any request that does not arrive on the LAN interface.
  - Tests for each limit.
- [x] TASK-115 [all] Biometric leak guard
  - One test suite in `tests/unit/` asserts there are no image, crop, embedding or template bytes in outbox payloads, cloud ingest, Sentry events, logs or app analytics; it runs in CI on every push alongside the privacy scanner from TASK-038.
  - A code search rule flags any new serialisation of `vec_blob` or crop buffers outside the hub.
- [ ] TASK-116 [hub] Offline mode test
  - With internet cut at the hub for 2 hours: matching and LAN alerts continue, enrolments succeed and stay `pending_notice`, the outbox grows and nothing is lost; when internet returns everything syncs in order, notices send and activations arrive.
  - Automated in `tests/integration/test_offline.py` with a network toggle on the mock cloud, and run once by hand on the Pi.
  - Status 2026-10-18: automated in tests/integration/test_offline.py; the manual run on a Pi is pending.
- [x] TASK-117 [app] Offline states in the app
  - Cashier mode tells "Hub not reachable" apart from "Hub online, internet down (members will activate later)"; Owner mode keeps showing LAN alerts with internet down and says FCM alerts are paused.
  - UI tests for every state.
- [ ] TASK-118 [all] Sentry error tracking
  - `SENTRY_DSN` is wired in the hub (Python SDK), the cloud (Next.js SDK) and the app (Android SDK) with release tags; a before-send scrubber drops phone numbers, names and any binary; the leak guard from TASK-115 covers Sentry.
  - One test event per component is visible in Sentry and noted in docs/MEMORY.md.
  - Status 2026-10-18: hub, cloud and app wired with before-send scrubbers and tests; one real event per component needs a Sentry DSN.
- [x] TASK-119 [cloud] Hub monitoring and ops alerts
  - A scheduled job checks `devices.last_seen_at` every 5 minutes and sends an ops notification when a hub is silent for 15 minutes or reports `locked`; `GET /api/ops/hubs` lists every hub with its status for the Remu team.
  - The schedule runs as a Supabase `pg_cron` job (assumption), because Vercel's Hobby plan allows only daily cron; if Vercel cron is used instead, the Pro plan is required and noted in docs/ARCHITECTURE.md.
  - OPEN: the ops notification channel for the pilot (email, or a WhatsApp message to the Remu team number).
- [ ] TASK-120 [hub] Crash recovery and uptime
  - A corrupt SQLite file is detected at start, backed up and restored from the last good daily copy kept inside the encrypted directory; the daily copy excludes the `templates` and `clips` tables and the clip files, so a restore can never bring back a deleted template; power loss during a write leaves the chain intact (WAL mode, fsync).
  - A test deletes a member, runs the daily copy and the restore, and asserts that no withdrawn member's template exists in any file under `REMU_DATA_DIR`.
  - Elapsed time: uptime over 7 days on the bench Pi is at least 99 percent, measured from health reports, while the next tasks proceed.
  - Status 2026-10-18: corruption detection, restore from a daily copy without templates or clips, and WAL with fsync are tested; the 7-day uptime measurement needs the bench Pi.
- [ ] TASK-121 [all] Latency QA
  - On the Pi with the real gate camera: 20 walk-ins by consenting team members; the time from crossing the door line to the owner phone alert is recorded over LAN and over FCM; target at least 85 percent within 3 seconds.
  - Misses are analysed (camera, hub, network) and recorded in docs/TEST_PLAN.md.
  - Status 2026-10-18: needs the Pi, the real gate camera and 20 consenting walk-ins.
- [ ] TASK-122 [all] Accuracy QA with the consented team set
  - The eval harness runs on a consented pilot-team pair set (recorded with written consent, kept off the repo) plus the public subset; TAR at FAR 0.1 percent and the Not them pairs from testing are reviewed.
  - The threshold ADR is confirmed or amended and the model pack version is pinned.
  - Status 2026-10-18: needs the consented team pair set.
- [ ] TASK-123 [docs] Model pack licence
  - The InsightFace `buffalo_l` and `buffalo_sc` packs are licensed for non-commercial research only (docs/RESEARCH.md section 3, ADR-003). Either a commercial licence is obtained or the hub switches to a commercially licensed pack of the same architecture; a switch re-runs `make eval` against the bars in TASK-021 and TASK-022 and re-pins `model_version`.
  - The outcome is recorded in docs/DECISIONS.md and docs/MEMORY.md; no shop goes live before this closes.
  - OPEN: which licence or pack, its cost, and who signs.
  - Independent: may run in parallel with TASK-112 to TASK-122.
  - Status 2026-10-18: needs a commercial licence or a different pack; no shop goes live before it closes.
- [ ] TASK-124 [all] Full test plan run and release v0.1
  - Every item in docs/TEST_PLAN.md is executed and ticked with date and tester; blockers are fixed; `make check` and CI are green; git tag `v0.1.0` with a release note.
  - The hub image, the app APK and the cloud deployment are the artefacts of the tag; docs/MEMORY.md marks Phase 8 complete.
  - Status 2026-10-18: blocked on the field items above; CI is green and make check runs every component.

Phase 8 exit check: v0.1.0 tagged, security findings closed or accepted in writing, the offline test passes, Sentry live, the model pack licence closed.

## Phase 9: Pilot: onboarding, training, metrics, iteration

- [ ] TASK-125 [docs] Legal pack for the pilot
  - One pack for the lawyer, building on the wording sign-off from TASK-071: the signed-off v1.0 texts, the privacy notice from docs/PRIVACY_AND_CONSENT.md, the consent record format, the processor agreement between Remu and each shop, and the DPDP mapping (notice, consent, retention, erasure, children, security safeguards) against the dates 2026-11-13 and 2027-05-13.
  - The sign-off, or the required changes, is recorded in docs/DECISIONS.md; every OPEN item in docs/PRIVACY_AND_CONSENT.md section 17 is closed or accepted in writing.
  - OPEN: the review date; no shop goes live before sign-off.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-126 [infra] Pilot kits
  - Between 5 and 10 kits: Pi 5 kit (9,000 to 10,000 rupees), optional gate IP camera (1,500 to 2,500 rupees), counter phone with stand and charger (0 to 6,000 rupees), standee (about 200 rupees); each kit's actual cost is recorded and stays at or under 15,000 rupees.
  - Each Pi is provisioned with TASK-105 and labelled with its pairing code.
  - Independent: may run in parallel with TASK-125 and TASK-127.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [x] TASK-127 [cloud] Pilot metrics job
  - A nightly job computes per shop and in total every success criterion in docs/PRD.md section 11: face-membership rate among billed adults with a phone (target at least 60 percent), notices delivered within 2 minutes (at least 90 percent), alerts within 3 seconds (at least 85 percent), Not them per shop per day (at most 1), Greeted rate (at least 50 percent), stored photos (zero, from the privacy scanner), members with a delivered notice (100 percent), STOP deletions within 1 hour (100 percent), hub uptime (at least 99 percent) and matching through outages, with hardware cost and setup time entered by hand.
  - `GET /api/ops/metrics?from=&to=` returns the numbers and a CSV; the Cashier mode "skip face" count (TASK-048) feeds the membership rate denominator.
- [ ] TASK-128 [docs] Owner and cashier training material
  - A one-page guide in Hindi and English: the cashier line, when to skip the tap (a shrug, walking away, looks under 18), households, remove member, what the owner does on an alert, and what to say when a customer asks about the camera.
  - A 30-minute training plan with a practice run on the counter phone: the trainer enrols their own number, receives the real notice, is matched once at the gate, and is removed afterwards. No test flag exists; a member is never enrolled or activated without a delivered notice.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-129 [all] First shop onboarding
  - One shop in the pilot city: install, pair, aim, train, go live; the setup time and every issue are recorded; the first real member is enrolled and the first real alert greeted within the first week.
  - The go-live checklist in docs/PILOT_PLAN.md is ticked with dates.
  - Depends on: TASK-125, TASK-126, TASK-128.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-130 [all] Remaining shops onboarding
  - The remaining shops (5 to 10 in total, jewellery or textile, one city) go live over two weeks with the same checklist; per-shop setup time is recorded against the 60-minute target.
  - Kit cost and setup time per shop go in docs/PILOT_PLAN.md.
  - Elapsed time: the two weeks run alongside TASK-131.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-131 [all] Weekly pilot review and fixes
  - Every week: the metrics from TASK-127 against the success criteria in docs/PRD.md section 11, Not them pairs reviewed, bugs triaged, and small fixes shipped to the cloud (Vercel), the app (APK or Play internal track) and the hub through the update channel decided in TASK-098; each week's notes go in docs/PILOT_PLAN.md.
  - Threshold changes go through the eval harness and an ADR, never a direct edit.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-132 [all] Day 30 checkpoint
  - A mid-pilot report: each criterion met or not, the top issues, and what changes for the second half; decided items become new tasks under this phase with the next free ids.
  - OPEN: the pilot start date, which fixes day 30 and day 60 on the calendar.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-133 [all] Day 60 pilot report
  - Final numbers for every criterion in docs/PRD.md section 11, per shop and in total; owner interviews on willingness to pay 500 to 1,500 rupees per shop per month; kit cost per shop; a go or no-go recommendation for the next phase.
  - The report is the results section of docs/PILOT_PLAN.md plus a short summary for the founder.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.
- [ ] TASK-134 [docs] Pilot documentation update
  - docs/PRD.md success criteria get the measured values next to the targets; docs/RESEARCH.md gets the field accuracy numbers.
  - docs/DECISIONS.md records every pilot decision, including the threshold and model pack in use at day 60.
  - docs/MEMORY.md marks Phase 9 complete and points to the Later section.
  - Status 2026-10-18: pilot task: needs the shops, the kits and the people of Phase 9.

Phase 9 exit check: the day 60 report is delivered, every criterion is measured, and go or no-go is recorded.

## Later

Out of scope for v0.1, in the brief's order of likelihood. None of these is a task. Each needs its own PRD section and phase before any code is written.

- Phone-as-hub (Tier 0: the counter phone decodes the gate RTSP and matches, zero extra hardware)
- Bluetooth thermal printer bill with the member line
- Owner web dashboard
- Razorpay subscription billing
- SMS fallback for customers without WhatsApp
- POS integrations (Vyapar, Tally, Petpooja)
- DVR playback jump to a visit
- WhatsApp broadcast and offer campaigns
- Loyalty points ledger beyond the 5 percent next-visit flag
- Multi-shop chains
- iOS app
- Hailo acceleration (Hailo-8L, about 6,000 rupees)
- Additional languages beyond Hindi and English
- The optional audio or video consent clip at enrolment
- Fuzzy commitment or homomorphic matching
- Matching on the counter camera stream (needs a new notice version and purpose)
