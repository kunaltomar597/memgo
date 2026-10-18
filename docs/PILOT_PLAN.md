# Remu Pilot Plan

Purpose: how we run the 60-day pilot in 5 to 10 jewellery or textile shops in one city, what we measure each week, and how we decide go or no-go.

This is Phase 9 in TASKS.md. It starts after Phase 8. Days count from Day 1, the day the first shop goes live. Written 2026-10-18.

OPEN: the pilot city.
OPEN: the start date; Day 1 should sit a few weeks before a wedding or festival season, when customers return.
OPEN: the lawyer who signs off the script, notice and standee wording before Day 1.

## 1. Goals and success criteria

The pilot tests opt-in offered as hospitality, recognition that owners act on, the consent and deletion chain with real customers, and willingness to pay 500 to 1,500 rupees per month.

| Measure | Target |
|---|---|
| Billed adult customers with a phone who agree to face membership | At least 60 percent |
| Notices delivered within 2 minutes | At least 90 percent |
| Returning ACTIVE members alerted within 3 seconds of entering | At least 85 percent |
| False alerts, measured by Not them | At most 1 per shop per day |
| Alerts where the owner taps Greeted | At least 50 percent |
| Stored face photos | Zero |
| Members with a consent record and a delivered notice | 100 percent |
| STOP requests deleted within one hour | 100 percent |
| Hub uptime (automatic restart, matching through internet outages) | At least 99 percent |
| Hardware cost per shop | At or under 15,000 rupees |
| Setup time by a non-engineer | Under 60 minutes |

## 2. Shop selection

5 to 10 shops in one city, mixing both trades; textile customers return more often inside 60 days. Every shop must have:

- Jewellery, or sarees and textiles, where one customer can be worth 20,000 rupees a year.
- The owner present daily, to receive the alert and greet.
- A DVR or NVR with RTSP (CP Plus, Hikvision, Dahua), or room for a gate IP camera.
- WiFi with internet, and sockets at the counter and at the hub.
- One main entrance.
- A counter where a phone on a stand faces the customer while its screen faces the cashier.
- A cashier willing to say the line at every bill; an owner willing to host a weekly visit.
- At least 10 bills a day (assumption).

Each shop signs a pilot agreement naming the owner as data fiduciary and Remu as data processor. OPEN: its wording, with the lawyer.

## 3. Hardware bill of materials

| Item | Cost in rupees |
|---|---|
| Existing DVR or NVR with RTSP | 0 |
| Optional dedicated gate IP camera, face-on at eye level | 1,500 to 2,500 |
| Raspberry Pi 5 kit (4 GB or 8 GB, case, power supply, microSD or NVMe) | 9,000 to 10,000 |
| Spare Android counter phone (minimum SDK 26) with stand and charger | 0 to 6,000 |
| Counter standee | About 200 |
| Total per shop | 10,000 to 15,000 |

Record the actual spend per shop.

OPEN: whether Remu lends the pilot hardware and collects it on a no-go, or shops buy it at cost on Day 1.

OPEN: a small UPS for the hub, inside the 15,000 rupee cap (SECURITY.md section 3.1). The hub keeps no unlock token on disk, so a hub that reboots during an internet outage stays locked until internet returns. Power cuts and internet drops often arrive together in Indian shops.

## 4. Site survey and camera placement

Placement matters more than the model. Check:

- Face-on. The camera sees the face from the front, not from the side or above.
- Eye level. Lens 1.6 to 1.8 metres from the floor.
- Distance. 1 to 2 metres to the capture point, one or two steps inside the door (assumption). The arithmetic: the hub decodes at 640 by 360 and ignores faces under 40 pixels (ARCHITECTURE.md section 7), and recognition quality falls sharply below about 60 pixels. With a typical 2.8 to 3.6 mm CCTV lens a face is roughly 20 to 25 pixels wide at 3 metres, about 35 pixels at 2 metres and about 60 pixels or more inside 1.5 metres. A capture point at 3 metres or more gives almost no usable face. Treat 60 pixels as the working minimum.
- Good light on the face at the capture point. If the entrance is dim, add one light above the camera.
- No backlight from the door. A bright doorway behind the customer gives a dark face and no match. Angle the camera or move it to one side so the doorway is out of frame.
- Network and power. The hub must reach the DVR on the shop LAN; prefer Ethernet to the DVR's router. Sockets for hub and phone.
- Phones to hub. The counter phone and the owner's phone must sit on the same WiFi or LAN segment as the hub, with AP or client isolation off and mDNS allowed. Guest WiFi, ISP routers with isolation on, or a DVR on its own switch or VLAN block pairing, enrolment, LAN alerts and clips without any error on screen. Before pairing, open the hub's health page (`GET /health` on port 8443) from both phones and confirm it answers.

