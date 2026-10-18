# Remu Product Requirements Document

Purpose: this document says what Remu is and why it exists (WHAT and WHY); ARCHITECTURE.md says how. Every fact comes from the product brief dated 2026-10-18, which wins over this document. This is not legal advice.

## 1. Product

- Name: Remu, from "remember".
- One line: Remu is a "shop memory" for Indian retail. It recognises an opted-in returning customer at the shop entrance and pings the shopkeeper's phone with who they are and what they bought, so the shopkeeper can greet them by name.
- Tagline: "Remu remembers."

## 2. Problem

High-ticket, infrequent, relationship-driven retail depends on the owner remembering customers. Jewellery, sarees and textiles, furniture and electronics showrooms first; salons and barbers later.

A customer who spent 20,000 rupees a year ago expects to be recognised on the next visit. Owners cannot remember hundreds of faces across years. When staff leave, the memory leaves with them.

Phone-number loyalty and CRM tools (Khatabook, Vyapar, Petpooja, Zoho) already capture most of the "remember the big spender" value, but only at the counter. Remu's unique value is recognition on entry, before the counter.

## 3. Target users

| Role | Who | What they do with Remu |
| --- | --- | --- |
| Owner (primary user) | The shop owner, on their own phone in Owner mode | Receives the alert, greets the member, owns the relationship. The legal "data fiduciary" for member data (the one answerable for the data under the law: the shop). |
| Cashier (secondary user) | Shop staff on the counter phone in Cashier mode | Enrols members at billing, types notes, edits households, handles removals. |
| Member (beneficiary) | A customer who gave a number for the bill and said okay to the door line | Installs nothing, never touches a screen. Is greeted by name and gets 5 percent off the next visit. |

