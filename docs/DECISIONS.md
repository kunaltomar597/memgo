# Remu Architecture Decisions

Purpose: the decisions behind Remu that must not be changed casually, each with its reason, so that no person or AI session reverses one by accident.

To change one, add a new ADR that supersedes it and mark the old one "Superseded by ADR-0NN"; never edit an accepted ADR. Where this file and the product brief of 2026-10-18 differ, the brief wins.

## ADR-001 Hub-first, not phone-as-hub, for the MVP

Status: Accepted, 2026-10-18.

Decision: Recognition runs on a dedicated hub (reference hardware Raspberry Pi 5, 4 GB or 8 GB) reading the gate camera over RTSP from the shop's DVR or NVR. Phone-as-hub (Tier 0) stays out of the MVP.

Reason: One Linux box gives a predictable frame rate, one model version, an encrypted data directory and a service that restarts itself. A phone cannot promise 99 percent uptime while also running the Cashier mode camera.

Consequences: Every shop needs a Pi 5 kit (9,000 to 10,000 rupees) and a pairing step in the owner app. Phone-as-hub can be added later as a tier; the hub API is the only contract the app depends on.

Alternatives considered: Phone-as-hub (heat and decode load on the counter phone); cloud matching (ADR-019); any other Linux x86 or ARM box (allowed, the Pi 5 is the reference image).

OPEN: Pi sourcing. Which supplier, and at what landed price.

OPEN: Which DVR brands to support first (CP Plus, Hikvision, Dahua).

## ADR-002 Only the hub computes embeddings

Status: Accepted, 2026-10-18.

Decision: Only the hub runs the embedding model. Cashier mode sends an aligned face crop with landmarks and the hub embeds it, exactly as it embeds gate frames.

Reason: A template is only comparable with one from the same model and version. Two embedding sites would drift in model pack, quantisation or alignment and silently lower accuracy.

Consequences: The enrol endpoint accepts crops, never vectors. `templates.model_version` records which model made each template; a model upgrade means re-embedding on the hub.

Alternatives considered: ML Kit or TFLite ArcFace on the phone (two model copies to keep in step).

## ADR-003 InsightFace buffalo_l on ONNX Runtime CPU

Status: Accepted, 2026-10-18.

Decision: SCRFD detection and ArcFace embedding from the InsightFace `buffalo_l` pack on ONNX Runtime CPU; `buffalo_sc` is the fallback on weak CPUs. The pack's age and gender model supplies the age gate. `REMU_MODEL_PACK` defaults to `buffalo_l`. This architecture (SCRFD plus ArcFace on ONNX Runtime) is used for development. Before the pilot, Remu must either obtain a commercial licence for the packs or switch to a commercially licensed pack of the same architecture, and re-run the harness in `tests/eval/`.

Reason: ArcFace-class embeddings handle a one to two year gap, glasses and beards well for adults. The library code is MIT; the model packs are released for non-commercial research use only and need a commercial licence before the pilot (OPEN in docs/RESEARCH.md section 3). It is expected to run on a Pi 5 CPU at 640 by 360, 5 to 8 frames per second; this is measured in Phase 7, not yet benchmarked.

Consequences: The harness in `tests/eval/` must pass before any change to the model pack or threshold is merged. The pack licence is a blocker for Phase 9: a commercial pilot cannot ship the `buffalo_l` or `buffalo_sc` packs as released. Hailo-8L (about 6,000 rupees) is a later option.

OPEN: InsightFace model pack licence. Obtain a commercial licence from the InsightFace authors or switch to a commercially licensed pack before the pilot, then re-run the harness. See docs/RESEARCH.md section 3.

OPEN: Pi 5 frame rate. No benchmark exists yet; measure in Phase 7.

Alternatives considered: FaceNet or dlib (weaker on ageing); a commercial SDK (licence cost).

## ADR-004 Cancelable templates by per-shop orthogonal projection

Status: Accepted, 2026-10-18.

Decision: Each 512-dimensional L2-normalised embedding is multiplied by a random orthogonal matrix seeded from the shop key before storage in `templates.vec_blob`. Sign-binarisation, fuzzy commitment and homomorphic matching are later research only.

Reason: An orthogonal rotation preserves cosine similarity, so accuracy is unchanged, while the stored vector is useless without the key. This is the BioHashing family of cancelable biometrics (ISO/IEC 24745). If a key leaks, rotate it and re-enrol.

