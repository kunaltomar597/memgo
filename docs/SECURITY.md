# Remu Security

Purpose: what Remu protects, who from, and the controls the hub, the app and the cloud must have before the pilot. Written 2026-10-18. Not legal advice.

## 1. Assets

- Face templates: `templates.vec_blob` on the hub, scrambled with the shop key. Biometric data. Never leaves the hub.
- Shop key: `/etc/remu/shop.key`, root-only. Seeds the scramble matrix and half the data directory key.
- Consent log: `consent_records`, mirrored to the cloud, anchored nightly in `hash_anchors`. Proof of consent and of deletion.
- Phone numbers: `members.phone_hash`, `members.phone_enc`, `whatsapp_messages.raw_webhook_json`. `phone_hash` is brute-forceable; treat it as the number.
- Member metadata: `members`, `households`, `purchases`, `visits`. Names, spend, notes, visit times.
- Clips: `clips` on the hub, 5 seconds per match, encrypted. Face video. LAN only. Gone after 30 days.
- Device and app tokens: `REMU_DEVICE_TOKEN` in `/etc/remu/hub.env`; `devices.token_hash` and `devices.data_unlock_token_hash` in the cloud. Pair a hub, unlock its data, admit a phone.
- Staff PINs: `staff.pin_hash`. Gate both app modes; attribute every action.
- WhatsApp and FCM credentials: `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN`, `FCM_SERVICE_ACCOUNT_JSON_BASE64`. Send as the Remu number; verify webhooks; push alerts.
- Cloud keys: `SUPABASE_SERVICE_ROLE_KEY`, `HUB_PAIRING_SECRET`, `CONSENT_EXPORT_SIGNING_KEY`. Bypass RLS, derive unlock tokens, sign exports.

## 2. Threat model

**Thief who takes the hub.** Gets the board, the storage, `/etc/remu/shop.key` and `REMU_DEVICE_TOKEN` in `/etc/remu/hub.env`, all on the unencrypted root partition. `REMU_DATA_DIR` is a LUKS container keyed from the shop key plus an unlock token the cloud issues only to a paired, unrevoked device. The thief does not need to power the Pi on: a copy of the SD card and the device token is enough to call `POST /api/devices/unlock` from any machine until the owner revokes the hub. Once revoked, there are no templates, clips, consent log or phone numbers to read. The protection is the revoke, so the window is the time until the owner notices, often the next morning. Proposed bindings the cloud can check at unlock, not in the brief: the Pi serial hashed at pairing (the hub already sends its serial in Flow A), the shop's public IP or LAN prefix seen at pairing, and refusal after a configurable silent period. OPEN: which of these ship in MVP.

**Rogue or careless staff.** A cashier with a PIN can enrol, search, remove members, edit households, and see names, phone numbers, notes and purchases in Cashier mode. Alerts, clips, settings and the export are Owner mode only, checked against `staff.role` on the hub and in the cloud. The hub API never returns `vec_blob`. Every enrolment and removal is attributed to a staff ID in the chain. An enrolment without the spoken line is a consent failure, not a valid consent. The WhatsApp notice still goes out, which gives the member a way to discover it, but silence after the notice is not a substitute for the original yes (docs/PRIVACY_AND_CONSENT.md section 7). When the owner learns of it, they tap Remove member and record the incident as a breach of the script.

**Malicious device on the shop LAN.** Gets 401 on every endpoint without an app token. Cannot impersonate the hub: the app pins the hub's certificate fingerprint and checks it against the one the cloud recorded. Can spoof the unencrypted RTSP stream; the worst outcome is a false alert the owner marks Not them.

**Cloud breach.** Gets the mirrored tables: `shops`, `staff` (`pin_hash` only), `households`, `members`, `consent_records`, `purchases`, `visits` without `clip_id`, `devices` (hashes only), `whatsapp_messages`, `hash_anchors`. No templates, clips or face images, because the cloud never receives them. OPEN: `phone_hash` is sha256 of the number and brute-forceable; decide the salt and who holds the key for `phone_enc` before Phase 4.

