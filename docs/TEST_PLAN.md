# Remu Test Plan

Purpose: defines what "working" means for Remu and is the checklist every task, review and pilot sign-off runs against.

Written 2026-10-18. Every number here comes from the brief. When a test and the brief disagree, the test is wrong.

## 1. How tests run

Commands:

- hub: `cd hub && make setup`, `make test`, `make lint`, `make typecheck`; `make fake-camera` (mediamtx looping the sample video) and `make dev`.
- app: `cd app && ./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug`, `./gradlew connectedAndroidTest`; Maestro flows in `app/maestro/`.
- cloud: `cd cloud && npm run lint`, `npm run typecheck`, `npm test`, `npm run build`; `npx playwright test` once the owner web dashboard exists.
- root: `make check` runs lint, typecheck and tests for every component. A task is not done until it passes.

Assumptions: hub tests live in `hub/tests/`; the root `tests/` folder holds `tests/integration/`, `tests/e2e/`, `tests/eval/` (the harness) and `tests/unit/`; `make eval` runs the harness.

Fixture faces: a 60-second video of 8 team volunteers who all signed the consent form, 5 enrolled as members and 3 never enrolled, kept outside the repo, fetched by `make setup`, never committed.

OPEN: the written consent form the volunteers sign for the fixture video.

Levels:

- Unit: one function, no network, camera or cloud. Every commit.
- Integration: one component with real SQLite, fake RTSP, local Supabase, mocked WhatsApp and FCM. Every commit.
- End-to-end: fake camera to hub to local cloud to app on an emulator. Nightly in CI and before each phase closes.
- Manual: a person, this checklist, a real phone and hub. Before Phases 3, 5, 7 and 8 close.
- Field: a pilot shop, real DVR, real customers. Phase 9, 60 days.

Ids are `T-<flow letter>-<number>`; automated tests carry the id in their name so `grep T-B-03` finds it; manual tests start at 20.

## 2. Flow A: Setup and pairing

Automated:

- T-A-01: the hub generates its pairing code at `remu-hub init` and `scripts/provision_hub.py` registers it in `devices.pairing_code`; `POST /api/devices/pair` with that code and an owner session links the device once. Single use: a second use returns 409. OPEN: the pairing direction (hub code typed into the app) is confirmed by the founder, docs/ARCHITECTURE.md flow A; this test follows that decision.
- T-A-02: a wrong code gets 401 and writes nothing; the right code links the `devices` row, the hub's next `POST /api/devices/claim` poll returns the device token, the row holds `token_hash`, `hub_version` and `last_seen_at`, and the raw token exists only on the hub. On the LAN, `POST /pair` with the same code returns the app token and the certificate fingerprint once.
- T-A-03: mediamtx is configured with two paths that match known DVR URL patterns (CP Plus, Hikvision, Dahua); the pattern probe in `remu-hub discover` finds both, since RTSP has no path listing; the chosen one lands in `REMU_RTSP_GATE_URL`.
- T-A-04: the app refuses a hub whose certificate differs from the one pinned at pairing, with a clear error.

Manual:

- T-A-20: a non-engineer sets up a shop from the box with Owner mode and the printed guide on a real CP Plus, Hikvision or Dahua DVR, aiming the camera until a face box shows at eye level in the preview. Under 60 minutes, timed, per shop.

OPEN: which DVR models to buy for the test bench.

## 3. Flow B: Enrolment at billing

Automated:

- T-B-01: `POST /enrol` (adult crop, landmarks, phone, bill, staff id) writes rows in `members` (`pending_notice`), `templates` (scrambled `vec_blob`, `model_version`), `purchases`, `consent_records` (`verbal_at_counter`, `hi-IN-v1.0`, `wa-v1.0`, `adult_check` `passed`) and `events_outbox` (the notice request).
- T-B-02: estimated age under 18 is refused: no row, no outbox entry, nothing on disk. An `override` field is rejected.
- T-B-03: a request carrying an `embedding` field is rejected. Only the hub embeds.
- T-B-04: the stored `vec_blob` is not the raw embedding: raw scores near zero against it, the same embedding scrambled with the shop key scores above 0.45.
- T-B-05: after two enrolments the second record's `prev_hash` equals the first's `record_hash`, and recomputing `record_hash` from the fields reproduces it.
- T-B-06: the phone is stored as `phone_hash` and `phone_enc` only. The plain number is not in the SQLite file.
- T-B-07: two adult faces as household "Sharma family", payer Rajesh: one `households` row with `payer_member_id`, two `members` rows sharing `household_id`, two consent records. A third face under 18 is refused by index; the adults still enrol.
- T-B-08: the cashier skips the face tap and saves the bill. `purchases` row saved, no template, no consent record.