Consequences: The shop key lives in a root-only file on the hub; losing it loses every template. This is a safeguard, not a substitute for consent.

Alternatives considered: Plain embeddings (a stolen database would be a face database).

## ADR-005 No face photos stored anywhere

Status: Accepted, 2026-10-18.

Decision: No component stores a face image. The hub keeps only the scrambled template and a 5-second encrypted match clip for 30 days. Unenrolled faces are discarded within seconds; nothing is stored about non-members.

Reason: Photos are what gets leaked or misused; a template is a derived, cancelable value, so a breach cannot become a face database (founder principle 5).

Consequences: The member card shows a name and a timeline, not a thumbnail. The clip is the only visual record; it stays on the hub and expires.

Alternatives considered: A thumbnail per member (recreates the photo store).

## ADR-006 SQLite on the hub in an encrypted data directory with a cloud-held unlock token

Status: Accepted, 2026-10-18.

Decision: The hub keeps every table of the data model in SQLite inside an encrypted data directory (`REMU_DATA_DIR`). At boot it fetches a device-bound unlock token from the cloud, which stores only `devices.data_unlock_token_hash`. The shop key lives in a separate root-only file.

Reason: A stolen hub that is unpaired or offline cannot be read, while the cloud still never sees the data.

Consequences: After a restart the hub needs one cloud contact before it can match; then it runs offline. The threat model and its limits (root on a running hub, a tampered image) are stated honestly in `docs/SECURITY.md`. This is the MVP target, not a hardware security module.

Alternatives considered: Postgres on the hub (overkill); unencrypted storage (a stolen microSD card reads anywhere).

## ADR-007 FastAPI LAN API over HTTPS with a pinned self-signed certificate

Status: Accepted, 2026-10-18.

Decision: The hub exposes a FastAPI service on the shop LAN over HTTPS with a self-signed certificate generated at first boot; the app pins it at pairing. WebSocket events carry alerts on the shop WiFi. Endpoints: pairing, enrol, members, households, visits, clips, consent export, health.

Reason: The hub has no public hostname, so no public certificate authority can issue for it. Pinning at pairing gives the app a trusted hub identity without the internet.

Consequences: The pairing code is the trust anchor: the hub generates it at `remu-hub init`, it is printed on the hub's sticker, and the owner types it into the app (TASKS.md TASK-028, docs/ARCHITECTURE.md flow A). Re-flashing the hub rotates the certificate and requires re-pairing.

Alternatives considered: Plain HTTP (face crops in transit).

OPEN: the pairing direction. The brief's section 8.1 says "pairing by a code shown in the owner app" and its flow A says the owner "enters the hub's pairing code from the app". TASKS.md and docs/ARCHITECTURE.md keep the hub-generated code typed into the app; the founder confirms this as a new ADR before TASK-028.

## ADR-008 Outbound-only hub to cloud sync with an outbox

Status: Accepted, 2026-10-18.

Decision: The hub opens HTTPS connections to the cloud and accepts no inbound internet connections. Everything it sends (alerts, consent records, visit summaries without biometrics, hash anchors, health) is written first to `events_outbox` and flushed when online. Withdrawals, notice-delivery status and configuration come back on the same channel.

Reason: Shop routers are unmanaged; no port forwarding means no hub exposed to the internet. The outbox keeps matching and enrolment working offline without losing anything.

Consequences: No member goes `active` while the hub is offline (ADR-012). A withdrawal reaches an offline hub only when it reconnects (ADR-016).

Alternatives considered: Inbound webhooks or a VPN per shop (exposure, cost, a single point of failure).

## ADR-009 Kotlin and Jetpack Compose, one Android app with two modes

Status: Accepted, 2026-10-18.

Decision: One Android app in `app/`, Kotlin with Jetpack Compose, minimum SDK 26. The mode, Cashier or Owner, is chosen at login. Cashier mode uses CameraX and ML Kit face detection; Owner mode gets alerts by Firebase Cloud Messaging and LAN WebSocket.

Reason: Both roles work in the same shop and share the hub pairing, member data and language setting. One codebase halves build and release work.

Consequences: The counter phone is a spare Android device; iOS is a later phase. Staff PINs separate the modes inside one install. Tooling: `./gradlew lint`, `./gradlew test`, `./gradlew connectedAndroidTest`.