If the existing camera fails face-on, eye level or the face-size minimum, install the gate IP camera with a narrower lens (6 mm or longer, assumption) aimed at the capture point; it fits inside the 15,000 rupee cap. DVR sub-streams are often CIF or D1 on CP Plus and Dahua, which makes the face even smaller; where the sub-stream is below 720p, use the main stream for the gate or the dedicated camera. OPEN: whether the hub decodes at 1280 by 720, or crops the door region of the main stream, and raises its minimum face to about 60 pixels (ARCHITECTURE.md section 7).

Preview check. In Owner mode after pairing, open the live preview of the gate stream. A staff member walks in at normal speed three times. Each walk-in must show a face box before the counter, and the box must be at least about 60 pixels wide in the 640-pixel frame (roughly one tenth of the frame width). Re-aim, move the capture point closer or change the lens until it does.

## 5. Onboarding checklist, in order

1. Survey (section 4).
2. DVR RTSP discovery in Owner mode; needs the DVR admin password.
3. Hub install and pairing: Pi on the LAN, create the shop from Owner mode, type the hub's pairing code from its sticker (OPEN: the founder confirms this direction, docs/ARCHITECTURE.md flow A). Start the stopwatch.
4. Gate stream selection and aiming, with the preview check.
5. Counter phone on its stand, wall-powered, Cashier mode logged in, screen to the cashier, camera to the customer.
6. Standee where the paying customer reads it: "Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।" (Members ko 5% off. Na card, na app.)
7. Staff PINs in Owner mode, one per cashier, so consent_records.cashier_id is correct. Stop the stopwatch and record the time.
8. Training, sections 6 and 7.
9. Lawyer-approved notice loaded: script_version and notice_version at the approved v1.0 values, Meta-approved template names set in the cloud. Send one notice to a Remu phone.
10. First test enrolment with staff: number, line, tap, Make member. Notice arrives, member turns ACTIVE, staff walks in, alert within 3 seconds, owner taps Greeted. Then Remove member; confirm template_deleted_at within one hour.
11. Go live: the cashier says the line at the next real bill.

Steps 3 to 7 are the setup-time measure.

## 6. Cashier training

The line, said while handing over the bill, looking at the customer:

- Hindi: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।"
- Romanised: "Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega."
- English: "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off."

Order at every bill: phone number, the line, wait for the okay or nod, tap the face, fill in amount, items and occasion, tap Make member.

Skip the tap if the customer says no, shrugs, hesitates or walks away. Skipping costs nothing; they stay a phone member. Never tap after the customer has turned away.

Households. Two or more adults paying together: say the line to the group, looking at each adult in turn. Wait for each adult to say okay or nod. Tap only the faces of the adults who did. An adult who shrugs, hesitates or says nothing is left out of the household and stays a phone member. Then label the household, for example "Sharma family, payer Rajesh". Only adults at the counter can be added; nobody is added on another person's say-so.

Under-18 refusal. If the hub refuses a face, there is no override and no retry. The phone shows "18 से कम / Under 18" to the cashier only; the customer never sees it. Never mention age. Say a neutral line and finish the bill:

- Hindi: "अभी मेंबरशिप नहीं बन पाई, कोई बात नहीं।"
- Romanised: "Abhi membership nahin ban paayi, koi baat nahin."
- English: "Membership did not go through this time, no problem."

If a customer asks how it works, or "How did you know me?", use the one approved answer. It lives in PRIVACY_AND_CONSENT.md section 16 and is copied here word for word; the lawyer signs off that one text with the script and notice.