OPEN: how a phone-only member is stored (which table and status), so the pilot can count the 60 percent conversion.
OPEN: what happens when an enrolled face already matches an active member above 0.45 (merge, replace, or warn the cashier).

Manual:

- T-B-20: Cashier mode shows the script line exactly: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.) with the English line under it.
- T-B-21: with three people at the counter phone the largest, closest face is pre-selected; a tap moves the selection, a second tap adds a face to the household. Hub unreachable: offline indicator, bill fields kept.

## 4. Flow C: Notice delivery

Automated (cloud, Vitest, Meta API mocked):

- T-C-01: an outbox event for a new member sends the template from `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI` or `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN` by the shop's notice language in `settings_json` (member language choice is OPEN in docs/PRIVACY_AND_CONSENT.md) and writes `whatsapp_messages` with `wamid` and status `sent`.
- T-C-02: the rendered body equals the brief text with `{shop_name}`, `{first_name}` and `{date}` filled, in Hindi and English, button label "👍".
- T-C-03: a signed `delivered` webhook sets `whatsapp_messages.status` `delivered`, `members.status` `active`, `whatsapp_status` and `delivered_at` in `notice_delivery_json`, and sends the activation to the hub.
- T-C-04: a `sent` or `read` webhook without a prior `delivered` leaves the member `pending_notice`. Only `delivered` activates. Read receipts can be switched off, so `read` is recorded on `whatsapp_messages` but never activates.
- T-C-05: a `failed` webhook (no WhatsApp) leaves the member `pending_notice` with status `failed`; Cashier mode member search shows "notice not delivered" (assumption).
- T-C-06: a thumbs-up button reply stamps `thumbs_up_at` on the consent record.
- T-C-07: the same webhook delivered twice causes one state change.
- T-C-08: hub offline when `delivered` arrives: the activation waits in the cloud, applies on reconnect, and only then is the member matched.

OPEN: whether `delivered_at` and `thumbs_up_at` append a new chained record or update `notice_delivery_json` in place; the append-only rule points to a new record.
OPEN: retry policy after a `failed` status.

Manual:

- T-C-20: enrol a test number on the real WhatsApp Business account. The message arrives with the exact text and the 👍 button; tapping it produces the webhook. Of ten enrolments, at least 9 arrive within 2 minutes.

## 5. Flow D: Gate recognition

Automated:

- T-D-01: the fake camera loops the sample video with one active member: a `visits` row with `score` at or above 0.45, `frames_agreed` at least 3, `camera` `gate`, `clip_id` set, an alert event in `events_outbox`, and under `make dev` an alert on the console.
- T-D-02: synthetic sequences. Matches on 3 of 5 consecutive frames alert; 2 of 5 do not.
- T-D-03: the same member seen again, fake clock: no second alert inside 10 minutes, an alert at 10 minutes and 1 second.
- T-D-04: a `pending_notice`, `withdrawn` or `expired` member walks past. No visit, no clip, no alert.
- T-D-05: only non-member faces for 10 minutes: no new row or file, data directory hash unchanged apart from the log, frame buffers released within 5 seconds.
- T-D-06: a clip is 5 seconds of H.264, encrypted at rest (`ffprobe` cannot read the stored file), `expires_at` is `created_at` plus 30 days.
- T-D-07: the alert event holds `member_id`, `visit_id`, `ts`, `score`, `frames_agreed`, `camera` and nothing else; the cloud builds the card from mirrored tables (assumption).
- T-D-08: Greeted sets `visits.greeted` true with `greeted_by`; Not them sets `visits.not_them` true. Both sync to the cloud.
- T-D-09: an alert arrives exactly once: LAN WebSocket on shop WiFi, FCM (mocked) on mobile data.

Manual:

- T-D-20: door line to phone buzz by stopwatch, 20 trials on the Pi 5, at least 17 under 3 seconds. The card reads "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}" with Greeted and Not them buttons.
- T-D-21: seen from the door as a customer: no screen, no beep, no light, no visible reaction.
- T-D-22: one household adult walking in raises the household alert with its label. A clip plays in Owner mode on shop WiFi and fails cleanly on mobile data.

## 6. Flow E: Withdrawal

Automated:

- T-E-01: a STOP webhook ("STOP", "stop", " Stop ") sets `members.status` `withdrawn` in the cloud; the hub deletes the `templates` row and appends a chained consent record with `withdrawn_at` and `template_deleted_at`, within 1 hour on a fake clock.
- T-E-02: Remove member in Cashier mode or Owner mode gives the same result at once, even with the cloud unreachable; the cloud learns through the outbox.
- T-E-03: a withdrawn member walking past is never matched. The original consent record still exists and the chain verifies end to end.
- T-E-04: a STOP arriving while the hub is offline keeps its time in the cloud; the hub deletes within 1 hour of reconnect and the gap shows in the export.

OPEN: whether withdrawal also deletes the member's clips and visits at once, or clips follow the 30-day rule.
OPEN: how to report a deletion that missed the 1-hour window because the hub was offline.

Manual: T-E-20, reply STOP from a test number on real WhatsApp; the hub log shows the deletion within 1 hour and the export shows both records.

## 7. Flow F: Expiry

Automated:

- T-F-01: nightly job, fake clock, members last seen 1094, 1095 and 1096 days ago: 1094 untouched; 1095 and 1096 lose the template, get status `expired` and a chained record with `template_deleted_at`.
- T-F-02: a member never seen since enrolment counts from `member_since` (assumption).
- T-F-03: clips at 29, 30 and 31 days: 29 kept; 30 and 31 deleted from disk, `clips` row removed, `visits.clip_id` nulled (assumption).
- T-F-04: a second run deletes nothing; a run missed while the hub was off happens at the next boot (assumption).

Manual: T-F-20, read the nightly job line in each pilot hub log weekly and record the counts.

## 8. Flow G: Consent export

Automated:

- T-G-01: an export for a shop with 50 consent records gives a 50-row CSV with every `consent_records` column and a PDF with the same records.
- T-G-02: recomputed `record_hash` matches every row, each `prev_hash` links, the chain head equals the latest `hash_anchors.chain_head_hash`, and a changed row breaks the check.
- T-G-03: the export is signed with `CONSENT_EXPORT_SIGNING_KEY`; the public key verifies it and one changed byte fails.
- T-G-04: a user of shop A requesting shop B's export gets 403.

OPEN: whether the export shows the owner decrypted phone numbers (to prove whose consent) or `phone_hash` only.

Manual: T-G-20, Export in Owner mode gives PDF and CSV in one tap; Hindi names render in the PDF.

## 9. Flow H: Offline

Automated:

- T-H-01: with `REMU_CLOUD_URL` unreachable, enrolment succeeds with `pending_notice`, active members still match, alerts reach Owner mode over the LAN, the outbox grows.
- T-H-02: when internet returns the outbox flushes in order in batches of up to 200 every 30 seconds, `synced_at` is set, the cloud receives each event once (event id as idempotency key), and queued events survive a hub restart.
- T-H-03: a member enrolled offline becomes `active` only after the cloud confirms delivery and the hub has received the activation.
- T-H-04: after a power cycle while offline, the hub boots, reports "locked, waiting for cloud" in `GET /health`, does not crash, and resumes when internet returns.

OPEN: a hub that reboots during an outage cannot unlock its data directory without the cloud token, so matching stops until internet returns; SECURITY.md decides whether a time-limited cached token is allowed, and T-H-04 asserts that choice.

Manual: T-H-20, pull the internet cable at a pilot shop for 2 hours of trading: alerts continue on WiFi, enrolments queue, notices go out after reconnect, those members then activate.

## 10. Evaluation harness (`tests/eval/`)

Datasets. Development: a public face-pair benchmark subset with age-gap, pose and low-light subsets, downloaded by the harness, never committed. Pilot: consented pairs added in Phase 9. Both live outside the repo, passed as a path argument.

OPEN: which public benchmark, and whether its licence allows this use.
OPEN: the protocol and consent wording for the pilot pair set.

Metrics. The harness scores every pair on scrambled templates and reports true accept rate at a false accept rate of 0.1 percent, the threshold at that rate, a ROC table as CSV, and the same per subset. It also reports true accept rate and false accept rate at the deployed `REMU_MATCH_THRESHOLD` (0.45), overall and per subset, since the hub runs at that fixed value and not at the proposed one. A raw-embedding run must match within 0.1 points, since the rotation preserves cosine.

Threshold selection. The harness proposes the threshold at a false accept rate of 0.1 percent. `REMU_MATCH_THRESHOLD` stays 0.45 until a task changes it with the harness report attached; lowering it also needs 14 pilot days with `visits.not_them` under 1 per shop per day (assumption).

Pass criteria. On the development set, at the deployed threshold of 0.45: false accept rate at or under 0.1 percent, true accept rate at least 95 percent overall and 85 percent on the ageing subset, and no drop of more than 0.5 points against the last merged run. The row at the proposed threshold is reported beside it for the threshold decision.