Alternatives considered: Two apps (duplicate pairing and auth code); Flutter or React Native (weaker CameraX and ML Kit fit).

## ADR-010 Next.js 15, Supabase and Vercel, all in Mumbai

Status: Accepted, 2026-10-18.

Decision: The cloud in `cloud/` is Next.js 15 with TypeScript on Vercel functions in Mumbai (bom1), with Supabase Postgres, Row Level Security and Supabase Auth in Mumbai (AWS ap-south-1). The MVP cloud is API routes, webhooks and the Supabase schema.

Reason: All data stays in India, which matters under DPDP and to shop owners. Supabase gives Postgres, auth and RLS without running servers; Vercel gives a preview deployment per branch.

Consequences: Every Supabase table gets an RLS policy before it holds shop data. Webhooks verify `WHATSAPP_APP_SECRET`. The owner web dashboard and Razorpay billing come later.

Alternatives considered: A Python cloud to share code with the hub (the cloud is mostly webhooks and auth); Firebase (weaker SQL and row security).

## ADR-011 WhatsApp Cloud API as the written notice channel

Status: Accepted, 2026-10-18.

Decision: The written notice goes out through the Meta WhatsApp Cloud API as a pre-approved utility template carrying the invoice and the member line, with one quick-reply button labelled "👍". Sent, delivered, read, STOP and thumbs-up return by webhook. `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN` and `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI` name the templates.

Reason: Nearly every customer who gives a number has WhatsApp, the bill already goes there and a message costs well under one rupee. The delivery receipt is machine-readable proof that the notice was delivered to the customer's WhatsApp number. It does not prove the customer read it.

Consequences: Remu needs a Meta Business account, a WhatsApp Business Account, a dedicated number and template approval before the pilot. A customer without WhatsApp cannot become `active` in the MVP; SMS fallback is a later phase.

Risk: the notice body contains "5% off your next visit", which Meta may treat as promotional. Meta can approve the template as marketing instead of utility, or re-categorise it later. Marketing templates cost more and are subject to marketing-message limits in India; the cost stays well under one rupee either way. Prepare a utility variant without the discount sentence as the second wording. Any wording change is a new `notice_version`.

Alternatives considered: SMS (no quick reply, weaker receipts); printed bill only (no delivery proof).

OPEN: WhatsApp Business Account and template approval: which entity and number, and who submits the templates to Meta.

## ADR-012 Member activation is gated on notice delivery

Status: Accepted, 2026-10-18.

Decision: A new member is `pending_notice` until the WhatsApp delivery webhook confirms delivery. The cloud then sets `active` and informs the hub. The hub never matches a `pending_notice` member. `members.status` holds `pending_notice`, `active`, `withdrawn` or `expired`.

Reason: Recognition must not run before the customer holds the written notice. A delivery receipt makes this provable per member.

Consequences: Activation needs internet; during an outage new members wait and Cashier mode shows the offline indicator. Target: at least 90 percent of notices delivered within 2 minutes.

Alternatives considered: Activate at enrolment and notify later (recognition before notice); require the thumbs-up first (a hurdle that would cut the 60 percent enrolment target).

## ADR-013 Consent is a verbal yes plus a written notice, with no customer tap

Status: Accepted, 2026-10-18.

Decision: The cashier says the scripted line, the customer says okay or nods, and the cashier taps the customer's face on the counter phone and presses "Make member". That tap is the consent timestamp. The written notice follows on WhatsApp and, later, on the printed bill. Wording is versioned: `script_version` hi-IN-v1.0, `notice_version` wa-v1.0.

The line, Hindi: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" Romanised: "Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega."

Reason: The member never installs anything and never touches a screen; consent as hospitality keeps enrolment high and the moment human. DPDP needs a clear, informed, specific affirmative act; the spoken yes, the plain purpose in the notice ("we recognise you at the door") and the versioned record make that case, and the shop carries the burden of proof (ADR-015).

Consequences: The cashier's honesty is part of the control. A changed script or notice is a new version; old records keep the old version. Consent buried in bill terms is a dark pattern and is never used.

Alternatives considered: A customer tap or signature (reads as surveillance); an audio or video clip of the yes (optional later phase).

OPEN: Lawyer sign-off on the exact script and notice wording before the pilot.

## ADR-014 Optional thumbs-up double opt-in

