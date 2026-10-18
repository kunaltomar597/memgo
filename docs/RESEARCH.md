# Remu Research Record

Purpose: the research done before coding, so that every product, legal and technical decision in the Remu repo can be traced to a user need, a competitor, a law or a measured fact. Written 2026-10-18. The product brief wins where this document and the brief disagree.

## 1. Users and their pain

### The owner

The owner of a jewellery, saree, textile, furniture or electronics showroom sells high-ticket items to people who visit rarely. A customer who spent 20,000 rupees a year ago expects to be recognised when they come back. The owner cannot hold hundreds of faces across years. When the owner is not at the door, nobody recognises anyone. The owner is the primary user of Remu, receives the alert on their own phone, and is the legal data fiduciary for member data.

What the owner wants: to walk up to a returning member and say their name, the occasion, and what they bought last time, before the member reaches the counter. What the owner fears: a wrong name said to a stranger, a customer who feels watched, and anything that slows billing.

### The cashier

The cashier already asks for a phone number at billing, because phone-number loyalty and WhatsApp offers are standard practice in India. Remu adds one spoken line and one tap. If the customer shrugs, the cashier skips the tap and the shop loses nothing. The cashier also types notes, groups households, searches members and handles removals. The cashier's constraint is time at the counter during a festival rush; anything over about 10 seconds per enrolment will be skipped.

### The customer, who becomes a member

The customer never installs anything and never touches a screen. Their reward is being greeted by a person who remembers them, plus 5 percent off the next visit. The surprise at the door is a product requirement: no screen, no beep, no visible camera reaction. The customer's pain is the opposite of the owner's: being forgotten after a big spend, and being treated as a stranger by new staff.

### The high-ticket, infrequent visit

The visit pattern that makes Remu worth building is one to two years between visits, 20,000 rupees and up per visit, and a purchase tied to a family occasion (wedding, festival, birth). Phone-number CRM tools help after the customer reaches the counter. Nothing today helps in the first 30 seconds at the door, which is when the greeting has to happen.

### Staff turnover

Retail floor staff change often. When a salesperson leaves, the memory of who bought what leaves with them. Remu keeps the memory in the shop's hub and the owner's phone, so a new cashier on day one can greet a member of three years' standing. The 1095-day expiry rule matches the longest visit gap the product is designed for.

OPEN: the pain points above come from the founder's design conversation, not from interviews. Interview at least 5 owners and 5 cashiers in the pilot city before Phase 3 and record the findings here.

## 2. Alternatives and competitors

### Phone-number CRM and billing tools common in India

