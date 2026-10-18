# Remu: Product Brief (single source of truth for all project documents)

Written on 2026-10-18 by the founder and Claude after a long design conversation. Every document in the Remu repo must agree with this brief. Where a document needs a detail this brief does not give, choose the simplest option and state it as an assumption.

## 1. One line

Remu is a "shop memory" for Indian retail. It recognises an opted-in returning customer at the shop entrance and pings the shopkeeper's phone with who they are and what they bought, so the shopkeeper can greet them by name. The name comes from "remember". Tagline: "Remu remembers."

## 2. Problem

High-ticket, infrequent, relationship-driven retail (jewellery, sarees and textiles, furniture, electronics showrooms, later salons and barbers) depends on the owner remembering customers. A customer who spent 20,000 rupees a year ago expects to be recognised on the next visit. Owners cannot remember hundreds of faces across years, and when staff leave, the memory leaves with them.

## 3. Users

- Primary user: the shop owner. Receives the alert, greets the customer, owns the customer relationship, and is the legal "data fiduciary" for member data.
- Secondary user: the cashier or staff. Enrols members at billing, types notes, handles removals.
- Beneficiary: the customer, called a "member" once enrolled. The member never installs anything and never touches a screen at the counter.
- Remu (the company) is the "data processor" that supplies the hub, the app and the cloud, and never holds a face.

## 4. The core moment

A member walks in after a year. The gate camera feed reaches the hub, the hub matches the face against the shop's members in under a second, and the owner's phone buzzes with a card: name, household, last visit, last purchase, notes. Nothing happens at the door: no screen, no beep, no visible camera reaction. The owner walks over and greets the member by name with specifics. The customer feels remembered by a person, not scanned by a machine. Preserving this surprise is a product requirement.

## 5. Non-negotiable principles (founder decisions)

1. Opt-in membership, never covert. Legal basis is the Digital Personal Data Protection Act 2023 (DPDP). Rules were notified in November 2025. The consent manager framework starts 13 November 2026, and the main obligations on notice, consent, security, retention and erasure become enforceable on 13 May 2027. Consent must be informed, specific, and given by a clear affirmative act, and the burden of proving consent is on the shop. A lawyer must sign off the exact script and notice wording before pilot. This brief is not legal advice.
2. Consent is taken as hospitality, not as a form. The customer never taps anything. The cashier says a scripted line, the customer says okay or nods, and the cashier taps the customer's face on the counter phone. That enrol tap is the consent timestamp. There is no separate consent button.
3. A written notice accompanies the consent. It goes on the WhatsApp invoice and, where the shop prints bills from Remu (later phase), on the printed bill. The notice names the purpose plainly: "we recognise you at the door". The WhatsApp message ends with an optional one-tap thumbs-up quick-reply button. A tap converts verbal consent into written consent. Not tapping changes nothing.
4. A member becomes ACTIVE for gate recognition only after the written notice is confirmed delivered by the WhatsApp delivery receipt. Until then the member is PENDING_NOTICE and is never matched at the gate.
5. No face photos are stored anywhere, ever. The only stored biometric is a cancelable template on the shop's hub, scrambled with a per-shop secret key. Remu's cloud never receives a face image, a template, or a clip.
6. Consent records are append-only, hash-chained, versioned (script version and notice version), stored separately from biometrics, mirrored to the cloud, and exportable as PDF and CSV in one tap.
7. Withdrawal: a WhatsApp reply "STOP", or an owner or cashier tap on "Remove member", deletes the template within one hour and logs the deletion in the same chain. The consent record itself is never deleted, because it proves the deletion.
8. Auto-expiry: a member unseen for 3 years (1095 days) is deleted and logged. Match clips are kept 30 days, then deleted.
9. No minors. The hub estimates age at enrolment; an estimate under 18 refuses enrolment with no override. Children's data needs verifiable parental consent under DPDP and is out of scope.
10. Unenrolled faces at the gate are discarded within seconds. Nothing is stored about non-members.
11. Cheap and simple. Existing CCTV through the DVR's RTSP stream, one hub of about 10,000 rupees, the owner's own phone. Target hardware cost at or under 15,000 rupees per shop. Setup by a non-engineer in under 60 minutes using the owner app.
12. Two member layers. A "phone member" is anyone who gives a number for the bill (standard practice in India; enables WhatsApp offers). A "face member" is a phone member who heard the door line and said okay. If the customer shrugs or walks away, the cashier skips the tap and the shop loses nothing.