**Remu insider.** A cloud breach plus `HUB_PAIRING_SECRET`, which derives unlock tokens, plus configuration pushed to hubs. Still no face or template without holding a hub. Controls: named production access, no service role key on laptops, every export logged. OPEN: the named list with production access during the pilot.

**Compromised DVR.** Can feed the hub any video, including a replay of a member's face for a false alert, and can attack the hub like any LAN device. Reads nothing: the hub only pulls pixels and never sends anything back.

**Lost owner phone.** Holds a Supabase Auth session, the hub app token, the FCM registration, and in-memory state plus DataStore for session and mode; the app has no database and no cache of member data (docs/ARCHITECTURE.md section 3.2). Controls: PIN on open, session timeout, tokens in Android Keystore, no screenshots, clips never cached, sign-out of the lost phone from another phone in Owner mode. OPEN: with no web dashboard in MVP, a single-phone owner must call Remu support to revoke a lost phone.

## 3. Requirements by area

### 3.1 Hub

- Data at rest: `REMU_DATA_DIR` (`/var/lib/remu`) is a LUKS container holding SQLite and clips, keyed by `HKDF(shop_key || unlock_token)`. `remu-hub unlock` fetches the token at boot. The cloud derives it from `HUB_PAIRING_SECRET`, the device id and a generation counter, stores only `data_unlock_token_hash`, and refuses a revoked device. Without it the hub serves `health` only.
- Cached token, decided here: no unlock token is ever written to disk. The Pi 5 has no secure element, and a token beside the shop key would hand a thief everything. A running hub matches through any internet outage; only a reboot needs the cloud. A hub that cannot reach the cloud at boot reports `locked, waiting for cloud` in `health` and retries every 60 seconds (TASK-101). This decision needs an ADR in docs/DECISIONS.md; until then docs/ARCHITECTURE.md section 11, docs/RULES.md and docs/TEST_PLAN.md still carry the question as OPEN and should point here. OPEN: the founder accepts that a hub rebooting during an internet outage cannot match until internet returns, or adds a small UPS inside the 15,000 rupee cap.
- Shop key: 32 random bytes made at first boot, mode 0600, root-owned. Never in logs, health, exports or the cloud.
- Network: outbound HTTPS to `REMU_CLOUD_URL` only. One listening port, 8443, for the LAN API and WebSocket. Recommended: no SSH in the shipped image. OPEN: whether SSH stays enabled on pilot hubs, and if not, what pilot support access looks like (none, or an owner-enabled time-limited shell). docs/ARCHITECTURE.md section 12 and scripts/README.md carry the same OPEN, and TASK-098 and TASK-131 assume SSH; one ADR closes all of them.
- LAN TLS and pairing: a self-signed certificate made at first boot, its fingerprint recorded by the cloud and pinned in the app; a new certificate means re-pairing. The hub generates its 6-digit single-use pairing code at `remu-hub init`; it is printed on the hub's sticker and the owner types it into the app (OPEN: the founder confirms this direction, docs/ARCHITECTURE.md flow A). Once the owner has entered it, the hub's claim poll exchanges it for `REMU_DEVICE_TOKEN`. Each phone gets its own revocable app token; the counter phone joins with a one-time invite code valid 10 minutes.
- Model packs: the SHA-256 of every file in `buffalo_l` and `buffalo_sc` is pinned in the repo; on mismatch the hub refuses to start.
- Updates: recommended, a signed wheel pulled over the outbound connection, verified against a release public key baked into the image, rolled back if `health` fails after restart. OPEN: this versus manual update over SSH for the pilot, the same OPEN as docs/ARCHITECTURE.md section 12. Either way, unsigned builds run only under `make dev`. The service runs unprivileged; only `remu-hub unlock` needs root.

