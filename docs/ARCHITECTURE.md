# Remu Architecture

Purpose: HOW Remu works: the components, the data, the flows between them, and the rules every change must keep. WHAT and WHY are in docs/PRD.md. The brief of 2026-10-18 wins over anything written here.

## 1. Overview

Three things we build (hub, app, cloud) and two services we rent (WhatsApp Cloud API, Firebase Cloud Messaging). All face work happens on the hub inside the shop. The cloud never sees a face.

```
 SHOP LAN (no inbound internet ports)           |  INTERNET (India region only)
 [DVR / NVR or gate IP camera]                  |
      | RTSP, H.264 sub-stream, detect 5 to 8 fps|
      v                                         |
 +--------------------------+  HTTPS, outbound only, device token  +-------------------------+
 | HUB (Raspberry Pi 5)     | ---------------------------------> | CLOUD (Vercel bom1 +    |
 | decode, detect, embed,   |  consent records, members,         | Supabase ap-south-1)    |
 | scramble, match, store,  |  purchases, visits (no clip_id),   | accounts, pairing,      |
 | clip, alert              |  WhatsApp requests, hash anchor,   | mirror, member status,  |
 | SQLite, encrypted dir    |  health                            | export, FCM relay       |
 | NEVER LEAVES: face crops,| <................................. +-------------------------+
 | embeddings, templates,   |  pulled: activation, withdrawal,      |              |
 | clips                    |  notice status, config                | FCM (ids)    | template send
 +--------------------------+                                       v              | <- webhooks
     ^               ^                                         +---------+        v
     | HTTPS + WS    | HTTPS + WS  (LAN, pinned cert)          |   FCM   |  +--------------------+
 +---------------+ +------------------+                        +---------+  | WhatsApp Cloud API |
 | COUNTER PHONE | | OWNER PHONE      | <-- alert push (off LAN) --+        +--------------------+
 | app, Cashier  | | app, Owner mode  |                                       | notice + button
 | mode: crop +  | | alert card,      |                                       | STOP reply
 | landmarks,    | | member card,     |                                       v
 | phone, bill,  | | clips (LAN only),|                                [member's WhatsApp]
 | notes -> hub  | | Greeted/Not them |
 +---------------+ +------------------+
```

Never leaves the hub: face crops (freed once embedded), raw embeddings, templates (`templates.vec_blob`), clips. The cloud never receives image bytes. It holds names, encrypted phone numbers, bills, visit summaries, consent records and device records.

## 2. Components

### 2.1 Hub

Does: the face pipeline (section 7), enrolment with the age gate, hash-chained consent records, visits and clips, the LAN API, the `events_outbox` queue, the nightly jobs.

Technology: Python 3.11, OpenCV with the FFmpeg backend, InsightFace `buffalo_l` (fallback `buffalo_sc`) on ONNX Runtime CPU, numpy, FastAPI, SQLite, `ruff`, `mypy`, `pytest`, `make`. Reference hardware: Raspberry Pi 5, 4 GB or 8 GB. Any Linux x86 or ARM box works.

Never does: send an image, crop, embedding, template or clip off the device; open an inbound internet port; match a member whose status is not `active`; keep anything about a non-member face; embed a face the age gate refused.

### 2.2 App

One Android app, two modes chosen at login.

Cashier mode: camera preview with a box on every face, the largest pre-selected, tap to change or add household adults, fields for phone, bill, items, occasion and notes, the script line in Hindi and English, one "Make member" button, member search, purchases, remove member, household editing, offline indicator.

Owner mode: alerts by FCM and LAN WebSocket, the alert card with "Greeted" and "Not them", member card with purchases, notes, timeline and clips (LAN only), settings, staff PINs, device status, consent export, Hindi or English, pairing.

Technology: Kotlin, Jetpack Compose, minimum SDK 26, CameraX, ML Kit face detection (boxes and crops only), OkHttp with the pinned hub certificate, Retrofit, in-memory state only, DataStore for session and mode, Firebase Messaging, Gradle.

Never does: run a recognition model; keep a crop after the enrolment request is sent; keep a database of its own (no Room, no SQLiteOpenHelper, per docs/RULES.md); hold the hub's database; talk to the hub without the pinned certificate; play a clip off the LAN.

### 2.3 Cloud

Does: shop accounts and users, device pairing and tokens, the data unlock token, the mirror of non-biometric tables, chain checks and nightly hash anchors, WhatsApp sends and webhooks, member status transitions, FCM relay, signed consent export (PDF and CSV), hub health.

Technology: Next.js 15 app routes on Vercel (bom1), TypeScript, Supabase Postgres with Row Level Security and Supabase Auth (ap-south-1), Vitest, Playwright once the dashboard exists.

Never does: accept image bytes on any route; store a face image, template or clip; call into the hub; send anything but an approved template; mark a member `active` without a delivery receipt.

### 2.4 WhatsApp

Meta WhatsApp Cloud API, one approved utility template per language (`WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN`, `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI`): invoice line, member notice, quick-reply button. The cloud sends; webhooks return sent, delivered, read, the thumbs-up tap and STOP. Delivery gates activation. A number without WhatsApp cannot be activated in MVP.

Never does: carry more than the template text, shop name, first name and date; receive a photo.

### 2.5 Hardware

Existing DVR or NVR with RTSP (CP Plus, Hikvision, Dahua) or a dedicated gate IP camera, face-on at eye level. Raspberry Pi 5 kit with the active cooler and the RTC battery. A spare Android phone on a stand at the counter. A standee. Total at or under 15,000 rupees per shop. The bill of materials with prices is in docs/PILOT_PLAN.md section 3.