- Hindi: "आपने पिछली बार बिल के समय हमारी मेंबरशिप ली थी। हम मेंबर्स को दरवाज़े पर कैमरे से पहचानते हैं। हम आपकी फोटो नहीं रखते। हर विज़िट की एक छोटी क्लिप दुकान के बॉक्स में 30 दिन रहती है, फिर मिट जाती है। अगर नहीं चाहिए तो WhatsApp पर STOP लिखिए, एक घंटे में हट जाएगा।"
- Romanised: "Aapne pichhli baar bill ke samay hamari membership li thi. Hum members ko darwaaze par camera se pehchaante hain. Hum aapki photo nahin rakhte. Har visit ki ek chhoti clip dukaan ke box mein 30 din rehti hai, phir mit jaati hai. Agar nahin chahiye to WhatsApp par STOP likhiye, ek ghante mein hat jaayega."
- English: "You joined our membership at the bill last time. We recognise members by camera at the door. We do not keep your photo. A short clip of each visit stays on the shop's own box for 30 days and is then deleted. If you don't want this, reply STOP on WhatsApp and it is removed within an hour."

OPEN: the lawyer approves this answer with the script and notice (PRIVACY_AND_CONSENT.md section 16). It carries no version id of its own; the only version ids are hi-IN-v1.0 for the cashier line and wa-v1.0 for the notice.

No WhatsApp on that number: the member shows as "Notice pending" (सूचना बाकी, Soochna baaki) and the door will not recognise them. Tell the customer the offer is on WhatsApp only.

Removing a member. Cashier mode: member search, open the member, tap Remove member. The template goes within one hour; the consent record keeps the deletion as proof. A WhatsApp STOP does the same.

## 7. Owner training

Alerts reach your phone on the shop WiFi and on mobile data. The card reads "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}". Keep the phone on you with vibration on, so you feel every alert, and check the phone when it buzzes. Do not use a ringtone: a sound at the door gives away the recognition and spoils the surprise. Walk over and greet by name with one specific from the card. Do not bring up the camera yourself. If the customer asks, use the answer in section 6.

Greeted and Not them. Tap one on every alert. Not them marks a false match and feeds accuracy tuning. No tap counts as not greeted. A member is not alerted again for 10 minutes.

Notes. After the visit, add a note on the member card; it shows on the next alert. Purchases, the visit timeline and clips (shop WiFi only, 30 days) are on the same card.

Export. Settings, then Export: a signed PDF and CSV of every consent record.

If device status shows the hub offline, first check that the shop internet is up. Only when the internet is up: switch the hub off, wait ten seconds, switch it on, then message the pilot group. If the status reads "locked, waiting for cloud", do not switch it off; wait for the internet to return and the hub unlocks itself. A hub that is already running keeps recognising members through an internet outage, so switching it off during an outage only makes it worse.

## 8. Weekly metrics

From the cloud mirror weekly, plus hub checks at the visit.

| Metric | Target | Where it comes from |
|---|---|---|
| Enrolment rate | 60 percent | members created in the week over bills with a phone number from the shop's bill book (assumption: MVP does not see non-member bills) |
| Notice delivery time | 90 percent within 2 minutes | consent_records: delivered_at minus ts; plus members pending_notice after 24 hours |
| Recognition rate | 85 percent within 3 seconds | visits with not_them false over returns. Misses: purchases rows for ACTIVE members with no visits row in the previous hour (assumption: repeat bills go through member search), plus owner reports. Latency from staged walk-ins and hub logs |
| False alerts | At most 1 per shop per day | visits with not_them true, by shop and day |
| Greeting rate | 50 percent | visits with greeted true over visits with not_them false |
| Deletion timing | 100 percent within 1 hour | consent_records: template_deleted_at minus withdrawn_at |
| Uptime | 99 percent | devices.last_seen_at and health reports: minutes without a heartbeat |
| Zero photos | Zero | hub data directory: templates and encrypted clips only, none older than 30 days |

OPEN: add a "skipped" counter to Cashier mode so the enrolment denominator comes from the app.

## 9. Feedback loops

- One WhatsApp group per shop (owner, cashiers, Remu) for same-day problems.
- Weekly 20-minute visit: metrics, three staged walk-ins, hub check, what customers said. The cashier tallies refusals: no, why, camera concern, no WhatsApp.
- Threshold tuning from Not them and misses. REMU_MATCH_THRESHOLD starts at 0.45 with 3 of 5 frames. Any change passes the evaluation harness in tests/eval/ first, then one shop for a week, then the rest.
- Member clips are never used for tuning. The consented pilot set comes from volunteers with a separate written consent. OPEN: that wording, with the lawyer.
- Bugs go to Sentry, then TASKS.md under Phase 9. Builds ship to one shop first.