## 6. The scripts and notices (exact wording, versioned as v1.0)

Cashier line, Hindi (Devanagari): "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।"
Cashier line, romanised: "Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega."
Cashier line, English: "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off."

WhatsApp notice, English: "Welcome to {shop_name} members, {first_name}. Member since {date}. No card needed, we recognise you at the door. 5% off your next visit. Reply STOP any time to leave." Quick-reply button label: "👍"
WhatsApp notice, Hindi: "{shop_name} के मेंबर बनने के लिए धन्यवाद, {first_name} जी। मेंबर since {date}। कार्ड की ज़रूरत नहीं, हम आपको दरवाज़े पर पहचान लेंगे। अगली विज़िट पर 5% ऑफ। कभी भी STOP लिखकर हट सकते हैं।"

Printed bill line (later phase): "Member since {date}. No card needed, we recognise you at the door. 5% off next visit. Reply STOP on WhatsApp to leave."

Counter standee: "Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।"

Owner alert card: "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}". Two buttons: "Greeted" and "Not them". "Not them" marks a false match and feeds accuracy tuning.

## 7. Households

The cashier can tap several adult faces at the counter and group them as one household, for example "Sharma family, payer Rajesh". Any household member's arrival triggers the household alert. Each adult hears the same line and the cashier addresses the group; the privacy document must note that each adult is consenting individually and that only adults present at the counter can be added. The age gate applies to every face.

## 8. Components and tech stack

### 8.1 Hub (`hub/`, Python 3.11)

- Reference hardware: Raspberry Pi 5 (4 GB or 8 GB) with case, power supply and microSD or NVMe, about 9,000 to 10,000 rupees. Any Linux x86 or ARM box works (old laptop, Intel N100 mini PC). Development runs on macOS or Linux with a webcam or a fake RTSP stream.
- Input: RTSP streams from the shop's DVR or NVR (CP Plus, Hikvision, Dahua all expose RTSP) or from a dedicated IP camera (1,500 to 2,500 rupees) mounted face-on at eye level at the gate. Optional second RTSP stream for a counter CCTV camera. Decode with OpenCV's FFmpeg backend at reduced resolution (for example 640 by 360 at 5 to 8 frames per second).
- Face pipeline: InsightFace SCRFD detector plus ArcFace embedding from the `buffalo_l` model pack (fallback `buffalo_sc` on weak CPUs), running on ONNX Runtime CPU. The pack's age and gender model provides the age gate. Optional Hailo-8L accelerator later (about 6,000 rupees), not in MVP. Note: the InsightFace library is MIT licensed but the model packs are licensed for non-commercial research use; a commercial licence or a commercially licensed pack of the same architecture is required before the pilot (OPEN).
- Embedding consistency rule: the hub is the only place that computes embeddings. The counter phone sends an aligned face crop with landmarks; the hub embeds it. Enrolment and gate matching therefore always use the same model and version, which is required for accurate matching.
- Template protection: 512-dimensional L2-normalised embedding, multiplied by a per-shop random orthogonal matrix seeded from the shop key, optionally sign-binarised later. An orthogonal rotation preserves cosine similarity, so matching accuracy is unchanged, and the stored vector is useless without the key. This is the BioHashing family of cancelable biometrics (ISO/IEC 24745). It is a safeguard, not a substitute for consent. Fuzzy commitment and homomorphic matching are documented as later research options only.
- Matching: cosine similarity against all active templates in the shop. Initial threshold 0.45, chosen conservatively to prefer a missed match over a false alert, then tuned with the evaluation harness. Require agreement in 3 of 5 consecutive frames before alerting. De-duplicate alerts to once per member per 10 minutes.
- Storage: SQLite in an encrypted data directory. Tables listed in section 10. The shop key lives in a root-only file on the hub. The data directory is unlocked at boot with a device-bound token fetched from the cloud, so a stolen hub that is unpaired or offline cannot be read, while the cloud still never sees the data. Document this as the MVP target, with the threat model and the limits stated honestly.
- Local API: FastAPI over the shop LAN with HTTPS (self-signed certificate pinned in the app at pairing) plus WebSocket events for the app. Endpoints: pairing, enrol, members, households, visits, clips, consent export, health.
- Cloud sync: outbound HTTPS only, no inbound ports. Sends alert events, consent records, visit summaries without biometrics, nightly hash anchors, and health. Receives withdrawals, notice-delivery status, configuration. Works fully offline for matching and enrolment; queues everything else in an outbox table. Notice delivery needs internet, so members stay PENDING_NOTICE until the hub is back online and the cloud confirms delivery.
- Clips: a 5-second H.264 clip per match, encrypted at rest, 30-day retention, viewable from the owner app over the LAN only.
- Jobs: nightly expiry (1095 days unseen), clip cleanup (30 days), hash anchor, outbox flush, health report.
- Deployment: systemd service, Python virtual environment, a `remu-hub` command line, provisioning image for the Pi, pairing by a code shown in the owner app.
- Tooling: `ruff` (lint and format), `mypy` (types), `pytest` (tests), `make` targets.