Never does: show the customer anything at the gate. No screen, no light, no sound.

## 3. Repository layout

The top-level tree (`hub/`, `app/`, `cloud/`, `tests/`, `scripts/`, `docs/`, `.cursor/rules/`) is drawn once, in README.md. This section covers the inside of each component.

Commands are in CLAUDE.md. `make check` at the root runs lint, typecheck and tests for every component.

### 3.1 Hub internals

Module names follow TASKS.md.

```
hub/                 pyproject.toml, Makefile, models/ (gitignored), tests/
└── remu_hub/
    ├── settings.py  REMU_* variables, defaults, validation, one typed settings object
    ├── video/       source.py (OpenCV RTSP reader, reconnect, throttle)
    ├── faces/       models.py (pack loader), detect.py (SCRFD), embed.py (align and ArcFace), age.py, scramble.py (matrix from the shop key)
    ├── match/       gallery.py (active templates, cosine), decide.py (3 of 5, cooldown)
    ├── pipeline.py  wires source, detect, embed, scramble, match and decide into one loop
    ├── db/          schema.sql, migrations/, consent.py (chain writer), members.py (state machine), crypto.py
    ├── clips/       recorder.py, encrypt.py
    ├── api/         app.py (FastAPI, HTTPS, tokens, staff PINs), routes/, GET /ws
    ├── sync/        outbox.py, client.py (flush), inbox.py
    ├── jobs/        expire_members.py, cleanup_clips.py, delete_templates.py, anchor.py, health.py, scheduler
    └── cli.py       remu-hub init | db migrate | models download | staff add | consent verify | consent export | discover | demo | jobs run | status
```

Dependency direction: `api`, `jobs` and `cli` call `db`, `match`, `faces` and `sync`. `faces` knows nothing about the database. `db` knows nothing about HTTP.

### 3.2 App internals

```
app/app/src/main/java/com/remu/app/
├── core/      theme, navigation, strings in values/ and values-hi/
├── data/      repositories and in-memory state (members, visits, alerts); DataStore (session, mode); no database
├── network/   hub/ (pinned OkHttp, Retrofit, WebSocket), cloud/ (Supabase, Retrofit), fcm/
└── feature/   login/, pairing/, enrol/, members/, alerts/, timeline/, export/, settings/
```

One package per screen with its composables, one ViewModel and one UI state class. Screens observe the ViewModel and never touch OkHttp or SQLite. The app has no database: what a screen needs is held in memory and fetched again from the hub or the cloud.

OPEN: the applicationId is assumed to be `com.remu.app` until the founder confirms the domain Remu will own.

### 3.3 Cloud internals

Route and file names follow TASKS.md.

```
cloud/
├── src/app/api/
│   ├── health/                      GET liveness
│   ├── shops/                       POST create shop (owner app)
│   ├── shops/[id]/devices/          GET hub status for the app
│   ├── shops/[id]/consent-export/   POST signed PDF and CSV
│   ├── devices/pair/                POST hub pairing code plus owner session
│   ├── devices/claim/               POST hub claim poll, returns the device token
│   ├── devices/unlock/              POST data unlock token
│   ├── devices/fcm-token/           POST owner phone FCM token
│   ├── hub/events/                  POST outbox batch in
│   ├── hub/inbox/                   GET activation, withdrawal, status, feedback, config out
│   ├── hub/anchor/                  POST nightly chain head
│   ├── webhooks/whatsapp/           GET verify, POST status, button, STOP
│   ├── members/[id]/withdraw/       POST Remove member off the shop WiFi
│   ├── visits/[id]/feedback/        POST Greeted or Not them off the shop WiFi
│   ├── visits/[id]/card/            GET alert card fields after an FCM push
│   └── ops/{hubs,metrics}/          GET hub monitoring and pilot metrics
├── supabase/migrations/             0001_shops.sql onwards, RLS policies beside each table
├── src/lib/                         supabase.ts, auth.ts, whatsapp.ts, fcm.ts, consentChain.ts, memberState.ts, export.ts
└── tests/                           Vitest
```

## 4. Data model

Hub SQLite holds everything. The cloud mirrors the non-biometric tables and adds its own. "Author" is the side that creates rows; the other side receives them through sync. Columns are in the brief, section 10; the migrations copy them exactly.

| Table | Where | Author | Notes |
|---|---|---|---|
| shops | mirrored | cloud | `shop_key_ref` names the key file path, never the key; `settings_json` holds the notice language and the script and notice versions. Threshold, cooldown and frame counts come only from the hub's `REMU_*` variables (section 10) |
| staff | mirrored | hub | `pin_hash` is Argon2id; the hub verifies PINs offline; rows reach the cloud as `staff_upsert` events |
| households | mirrored | hub | `payer_member_id` names the payer |
| members | mirrored | hub creates, both transition | `phone_hash` is sha256 of the E.164 number, lookup only, brute-forceable; `phone_enc` is the protected copy |
| templates | hub only | hub | `vec_blob` is the scrambled 512 float32 vector, 2 KB; no sync serialiser exists |
| consent_records | mirrored, append-only | hub | written only by `append_consent_record()`; triggers reject UPDATE and DELETE |
| purchases | mirrored | hub | one row per bill |
| visits | mirrored without `clip_id` | hub | one row per alert, never per frame |
| clips | hub only | hub | `path_enc` points at an encrypted file in the data directory |
| events_outbox | hub only | hub | every outbound item; `synced_at` set on cloud ack |
| devices | cloud only | cloud | `pairing_code`, `token_hash`, `hub_version`, `last_seen_at`, `data_unlock_token_hash` |
| whatsapp_messages | cloud only | cloud | one row per send, updated per webhook; `raw_webhook_json` kept for disputes |
| hash_anchors | cloud only | cloud | one row per shop per day |
| push_tokens | cloud only, assumed, not in the brief | cloud | `id, shop_id, user_id, fcm_token, updated_at`, for the FCM relay |