- Khatabook: digital ledger and payment reminders for small merchants. Captures phone numbers and dues. No face recognition, no door alert, no consent record. Source: [Khatabook](https://khatabook.com/).
- Vyapar: GST billing, inventory and party ledger for small businesses, with WhatsApp bill sharing. Holds the purchase history Remu needs. No door recognition. Source: [Vyapar](https://vyaparapp.in/).
- Petpooja: restaurant POS with a loyalty and CRM layer keyed on phone number. Strong in food, not in jewellery or textiles. Source: [Petpooja](https://www.petpooja.com/).
- Zoho (Books, CRM, Bigin): general billing and CRM, phone and email keyed, strongest for firms with a sales team. Source: [Zoho CRM](https://www.zoho.com/crm/).

What these do well: capture the phone number, hold the ledger, send WhatsApp offers, and remind about dues. What they do not do: recognise a person at the entrance before anyone speaks. They all begin at the counter. The brief lists POS integrations with Vyapar, Tally and Petpooja as a later phase, so these are partners for purchase history as much as competitors.

### Global face-recognition retail vendors and past trials

- NEC VIP recognition trial in the UK around 2013 (NeoFace). Promised to spot VIP shoppers and alert staff. Did not spread beyond trials. Source: [Decision Marketing](https://www.decisionmarketing.co.uk/?p=20400).
- Shiba Denshi, Japan, 2008. A camera-plus-PC system to flag VIP customers to staff. Priced for large stores. Source: [TechCrunch, 2008-07-18](https://techcrunch.com/2008/07/18/new-face-recognition-system-helps-stores-to-identify-vip-customers/amp/).
- Advantech FaceView with CyberLink FaceMe. A current enterprise offering for VIP and member recognition in retail. Source: [CyberLink case study](https://www.cyberlink.com/faceme/insights/cases/retail/360/advantech-faceview-retail-faceme-facial-recognition).

What happened to them: the earlier trials stalled on price and privacy backlash, not on the technology. Hardware that cost a few lakh rupees per store in 2013 now costs about a tenth of that; the Remu hub is about 10,000 rupees. The privacy side is covered in section 4: every enforcement action found in this research was against covert collection from all shoppers, never against opted-in members.

### Why the phone number is the real competitor

The phone number already gives a shop most of the "remember the big spender" value: the ledger, the purchase history, the WhatsApp channel, and withdrawal by a STOP reply. It costs nothing and no customer objects to it. Remu does not try to replace it. A "phone member" in Remu is exactly this customer. The "face member" is the phone member who also heard the door line and said okay.

What Remu uniquely adds: recognition on entry, before the counter. The alert reaches the owner's phone in under a second of the match and within 3 seconds of the member entering, so the greeting happens while the member is still at the door. No phone-number tool can do that, because the phone number is not spoken until billing.

## 3. Technical feasibility

### Model choice

The hub uses InsightFace SCRFD for detection and ArcFace embeddings from the `buffalo_l` pack (ResNet50 trained on WebFace600K), with `buffalo_sc` as the fallback on weak CPUs, on ONNX Runtime CPU. `buffalo_l` reports 99.83 percent on LFW and 91.25 on the MR-ALL aggregate, and ships an age and gender model that gives the age gate. Source: [insightface on PyPI](https://pypi.org/project/insightface/).

OPEN: the InsightFace model packs are released for non-commercial research use only; the library code is MIT. Before the pilot, either obtain a commercial licence from the InsightFace authors or switch to a pack with a commercial licence, and re-run the evaluation harness in `tests/eval/`. This is a blocker for Phase 9.

### Age estimation

The age gate uses the age and gender model in the pack. Age estimators of this kind have a mean absolute error of several years on adult faces. A hard cut at 18 will therefore refuse some adults aged 18 to 23, a core wedding-buyer segment, and will pass some people aged 16 to 17. The gate is a DPDP control, so its error must be measured, not assumed. Policy stays: refuse when in doubt, no override.

OPEN: add an age subset to the evaluation harness in `tests/eval/` with a labelled set. Record the refusal rate for adults aged 18 to 25 and the pass rate for ages 15 to 17. Feed the numbers into the OPEN in docs/ARCHITECTURE.md on raising the cut-off above 18.

### Expected accuracy for one to two year gaps

ArcFace-class embeddings handle a one to two year gap, glasses and beards well for adults. Ageing in adults changes the embedding far less than pose and light do. The evaluation harness reports true accept rate at a false accept rate of 0.1 percent, with an age-gap pair subset, and must pass before any change to the model pack or the threshold 0.45 is merged. The starting threshold is conservative on purpose: a missed match costs a greeting, a false alert costs trust.

### Small galleries

A shop has a few thousand active members at most. Matching one probe against a few thousand 512-dimensional vectors is a single matrix multiply and runs in well under a millisecond on a Pi 5. More important, false alarm rate grows with gallery size, so a gallery of thousands rather than millions keeps false alerts rare at the same threshold. The 3 of 5 consecutive frame rule and the 10-minute cooldown per member cut false alerts further.

### Camera placement

Placement matters more than the model. The gate camera should be face-on, at eye level, with the member walking towards it, in decent light, and without the shop's doorway backlit behind the face. Most existing CCTV is mounted high and angled down, which gives foreheads and tops of heads. The setup flow in the app shows a live preview so the owner can aim; where the DVR camera is wrong, a dedicated IP camera at 1,500 to 2,500 rupees fixes it.

### Pi 5 capability

The hub decodes the RTSP stream at reduced resolution, for example 640 by 360 at 5 to 8 frames per second, through OpenCV's FFmpeg backend. Detection with SCRFD at that size and a ResNet50 embedding for each detected face is within reach of the Pi 5's four Cortex-A76 cores, and `buffalo_sc` is the fallback. The Hailo-8L accelerator at about 6,000 rupees is a later option for busy shops.

OPEN: no published Pi 5 benchmark for `buffalo_l` was found. Measure detection and embedding latency separately on the Pi 5 in Phase 7 and record the numbers here. The target is a match in under a second and an alert within 3 seconds of entry.

### RTSP availability on CP Plus, Hikvision and Dahua

All three expose RTSP streams from the DVR or NVR, and the main and sub streams are addressed by URL patterns that differ per brand. CP Plus hardware is largely Dahua OEM and follows the Dahua pattern. Sources: [Hikvision RTSP URL format](https://www.hikvision.com/en/support/faq/how-to-get-rtsp-stream-via-hikvision-devices/), [Dahua RTSP URL format](https://dahuawiki.com/Remote_Access/RTSP_via_VLC). The setup flow discovers candidate RTSP URLs and lets the owner pick the gate stream. The hub reads the sub stream to keep decode cheap.

OPEN: CP Plus has no single public RTSP document; confirm the URL pattern on the pilot shops' actual DVR models in Phase 7.

### WhatsApp Cloud API

The cloud sends the invoice-plus-notice as a pre-approved utility template with a quick-reply button, and receives sent, delivered, read, STOP and thumbs-up events by webhook. Needs a Meta Business account, a WhatsApp Business Account, a dedicated number and template approval. From 1 July 2025 Meta bills per delivered template message by category, with volume tiers for utility messages. Source: [Meta WhatsApp pricing](https://developers.facebook.com/docs/whatsapp/pricing). The delivery receipt is what moves a member from PENDING_NOTICE to ACTIVE.

### Firebase Cloud Messaging

Owner mode receives alerts by FCM push when off the shop WiFi and by LAN WebSocket when on it. FCM is free and reaches an Android phone with the app installed in a few seconds under normal conditions. Two conditions apply. First, alerts must be sent at priority high; data-only messages at normal priority are deferred while the phone is in Doze. Second, Xiaomi, Vivo, Oppo and Realme phones, which are common in the hands of Indian retail owners, kill background apps and drop pushes unless the app is exempted from battery optimisation and autostart. The Owner mode setup wizard (TASK-106) must walk the owner through that exemption, and the 3-second latency QA (TASK-121) must include phones from those brands. Source: [FCM documentation](https://firebase.google.com/docs/cloud-messaging). The LAN path exists because FCM latency is not guaranteed and the shop's internet may be down; matching continues offline and the LAN alert still fires.

## 4. Legal landscape

This section is research, not legal advice. A lawyer must sign off the exact script and notice wording before the pilot.

### DPDP Act 2023 and the 2025 Rules

The Digital Personal Data Protection Act 2023 is the legal basis for consent. The Rules were notified in November 2025 with a phased start: institutional setup (the Data Protection Board) on notification; the consent manager framework (Rule 4) one year later, on 13 November 2026; and the remaining obligations on notice, consent, security, retention and erasure (Rules 3, 5 to 16, 22 and 23) eighteen months later, on 13 May 2027. Sources: [PIB summary of the Rules](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/nov/doc20251117695301.pdf), [Mondaq on the final Rules](https://webiis08.mondaq.com/india/data-protection/1775140/from-draft-to-reality-key-changes-in-indias-dpdp-rules-2025), [Uniqus timeline](https://uniqus.com/digital-personal-data-protection-act-timelines/), [Taxmann analysis](https://www.taxmann.com/post/blog/analysis-indias-dpdp-act-and-rules/), [Maheshwari and Co guide](https://www.maheshwariandco.com/blog/dpdp-act-compliance-guide-2025/), [Outlook Business on the notification](https://www.outlookbusiness.com/amp/story/deeptech/tech/govt-notifies-dpdp-rules-experts-flag-need-for-data-mapping-consent-systems).

The dates 13 November 2026 and 13 May 2027 are fixed by the brief and used in every document. Some secondary sources print the Gazette publication date as 14 November 2025 instead of 13. Before the pilot, the lawyer confirms the three dates against the Gazette text as a check. This does not change the dates Remu builds to.

What this means for Remu: consent must be informed, specific, and given by a clear affirmative act, and the shop carries the burden of proving it. The brief's design answers each point: the spoken line names the purpose, the enrol tap is the timestamp, the WhatsApp notice is the written record, and the append-only hash-chained `consent_records` table with cloud mirror and one-tap export is the proof. The pilot runs before 13 May 2027, so Remu will be built as if those obligations already apply.

### Penalties

The Schedule to the Act sets ceilings per instance: up to 250 crore rupees for failing to take reasonable security safeguards to prevent a personal data breach, and up to 200 crore for failing to notify the Board and affected people of a breach. Children's data obligations carry up to 200 crore. Sources: [Konfirmity on the Schedule](https://www.konfirmity.com/blog/dpdp-penalties), [Rainmaker on enforcement](https://rainmaker.co.in/penalties-enforcement-under-indias-dpdp-act-2023-18-month-dpbi-action-plan-for-indian-companies/), [AMLEGALS](https://amlegals.com/penalties-under-the-digital-personal-data-protection-act2023/). These are maximums set by the Board, not fixed fines. The security ceiling is why no face photo is ever stored and the only biometric is a cancelable template on the hub. The children's ceiling is why the age gate has no override.

### IT Act 2011 Sensitive Personal Data rules

The Information Technology (Reasonable Security Practices and Procedures and Sensitive Personal Data or Information) Rules 2011 already list biometric information as sensitive personal data under Rule 3, and Section 43A of the IT Act makes a body corporate that is negligent with it liable to compensate the affected person. These rules stay in force until the DPDP obligations replace them. Sources: [Mondaq summary](https://www.mondaq.com/india/data-protection/626190/information-technology-reasonable-security-practices-and-procedures-and-sensitive-personal-data-or-information-rules-2011), [S.S. Rana summary](https://ssrana.in/articles/information-technology-reasonable-security-practices-and-procedures-and-sensitive-personal-data-or-information/). So a scrambled template is sensitive data today, before DPDP enforcement, and the shop and Remu must treat it that way from the first pilot day.

### 2023 dark patterns guidelines

The Central Consumer Protection Authority notified the Guidelines for Prevention and Regulation of Dark Patterns on 30 November 2023 under the Consumer Protection Act 2019. The list names "basket sneaking", which is sneaking items or terms into a transaction without the consumer's choice. Sources: [SCC Online](https://www.scconline.com/blog/post/2023/12/04/ccpa-notifies-guidelines-for-prevention-and-regulation-of-dark-patterns-2023-legal-news/), [JSA Law](https://www.jsalaw.com/newsletters-and-updates/ccpa-issues-guidelines-for-prevention-and-regulation-of-dark-patterns-2023/). For Remu: consent buried in bill terms and conditions is not consent. The line must be spoken, the notice must be plain, and the thumbs-up button is optional and changes nothing if not tapped.

### Precedents

- 7-Eleven Australia, determination published 14 October 2021. The OAIC found that collecting faceprints from customer feedback tablets without notice or consent breached the Privacy Act and ordered all faceprints destroyed. Faceprints were treated as biometric information. Source: [OAIC](https://www.oaic.gov.au/newsroom/oaic-finds-against-7-eleven-over-facial-recognition).
- Rite Aid, United States, December 2023. The FTC banned Rite Aid from face recognition for five years after thousands of false matches led staff to wrongly follow and accuse customers, and ordered the data and algorithms deleted. Source: [AP, 2023-12-20](https://kvia.com/news/ap-national/2023/12/20/rite-aid-banned-from-using-facial-recognition-technology-in-stores-for-five-years/).
- Bunnings, Australia, published 19 November 2024. The OAIC found that scanning every shopper to catch a few high-risk people was disproportionate and breached the Privacy Act. Source: [OAIC factsheet](https://oaic.gov.au/__data/assets/pdf_file/0027/243936/Bunnings-determination-factsheet.pdf), [MinterEllison](https://www.minterellison.com/articles/bunnings-under-the-hammer-for-facial-recognition-breach).

The common thread: covert collection from everyone, no consent, and in Rite Aid's case false matches acted on by staff. Remu's answers are opt-in only, unenrolled faces discarded within seconds, the conservative 0.45 threshold with 3 of 5 frames, the "Not them" button, and an alert that goes to the owner's phone rather than triggering any action against the customer.

## 5. Costs

### Hardware per shop

| Item | Rupees |
| --- | --- |
| Existing DVR or NVR with RTSP | 0 |
| Optional dedicated gate IP camera | 1,500 to 2,500 |
| Raspberry Pi 5 kit (4 GB or 8 GB, case, power, storage) | 9,000 to 10,000 |
| Counter phone (spare Android, stand, charger) | 0 to 6,000 |
| Counter standee | about 200 |
| Total | 10,000 to 15,000 |

Later: Hailo-8L about 6,000 rupees for very busy shops; a 5,000 rupee Bluetooth thermal printer for printed bills. The cap is 15,000 rupees per shop and the pilot success criteria measure it.

### Messaging

One utility template per enrolment, well under one rupee per message. A shop enrolling 300 members a month spends under 300 rupees on WhatsApp. STOP replies and thumbs-up taps arrive inside the service window at no charge. Source: [Meta WhatsApp pricing](https://developers.facebook.com/docs/whatsapp/pricing).

OPEN: confirm the current India utility rate in rupees from Meta's rate card, and the markup if a business solution provider is used instead of the Cloud API directly.

### Cloud

Supabase in the Mumbai region (AWS ap-south-1) and Vercel functions in Mumbai (bom1). The cloud holds no biometrics and only mirrors small tables, so the pilot of 5 to 10 shops fits in the free or lowest paid tiers of both. Sources: [Supabase pricing](https://supabase.com/pricing), [Vercel pricing](https://vercel.com/pricing). Sentry's free tier covers pilot error tracking. FCM is free.

### Pricing check

Software at 500 to 1,500 rupees per shop per month. One retained 20,000 rupee customer per month pays for it many times over. Hardware is sold at cost or a small margin.

## 6. Open research questions

- OPEN: Local language needs by city. The MVP ships Hindi and English. The pilot city decides whether the cashier line and the WhatsApp notice need Marathi, Gujarati, Tamil, Telugu, Kannada or Bengali first. Pick the pilot city, then confirm with the 5 to 10 pilot shops before Phase 3.
- OPEN: Opt-in rate. The pilot target is at least 60 percent of billed adult customers with a phone agreeing to face membership. There is no Indian data on this. Measure it in the first two weeks of the 60-day pilot and revisit the 5 percent discount if the rate is low.
- OPEN: Real false alert rate at doors. The target is at most 1 false alert per shop per day, measured by "Not them" taps. Lab numbers at a false accept rate of 0.1 percent do not predict a backlit doorway in Jaipur at 7 pm. Measure per shop and per camera in the pilot and tune the threshold 0.45 with the harness.
- OPEN: DVR brands in the pilot city. Survey the 5 to 10 pilot shops for DVR brand, model, firmware, and whether the sub stream is enabled, before Phase 7. CP Plus is expected to dominate; confirm.
- OPEN: Owner interviews, InsightFace commercial licence, age estimator error, Pi 5 latency and WhatsApp rupee rate, as marked in the sections above.