Status: Accepted, 2026-10-18.

Decision: The notice ends with one quick-reply button, "👍". A tap stamps `thumbs_up_at` on the consent record and converts verbal consent into written consent. Not tapping changes nothing; the member still becomes `active` on delivery.

Reason: A written yes is stronger evidence than a delivered notice and costs one tap; keeping it optional keeps enrolment friction at zero.

Consequences: The pilot reports the share of members who tapped. The thumbs-up never gates any feature.

Alternatives considered: Mandatory thumbs-up (rejected, see ADR-012); no button (loses cheap written evidence).

## ADR-015 Append-only, hash-chained consent records mirrored to the cloud

Status: Accepted, 2026-10-18.

Decision: `consent_records` is append-only. Each record carries `prev_hash` and `record_hash`, forming a chain per shop; withdrawal and template deletion are new chained records, not edits. The table is mirrored to the cloud without biometrics, the chain head is anchored nightly in `hash_anchors`, and the owner exports PDF and CSV in one tap, signed with `CONSENT_EXPORT_SIGNING_KEY`.

Reason: The shop must prove consent, and prove deletion, years later, even if the hub is lost. A chain plus an anchor held elsewhere makes silent edits detectable.

Consequences: Nothing in `consent_records` is ever updated or deleted, including after withdrawal. A hub restored from backup is checked against the anchors.

Alternatives considered: A mutable consent flag on `members` (no proof).

## ADR-016 Deletion within one hour, 30-day clips, 1095-day expiry

Status: Accepted, 2026-10-18.

Decision: A withdrawal (WhatsApp reply STOP, or an owner or cashier tap on "Remove member") deletes the template within one hour and logs `template_deleted_at`. Clips expire after 30 days (`REMU_CLIP_RETENTION_DAYS`). A member unseen for 1095 days (`REMU_MEMBER_EXPIRY_DAYS`) is set to `expired`, the template is deleted and the deletion is logged. Nightly jobs enforce both.

Reason: DPDP requires erasure on withdrawal and retention no longer than the purpose needs. Three years matches the buying cycle of high-ticket retail; 30 days is enough to review a false alert.

Consequences: Withdrawal is honoured for a `pending_notice` member too. Pilot criterion: 100 percent of STOP requests deleted within one hour.

Assumption: for a hub that is offline when the STOP arrives, the hour is counted from the moment the hub receives the withdrawal; the brief does not say.

Alternatives considered: Longer expiry (no purpose after three years of absence).

## ADR-017 Age gate with no override

Status: Accepted, 2026-10-18.

Decision: At enrolment the hub estimates age with the `buffalo_l` age and gender model. An estimate under 18 refuses enrolment. No override exists for the cashier or the owner. The gate applies to every face in a household.

Reason: Children's data needs verifiable parental consent under DPDP, which is out of scope. An override would get used, so there is none. Refusing a few young-looking adults costs a little enrolment; enrolling one child costs the product.

Consequences: `consent_records.adult_check` records the result. The cashier sees a plain refusal, not an age number.

Alternatives considered: Owner override with a reason; an ID check (breaks the no-form principle).

## ADR-018 Threshold 0.45, 3 of 5 frames, 10-minute cooldown

Status: Accepted, 2026-10-18.

Decision: A match is a cosine similarity of at least 0.45 (`REMU_MATCH_THRESHOLD`) against the shop's active templates, agreed in 3 of 5 consecutive frames (`REMU_FRAMES_REQUIRED`, `REMU_FRAMES_WINDOW`), with one alert per member per 10 minutes (`REMU_ALERT_COOLDOWN_MINUTES`). The threshold changes only through the harness in `tests/eval/`.

Reason: A missed greeting is a small loss; a wrong greeting is embarrassing, so the starting values prefer a missed match over a false alert. Frame agreement removes single-frame flukes.

Consequences: Pilot targets: at least 85 percent of returning `active` members alerted within 3 seconds, at most 1 false alert per shop per day measured by Not them. The harness reports true accept rate at a false accept rate of 0.1 percent.

Alternatives considered: A lower threshold (not until the harness shows the false accept rate holds).

## ADR-019 The cloud never holds biometrics

Status: Accepted, 2026-10-18.