OPEN: the founder confirms these starting bars.

Rule. The harness must pass before any change to `REMU_MODEL_PACK`, the `buffalo_l` or `buffalo_sc` pack version, `REMU_MATCH_THRESHOLD`, face alignment or the matching code is merged; CI runs it on pull requests touching those paths and blocks the merge on failure.

## 11. Accuracy field tests

Five consented volunteers enrolled at a bench shop, 10 passes per condition, recording alert yes or no, seconds to alert, and score.

- Daylight, evening tube light, backlit door: alert in all three; backlit may miss, noted in the camera placement advice.
- Face-on, 15 and 30 degrees: alert at 0 and 15; at 30 a miss is accepted, a false alert is not.
- Glasses on and off, enrolled without: alert.
- Beard growth, enrolled clean-shaven and tested after 4 weeks: alert.
- Surgical mask: a miss is expected, a false alert is a failure.
- 2, 4 and 6 metres: alert at 2 and 4, record 6.
- Three members walking in together: three alerts, one each, no duplicates.
- Two members with a non-member between them: two alerts, nothing for the non-member.

False alerts across the session: at most 1, the pilot limit per shop per day.

## 12. Performance tests

On the Raspberry Pi 5 reference kit, `buffalo_l`, gate stream 640 by 360 at 5 to 8 frames per second.

- T-X-01: first frame containing the member to the alert event leaving the hub, 50 trials. 85th percentile at or under 3 seconds.
- T-X-02: at least 5 frames per second sustained through detect, embed and match; below that, `buffalo_sc` is used with its harness report attached.
- T-X-03: over 1 hour of trading, steady-state average under 60 percent of the four cores and under 1.5 GB resident on the 4 GB kit (ARCHITECTURE section 14); `vcgencmd get_throttled` reports `0x0`.
- T-X-04: kill and restart mediamtx, or pull the camera cable on the real DVR. The hub reconnects within 10 seconds, logs the gap and keeps running.
- T-X-05: 24-hour soak on the fake camera: memory growth under 10 percent, no leaked file descriptors or ffmpeg processes, a match at hour 24 alerts normally.
- T-X-06: after `kill -9`, systemd restarts `remu-hub.service` after its 10-second delay and the service is back within 30 seconds (TASK-099).

## 13. Privacy tests

They run in CI and on every pilot hub at day 30 and day 60.

- T-P-01: `scripts/privacy_scan.py` walks the hub data directory and logs after 100 enrolments and 500 matches: no JPEG, PNG, WebP or BMP magic bytes, no raw H.264 start codes (clips are encrypted), no enrol request body, SQLite included.
- T-P-02: the same scanner over a cloud database dump finds nothing; no `bytea` column exists and no column is named `vec_blob`, `template`, `image` or `clip`.
- T-P-03: `multipart/form-data` or an image content type on any cloud route gets 415 before any handler runs and is not logged; a JSON body with a key named like `vec_blob`, `template`, `image`, `crop` or `clip`, or a base64 blob over 1 KB, gets 400. Body limits are per route: 1 MB on `POST /api/hub/events` so an outbox batch of consent records at about 1 KB each fits, 64 KB on every other route (assumption). Over the limit gets 413.
- T-P-04 to T-P-08 are flow tests that carry privacy rules: T-D-04 (`pending_notice` never matched), T-E-01 (STOP deletes within 1 hour, logged), T-F-01 and T-F-03 (expiry at 1095 and 30 days), T-G-02 (export matches chain and anchor), T-D-05 (nothing for non-members).
- T-P-09: run every flow at `REMU_LOG_LEVEL` `DEBUG`, then grep hub, cloud and app logs and Sentry payloads: no 10-digit phone number, fixture `display_name`, notes text or embedding values.
- T-P-10: every outbound hub request, recorded through a proxy during a full run, carries no `vec_blob`, 512-float array, crop or clip.
- T-P-11: an age gate refusal leaves no row, no file and no log line with the face.

## 14. Offline tests

Flow H covers these. Phase 8 gates: T-H-01 (enrol and match with internet down), T-H-02 (outbox flush on reconnect), T-H-03 (activation only after delivery); T-H-20 repeats them in a pilot shop.

## 15. Security tests

SECURITY.md owns the threat model, device token, data directory unlock, certificate pinning, secrets and the Phase 8 review. It relies on T-A-02 and T-A-04 (pairing, pinning), T-H-04 (locked data directory), T-C-03 and section 17 (webhook signatures), T-G-03 and T-G-04 (signing, tenant isolation) and T-P-03 (payload rejection). `make check` also runs `pip-audit` and `npm audit` (assumption). Phase 8 closes only after its checklist is run by hand on a Pi 5 and a Vercel preview deployment.