### 8.2 Android app (`app/`, Kotlin, Jetpack Compose, minimum SDK 26)

One app with two modes chosen at login.

- Cashier mode (a spare Android phone on a stand at the counter, wall-powered, screen facing the cashier, camera facing the customer): live CameraX preview with ML Kit face detection drawing a box on every face; the largest, closest face is pre-selected; the cashier taps to change the selection or to add more adults to a household; fields for phone number, bill amount, items, occasion and notes; the script line is shown on screen in Hindi and English as a prompt; a single "Make member" button. On tap the app sends the face crop(s) with landmarks, the phone number, the bill details and the staff ID to the hub over the LAN. The hub runs the age gate, embeds, scrambles, stores, writes the consent record, and asks the cloud to send the WhatsApp notice. Also: member search, remove member, household editing, offline indicator.
- Owner mode (the owner's own phone): alerts by Firebase Cloud Messaging push and by LAN WebSocket when on the shop WiFi; the alert card with "Greeted" and "Not them"; member card with purchases, notes, visit timeline and clips (LAN only); settings; staff PINs; device status; consent export; language switch Hindi or English.
- Tooling: Gradle, `./gradlew lint`, `./gradlew test`, `./gradlew connectedAndroidTest`; Maestro or Compose UI tests for end-to-end flows.

### 8.3 Cloud (`cloud/`, Next.js 15, TypeScript, Supabase, Vercel)

- Supabase Postgres with Row Level Security and Supabase Auth, project in the Mumbai region (AWS ap-south-1). Vercel functions in the Mumbai region (bom1). All data stays in India.
- Responsibilities: shop accounts and users, device pairing and tokens, consent log mirror (no biometrics), nightly hash anchors, WhatsApp Cloud API integration (send the invoice-plus-notice template with the quick-reply button; receive webhooks for sent, delivered, read, STOP and thumbs-up), FCM relay for alerts, member status transitions (PENDING_NOTICE to ACTIVE on delivery, to WITHDRAWN on STOP), consent export generation (PDF and CSV), health and uptime of hubs.
- Never stores: face images, templates, clips.
- MVP cloud is API routes plus webhooks plus the Supabase schema. The owner web dashboard and Razorpay subscription billing are later phases.
- Tooling: `npm run dev`, `npm run lint`, `npm run typecheck`, `npm test` (Vitest), `npm run build`, Playwright for end-to-end once the dashboard exists.

### 8.4 WhatsApp

Meta WhatsApp Cloud API with a pre-approved utility template for the invoice and member notice, including the quick-reply button. Needs a Meta Business account, a WhatsApp Business Account, a dedicated number, and template approval. Message cost is well under one rupee. The notice is the written consent record; delivery status comes back by webhook and gates member activation. If a customer has no WhatsApp, the member cannot be activated in MVP; an SMS fallback is a later phase.

### 8.5 Hardware bill of materials per shop

- Existing DVR or NVR with RTSP: 0 rupees.
- Optional dedicated gate IP camera: 1,500 to 2,500 rupees.
- Raspberry Pi 5 kit: 9,000 to 10,000 rupees.
- Counter phone: a spare Android phone, 0 to 6,000 rupees, with stand and charger.
- Counter standee: about 200 rupees.
- Total: 10,000 to 15,000 rupees per shop.
- Later: Hailo-8L about 6,000 rupees for very busy shops; a 5,000 rupee Bluetooth thermal printer for printed bills.

## 9. Scope

### 9.1 MVP (v0.1, pilot release)

Hub pipeline (RTSP to detect to embed to scramble to store to match to alert); evaluation harness for threshold; enrolment from the counter phone; households; age gate; consent records with hash chain and cloud mirror; WhatsApp notice send with delivery, STOP and thumbs-up webhooks; PENDING_NOTICE to ACTIVE gating; FCM and LAN alerts; owner alert screen with Greeted and Not them; member card, purchases, notes, visit timeline with clips over LAN; remove member, withdrawal and deletion within one hour; nightly expiry job; consent export CSV and PDF; device pairing and provisioning; offline operation with outbox; Hindi and English UI; Pi 5 image with encrypted data directory; health reporting; basic error tracking (Sentry).

### 9.2 Out of scope for MVP (later phases, in this order of likelihood)

Phone-as-hub (Tier 0: the counter phone decodes the gate RTSP and matches, zero extra hardware); Bluetooth thermal printer bill with the member line; owner web dashboard; Razorpay subscription billing; SMS fallback for customers without WhatsApp; POS integrations (Vyapar, Tally, Petpooja); DVR playback jump to a visit; WhatsApp broadcast and offer campaigns; loyalty points ledger beyond the 5% next-visit flag; multi-shop chains; iOS app; Hailo acceleration; additional languages beyond Hindi and English; the optional audio or video consent clip at enrolment; fuzzy commitment or homomorphic matching.

## 10. Data model

Hub SQLite holds everything. The cloud mirrors only the non-biometric tables marked "mirrored".

- shops: id, name, city, shop_key_ref, script_version, notice_version, settings_json. Mirrored.
- staff: id, shop_id, name, role (owner or cashier), pin_hash, created_at. Mirrored.
- households: id, shop_id, label, payer_member_id, created_at. Mirrored.
- members: id, shop_id, household_id, display_name, phone_hash, phone_enc, status (pending_notice, active, withdrawn, expired), member_since, last_seen_at, discount_flag, notes, created_at, updated_at. Mirrored.
- templates: id, member_id, vec_blob (scrambled), model_version, created_at. Hub only, never leaves the hub.
- consent_records: id, shop_id, member_id, bill_no, ts, cashier_id, channel, script_version, notice_version, purposes_json (recognise_at_entry, whatsapp_offers), notice_delivery_json (printed_on_bill, whatsapp_msg_id, whatsapp_status, delivered_at, thumbs_up_at), adult_check, withdrawn_at, template_deleted_at, prev_hash, record_hash. Append-only. Mirrored.
- purchases: id, member_id, bill_no, amount, items_text, occasion, ts. Mirrored.
- visits: id, member_id, ts, camera, score, frames_agreed, clip_id, greeted (bool), greeted_by, not_them (bool). Mirrored without clip_id.
- clips: id, visit_id, path_enc, created_at, expires_at. Hub only.
- events_outbox: id, type, payload_json, created_at, synced_at. Hub only.
- devices (cloud): id, shop_id, pairing_code, token_hash, hub_version, last_seen_at, data_unlock_token_hash.
- whatsapp_messages (cloud): id, member_id, template_name, wamid, status, status_ts, raw_webhook_json.
- hash_anchors (cloud): id, shop_id, date, chain_head_hash, received_at.

Consent record example (JSON):
{"consent_id":"c_8f3a","shop_id":"sharma-jewellers-jaipur","member_id":"m_2041","phone_hash":"sha256:...","bill_no":"INV-2026-10-18-0417","timestamp":"2026-10-18T17:42:11+05:30","cashier_id":"staff_03","channel":"verbal_at_counter","script_version":"hi-IN-v1.0","notice_version":"wa-v1.0","purposes":{"recognise_at_entry":true,"whatsapp_offers":true},"notice_delivery":{"printed_on_bill":false,"whatsapp_msg_id":"wamid.HBg...","whatsapp_status":"delivered","delivered_at":"2026-10-18T17:42:40+05:30","thumbs_up_at":null},"adult_check":"passed","withdrawn_at":null,"template_deleted_at":null,"prev_hash":"sha256:...","record_hash":"sha256:..."}

## 11. Key flows

A. Setup and pairing: owner installs the app, creates the shop in the cloud, plugs in the hub, enters the hub's pairing code from the app, picks the gate stream from the DVR's discovered RTSP URLs, aims the camera using a live preview, places the counter phone, prints the standee.
B. Enrolment at billing: cashier asks for the number, says the line, taps the face, hits Make member. Hub age-gates, embeds, scrambles, stores, writes the consent record, queues the WhatsApp request. Member status PENDING_NOTICE.
C. Notice delivery: cloud sends the WhatsApp template; delivery webhook arrives; cloud sets ACTIVE and informs the hub; the hub starts matching this member. A thumbs-up webhook stamps thumbs_up_at on the consent record.
D. Gate recognition: hub detects faces in the gate stream, embeds, scrambles, matches against ACTIVE templates, requires 3 of 5 frames, writes a visit, saves a 5-second clip, sends an alert event; cloud pushes FCM; app shows the card; owner taps Greeted or Not them.
E. Withdrawal: STOP webhook or Remove member tap; cloud marks WITHDRAWN; hub deletes the template within one hour and logs template_deleted_at in a new chained record.
F. Expiry: nightly job deletes templates for members unseen for 1095 days and logs it; clips older than 30 days are deleted.
G. Consent export: owner taps Export; the cloud generates PDF and CSV from the mirrored log, signed with the export key.
H. Offline: hub keeps matching and enrolling; outbox syncs when internet returns; no new member goes ACTIVE until delivery is confirmed.

## 12. Success criteria for the pilot (60 days, 5 to 10 jewellery or textile shops in one city)

- At least 60 percent of billed adult customers with a phone agree to face membership.
- At least 90 percent of notices delivered within 2 minutes.
- At least 85 percent of returning ACTIVE members alerted within 3 seconds of entering; at most 1 false alert per shop per day (measured by Not them).
- Owners tap Greeted on at least 50 percent of alerts.
- Zero stored photos; 100 percent of members have a consent record with a delivered notice; 100 percent of STOP requests deleted within one hour.
- Hub uptime at least 99 percent, with automatic restart, and continued matching through internet outages.
- Hardware at or under 15,000 rupees per shop; setup under 60 minutes by a non-engineer.

## 13. Pricing hypothesis

Hardware at cost or a small margin. Software 500 to 1,500 rupees per shop per month. One retained 20,000 rupee customer per month pays for it. Razorpay billing comes after the pilot.

## 14. Evaluation harness (`tests/eval/`)

A labelled pair set: a public face-pair benchmark subset for development, plus a consented pilot set later. Reports true accept rate at a false accept rate of 0.1 percent, picks the threshold, and tests robustness to ageing (an age-gap pair subset), low light and angle. The harness must pass before any change to the model pack or threshold is merged.

## 15. Build approach

Vertical slices, one feature at a time, each task small enough for one AI coding session, with TASK IDs. Order: hub first on the developer laptop with a fake RTSP server (mediamtx looping a sample video) so that a face in the sample video gets enrolled and then matched and an alert prints to the console; then the hub API and consent records; then the Android Cashier mode; then the cloud with WhatsApp; then Owner mode alerts; then withdrawal, expiry and export; then the Pi image and real DVR; then hardening and QA; then the pilot. Every task ends with tests, review, commit, and a documentation update.

Phase skeleton for TASKS.md (use these names and numbers exactly):
- Phase 0: Project setup (repo, tooling, CI, docs in place)
- Phase 1: Hub core pipeline on the dev machine plus the eval harness
- Phase 2: Hub local API, enrolment endpoint, SQLite, consent records and hash chain
- Phase 3: Android app, Cashier mode
- Phase 4: Cloud: Supabase schema, pairing, consent mirror, WhatsApp send and webhooks, activation
- Phase 5: Android app, Owner mode: alerts, member card, feedback, timeline and clips
- Phase 6: Withdrawal, deletion, expiry, consent export
- Phase 7: Hub on the Pi 5: image, systemd, encrypted data directory, real DVR RTSP, pairing
- Phase 8: Hardening: security review, offline mode, monitoring, QA
- Phase 9: Pilot: onboarding, training, metrics, iteration
- Later: the out-of-scope list in section 9.2

## 16. Repository layout

Remu/
├── README.md
├── CLAUDE.md                 (Claude Code project context; points to the docs and rules)
├── TASKS.md
├── .env.example
├── .gitignore
├── docs/
│   ├── BRIEF.md               (this file)
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DESIGN.md
│   ├── RULES.md
│   ├── TEST_PLAN.md
│   ├── SECURITY.md
│   ├── PRIVACY_AND_CONSENT.md
│   ├── DECISIONS.md
│   ├── MEMORY.md
│   ├── PILOT_PLAN.md
│   ├── RESEARCH.md
│   └── PROMPTS.md
├── .cursor/rules/
│   ├── general.mdc
│   ├── frontend.mdc           (Android app, later the web dashboard)
│   ├── backend.mdc            (hub and cloud)
│   └── testing.mdc
├── hub/                       (Python: remu_hub package, tests, Makefile, pyproject.toml)
├── app/                       (Android: Gradle project)
├── cloud/                     (Next.js + Supabase)
├── tests/
│   ├── unit/  integration/  e2e/  eval/
└── scripts/                   (fake camera, provisioning, release helpers)

Commands to standardise everywhere:
- hub: `cd hub && make setup`, `make dev`, `make fake-camera`, `make test`, `make lint`, `make typecheck`
- app: `cd app && ./gradlew assembleDebug`, `./gradlew test`, `./gradlew lint`, `./gradlew connectedAndroidTest`
- cloud: `cd cloud && npm run dev`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`, `npx playwright test`
- root: `make check` runs lint, typecheck and tests for every component

## 17. Environment variables for .env.example

Hub: REMU_SHOP_ID, REMU_DEVICE_TOKEN, REMU_CLOUD_URL, REMU_RTSP_GATE_URL, REMU_RTSP_COUNTER_URL (optional), REMU_DATA_DIR, REMU_MODEL_PACK (buffalo_l), REMU_MATCH_THRESHOLD (0.45), REMU_FRAMES_REQUIRED (3), REMU_FRAMES_WINDOW (5), REMU_ALERT_COOLDOWN_MINUTES (10), REMU_CLIP_RETENTION_DAYS (30), REMU_MEMBER_EXPIRY_DAYS (1095), REMU_LOG_LEVEL, SENTRY_DSN.
Cloud: NEXT_PUBLIC_SUPABASE_URL, NEXT_PUBLIC_SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY, DATABASE_URL, WHATSAPP_PHONE_NUMBER_ID, WHATSAPP_BUSINESS_ACCOUNT_ID, WHATSAPP_ACCESS_TOKEN, WHATSAPP_VERIFY_TOKEN, WHATSAPP_APP_SECRET, WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN, WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI, FCM_SERVICE_ACCOUNT_JSON_BASE64, HUB_PAIRING_SECRET, CONSENT_EXPORT_SIGNING_KEY, SENTRY_DSN, RAZORPAY_KEY_ID (later), RAZORPAY_KEY_SECRET (later).
App: REMU_CLOUD_URL in build config; google-services.json for FCM, never committed.

## 18. Research and legal landscape (for RESEARCH.md and PRIVACY_AND_CONSENT.md)

- DPDP Act 2023; Rules notified November 2025; phased: institutional setup November 2025, consent manager framework 13 November 2026, remaining obligations 13 May 2027. Penalties up to 250 crore rupees for failing security safeguards and up to 200 crore for breach-notification failures. Sources from the design conversation: Taxmann analysis https://www.taxmann.com/post/blog/analysis-indias-dpdp-act-and-rules/ ; Uniqus timeline https://uniqus.com/digital-personal-data-protection-act-timelines/ ; Maheshwari and Co guide https://www.maheshwariandco.com/blog/dpdp-act-compliance-guide-2025/ ; Outlook Business on the rules notification https://www.outlookbusiness.com/amp/story/deeptech/tech/govt-notifies-dpdp-rules-experts-flag-need-for-data-mapping-consent-systems
- The IT Act's 2011 Sensitive Personal Data rules already classify biometrics as sensitive personal data.
- India's 2023 guidelines on dark patterns list "sneaking" terms into a transaction as a named practice; consent buried in bill terms and conditions is not consent.
- Cautionary precedents: 7-Eleven Australia was ordered in 2021 to destroy face data collected without consent; Rite Aid in the United States was banned from face recognition for five years in 2023; face templates were treated as biometric data in both. Earlier retail VIP-recognition attempts: NEC trial in the UK around 2013 https://www.decisionmarketing.co.uk/?p=20400 ; Shiba Denshi in Japan in 2008 https://techcrunch.com/2008/07/18/new-face-recognition-system-helps-stores-to-identify-vip-customers/amp/ ; Advantech FaceView with CyberLink FaceMe https://www.cyberlink.com/faceme/insights/cases/retail/360/advantech-faceview-retail-faceme-facial-recognition . These stalled on price and privacy backlash, not technology; today's hardware is roughly a tenth of the cost.
- Competing approach: phone-number loyalty and CRM tools already common in India (Khatabook, Vyapar, Petpooja, Zoho). They capture most of the "remember the big spender" value without biometrics. Remu's unique value is recognition on entry, before the counter.
- Technical feasibility: ArcFace-class embeddings handle a one to two year gap, glasses and beards well for adults; small per-shop galleries (a few thousand faces) keep false alarms low; camera placement (face-on, eye level, decent light) matters more than the model.

## 19. Writing conventions for every document

- Markdown with one H1 title, a one-line purpose statement, then sections. Keep it practical and specific to Remu; no generic filler.
- Plain English, short sentences, no em dashes, no marketing fluff, no AI-sounding phrases.
- Use the exact component names: hub, app (Cashier mode, Owner mode), cloud. Use "member" for an enrolled customer, "owner" and "cashier" for shop roles.
- Hindi text in Devanagari with a romanised version beside it.
- Keep every number consistent with this brief (threshold 0.45, 3 of 5 frames, 10-minute cooldown, 30-day clips, 1095-day expiry, 1-hour deletion, 5 percent discount, 60-day pilot, 5 to 10 shops, 15,000 rupee hardware cap, 500 to 1,500 rupees per month).
- Dates are absolute (2026-10-18 is today).
- Where a document names a command, environment variable, table, file or phase, use the names from this brief exactly.
- Mark anything that still needs a human decision with the tag "OPEN:" so it can be found later.