### 3.2 App

- Staff PIN: both modes open with a PIN checked against `staff.pin_hash` (Argon2id) on the hub, offline, at least 4 digits, with a growing delay after wrong attempts. OPEN: idle lock period for Cashier mode; the counter phone stays on a stand all day.
- Tokens: hub app token, Supabase session and FCM registration in Android Keystore-backed encrypted storage, never in plain SharedPreferences or Logcat. `google-services.json` and the upload key are never committed. Release builds set `android:allowBackup="false"`.
- Certificate pinning: the hub's fingerprint at pairing. The cloud is reached with standard TLS validation against the Android trust store and no pin: Vercel rotates leaf and intermediate certificates, so a pin would break the app for little gain over platform TLS.
- Screens: alert card, member card, member search and clip player set `FLAG_SECURE`. Face crops stay in memory and are dropped after the hub reply.
- Owner mode session: PIN again after 15 minutes idle (assumption). The FCM push carries only `shop_id` and `visit_id`; the app fetches the card and builds the notification locally. OPEN: whether the lock-screen notification shows the first name or only "Member at the gate".

### 3.3 Cloud

- Row Level Security on every table, every row scoped by `shop_id` directly or through `member_id`. A user reads only the shop in their `staff` row. The anon key cannot read `consent_records`.
- `SUPABASE_SERVICE_ROLE_KEY` only in server code; never under a `NEXT_PUBLIC_` name, in the app, or on a laptop for production. Hubs hold no Supabase key; they call `/api/hub/*` with the device token, compared to `devices.token_hash`.
- WhatsApp webhooks: verify `X-Hub-Signature-256` (HMAC-SHA256 of the raw body with `WHATSAPP_APP_SECRET`) before parsing; 401 on mismatch, body not logged. The GET handshake answers only `WHATSAPP_VERIFY_TOKEN`. Idempotent on `wamid` and status: the same status for the same `wamid` twice changes nothing twice; one row per `wamid` in `whatsapp_messages`.
- FCM: `FCM_SERVICE_ACCOUNT_JSON_BASE64` decoded in memory inside the relay route only; the service account has only the FCM send role.
- Rate limits before any database work: pairing codes per shop and per IP, webhooks per second, sign-ins per user, exports per shop.
- Region: Supabase in Mumbai (AWS ap-south-1), Vercel functions in bom1, nothing elsewhere. Exceptions: the WhatsApp notice passes through Meta, the FCM push through Google with ids only. OPEN: Sentry stores events outside India; the lawyer confirms scrubbed error data may leave, or Remu self-hosts Sentry in India.
- Exports: CSV and PDF signed with `CONSENT_EXPORT_SIGNING_KEY` (Ed25519, detached signatures), logged with requester, shop, time and file hash. OPEN: the brief has no export log table; simplest option is `export_log` in the cloud.

## 4. Secrets management

`.env` files are never committed; `.env.example` holds the names from the brief's section 17 and no values. `.gitignore` covers `.env*`, `google-services.json`, keystores, model packs, keys, tokens, data directories and clips. Production values live in Vercel and the Supabase dashboard; hub values in root-only `/etc/remu/hub.env`. `REMU_RTSP_GATE_URL` and `REMU_RTSP_COUNTER_URL` carry the DVR password and are secrets.

Rotation:

- `WHATSAPP_ACCESS_TOKEN`: every 90 days or on suspicion; new system-user token in Meta Business, update Vercel.
- `WHATSAPP_APP_SECRET` and `WHATSAPP_VERIFY_TOKEN`: on suspicion; reset in the Meta app, re-verify the webhook.
- `SUPABASE_SERVICE_ROLE_KEY`: on suspicion or when someone with access leaves; rotate in Supabase, update Vercel.
- `FCM_SERVICE_ACCOUNT_JSON_BASE64`: yearly; new key in Google Cloud, delete the old one.
- `HUB_PAIRING_SECRET`: not in MVP, because unlock tokens derive from it and every hub would first need to re-key its container. OPEN: build a re-key flow or accept a long-lived secret.
- `CONSENT_EXPORT_SIGNING_KEY`: yearly; keep old public keys so old exports still verify.
- `REMU_DEVICE_TOKEN`: on suspicion; revoke the hub in Owner mode, which bumps the generation, then re-pair.
- Shop key: on compromise only; old templates are deleted and every member re-enrols.
- Staff PINs: when staff leave; the owner edits or deletes the `staff` row.

## 5. Input validation

- Hub API: every request needs a valid app token; every write needs a staff session with the right `staff.role`. An enrol request is checked for face count (at most 6, assumption), crop size (under 300 KB each, the limit in TASK-044), a decodable image, the expected landmark count, an Indian E.164 phone number, a non-negative amount, length limits on text fields, and a staff ID of this shop. Ids must belong to the shop. No file paths from clients.
- Webhooks: signature, then schema, then processing. The raw body is kept in `raw_webhook_json` for disputes and treated as data, never rendered unescaped.
- App forms: validated on the phone and again on the hub, which is the authority. Pairing codes are compared in constant time.

## 6. Logging

Logged: `ts`, `level`, `component`, `event`, `shop_id`, `request_id`, `member_id`, `visit_id`, `duration_ms`, `outcome`, match score, `frames_agreed`, queue depth, errors. Level from `REMU_LOG_LEVEL`; DEBUG is never on in a shop.

Never logged at any level: phone numbers (plain or hashed), display names, notes, items, face crops, landmarks, embeddings or templates, clip bytes, tokens, keys, PINs, pairing codes, raw webhook bodies, RTSP URLs. A test in `tests/unit` runs a full enrol-and-match with a known name and number and fails if either appears in the output. Sentry gets `send_default_pii` off, request bodies stripped and a `before_send` scrubber.

## 7. Breach response under DPDP

A breach is any unauthorised access to, disclosure, alteration or loss of personal data, including a hub stolen while paired, a cloud database exposure, a leaked credential or a template alive past its deadline.

Duties: the owner is the data fiduciary and carries the legal duty to notify the Data Protection Board and each affected member. Remu is the data processor and must tell affected shops without delay and give them the evidence. The Rules notified in November 2025 set the format and deadline of the report. Penalties run up to 250 crore rupees for failing security safeguards and up to 200 crore for breach-notification failures. They become enforceable on 13 May 2027; Remu meets them from the first pilot. OPEN: the lawyer confirms the notification deadline, the report format and the split of duties between shop and Remu in the processing agreement.

Kill switch: "Revoke hub" on the device status screen in Owner mode. The cloud marks the device revoked, bumps the generation and stops issuing unlock tokens. An online hub receives `device_revoked` through its inbox (assumed message, docs/ARCHITECTURE.md section 10), stops matching, locks the data directory and drops the key from memory. An offline hub cannot unlock at its next boot. OPEN: whether revoke should also wipe `templates` and the shop key on an online hub, at the cost of re-enrolment after a false alarm.

Evidence: the consent log says who was enrolled, under which script and notice version, who withdrew, and which templates were deleted and when. The nightly `hash_anchors` prove it was not rewritten afterwards. The signed export goes to the Board.

## 8. Retention and deletion enforcement

- Deletion within 1 hour. Trigger: STOP webhook or Remove member tap. Action: the cloud marks WITHDRAWN; the hub deletes the `templates` row and the member's clips and appends a chained record with `template_deleted_at`. Verified by the Flow E tests with a fake clock and a nightly self-check that no withdrawn member has a template.
- Clips after 30 days. `clips.expires_at` is `created_at` plus 30 days; nightly `jobs/cleanup_clips` deletes file and row. Verified by T-D-06 and T-F-03 and a self-check that no clip is older than 30 days.
- Expiry after 1095 days unseen. The later of `last_seen_at` and `member_since` is older than 1095 days; nightly `jobs/expire_members` deletes the template, sets `expired` and appends a chained record. Verified by T-F-01 and a self-check that no active member is unseen over 1095 days.
- Unenrolled faces: discarded within seconds, nothing written. Verified by T-D-05 and a test that no rows, files or images are written.