Decision: The cloud stores no face image, template or clip. `templates`, `clips` and `events_outbox` are hub-only; `visits` is mirrored without `clip_id`. Remu, the company, is the data processor and never holds a face; the shop owner is the data fiduciary.

Reason: A central store of faces for many shops is what regulators, customers and attackers would all target. With biometrics on the hub, a cloud breach exposes names, phone hashes and visit summaries, not faces.

Consequences: No central or multi-shop recognition. Cloud backups cannot restore templates; a lost hub means re-enrolment.

Alternatives considered: Encrypted template backup in the cloud (rejected, it would make Remu a biometric holder in substance).

## ADR-020 Two-layer membership: phone member and face member

Status: Accepted, 2026-10-18.

Decision: A phone member is anyone who gives a number for the bill. A face member is a phone member who heard the door line and said okay. `members.phone_hash` and `members.phone_enc` hold the number; a `templates` row exists only for a face member. If the customer shrugs or walks away, the cashier skips the tap and the shop loses nothing.

Reason: Asking for a number at billing is standard practice in India and enables WhatsApp offers. An optional second layer means the shop never pressures anyone and still gets a usable phone list.

Consequences: `purposes_json` records `recognise_at_entry` and `whatsapp_offers` separately. Enrolment is measured as face members over billed adults with a phone, target at least 60 percent.

Alternatives considered: Face membership only (every refusal would be a lost contact); loyalty points (later phase).

OPEN: Discount policy per shop. The v1.0 scripts promise 5 percent off the next visit; a different offer needs a new script and notice version.

## ADR-021 Households

Status: Accepted, 2026-10-18.

Decision: The cashier can tap several adult faces at the counter and group them into one `households` row with a label and a `payer_member_id`, for example "Sharma family, payer Rajesh". Any household member's arrival triggers the household alert. Each adult hears the line and consents individually; only adults present at the counter can be added; the age gate applies to every face.

Reason: In jewellery and textile shops the buyer is often a family, and the owner wants to greet the family, not only the payer. One alert per household keeps the owner's phone quiet.

Consequences: One label and one payer per household, but one consent record per member. Withdrawal by one member removes only that member's template.

Alternatives considered: Payer only (misses the arriving spouse).

## ADR-022 Hindi and English only for the MVP

Status: Accepted, 2026-10-18.

Decision: The app, the cashier script, the WhatsApp notice and the standee ship in Hindi and English. The owner picks the language in settings. Other languages are a later phase.

Reason: The pilot is 5 to 10 shops in one city, where these two cover owners and cashiers. Each language is a versioned script, a versioned notice and an approved WhatsApp template.

Consequences: Hindi appears in Devanagari with a romanised version beside it in every document. Template names carry the language (`_EN`, `_HI`).

Alternatives considered: English only (cashiers would improvise the line).

## ADR-023 Pricing hypothesis

Status: Accepted, 2026-10-18.

Decision: Hardware is sold at cost or a small margin, within the 15,000 rupee cap per shop. Software is 500 to 1,500 rupees per shop per month. Razorpay subscription billing comes after the pilot.

Reason: One retained 20,000 rupee customer per month pays for the subscription. Hardware at cost removes the "why so expensive" objection and keeps Remu's income in the recurring part.

Consequences: No billing code in the MVP. `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` sit in `.env.example` marked later.

Alternatives considered: Free hardware with a higher subscription (cash risk for Remu).

OPEN: The exact monthly price within 500 to 1,500 rupees, set after the pilot.

## ADR-024 Pilot segment: jewellery and textile shops in one city

Status: Accepted, 2026-10-18.

Decision: The 60-day pilot runs in 5 to 10 jewellery or textile shops in one city. Furniture, electronics showrooms, salons and barbers come later.

Reason: These shops have the highest ticket, the longest gap between visits and the strongest habit of greeting by name, so recognition on entry is worth the most and is easiest to measure.

Consequences: Success is the brief's pilot criteria: at least 60 percent enrolment, 85 percent alerted within 3 seconds, at most 1 false alert per shop per day, at least 50 percent Greeted, hardware at or under 15,000 rupees, setup under 60 minutes.

Alternatives considered: Salons (frequent visits, lower ticket).

OPEN: Which city hosts the pilot.

## ADR-025 The Greeted and Not them feedback buttons

Status: Accepted, 2026-10-18.