Remu (the company) is the "data processor" (it does the work on the shop's instructions). It supplies the hub, the app and the cloud, and never holds a face. The member is the "data principal" (the person the data is about).

Pilot segment: 5 to 10 jewellery or textile shops in one city, for 60 days. High bill values, visits months apart, phone numbers already taken for bills.

## 4. Goal

A shop greets a returning member by name, with specifics, within 3 seconds of them walking in. The member carries no card, installs no app, and is never visibly scanned. Consent is opt-in and provable under the Digital Personal Data Protection Act 2023 (DPDP). Hardware costs 15,000 rupees or less per shop, and a non-engineer sets it up in under 60 minutes.

## 5. The core moment

An afternoon at a jewellery shop. Rajesh Sharma walks in. His last visit was 2025-10-11, a year ago, when he bought a gold chain for 20,000 rupees. He said okay at billing that day, his WhatsApp notice was delivered, and he has been ACTIVE since.

The gate camera's RTSP stream (the video feed a DVR already gives out) reaches the hub. The hub matches his face against the shop's ACTIVE templates in under a second, writes a visit, saves a 5-second clip and sends an alert. The owner's phone buzzes:

"Rajesh Sharma · Sharma family, payer Rajesh · Last visit 2025-10-11 · ₹20,000, gold chain · Note: daughter's wedding next winter"

Nothing happens at the door. No screen, no beep, no visible camera reaction. The owner pockets the phone, walks over, greets Rajesh by name and asks about the wedding. Later, the owner taps "Greeted".

Rajesh feels remembered by a person, not scanned by a machine. Preserving this surprise is a product requirement. Everything visible is on the shopkeeper's side. (Names and details are illustrative.)

## 6. Principles

Founder decisions, fixed for every phase.

1. Opt-in membership, never covert. The legal basis is DPDP, and a lawyer must sign off the exact script and notice wording before the pilot.
2. Consent is taken as hospitality, not as a form. The cashier says the line, the customer says okay or nods, and the cashier's enrol tap on the face is the consent timestamp; there is no consent button.
3. A written notice accompanies the consent, on the WhatsApp invoice now and the printed bill later, naming the purpose plainly: "we recognise you at the door". An optional one-tap thumbs-up turns verbal consent into written consent.
4. A member becomes ACTIVE for gate recognition only after the WhatsApp delivery receipt confirms the notice. Until then the member is PENDING_NOTICE and is never matched.
5. No face photos are stored anywhere, ever. The only stored biometric is a cancelable template on the shop's hub, scrambled with a per-shop secret key; the cloud never receives a face image, a template or a clip.
6. Consent records are append-only, hash-chained (each record carries a fingerprint of the one before, so any edit shows), versioned by script and notice version, stored separately from biometrics, mirrored to the cloud, and exportable as PDF and CSV in one tap.
7. A WhatsApp reply "STOP" or a tap on "Remove member" deletes the template within one hour and logs the deletion in the same chain. The consent record is never deleted, because it proves the deletion.
8. A member unseen for 3 years (1095 days) is deleted and logged. Match clips are kept 30 days, then deleted.
9. No minors. The hub estimates age at enrolment and refuses an estimate under 18, with no override.
10. Unenrolled faces at the gate are discarded within seconds. Nothing is stored about non-members.
11. Cheap and simple. Existing CCTV over RTSP, a hub of about 10,000 rupees and the owner's own phone: at or under 15,000 rupees per shop, set up by a non-engineer in under 60 minutes.
12. Two member layers. A "phone member" gives a number for the bill; a "face member" also said okay to the door line. If the customer shrugs or walks away, the cashier skips the tap and the shop loses nothing.

## 7. Core features

1. Gate recognition on the hub. RTSP in, detect, embed, scramble, match against ACTIVE templates at cosine similarity threshold 0.45 (a 0 to 1 score of how alike two faces are), 3 of 5 consecutive frames agreeing, one alert per member per 10 minutes.
2. Enrolment in Cashier mode. Live preview, the largest and closest face pre-selected, fields for phone number, bill amount, items, occasion and notes, the script line on screen, one "Make member" button. The line, v1.0, shown in Hindi and English: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.)
3. Households. Several adult faces grouped with a label and a payer. Any member's arrival triggers the household alert.
4. Age gate. The hub refuses enrolment when the estimated age is under 18.
5. Consent records. Append-only, hash-chained, versioned, mirrored to the cloud without biometrics.
6. WhatsApp notice. The invoice-plus-notice template with the thumbs-up ("👍") quick-reply button, and webhooks for sent, delivered, read, STOP and thumbs-up.
7. Member status. PENDING_NOTICE to ACTIVE on delivery, to WITHDRAWN on STOP or removal, to EXPIRED after 1095 days unseen.
8. Owner alerts. Firebase Cloud Messaging (FCM, Google's phone push service) and LAN WebSocket, a card with name, household, last visit, last purchase and notes, and the buttons "Greeted" and "Not them".
9. Member card in Owner mode. Purchases, notes, visit timeline, 5-second clips over the LAN only.
10. Withdrawal and deletion. STOP or "Remove member" deletes the template within one hour and logs template_deleted_at in a new chained record.
11. Nightly jobs. Expiry of members unseen 1095 days and clip cleanup after 30 days, both logged.
12. Consent export. PDF and CSV in one tap, signed with the export key.
13. Setup and pairing. Create the shop, pair the hub by code, pick the gate RTSP stream, aim the camera with a live preview, print the standee: "Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।" (Members ko 5% off. Na card, na app.)
14. Offline operation. The hub keeps matching and enrolling; an outbox syncs later; no member goes ACTIVE until delivery is confirmed.
15. Hindi and English UI, staff PINs, device status, health reporting.

## 8. User stories

Owner

- As an owner, I want my phone to buzz with the member's name, household, last visit, purchase and notes as they walk in, so that I can greet them with specifics.
- As an owner, I want to tap "Greeted" or "Not them" on each alert, so that false matches are counted and matching gets tuned.
- As an owner, I want to export every consent record as PDF and CSV in one tap, so that I can prove consent when asked.

Cashier

- As a cashier, I want the script line on screen in Hindi and English, so that I say the same words to every customer.
- As a cashier, I want to group the adults at the counter into a household with a payer, so that any of them arriving triggers the alert.
- As a cashier, I want to search for and remove a member, so that I can honour a request to leave on the spot.

Member

- As a member, I want to be greeted by name, so that the shop feels like it knows me.
- As a member, I want to be told plainly at billing and on WhatsApp that the shop recognises me at the door, so that I know what I agreed to.
- As a member, I want to leave by replying STOP, so that my template is deleted within one hour.

## 9. MVP scope (v0.1, pilot release)

- [ ] Hub pipeline: RTSP to detect to embed to scramble to store to match to alert
- [ ] Evaluation harness for the match threshold
- [ ] Enrolment from the counter phone (Cashier mode)
- [ ] Households
- [ ] Age gate
- [ ] Consent records with hash chain and cloud mirror
- [ ] WhatsApp notice send with delivery, STOP and thumbs-up webhooks
- [ ] PENDING_NOTICE to ACTIVE gating
- [ ] FCM and LAN alerts
- [ ] Owner alert screen with Greeted and Not them
- [ ] Member card: purchases, notes, visit timeline with clips over LAN
- [ ] Remove member, withdrawal and deletion within one hour
- [ ] Nightly expiry job
- [ ] Consent export CSV and PDF
- [ ] Device pairing and provisioning
- [ ] Offline operation with outbox
- [ ] Hindi and English UI
- [ ] Pi 5 image with encrypted data directory
- [ ] Health reporting
- [ ] Basic error tracking (Sentry)

## 10. Out of scope for MVP

Later phases, in the brief's order of likelihood:

1. Phone-as-hub (Tier 0): the counter phone decodes the gate RTSP and matches, zero extra hardware
2. Bluetooth thermal printer bill with the member line
3. Owner web dashboard
4. Razorpay subscription billing
5. SMS fallback for customers without WhatsApp
6. POS integrations (Vyapar, Tally, Petpooja)
7. DVR playback jump to a visit
8. WhatsApp broadcast and offer campaigns
9. Loyalty points ledger beyond the 5 percent next-visit flag
10. Multi-shop chains
11. iOS app
12. Hailo acceleration
13. Additional languages beyond Hindi and English
14. Optional audio or video consent clip at enrolment
15. Fuzzy commitment or homomorphic matching

## 11. Success criteria

| Metric | Target |
| --- | --- |
| Billed adult customers with a phone who agree to face membership | At least 60 percent |
| Notices delivered within 2 minutes | At least 90 percent |
| Returning ACTIVE members alerted within 3 seconds of entering | At least 85 percent |
| False alerts per shop per day (measured by Not them) | At most 1 |
| Alerts on which the owner taps Greeted | At least 50 percent |
| Stored face photos | Zero |
| Members with a consent record and a delivered notice | 100 percent |
| STOP requests with the template deleted within one hour | 100 percent |
| Hub uptime, with automatic restart | At least 99 percent |
| Matching continues through internet outages | Every outage |
| Hardware cost per shop | At or under 15,000 rupees |
| Setup time by a non-engineer | Under 60 minutes |

## 12. Pricing hypothesis

- Hardware at cost or a small margin: 10,000 to 15,000 rupees per shop.
- Software at 500 to 1,500 rupees per shop per month.
- One retained 20,000 rupee customer per month pays for it.
- Razorpay billing comes after the pilot; any pilot charges are collected manually.

## 13. Risks and assumptions

Risks

- Legal. DPDP Rules were notified in November 2025; the consent manager framework starts 13 November 2026; notice, consent, security, retention and erasure obligations become enforceable on 13 May 2027, with penalties up to 250 crore rupees. The burden of proving consent is on the shop. Mitigation: lawyer sign-off, the hash-chained log, the thumbs-up upgrade to written consent.
- Backlash. Earlier retail VIP recognition (Shiba Denshi, NEC, Advantech FaceView) stalled on price and privacy; 7-Eleven Australia (2021) and Rite Aid (2023) were punished for covert face data. Mitigation: never covert, no photos, the standee, one-step STOP.
- False alerts. A wrong greeting embarrasses the owner. Mitigation: threshold 0.45 prefers a missed match, 3 of 5 frames, the evaluation harness, "Not them" feedback.
- Camera placement. Face-on, eye level and decent light matter more than the model. Mitigation: live aiming preview in setup, optional gate camera at 1,500 to 2,500 rupees.
- WhatsApp dependency. No WhatsApp means no activation in MVP, and Meta template approval takes time. Mitigation: start approval early in Phase 4; SMS fallback is a later phase.
- Hub theft. Mitigation: encrypted data directory unlocked by a device-bound cloud token, shop key in a root-only file; limits in SECURITY.md.
- Low opt-in. Below 60 percent, the gallery stays small and alerts are rare. Mitigation: the 5 percent discount, the script, the standee.

Assumptions

- Pilot shops have a DVR or NVR with RTSP, or accept a dedicated gate camera.
- The shop has WiFi shared by the hub, the counter phone and the owner's phone.
- Most pilot customers have WhatsApp on the number they give for the bill.
- A spare Android phone (minimum SDK 26) is available or costs up to 6,000 rupees.
- ArcFace-class embeddings handle a one to two year gap, glasses and beards for adults.

## 14. Open questions

OPEN: lawyer sign-off on script v1.0, notice v1.0 and the standee text before the pilot.
OPEN: which city and which 5 to 10 jewellery or textile shops for the 60-day pilot.
OPEN: whether pilot shops pay for software during the 60 days, and at what price in the 500 to 1,500 rupee range.
OPEN: how the 5 percent next-visit discount is applied at the counter, since Remu only holds discount_flag.
OPEN: whether the Hindi WhatsApp notice keeps "Member since" in English or becomes fully Hindi.