The self-checks run nightly and report counts in the `health` outbox event (field names assumed) for the cloud to alert on; `jobs/delete_templates` runs every 5 minutes as the safety net for any withdrawn or expired member that still has a template.

The one-hour clock starts when the withdrawal reaches the hub. A Remove member tap arrives over the LAN even offline; a STOP reply comes through the cloud, so an offline hub deletes within one hour of reconnecting and the cloud records the delay. OPEN: how the notice and `docs/PRIVACY_AND_CONSENT.md` word this case.

## 9. Backups

- The cloud mirror is the backup for the consent log and member metadata. Pilot baseline: Supabase daily backups, in Mumbai. Point-in-time recovery is a paid add-on on the Pro plan, and docs/RESEARCH.md plans the pilot on the free or lowest tiers. OPEN: budget the Pro plan plus point-in-time recovery, or accept up to one day of loss on the mirror. One restore is rehearsed before the pilot.
- Templates and clips are never backed up off the hub, by design. A dead hub (failed card, fire, theft) means every member must be re-enrolled by tapping their face again at the counter. Tell owners this plainly at onboarding. Consent records and status survive in the cloud, and the replacement hub lists those members as needing re-enrolment. OPEN: the lawyer confirms that re-capturing a template under the same consent record needs no new notice.
- OPEN: an owner-made encrypted backup of templates to a USB stick kept in the shop. Simplest answer: no.

## 10. Supply chain

- Pinned dependencies with lock files: `hub/` through `pyproject.toml` plus a lock file, `app/` through the Gradle version catalog, `cloud/` through `package-lock.json` with `npm ci` in CI. Upgrades arrive as reviewed pull requests with a vulnerability scan.
- Model packs are downloaded by `make setup` against the pinned hashes. The eval harness in `tests/eval/` must pass before any change to the model pack or the 0.45 threshold is merged.
- Hub releases: built in CI from a tagged commit, signed, published with hashes, as is the Pi image from `scripts/build_pi_image.sh`. OPEN: signing tool (minisign or Sigstore) and who holds the release key.

## 11. Honest limits

- A thief with the hub and a live pairing. The device token and the shop key sit on the unencrypted root partition. Any machine with a copy of them can fetch the unlock token and open the container until the owner revokes the hub; the thief does not need to power the Pi on. The window is the time until the owner notices and taps Revoke hub, which may be hours. Root on a running hub or a tampered image defeats the design too.
- A compromised cloud. Whoever holds `HUB_PAIRING_SECRET` can derive the unlock token for any device they also physically hold, and can push status changes to hubs, including marking a member ACTIVE without a delivered notice. The cloud alone still gets no biometric data.
- Model inversion. Research shows face embeddings can be partly inverted into face-like images, so key plus templates together are sensitive, and inside one shop templates are still identifiers. Templates are biometric data in law whatever the protection; the scramble is a safeguard, not a substitute for consent.
- No liveness detection: a printed photo at the gate can cause a false alert, a wrong greeting rather than data exposure.
- No formal audit yet. As of 2026-10-18 no third-party penetration test or DPDP audit has been done. Planned for Phase 8. OPEN: budget and vendor.

## 12. DPDP obligations and the controls that meet them