Decision: Every owner alert card has two buttons, "Greeted" and "Not them". Greeted writes `visits.greeted` and `visits.greeted_by`. Not them writes `visits.not_them`, marks a false match and feeds threshold tuning through the harness. The card reads: "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}".

Reason: The product's whole value is one greeting. Two taps give the only ground truth the pilot has: whether the alert was right and whether it was used.

Consequences: Both taps work offline and sync through `events_outbox`. A Not them carries the visit id and its 30-day clip on the hub, never a photo.

Alternatives considered: A star rating (too much at the shop door).

## ADR-026 Direct ONNX port of SCRFD and ArcFace, no `insightface` package

Status: Accepted, 2026-10-18 (build).

Decision: The hub runs the InsightFace model files directly on ONNX Runtime with its own SCRFD post-processing (`faces/detect.py`), Umeyama alignment (`faces/align.py`) and ArcFace embedding (`faces/embed.py`). The `insightface` Python package is not a dependency.

Reason: The package needs a C compiler to install and pulls in extra libraries, which complicates the Pi image. The port uses the same preprocessing as the reference: detection mean 127.5 and scale 1/128, recognition mean 127.5 and scale 1/127.5, and the age model with no external normalisation, because its graph starts with its own subtract and multiply nodes (checked on the model files).

Consequences: `faces/detect.py` must follow any change in the model pack's output layout. The eval harness (ADR-031) catches a preprocessing mistake as a drop in accuracy.

Alternatives considered: the `insightface` package (heavier install); OpenCV DNN face detectors (lower accuracy than SCRFD).

## ADR-027 The fixture video is generated from a public-domain portrait

Status: Accepted, 2026-10-18 (build). Closes the OPEN items in TASK-007 and TASK-013.

Decision: Unit tests and the fake gate camera use one committed face image, the NASA portrait of astronaut Eileen Collins (public domain, from scikit-image), in `hub/tests/fixtures/`. `cd hub && make setup` generates `tests/eval/samples/gate_fixture.mp4` from it with ffmpeg. Nothing is hosted or downloaded.

Reason: No customer and no volunteer appears in any test media, so no consent form or hosting is needed for development.

Consequences: Tests cover one identity only. Multi-person accuracy comes from the eval harness on LFW (ADR-031) and later the consented team set (TASK-122).

## ADR-028 The plain phone number is sealed in the outbox

Status: Accepted, 2026-10-18 (build).

Decision: The `notice_request` event's `phone_e164` is stored in `events_outbox` sealed with AES-GCM under a key derived from the shop key. The sync client unseals it only to send. Once the cloud acknowledges the event, the hub sets the field to null.

Reason: The privacy scanner found the plain number in the SQLite WAL file during the Phase 2 integration test. The contract allows the number in transit to the cloud, not at rest on the hub.

Consequences: The outbox needs the phone sealer configured, which `Hub` does at start. A test asserts the stored row holds no digits of the number.

## ADR-029 Shop key, device token and pairing code live outside the encrypted data directory

Status: Accepted, 2026-10-18 (build).

Decision: On the Pi the shop key is `/etc/remu/shop.key` (root, 0600), handed to the services with systemd `LoadCredential`. The device token and pairing code live in `/var/lib/remu/state` (`REMU_STATE_DIR`). The hub runs as the dedicated `remu` user. `ExecStartPre=+remu-hub unlock` runs as root, fetches the unlock token and opens the LUKS volume at `REMU_DATA_DIR`.

Reason: The unlock needs the shop key and the device token before the volume is open, so they cannot be inside it. `LoadCredential` keeps the key root-owned while an unprivileged service reads it.

Consequences: The honest limit in docs/SECURITY.md stands. A copied SD card holds the shop key and the device token, so it can fetch an unlock token until the device is revoked.

## ADR-030 The console demo stays in memory

Status: Accepted, 2026-10-18 (build). Deviates from TASK-033's demo bullet.

Decision: `remu-hub demo` keeps its Phase 1 behaviour: an in-memory gallery and an ephemeral key. The enrol-through-the-API-and-SQLite flow is covered by `tests/integration/test_enrol_then_match.py`, where test code sets the member active.

Reason: A demo that enrols through SQLite would have to activate the member itself, and TASK-033 also says no hub code path may activate a member. Test code may; shipped code may not.

## ADR-031 The 0.45 threshold is kept

Status: Accepted, 2026-10-18 (build). Records TASK-023.