## 16. Android tests

- Unit (`./gradlew test`): status mapping, rupee and date formatting, the card text builder (brief format, with and without a household, with empty notes), the pinned certificate loader.
- Screen sizes: screenshots of every Cashier mode and Owner mode screen at 360dp, 411dp and a 10-inch tablet: no clipped or overlapping text in either language, camera preview at its aspect ratio.
- Hindi: a string resource test checks the script line, notice and standee text against the brief byte for byte. Conjuncts and nukta such as "पहचान" (pehchaan) and "ज़रूरत" (zaroorat) render on an Android 8 (SDK 26) emulator and a real phone. The language switch changes every screen.
- Cashier flow (Compose UI tests or Maestro): PIN login, preview with boxes and the pre-selected face, phone, amount, items, occasion and notes, Make member, confirmation; the hub request carries crops with landmarks, phone, bill and `staff_id`. Also offline indicator, member search, Remove member with confirmation, household editing.
- Owner flow: inject an alert over LAN WebSocket and over FCM; the card shows, Greeted and Not them call the right endpoint, the member card shows purchases, notes, timeline and clips on LAN; settings, staff PINs, device status, Export and language switch reachable.
- Permissions and power: a clear message when camera permission is denied; plugged in, the screen stays on in Cashier mode.

OPEN: which two real phones to buy, a cheap Android 8 counter phone and a current owner phone.

## 17. Cloud tests

Vitest with a local Supabase.

- Webhook signature: `X-Hub-Signature-256` HMAC with `WHATSAPP_APP_SECRET`; a bad or missing signature gets 401 and no write. The GET challenge needs `WHATSAPP_VERIFY_TOKEN`.
- Row Level Security: shop A's user querying shop B's `members`, `consent_records` or `visits` gets zero rows; the anon key cannot read `consent_records`; `SUPABASE_SERVICE_ROLE_KEY` is used only in server routes.
- State transitions, every pair tested, the same four as docs/ARCHITECTURE.md section 5. Allowed: `pending_notice` to `active` (delivered), `pending_notice` to `withdrawn`, `active` to `withdrawn`, `active` to `expired`. Rejected: `pending_notice` to `expired`, `withdrawn` to `active`, `expired` to `active`, `expired` to `withdrawn`. OPEN: a pending window (proposed 7 days) after which an undelivered notice expires the member is a proposal only, docs/ARCHITECTURE.md section 5; if the lawyer accepts it, `pending_notice` to `expired` moves to the allowed list here, in T-C-05 and in TASK-089 in one change.
- Hash anchors: the nightly anchor lands in `hash_anchors`; a mismatch against the mirrored chain raises a Sentry alert.
- FCM relay: an alert event goes to that shop's owner devices only.
- Region: config check, Supabase project in ap-south-1, Vercel functions in bom1.

OPEN: whether a withdrawn member who rejoins becomes a new member or reactivates the old one.
OPEN: whether the FCM payload carries the card text or only ids (the app then fetches the card), as it passes through Google's servers.

## 18. Pilot acceptance checklist

The pilot is 60 days in 5 to 10 jewellery or textile shops in one city. Each line maps to a success criterion in the brief; all must pass.

Gates before day 1: lawyer sign-off on the v1.0 script and notice; Meta template approval; T-P-01 to T-P-11 green on each hub; T-A-20 under 60 minutes per shop.

- Face membership: face members divided by phone members over 60 days, from the cloud mirror. At least 60 percent.
- Notice delivery: `whatsapp_messages.status_ts` minus `consent_records.ts`. At least 90 percent within 2 minutes.
- Alert speed: hub timing per visit plus T-D-20 samples per shop. At least 85 percent of returning active members within 3 seconds.
- False alerts: `visits.not_them` per shop per day. At most 1.
- Greeted: `visits.greeted` divided by alerts. At least 50 percent.
- Zero stored photos: T-P-01 and T-P-02 on every hub at day 30 and 60. Zero findings.
- Consent coverage: active members without a consent record whose `whatsapp_status` is `delivered`. Zero.
- STOP: `template_deleted_at` minus the STOP time, every withdrawal. 100 percent within 1 hour.
- Uptime: health reports, T-X-06 restart, T-H-20 outage. At least 99 percent, matching continues offline.
- Hardware: receipts per shop. At or under 15,000 rupees.
- Setup: T-A-20 per shop. Under 60 minutes.

OPEN: the pilot city and the pilot start date.