| Obligation | Control |
|---|---|
| Consent is informed, specific and a clear affirmative act | Cashier line v1.0, WhatsApp notice v1.0, the enrol tap as timestamp, optional thumbs-up; versions on every record |
| Burden of proof on the shop | Append-only, hash-chained `consent_records`; nightly `hash_anchors`; one-tap signed export as PDF and CSV |
| Notice that names the purpose | "we recognise you at the door"; `purposes_json`; PENDING_NOTICE until delivery is confirmed |
| No consent sneaked into terms (2023 dark-pattern guidelines) | A separate spoken line and a separate notice, never inside bill terms |
| Reasonable security safeguards (up to 250 crore rupees) | Sections 3 and 10: encrypted hub, scrambled templates, no face photos, pinned hub HTTPS, RLS, signed releases; the update channel is OPEN in 3.1 |
| Breach notification (up to 200 crore rupees) | Section 7, kill switch, consent log and signed export as evidence |
| Erasure on withdrawal | STOP or Remove member deletes the template within 1 hour and logs it |
| Erasure once the purpose is served | 1095-day expiry, 30-day clips, strangers discarded within seconds |
| Children need verifiable parental consent | Age gate at enrolment; an estimate under 18 refuses with no override |
| Right to access and correct | Member card in Owner mode. OPEN: a written access-request process for MVP; simplest is an owner export of that member's record and purchases |
| Processor acts under the fiduciary's contract | Remu never holds a face or template. OPEN: processing agreement from the lawyer |
| Biometrics are sensitive personal data (IT Act 2011 rules) | Same safeguards; biometrics never leave the hub |
| Keep data in India (founder decision) | Supabase Mumbai (ap-south-1), Vercel bom1; exceptions in section 3.3 |

Review at the end of Phase 8 and after every incident.

## 13. Security review of 2026-10-18 (TASK-112): what the build does today

An independent review read the hub, the cloud and the app against this document. It found 7 high, 7 medium and about 15 low findings. The fixes are in the commits tagged TASK-112, and each has a regression test (`hub/tests/test_security_review.py`, `cloud/test/securityReview.test.ts`, and the app's tests).

| Threat | As built after the fixes |
|---|---|
| Stolen hub | On the Pi the data directory is a LUKS volume opened only with the cloud's unlock token. The hub refuses to serve from a plain folder. The shop key, device token and LAN certificate sit outside the volume (ADR-029), so a copied card can still fetch an unlock token until the owner revokes the device. Revoke now stops matching and every LAN route at once and survives a restart. |
| Stolen counter phone | The token is Keystore-encrypted and never backed up. Every write needs a PIN-verified staff session that lives only in memory. Counter tokens cannot read clips or alerts, and the owner can revoke any paired phone from Owner mode. |
| Rogue cashier | Enrolments and removals are attributed to the PIN session in the consent chain. A cashier cannot pull clips or the alert stream, and cannot freeze sync with long text, because free text is capped and the hub splits a rejected batch. Enrolling a stranger's face against the cashier's own number is still possible. The written notice goes to that number, so the owner's review of Not them and the consent export are the controls. |
| Cloud breach | No template, crop or clip is ever in the cloud. Plain phone numbers are cleared once a notice reaches a final status and on withdrawal, and raw webhook JSON holds only phone hashes. A cloud that queues a false activation is refused unless the hub's own chain already records the delivered notice. |
| WhatsApp number takeover | A new holder of a member's number receives later notices and can send STOP, which withdraws safely. With the Meta app secret an attacker could forge signed delivery webhooks; the hub's own chain check limits this to members whose delivery the cloud also forged. |

Still open after the review:

- OPEN: the pending window for notices that never deliver (templates of `pending_notice` members are kept until withdrawn).
- OPEN: pairing codes are stored in the cloud as plain SHA-256. A keyed hash with `HUB_PAIRING_SECRET` would resist offline guessing after a database leak.
- First pairing is still trust on first use on the LAN. The cloud cross-check catches a man in the middle afterwards, not during pairing.
- The webhook rate limit is per Vercel function instance. It guards against floods, not an exact quota.