Who authors `staff`: the hub, as TASKS.md settles it. TASK-027 creates staff rows on the hub (`POST /staff`) in Phase 2, before the cloud exists; TASK-082 has Owner mode create cashier PINs through the same route, and the row reaches the cloud through the `staff_upsert` outbox event. The `config` inbox message (section 10) never carries staff rows. The hub-created row is the one the hub verifies PINs against.

OPEN: how a phone member (number only, no face) is stored. `members.status` has no value for it, and the v1.0 notice promises door recognition, so a phone-only member needs its own template text. Founder and lawyer, before Phase 3.

Consent record: the field-by-field JSON example lives in docs/PRIVACY_AND_CONSENT.md section 6; the hub stores it and the cloud mirrors it unchanged.

`prev_hash` is the previous `record_hash` for the shop, or `sha256:genesis` for the first record. `record_hash` is sha256 over the canonical JSON (sorted keys, no whitespace, `record_hash` omitted). Delivery, thumbs-up, withdrawal and template deletion each append a new record for the same member carrying the new `notice_delivery_json`, `withdrawn_at` or `template_deleted_at`. Earlier records are never edited. The hub is the only writer of the chain. The cloud never appends a record of its own: it inserts only records it received from the hub, so one per-shop `prev_hash` chain has one author and the nightly anchor check (section 10) can hold. Every record travels to the mirror as a `consent_record` outbox event.

## 5. Member state machine

States: `pending_notice`, `active`, `withdrawn`, `expired`. The last two are terminal.

| From | To | Trigger | Who |
|---|---|---|---|
| (new) | pending_notice | "Make member" tap passes the age gate; consent record written | hub |
| pending_notice | active | WhatsApp `delivered` webhook; cloud writes `member_status` (active) to the inbox; hub applies it and starts matching | cloud, then hub |
| pending_notice | withdrawn | STOP webhook, or "Remove member" tap in Cashier or Owner mode | cloud or hub |
| active | withdrawn | STOP webhook, or "Remove member" tap; template deleted within 1 hour | cloud or hub |
| active | expired | nightly job: unseen for 1095 days; template deleted | hub |

Rules: every transition goes through `db/members.py:transition(member_id, to, by, reason)`, which writes the member row, the chained record, the template deletion where the target state needs it, and the outbox event in one SQLite transaction. The cloud mirrors the logic in `src/lib/memberState.ts`. A terminal state is never left; a withdrawn member who asks to rejoin is enrolled as a new member. A `member_status` (active) message reaching a withdrawn member is logged and ignored. A `failed` notice (number not on WhatsApp) leaves the member `pending_notice`; the app shows "Notice failed", and correcting the number queues a new send. A `pending_notice` member stays `pending_notice` until withdrawn; there is no timed expiry for an undelivered notice. These four transitions are the ones docs/TEST_PLAN.md section 17 tests.

OPEN: proposal only, not in the state machine: a pending window (suggested 7 days) after which an undelivered notice expires the member and deletes its template. The lawyer decides. If accepted, add the transition here, in docs/TEST_PLAN.md section 17 and T-C-05, TASK-089 and docs/DECISIONS.md in one change.

## 6. Key flows

### A. Setup and pairing

1. Owner, app (Owner mode): signs in, creates the shop. Cloud: writes `shops` and the owner's `staff` row.
2. Owner: plugs the hub into power and the DVR's network. Hub: on first boot generates the shop key and a self-signed certificate, advertises `_remu-hub._tcp` on mDNS.
3. Hub: at `remu-hub init` generates a 6-digit single-use pairing code, printed to the console and to `pairing.txt`; `scripts/provision_hub.py` registers it in `devices.pairing_code` and prints the sticker. App: "Add hub"; the owner types the code from the sticker and the app lists hubs found on the LAN. OPEN: the pairing direction. This flow, TASK-028, TASK-057, TASK-105 and docs/PILOT_PLAN.md step 3 have the hub generate the code and the owner type it into the app; the brief's section 8.1 says "pairing by a code shown in the owner app". The founder confirms once, as a new ADR in docs/DECISIONS.md; then this flow, section 9, docs/SECURITY.md 3.1, docs/TEST_PLAN.md T-A-01 and T-A-02 and the three tasks follow it.
4. App: `POST /api/devices/pair` with the code and the owner session. Cloud: links the device to the shop; the code cannot be reused. Hub: has been polling `POST /api/devices/claim` with the code and its serial every 30 seconds since `remu-hub init`, and receives the device token on its next poll.
5. Cloud: stores only `token_hash` and the hub's certificate fingerprint. Hub: stores the token root-only, calls `POST /api/devices/unlock`, unlocks the data directory. App: `POST /pair` on the hub with the same code; the hub returns an app token and its certificate SHA-256 fingerprint, which the app pins after checking it against the cloud's copy.
6. App: `GET /discover` lists the DVR's RTSP URLs; the owner picks the gate stream, aims the camera on `GET /preview` (MJPEG with detection boxes, stops after 10 minutes), saves with `PUT /settings/streams` (assumption, no task names the save route).
7. App: "Add counter phone" calls `POST /pair/invite` (owner token) and shows the one-time invite code, valid 10 minutes, as a QR code; the counter phone scans it in Cashier mode and exchanges it at `POST /pair` for its own app token and the fingerprint. Owner: prints the standee.