Decision: `REMU_MATCH_THRESHOLD` stays at 0.45 for the pilot.

Evidence: see the eval numbers recorded in docs/RESEARCH.md section 7 (LFW, `buffalo_l:4c06341c`, centre face, 8,400 pairs). At 0.45 the false accept rate is 0 on every subset, and scrambled templates score exactly like raw ones.

Reason: 0.45 sits well above the threshold that gives a false accept rate of 0.1 percent, so a false alert is rare, which the brief prefers to a missed one. The 3 of 5 frame vote adds a second guard at the door.

Consequences: Two bars are not met yet. The `age_gap` subset has no licence-clean data source and is reported as missing. The harness exits with code 3 ("incomplete") until a consented age-gap set exists (TASK-122). Any threshold change needs a new harness report and an ADR.

OPEN: a licence-clean age-gap pair set; confirm the LFW terms allow internal development use.

## ADR-032 A phone member is a member row with no template

Status: Accepted, 2026-10-18 (build). Closes the TASK-048 OPEN provisionally.

Decision: "Skip face, save bill only" creates a `members` row with status `pending_notice`, no template and no consent record. It never enters the gallery and no notice is sent. If the same number later gets a face, the same member gains a template, a consent record and a notice.

Reason: This needs no new status value and keeps the state machine of docs/ARCHITECTURE.md section 5 unchanged.

OPEN: the lawyer confirms whether phone-only members need their own notice text before WhatsApp offers are sent to them.

## ADR-033 Pairing direction: the hub's code is typed into the app

Status: Accepted, 2026-10-18 (build). Closes the TASK-028 OPEN for the MVP; the founder may supersede it.

Decision: `remu-hub init` generates the pairing code and writes it to the sticker file. The owner types it into the app, which sends it to the hub (`POST /pair`) and to the cloud (`POST /api/devices/pair`). The hub receives its device token through the claim poll.

Reason: The hub never has to show a screen, and the full-stack test exercises this path end to end.

## ADR-034 The Pi image is built on a Pi

Status: Accepted, 2026-10-18 (build). Closes the first OPEN in TASK-098.

Decision: `scripts/build_pi_image.sh` runs on a freshly flashed Raspberry Pi OS Lite 64-bit (Bookworm, Python 3.11). The finished card is cloned for the other kits.

Reason: No cross-architecture container build to maintain, and the result is exactly what runs in the shop.

OPEN: whether SSH stays enabled on pilot hubs, and the update channel (signed wheel pulled by the hub, or manual SSH).

## ADR-035 jsonb parameters are cast through text in the cloud

Status: Accepted, 2026-10-18 (build).

Decision: Every jsonb SQL parameter in `cloud/src` is written `$n::text::jsonb`, with a test that fails on a bare `$n::jsonb`.

Reason: The postgres.js driver serialises jsonb-typed parameters itself, so pre-stringified JSON was stored as a JSON string. The PGlite unit tests did not show it; the full-stack test against real Postgres did. It affected inbox payloads and the JSON columns of mirrored consent records.

## ADR-036 Lock-screen notification text

Status: Accepted, 2026-10-18 (build), provisional. Closes the TASK-076 OPEN for the MVP.

Decision: The Android lock screen shows only "Member at the gate" (Hindi: "गेट पर मेंबर"). The name appears once the phone is unlocked.

Reason: An FCM push carries ids only. A name on the lock screen would show a customer's identity to anyone near the owner's phone.

## ADR-037 Dependencies added during the build

Status: Accepted, 2026-10-18 (build).

- Hub (Python 3.11): numpy, opencv-python-headless, onnxruntime, fastapi, uvicorn, httpx, cryptography, argon2-cffi, pydantic and sentry-sdk, pinned in `hub/uv.lock`.
- Cloud (Node 20): next, react, react-dom, zod, jose, postgres, @supabase/supabase-js 2.109.0 (the last release for Node 20), pdf-lib with @pdf-lib/fontkit and regenerator-runtime, and @sentry/nextjs. Tests use @electric-sql/pglite.
- App: AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, CameraX 1.3.4, ML Kit face detection 16.1.6, OkHttp 4.12, Media3 1.3.1, the Firebase BOM (applied only when `google-services.json` exists), sentry-android and zxing.
- Fonts: Inter and Noto Sans Devanagari under the OFL, with their licence files.