## 10. Risks and mitigations

| Risk | Signal | Mitigation |
|---|---|---|
| Low opt-in | Enrolment under 40 percent after Week 3 | Watch the cashier say the line; fix delivery. Check the standee and that the 5 percent discount is honoured |
| Poor camera angle | Many misses; faces small or from above | Gate IP camera at 1.6 to 1.8 metres; re-run the preview check |
| WiFi drops | Hub offline; members stuck in pending_notice | Ethernet to the router. The hub matches offline and queues the outbox; LAN alerts work without internet |
| Cashier skips the line | Members under one cashier_id only, or none for a shift | Per-cashier counts weekly; the owner reminds. OPEN: a small staff incentive |
| Hindi versus local language | No response to the Hindi line | Hindi-speaking city first (assumption). OPEN: a local-language line, which needs lawyer approval and a new script_version |
| Owner ignores alerts | Greeting rate under 50 percent | Phone on the owner with vibration on, not on the desk; weekly numbers. An owner who will not greet leaves the pilot |
| WhatsApp template rejection | Meta rejects the template | Submit in Phase 4 with a second wording ready. Without approval no member goes ACTIVE; this blocks go-live |
| Few returns in 60 days | Jewellery customers return rarely | Textile shops, a festival start, staged walk-ins. OPEN: a longer measurement window for jewellery shops |

## 11. 60-day timeline

Before Day 1: Phases 0 to 8 done, shops signed and surveyed, lawyer sign-off on v1.0 wording, WhatsApp templates approved, hardware bought.

| Week | Days | What happens |
|---|---|---|
| 1 | 1 to 7 | Onboard shops 1 to 4 |
| 2 | 8 to 14 | Onboard the rest. All live by Day 14. First metrics pull |
| 3 | 15 to 21 | Enrolment focus. Fix cameras and lines |
| 4 | 22 to 28 | First returning members. Review Not them and misses |
| 5 | 29 to 35 | Threshold tuning if the harness passes. Staged walk-ins everywhere |
| 6 | 36 to 42 | All criteria measured for the first time |
| 7 | 43 to 49 | Pricing conversation with every owner. Second measurement |
| 8 | 50 to 56 | No new app or hub builds after Day 50 |
| 9 | 57 to 60 | Final metrics, exit interviews, go or no-go, payment asked |

## 12. Exit criteria and the go decision

- Privacy criteria hold with no exceptions: zero stored photos, every ACTIVE member with a delivered notice, every withdrawal deleted within one hour. One failure stops that shop until the cause is fixed and recorded in DECISIONS.md.
- Technical criteria (delivery, recognition, false alerts, uptime, hardware cost, setup time) are judged over Weeks 6 to 9, after tuning.
- Adoption criteria (60 percent opt-in, 50 percent greeted) are judged over the whole pilot.

Go: privacy met everywhere; technical and adoption met overall and in at least half the shops; at least half the shops agree to pay; no unresolved legal objection. OPEN: the number of paying shops needed, and who decides.

No-go on the thesis: opt-in under 40 percent after the section 10 fixes, or greeting under 30 percent, in most shops. No-go on the technology: recognition under 70 percent or more than 3 false alerts per shop per day after tuning.

When a shop leaves: every template is deleted and logged, the owner gets the consent export, and the hub is wiped.

## 13. The pricing test

Software is free for the 60 days (assumption); hardware is at cost or a small margin (open item in section 3). OPEN: PRD.md section 14 still leaves open whether pilot shops pay for software during the 60 days and at what price; this plan and the PRD must say the same thing, closed by an ADR in DECISIONS.md.

In Week 7, before naming a price, ask each owner how many customers they greeted by name this month that they would have missed, and what one is worth in a year. The anchor: one retained 20,000 rupee customer a month pays for Remu.

Then offer a price. Assumption: shops are split across 500, 1,000 and 1,500 rupees per month, assigned before Day 1, a jewellery and a textile shop at each point. The offer: the same service from Day 61, billed monthly by UPI or bank transfer until Razorpay billing exists. Record the price offered, the owner's first number, and yes or no.

After the pilot, shops that say yes pay from Day 61 at their offered price; shops that say no return lent hardware. The share paying at each point sets the launch price.

OPEN: the price assignment per shop.