### B. Enrolment at billing

1. Cashier: asks for the phone number and says the line, `hi-IN-v1.0`: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.)
2. App (Cashier mode): ML Kit tracks faces; the cashier confirms the face, adds household adults if any, fills phone, amount, items, occasion, notes, taps "Make member".
3. App: `POST /enrol` with one face crop (cut with a generous margin) and five landmarks per face, phone, bill, staff id, household label.
4. Hub (`faces/detect`, `faces/embed`): re-runs SCRFD on each crop and aligns from its own keypoints, so the enrolment crop is aligned exactly as a gate frame; the phone's landmarks are only a fallback hint (section 7). Hub (`faces/age`): age gate per crop. Under 18 refuses that face, no override, nothing stored.
5. Hub: embeds, L2-normalises, scrambles, writes `members` (`pending_notice`), `templates`, `households`, `purchases`, calls `append_consent_record()`, queues `consent_record`, `member_upsert`, `purchase`, `notice_request`. One transaction. Crops freed.
6. Hub: returns the member ids. App: shows "Notice pending".

OPEN: what the hub does when a new crop already matches an active member above 0.45. Proposed: return the existing member for the cashier to confirm.

### C. Notice delivery

1. Hub (`sync/client`): posts the batch to `POST /api/hub/events`.
2. Cloud: upserts the mirror rows, checks the chain link, sends the template in the shop's notice language with shop name, first name and date, writes `whatsapp_messages`.
3. WhatsApp: posts status webhooks to `POST /api/webhooks/whatsapp`. Cloud: checks the signature with `WHATSAPP_APP_SECRET`; a repeated webhook is a no-op.
4. Cloud: on `delivered` (never on `read`), records the fact in `whatsapp_messages`, sets `active` in its mirror through `src/lib/memberState.ts`, and writes `member_status` (active) and `notice_status` (with the `wamid`, `whatsapp_status=delivered` and Meta's `delivered_at`) to the hub's inbox. The cloud appends nothing to the consent chain.
5. Hub (`sync/inbox`): applies the transition, appends the delivery record to the chain with the `notice_delivery_json` from the inbox message, queues `consent_record` so the mirror receives that record, and loads the template into the gallery. Matching starts.
6. Thumbs-up webhook: cloud records it in `whatsapp_messages` and sends `thumbs_up` with the timestamp; hub appends the chained record with `thumbs_up_at`.

### D. Gate recognition

1. Hub (`video/source`): decodes the gate stream at 640 by 360, 5 to 8 frames per second.
2. Hub (`faces/detect`, `faces/embed`): SCRFD detects, aligns to 112 by 112, ArcFace embeds, L2-normalises.
3. Hub (`faces/scramble`, `match/gallery`): scrambles, cosine against every active template; the best score above 0.45 is a candidate.
4. Hub (`match/decide`): the same member in 3 of the last 5 frames fires; the cooldown skips a member alerted in the last 10 minutes.
5. Hub: writes `visits`, starts a 5-second clip, queues `visit_summary` and `alert`, emits the alert on `GET /ws`.
6. Cloud: on `alert`, sends an FCM data message with `shop_id`, `visit_id`, `member_id` to every `push_tokens` row of the shop.
7. App (Owner mode): shows the card from whichever path arrives first, de-duplicated by `visit_id`; off the LAN it fetches the card text from `GET /api/visits/{id}/card`. Owner taps "Greeted" or "Not them". App: `PATCH /visits/{id}` on the LAN, `POST /api/visits/{id}/feedback` off it; the hub gets the truth either way (the cloud's copy reaches it as a `visit_feedback` inbox message).

### E. Withdrawal

1. Trigger: STOP webhook (cloud) or "Remove member" tap (app, either mode).
2. Cloud: sets `withdrawn`, writes `member_status` (withdrawn) to the inbox. For a tap on the LAN (`POST /members/{id}/remove`) the hub transitions first and sends `member_upsert` with status `withdrawn` up; off the LAN, Owner mode calls `POST /api/members/{id}/withdraw` and the hub applies it from the inbox.
3. Hub: deletes the `templates` row and, as an assumption until docs/TEST_PLAN.md flow E closes its OPEN, the member's clips at once; appends a chained record with `withdrawn_at` and `template_deleted_at`; queues it as `consent_record`. Target: within 1 hour of the trigger reaching the hub. `jobs/delete_templates` runs every 5 minutes as the safety net for a crash between transition and commit.

STOP attribution. Every shop's notice goes out from the one Remu WhatsApp number, so a plain-text STOP carries no shop. The cloud withdraws the number from every shop that sent it a notice (lookup by `phone_hash` across shops). A STOP sent as a quoted reply carries `context.id`; the cloud uses it to narrow the withdrawal to the shop whose `wamid` it matches. A STOP from a number that matches no member row yet (the hub was offline and the consent record has not arrived) is stored in `whatsapp_messages` and applied when the member row arrives; the 1-hour clock is recorded from the STOP's own timestamp.

### F. Expiry

1. Hub (`jobs/expire_members`), nightly at 03:00 Asia/Kolkata: `active` members whose later of `last_seen_at` and `member_since` is older than 1095 days go to `expired`; templates deleted; chained records appended; events queued.
2. Hub (`jobs/cleanup_clips`): deletes clips past `expires_at`, 30 days after creation.
3. Both jobs, and `jobs/anchor`, run only when the hub clock is NTP-synchronised (section 12). A hub with an unsynchronised clock skips the night and reports `clock_synced: false` in `GET /health`, so a wrong clock can never delete templates in bulk.

### G. Consent export

1. App (Owner mode): "Export". `POST /api/shops/{id}/consent-export?format=csv` or `format=pdf`.
2. Cloud: reads the mirrored `consent_records`, re-verifies the chain, renders CSV and PDF, signs both with `CONSENT_EXPORT_SIGNING_KEY` (Ed25519, detached signatures), returns a short-lived download link.
3. Hub, with no internet: `remu-hub consent export --csv <path>` on the console, or `GET /consent/export` on the LAN, gives an unsigned CSV with the same columns.

### H. Offline

1. Hub: keeps decoding, matching, alerting over the LAN and enrolling; writes everything to `events_outbox`.
2. No member goes `active` offline; new members show "Notice pending"; Cashier mode shows the offline indicator.
3. Internet returns: `sync/client` flushes the outbox in order, then `sync/inbox` pulls the inbox. Section 11 has the rest.

## 7. Face pipeline

- Capture: OpenCV with the FFmpeg backend opens `REMU_RTSP_GATE_URL` over `rtsp_transport=tcp` and prefers the DVR sub-stream (720p or lower) where one exists. H.264 must be decoded at the stream's native rate, because every P-frame depends on the one before, and the Pi 5 has no H.264 hardware decoder. So a dedicated reader thread decodes every frame and keeps only the newest one; the 5 to 8 frames per second throttle applies to detection, which takes the newest frame, resizes it to 640 by 360 and runs the rest of the pipeline. Sleeping between `VideoCapture.read()` calls is never used, because OpenCV buffers frames and that builds latency instead of dropping frames. A frame older than 500 ms is dropped, never queued. `REMU_RTSP_COUNTER_URL` is optional. OPEN: what the counter stream is for in the pilot (a second matching point tagging visits `camera=counter`, or aiming only); the brief lists it as optional without a purpose. The same OPEN is on TASK-108, and `.env.example` follows the decision.
- Detection: SCRFD from the pack (`det_10g` in `buffalo_l`, `det_500m` in `buffalo_sc`). Faces under 40 pixels across are ignored as too far to embed reliably.
- Alignment: the five SCRFD keypoints warp the face to the standard 112 by 112 ArcFace crop. For an enrolment crop from the counter phone the hub re-runs SCRFD on the crop (sent with a generous margin) and aligns from its own keypoints. ML Kit's landmarks follow a different definition (nose base, not nose tip), so aligning from them would differ from the gate alignment and lower enrol-to-gate similarity. The phone's landmarks are used only as a fallback hint when SCRFD finds no face in the crop. TASK-029 and TASK-044 follow this.
- Embedding: ArcFace (`w600k_r50` in `buffalo_l`, `w600k_mbf` in `buffalo_sc`) gives 512 floats. L2-normalisation makes cosine similarity a dot product.
- Projection: multiply by the shop's orthogonal matrix Q (section 8). The result is the template.
- Matching: one dot product against the matrix of all active templates, threshold `REMU_MATCH_THRESHOLD` = 0.45, chosen to prefer a missed match over a false alert and tuned by `tests/eval/`. Below it the frame is discarded and nothing is written.
- Voting: a member must win in `REMU_FRAMES_REQUIRED` = 3 of the last `REMU_FRAMES_WINDOW` = 5 frames of one face track. Then one visit, one alert. `visits.score` is the mean of the agreeing frames; `frames_agreed` is their count.
- Cooldown: `REMU_ALERT_COOLDOWN_MINUTES` = 10 per member. Inside it a match updates `last_seen_at` only.
- Age gate: the pack's `genderage` model runs on every enrolment crop. Under 18 refuses with no override; nothing is embedded or stored; a counter increments. The gate does not run on the gate stream, because nothing about non-members is kept.
- Non-members at the gate: embedding and track state are dropped at the end of the 5-frame window. No row, no file, no log line with a vector.
- Why only the hub embeds: embeddings from different ArcFace builds do not compare, and a phone and a hub would drift apart in model version. One model in one place keeps `templates.model_version` true and the eval harness meaningful. Changing `REMU_MODEL_PACK` invalidates every template: the harness must pass and every member must be re-enrolled.

OPEN: whether to set the age gate above 18 to absorb the estimator's error. Lawyer and founder.

## 8. Template protection

Derivation: the shop key is 32 random bytes generated on the hub at first boot and kept root-only at `/etc/remu/shop.key`; `shops.shop_key_ref` names that path. `faces/scramble.py` derives `seed = HKDF-SHA256(shop_key, info="remu/template-projection/v1")`, fills a 512 by 512 matrix with Gaussian values from a ChaCha20 stream keyed by the seed, takes its QR decomposition and fixes the signs of R's diagonal so Q is unique. Q is computed once per process and never written to disk.

What the stored vector can do: cosine comparison with other vectors from the same shop, because an orthogonal rotation keeps every angle. Accuracy equals raw matching.

What it cannot do: match against another shop's templates (different Q); serve as a probe in a standard face gallery; be inverted to a face without the shop key (with the key, partial inversion of an ArcFace embedding into a face-like image is possible, see docs/SECURITY.md section 11). A copied database without the key file is 2 KB of rotated numbers per member.

What it does not protect against: an attacker holding both the key file and the database (root on a running hub, or a copied disk plus a live unlock token); the raw embedding that exists in memory for a few milliseconds; linkage inside the shop, where templates are still identifiers. Q is invertible, so a leaked Q unscrambles every template made with it. The known-pair attack: `buffalo_l` is public, so an attacker who holds the template database and can pair a few hundred templates with photos of the members they belong to (names and phone numbers are in the cloud mirror) can compute raw embeddings and recover Q by orthogonal Procrustes, after which every template unscrambles. "Useless without the key" holds only while templates cannot be paired with identities, which is why the hub database and the cloud mirror must never leak together. Sign binarisation stays on the research list as the mitigation. Revocation means a new key, and because raw embeddings are not kept, every member must be re-enrolled. Sign binarisation is a later option that trades some accuracy for a harder inversion. Fuzzy commitment and homomorphic matching stay in docs/RESEARCH.md. None of this replaces consent; it limits the damage of a lost hub.

Data directory: `REMU_DATA_DIR` is a LUKS container keyed by `HKDF(shop_key || unlock_token)`. The cloud derives the unlock token from `HUB_PAIRING_SECRET`, the device id and a generation counter at each request, stores only `data_unlock_token_hash`, and refuses a revoked device. Unpairing bumps the generation. Development uses a plain directory with a warning in `GET /health`.

## 9. Hub to app LAN protocol

Transport: HTTPS on port 8443. The hub generates a self-signed certificate on first boot (SAN `remu-hub.local` plus the LAN address). The app pins the SHA-256 fingerprint at pairing and refuses any other certificate. mDNS `_remu-hub._tcp` advertises the hub with `shop_id` in TXT.

Auth: a bearer app token per paired phone; `GET /health` is the only route without one. Both modes add `POST /staff/verify-pin`, which returns the staff id and role for a correct PIN; `staff_id` is attached to every write. Route names follow TASKS.md.

| Method and path | Use |
|---|---|
| POST /pair | hub pairing code (owner phone) or invite code (counter phone); returns app token and certificate fingerprint |
| POST /pair/invite | owner token; one-time invite code for the counter phone, valid 10 minutes |
| POST /staff, POST /staff/verify-pin | create a staff row (owner), PIN login (both modes) |
| POST /enrol | faces, landmarks, phone, bill, household label, payer index, staff id |
| GET /members, GET /members?phone=, GET /members/{id} | list and search (name, phone, status), member card |
| PATCH /members/{id} | `display_name` and `notes` only |
| POST /members/{id}/remove | "Remove member" (withdrawal) |
| POST /members/{id}/purchases | a new bill for an existing member |
| POST /households, PATCH /households/{id} | create, edit household |
| GET /visits?member_id= | timeline |
| PATCH /visits/{id} | `greeted`, `greeted_by`, `not_them` |
| GET /clips/{id} | decrypted clip stream, LAN only |
| GET /consent/export | unsigned local CSV (same columns as `remu-hub consent export --csv`) |
| GET /discover, GET /preview, PUT /settings/streams | setup: RTSP discovery, MJPEG aim preview, save the gate stream (the save route is an assumption) |
| GET /health | section 15 |
| GET /ws | event stream; the app token goes in the first message |

WebSocket events are JSON keyed by `visit_id` with `type`, `id`, `ts`, `payload`: `alert` (visit id, member id, display name, household label, last visit, last purchase amount and items, notes), `member_updated`, `visit_feedback`, `stream_state`, `sync_state`, `health`. The hub keeps 24 hours of events so a reconnecting app passes `since` in its first message and misses nothing. Ping every 20 seconds.

## 10. Hub to cloud sync

Direction: the hub calls the cloud; the cloud never calls the hub. Bearer `REMU_DEVICE_TOKEN` over HTTPS to `REMU_CLOUD_URL`.

Outbox: every outbound item is a row in `events_outbox` with a ULID `id`, a `type` and `payload_json`, written in the same transaction as the change it describes. Types, as TASKS.md names them: `consent_record`, `member_upsert` (creation and every status change, including `withdrawn` and `expired`), `staff_upsert`, `purchase`, `notice_request` (the only event that carries the plain phone number), `visit_summary`, `visit_feedback`, `alert`, `health`. Template deletion travels as the chained `consent_record` that carries `template_deleted_at`; the nightly anchor goes straight to `POST /api/hub/anchor`. Serialisers are allow-listed per table; `templates` and `clips` have none.

Flush (`sync/client`): every 30 seconds, and at once after `alert` or `notice_request`. Batches of up to 200 rows in id order go to `POST /api/hub/events`. The cloud applies each row with the ULID as the idempotency key (`ON CONFLICT DO NOTHING` on mirrored ids) and returns, per event, `acked` or `rejected` with a reason; the hub sets `synced_at` on an ack. Errors are split in two. A network error or a 5xx retries the whole batch with exponential backoff from 5 seconds to a 5-minute cap, forever; nothing is dropped. A 4xx for one event (schema mismatch after an upgrade, an oversized note) marks that event `failed` with the reason, skips it, and raises `outbox_failed` in `GET /health` so a human looks at it; the events after it still flow, so one bad row never blocks an alert or a withdrawal. Every cloud side effect is keyed on the event ULID: a WhatsApp send, an FCM push or a mirror write for an event the cloud has already processed is a no-op, so a lost ack and a resent batch send nothing twice.

Inbox (`sync/inbox`): after each flush, and at least every 30 seconds, the hub calls `GET /api/hub/inbox?after=<cursor>` and applies `member_status` (active or withdrawn), `notice_status`, `thumbs_up`, `visit_feedback`, `config` and `device_revoked` (lock the data directory and stop; assumed, not in TASKS.md, required by docs/SECURITY.md section 7). The cursor advances only after a successful apply. `config` carries the notice language and the script and notice versions. It never carries staff rows (section 4) or threshold, cooldown or frame counts: the hub's tunables come only from the `REMU_*` environment variables, per docs/RULES.md. OPEN: whether the cloud may ever override a listed tunable; that needs an ADR first.

Anchor: at 03:30 Asia/Kolkata the hub posts `{date, chain_head_hash}` to `POST /api/hub/anchor`. The cloud recomputes the head from its mirror; a mismatch raises a Sentry alert and marks the shop for review. The cloud also checks `prev_hash` on every record as it arrives. Consent events inside a batch are applied in event ULID order and verified by `prev_hash` linkage, never sorted by `ts`, because a hub clock adjustment would reorder them.

## 11. Offline behaviour

Works without internet: decoding, matching, alerts over the LAN WebSocket, enrolment, member search, notes, purchases, "Remove member" (the hub transitions and deletes locally), clips, cashier PIN login, the unsigned CSV export.

Waits for internet: the WhatsApp notice, so no new `active` member; FCM alerts, so an owner off the LAN hears nothing until sync; the consent mirror; the hash anchor; the signed export; STOP replies, which the cloud records at once and the hub learns on reconnect. The 1-hour deletion target counts from when the withdrawal reaches the hub, and the cloud records the delay on the consent record.

Reconnect: `sync_state` goes to `online`, the outbox flushes in order, the inbox is pulled, the app refreshes.

Boot without internet: the data directory key needs the unlock token, so a hub that powers on during an outage reports `locked, waiting for cloud` in `GET /health`, retries every 60 seconds, and cannot match until the connection returns.

OPEN: accept that limit, or cache the unlock token on disk for a bounded window, which weakens the stolen-hub protection because the Pi 5 has no secure element. docs/SECURITY.md owns the decision; needed before Phase 7.

## 12. Deployment

Hub: a Raspberry Pi OS Lite 64-bit image built by `scripts/build_pi_image.sh`: Python 3.11 venv at `/opt/remu/venv` with the package installed from a versioned wheel, the model pack, and a first-boot script. Two systemd units, named as in TASKS.md: `remu-hub.service` runs the API and pipeline as the dedicated `remu` user with `Restart=always`, a 10-second restart delay and a watchdog ping, with `ExecStartPre=+/opt/remu/venv/bin/remu-hub unlock` (the `+` runs the unlock as root while the service itself runs unprivileged, as docs/SECURITY.md 3.1 requires); `remu-hub-jobs.service` runs the job scheduler. `EnvironmentFile=/etc/remu/hub.env` holds the `REMU_*` variables, `/var/lib/remu` is the LUKS container, logs go to journald, NTP is on, and `remu-hub status` shows both units. `scripts/provision_hub.py` registers each hub's pairing code in the cloud and prints the sticker (flow A).

Clock: consent timestamps, the 2-minute notice metric, the 1-hour deletion bound and the 1095-day expiry all depend on the hub clock. The Pi 5 kit includes the RTC battery so a hub that boots offline does not restore a stale clock (the item is on the bill of materials in docs/PILOT_PLAN.md section 3). `timedatectl` must report NTP synchronised before `jobs/expire_members`, `jobs/cleanup_clips` and `jobs/anchor` run; otherwise they skip the night. `GET /health` reports `clock_synced`. The notice delivery metric uses Meta's timestamps on both ends where they exist. Development runs the same package on macOS or Linux against `make fake-camera` (mediamtx looping a sample video) with a plain data directory.

OPEN: the hub update channel for the pilot (a signed wheel pulled by the hub versus manual SSH) and whether SSH stays enabled on pilot hubs.

App: Gradle. `./gradlew assembleDebug` for development, `./gradlew bundleRelease` signed with the upload key, Play internal testing track for pilot phones. `REMU_CLOUD_URL` is a `buildConfigField` per flavour (`dev`, `pilot`). `google-services.json` is never committed.

Cloud: one Vercel project with functions pinned to bom1, production from `main`, a preview deployment per pull request against a separate Supabase preview project. Supabase project in ap-south-1. Migrations run with `supabase db push` from CI, never by hand. Secrets live only in Vercel environment settings.

OPEN: the owner sign-in method for Supabase Auth (phone OTP, which costs SMS, or email and password). Needed before Phase 4.

## 13. Architectural rules

Each rule is checkable in review or by a test.

- Only the hub computes embeddings. No ONNX, InsightFace or ArcFace code exists in `app/` or `cloud/`.
- Cloud code never accepts image bytes. Every route rejects `multipart/form-data` and image content types with 415, and one Vitest test asserts it for every route.
- No sync serialiser exists for `templates` or `clips`. `sync/outbox.py` builds payloads from allow-listed columns only.
- The app has no database of its own: no Room, no SQLiteOpenHelper. The app UI never reads SQLite or OkHttp directly. Screens observe ViewModels; ViewModels call repositories that hold state in memory.
- Every write to `consent_records` goes through `db/consent.py:append_consent_record()`. SQLite triggers reject UPDATE and DELETE on the table.
- Only the hub appends to the consent chain. The cloud mirror inserts only records received from the hub through `consent_record`; no cloud code path creates a consent record.
- Every member status change goes through `db/members.py:transition()` on the hub and `src/lib/memberState.ts` in the cloud. A direct UPDATE of `members.status` fails review.
- Matching reads only templates whose member is `active`.
- Face crops on the hub live in memory and are freed after embedding. No code path writes an image file.
- The age gate runs before embedding at enrolment, and a refusal stores no row.
- Secrets come only from environment variables or the root-only files under `/etc/remu`. None in code, logs, Sentry events or the repository. `.env` is gitignored.
- The hub listens on one port, 8443, on the LAN. All internet traffic is outbound.
- The cloud never calls the hub.
- Every schema change is a migration file under `hub/remu_hub/db/migrations/` or `cloud/supabase/migrations/`.
- Timestamps are stored in UTC and shown in Asia/Kolkata. Record timestamps are ISO 8601 with offset.
- UI strings live in `values/` and `values-hi/`. No string literals in composables.
- Alerts are de-duplicated by `visit_id` in the app. FCM payloads carry ids, never names or notes.
- No change to `REMU_MODEL_PACK` or `REMU_MATCH_THRESHOLD` merges without a passing `tests/eval/` run.

## 14. Performance budgets

Targets, measured in Phase 1 on the laptop and Phase 7 on the Pi 5. None is a measurement yet.

- Entry to alert on the owner's phone: 3 seconds (pilot target: 85 percent of returning active members). Budget: DVR and RTSP latency up to 1.0 second (a dedicated IP camera about 0.3), 3 of 5 frames at 5 frames per second up to 1.0 second, hub processing per frame under 150 ms, LAN WebSocket under 100 ms, cloud plus FCM plus the card fetch under 1.5 seconds.
- Hub processing per detected frame (detect, align, embed, match) under 150 ms with one face. Matching 5,000 templates is a 5,000 by 512 dot product, under 5 ms.
- Decode is budgeted separately, because it runs at the stream's native rate on the reader thread (section 7): a 720p sub-stream at 15 frames per second on the Pi 5 CPU is allowed up to half of one core. A 1080p main stream is not supported as the gate stream; the setup step picks the sub-stream.
- Pi 5 CPU: steady-state average under 60 percent of the four cores at 8 detections per second with `buffalo_l`, leaving room for enrolment, clip encoding and sync. If measured above, drop to 5 detections per second, then to `buffalo_sc`. Service memory under 1.5 GB so the 4 GB Pi works. CPU temperature under 70 C with the active cooler.
- Measurement, Phase 7, before the budget is trusted: time `det_10g`, `det_500m`, `w600k_r50` and `w600k_mbf` separately on the Pi 5 at the 640 by 360 input, one face in frame, and record the numbers in docs/MEMORY.md. Published CPU figures put `det_10g` well above the per-frame budget on this class of CPU, so the expected outcome is `det_500m` for detection with `w600k_r50` for embedding on detected faces only; `det_10g` stays the laptop development default. Changing the detector or the pack goes through `tests/eval/` and an ADR, as ADR-003 requires.
- Stream loss: detected when no decoded frame arrives for 5 seconds; reconnect within 10 seconds with 1, 2, 4, 8 second backoff; `stream_state` emitted on each change.
- Enrolment round trip, "Make member" tap to hub reply, under 2 seconds on the LAN.
- Hub ready to match within 60 seconds of power on, including model load and unlock.
- Notice delivered within 2 minutes for 90 percent of members; the 30-second flush leaves most of that to WhatsApp.

## 15. Observability

Health: `GET /health` on the hub returns `hub_version`, `model_pack`, `uptime_s`, `data_dir_state` (`unlocked`, or `locked, waiting for cloud`), `clock_synced`, per-stream state and frames per second, `active_members`, `last_match_at`, `outbox_depth`, `outbox_failed`, `last_sync_at`, `disk_free_mb`, `cpu_pct`, `cpu_temp_c`, `mem_mb`, and counters for matches, alerts, `not_them`, enrolments and age gate refusals. There is no faces-detected counter in health, on disk or in sync: a count of detected faces includes non-members, and docs/PRIVACY_AND_CONSENT.md section 12 forbids keeping any count about them. A detections-per-second gauge may exist in process memory for the developer console only; it is never persisted or sent. The same JSON goes to the cloud as a `health` outbox event every 5 minutes. The cloud marks a hub offline after 15 minutes of silence and pushes a "hub offline" notice to the owner.

Errors: Sentry in all three components through `SENTRY_DSN`, `send_default_pii` off, and a `before_send` scrubber that drops any field whose name contains phone, name, notes, items, vec, landmarks, image, crop, token or key.

Logs: structured JSON lines with `ts`, `level`, `component`, `event`, `shop_id`, `request_id`, `member_id`, `visit_id`, `duration_ms`, `outcome`. The hub logs to journald at `REMU_LOG_LEVEL`, the cloud to Vercel logs, the app to Logcat in debug builds only.

Never logged, anywhere, at any level: phone numbers, display names, notes, items, face crops, landmarks, embeddings or templates, clip bytes, tokens, keys, pairing codes, WhatsApp access tokens, raw webhook bodies. Ids are fine. Values are not.
