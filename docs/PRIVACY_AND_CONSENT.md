# Privacy and Consent

Purpose: the complete consent and privacy design for Remu, written for lawyer review before the pilot. It states exactly what Remu does, what it stores, what it can prove, and what it does not claim. Source of truth: the product brief dated 2026-10-18. This document is not legal advice.

## 1. Roles

- The shop is the data fiduciary. The owner decides why member data is collected (recognition at entry, WhatsApp offers) and is answerable for it under the Digital Personal Data Protection Act 2023 (DPDP).
- Remu (the company) is the data processor. Remu supplies the hub, the app and the cloud, and processes member data only on the shop's instructions. Remu never holds a face image, a template or a clip.
- The member is the data principal. A member is a customer who gave a phone number for the bill, heard the cashier's line, said okay, and was tapped in at the counter.

The processor contract between Remu and each shop must contain at least these clauses:

- Purposes. recognise_at_entry and whatsapp_offers, and a ban on any other use by Remu.
- What Remu stores and never receives. Remu stores the mirrored tables in section 10 of the brief. Remu never receives face images, templates or clips.
- Security safeguards. The controls in SECURITY.md.
- Deletion duties. The one-hour deletion duty on withdrawal (section 9) and the 1095-day expiry (section 10).
- Member requests. Remu's duty to help the shop answer member requests and grievances.
- Breach notification. Remu notifies the shop without delay so the shop can meet its own deadline.
- Data location. The mirror (consent records, member metadata, visits, purchases, notes) stays in the Mumbai region: Supabase in AWS ap-south-1 and Vercel functions in bom1. Three narrow flows leave India. The WhatsApp notice (shop name, first name, date, and the phone number used to deliver it) passes through Meta's WhatsApp Cloud API. The push alert, carrying shop_id and visit_id only, passes through Google's Firebase Cloud Messaging. Scrubbed error events with no personal data go to Sentry. No other service, queue, cache or log sink outside India. OPEN: the lawyer confirms that these three flows are acceptable and how the contract and the notice describe them, or Remu self-hosts Sentry in India.
- Sub-processors. Supabase, Vercel, Meta WhatsApp Cloud API, Firebase Cloud Messaging and Sentry, and the shop's right to be told of changes.
- End of contract. Deletion or return of the mirrored data when the contract ends.
- Audit rights. The shop may audit Remu's processing.

OPEN: the processor agreement template itself does not exist yet. A lawyer must draft it.

## 2. Legal basis and timeline

- DPDP Act 2023 is the primary law. Consent must be free, specific, informed, unconditional and unambiguous, given by a clear affirmative act. The burden of proving consent is on the data fiduciary, the shop.
- DPDP Rules were notified in November 2025. The consent manager framework starts on 13 November 2026. The remaining obligations on notice, consent, security safeguards, retention and erasure become enforceable on 13 May 2027. Remu is built to meet those obligations from the pilot onward, not from the deadline.
- Penalties: up to 250 crore rupees for failing security safeguards and up to 200 crore rupees for breach notification failures.
- The IT Act's 2011 Sensitive Personal Data or Information rules already classify biometric information as sensitive personal data. Remu treats the scrambled template as sensitive personal data under those rules.
- The 2023 Guidelines for Prevention and Regulation of Dark Patterns name "sneaking" as a listed practice. Consent buried in bill terms and conditions is not consent. Remu's notice is a standalone, plainly worded WhatsApp message, not a clause.
- Cautionary precedents: in 2021 7-Eleven Australia was ordered to destroy face data collected without consent. In 2023 Rite Aid in the United States was banned from using face recognition for five years. Both regulators treated face templates as biometric data. Both cases involved covert use. Remu is opt-in only.

## 3. The consent flow, step by step

1. The customer is at the counter. The cashier asks for a phone number for the bill. This makes the customer a phone member. Nothing biometric happens yet.
2. The counter phone, in Cashier mode, shows the live preview with a box on each face and the script line on screen in Hindi and English as a prompt for the cashier.
3. The cashier says the line to the customer. The customer answers with a spoken okay or a nod. The customer never taps anything and never touches a screen.
4. If the customer says okay, the cashier taps the customer's face and hits "Make member". That enrol tap is the consent timestamp. There is no separate consent button. If the customer shrugs, says no, or walks away, the cashier does nothing and the customer stays a phone member only.
5. The hub runs the age gate, computes and scrambles the template, stores it, writes the consent record, and queues the WhatsApp notice. Member status is PENDING_NOTICE.
6. The cloud sends the WhatsApp notice as the written record of what was agreed. The message ends with an optional one-tap thumbs-up quick-reply button. A tap converts verbal consent into written confirmation. Not tapping changes nothing.
7. Only when the WhatsApp delivery receipt arrives does the member become ACTIVE. Until then the member is never matched at the gate. If the hub is offline, the member stays PENDING_NOTICE until delivery is confirmed.
8. The customer can reply STOP at any time. See section 9.

## 4. Scripts and notices, verbatim

Every script and notice carries a version id. The consent record stores the script_version and notice_version in force at the enrol tap. Any change to wording, including a single word, bumps the version. Old versions are never edited, so a record always points to the exact text the member heard or received.

Cashier line, script_version hi-IN-v1.0:
"आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।"
Romanised: "Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega."

English rendering of the same line, shown beside it on the Cashier screen, recorded under the same script_version hi-IN-v1.0:
"You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off."

WhatsApp notice, notice_version wa-v1.0 (English):
"Welcome to {shop_name} members, {first_name}. Member since {date}. No card needed, we recognise you at the door. 5% off your next visit. Reply STOP any time to leave."
Quick-reply button label: "👍"

WhatsApp notice, notice_version wa-v1.0 (Hindi):
"{shop_name} के मेंबर बनने के लिए धन्यवाद, {first_name} जी। मेंबर since {date}। कार्ड की ज़रूरत नहीं, हम आपको दरवाज़े पर पहचान लेंगे। अगली विज़िट पर 5% ऑफ। कभी भी STOP लिखकर हट सकते हैं।"
Romanised: "{shop_name} ke member banne ke liye dhanyavaad, {first_name} ji. Member since {date}. Card ki zaroorat nahin, hum aapko darwaaze par pehchaan lenge. Agli visit par 5% off. Kabhi bhi STOP likhkar hat sakte hain."
Quick-reply button label: "👍"

Printed bill line, v1.0 (later phase, not in MVP):
"Member since {date}. No card needed, we recognise you at the door. 5% off next visit. Reply STOP on WhatsApp to leave."

Counter standee, v1.0:
"Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।"
Romanised: "Members ko 5% off. Na card, na app."

Version ids. The brief gives two ids, hi-IN-v1.0 for the script and wa-v1.0 for the notice, and versions every text as v1.0. The hub, the cloud and the test plan (TASK-031, TASK-056, TEST_PLAN T-B-01, RULES, DESIGN, .env.example) write only those two ids. This document uses the same two ids for both languages so that the record the hub writes matches the text the member received. The language of the notice actually sent is the shop's notice language in settings_json at the time of the enrol tap. The English rendering of the line, the bill line and the standee carry no separate id. They are v1.0 of their own text and are listed in the lawyer checklist by name.

OPEN: whether the consent record must itself name the language of the notice sent, for example with a per-language id. If yes, the data model, TASK-031, TASK-056, TEST_PLAN T-B-01 and this document change together under one ADR.

OPEN: a lawyer must sign off every string above before the pilot. Any change produces v1.1.

## 5. What the customer sees and never sees

The customer sees: the cashier, the counter phone on its stand with its camera facing the customer, the standee, the WhatsApp message with the invoice and the notice, and the thumbs-up button. A door sign is proposed in section 15 and is OPEN. Until the lawyer and the founder decide, nothing is posted at the entrance beyond the shop's existing CCTV sign.

The customer never sees: a consent form, a checkbox, a screen to tap, a photo of their face, a beep or light at the door, any reaction from the camera, or the owner's alert card. At the door nothing visible happens. The owner walks over and greets the member. The surprise is a product requirement, and it is lawful only because the member agreed at the counter and received the written notice.

## 6. Consent record schema and hash chain

Each enrol tap writes one row to consent_records on the hub. The row is mirrored to the cloud. It contains no biometric data. Example:

```json
{"consent_id":"c_8f3a","shop_id":"sharma-jewellers-jaipur","member_id":"m_2041","phone_hash":"sha256:...","bill_no":"INV-2026-10-18-0417","timestamp":"2026-10-18T17:42:11+05:30","cashier_id":"staff_03","channel":"verbal_at_counter","script_version":"hi-IN-v1.0","notice_version":"wa-v1.0","purposes":{"recognise_at_entry":true,"whatsapp_offers":true},"notice_delivery":{"printed_on_bill":false,"whatsapp_msg_id":"wamid.HBg...","whatsapp_status":"delivered","delivered_at":"2026-10-18T17:42:40+05:30","thumbs_up_at":null},"adult_check":"passed","withdrawn_at":null,"template_deleted_at":null,"prev_hash":"sha256:...","record_hash":"sha256:..."}
```

Rules:

- Append-only. No row is ever updated or deleted. A status change (delivered, thumbs-up, withdrawn, template deleted, expired) is a new chained row that references the same member_id.
- Hash chain. record_hash is the SHA-256 of the row's content plus prev_hash, the record_hash of the previous row for the shop. Changing or removing any row breaks every hash after it.
- Nightly anchor. The hub sends the chain head hash to the cloud each night, stored in hash_anchors. The anchor proves the chain existed in that state on that date.
- Stored separately from biometrics. The templates table is on the hub only. The consent_records table is on the hub and the cloud. Nothing in a consent record can reconstruct a face.
- Exportable. The owner taps Export and the cloud produces PDF and CSV from the mirrored log, signed with CONSENT_EXPORT_SIGNING_KEY.

## 7. The proof-of-consent stack

A spoken yes cannot be proven directly. Remu does not record audio or video of the consent moment in MVP. What the shop can show is the following stack, and this document claims no more than that.

1. A scripted process prompted by the app. Cashier mode shows the script line on screen and the only way to enrol a face is the "Make member" button, which is designed to be pressed after the line is spoken and the customer says okay. The app cannot prove the line was spoken. An enrolment without the spoken line still sends the WhatsApp notice, which is why items 3 and 4 matter. The record stores cashier_id, bill_no, timestamp and script_version.
2. A cashier training log. Every cashier is trained on the script before their PIN is issued. The shop keeps a dated, signed training record. Assumption: the training log is a paper or PDF record kept by the shop, with the training date stored in the staff table as a later addition if needed.
3. A delivered, timestamped written notice. The WhatsApp notice names the purpose in plain words. Its delivery is confirmed by Meta's webhook and stored as whatsapp_msg_id, whatsapp_status and delivered_at. No member is ACTIVE without it.
4. Silence after a clear STOP option. The notice ends with "Reply STOP any time to leave". The member received it, could leave with one word, and did not. This is evidence that the member knew and continued, not a substitute for the original yes.
5. Optional thumbs-up written confirmation. Where the member tapped the button, thumbs_up_at is stamped and the consent is in writing from the member's own device.

Together: a trained cashier, following a script the app enforced, at a timestamped bill, followed by a delivered written notice with an easy exit, and in many cases a written thumbs-up. This is what the shop would show a regulator or a court.

OPEN: whether a verbal yes plus a delivered written notice satisfies the notice format required by the DPDP Rules, or whether the notice must precede or accompany the consent request itself. If the latter, the printed bill line or a counter card may need to move earlier in the flow.

## 8. Minors

The buffalo_l model pack's age and gender model estimates age at enrolment. An estimate under 18 refuses enrolment. There is no override for the cashier or the owner. The app shows a neutral message and the customer stays a phone member.

Why: children's personal data under DPDP needs verifiable parental consent and carries extra restrictions. Remu does not build that flow, so the only safe answer is refusal. The estimator is imperfect. Some adults will be refused. That cost is accepted. adult_check is recorded in every consent record.

## 9. Withdrawal

- Triggers: a WhatsApp reply "STOP", or an owner or cashier tap on "Remove member" in the app.
- Effect: the cloud marks the member WITHDRAWN and informs the hub. The hub deletes the template within one hour of the withdrawal reaching it and writes a new chained consent record with withdrawn_at and template_deleted_at. The member is never matched again.
- Offline hub. A "Remove member" tap reaches the hub over the LAN even when the internet is down, so it deletes within the hour. A STOP reply reaches the cloud first. If the hub is offline when the STOP arrives, the cloud records withdrawn_at at once, the hub deletes within one hour of reconnecting, and the delay is recorded on the consent record. During that gap the member is still in the matcher on the hub. OPEN: whether the WhatsApp notice and the staff scripts in section 16 must say that deletion can take longer than an hour when the shop's box is offline. The pilot metric "template_deleted_at minus withdrawn_at" measures from the time the withdrawal reaches the hub, so it follows this clock.
- The consent record is never deleted, because it proves the deletion happened.
- Withdrawal is as easy as consent. Consent was one spoken word and a tap by the cashier. Withdrawal is one typed word by the member.
- A member can re-join later only by going through the full counter flow again. The old withdrawn record stays in the chain.

## 10. Retention and expiry

| Data | Where | Retention |
|---|---|---|
| Scrambled template | hub, templates table | Until withdrawal, or 1095 days unseen, then deleted and logged |
| Template of a PENDING_NOTICE member whose notice is never delivered | hub, templates table | Proposed: deleted 7 days after the enrol tap if no delivery receipt arrives, logged as a chained record. The member is never matched in the meantime. OPEN: the lawyer confirms the window |
| Match clip (5 seconds, encrypted) | hub, clips table | 30 days, then deleted by the nightly job |
| Consent records | hub and cloud | Never deleted. Retained as proof of consent and of deletion |
| Visits, purchases, notes | hub and cloud | Life of the membership; assumption: deleted with the member record one year after withdrawal or expiry |
| Non-member faces at the gate | nowhere | Discarded within seconds, never written. See section 12 for incidental presence in a member's clip |
| Face images | nowhere | Never stored |

The nightly job on the hub runs expiry (REMU_MEMBER_EXPIRY_DAYS, 1095) and clip cleanup (REMU_CLIP_RETENTION_DAYS, 30). Every deletion is a chained record.

OPEN: the retention period for visit and purchase history after withdrawal or expiry (this document assumes one year). A lawyer should confirm against the Rules' retention and erasure provisions. Consent records are outside this question: they are kept for as long as the shop must be able to prove consent and deletion, and the lawyer should confirm that indefinite retention of a record with no biometric data is acceptable.

OPEN: the 7-day pending window above is a proposal from ARCHITECTURE.md. The lawyer decides the window.

## 11. Households

The cashier can tap several adult faces at the counter and group them as one household, for example "Sharma family, payer Rajesh". Rules:

- Each adult consents individually. The cashier says the line to the group and addresses each adult. Each adult gets their own consent record, their own template, and their own WhatsApp notice if they gave a number.
- Only adults present at the counter can be added. No one can be enrolled on another person's say-so.
- The age gate applies to every face. A child in the group is never enrolled.
- Limits: a household is a label and an alert grouping, nothing more. Any member's arrival triggers the household alert. Each member can STOP independently and only their own template is deleted. Assumption: a household member without their own WhatsApp number cannot be activated in MVP, because the notice cannot be delivered to them.

## 12. Non-members

Every face in the gate stream is detected, embedded, scrambled, and compared against ACTIVE templates. If there is no match, the embedding is discarded within seconds. No frame, crop, embedding, count or timestamp about a non-member is written to disk or sent anywhere, with one exception. When a member is matched, the hub saves a 5-second encrypted clip of the gate. Anyone else in frame at that moment, such as a companion or a passer-by, appears in that clip. Remu does not detect, identify, count or index those people. The clip is LAN-only, never uploaded, and deleted after 30 days. The gate camera is the shop's existing CCTV, which continues to record under the shop's own security notice, unchanged by Remu.

OPEN: the lawyer confirms that incidental presence in a member's clip is covered by the shop's CCTV notice, or says what else is needed.

## 13. What is explicitly not claimed

- Scrambled templates are still personal data. The per-shop orthogonal rotation (the BioHashing family, ISO/IEC 24745) makes the stored vector useless without the shop key and lets a shop revoke and reissue templates. It does not make the vector anonymous. Remu treats it as sensitive personal data.
- Encryption is a safeguard, not a substitute for consent. The encrypted data directory, the root-only shop key and the device-bound unlock token reduce the harm of a stolen hub. They do not change the legal basis, which is consent.
- CCTV security notices do not cover recognition. A "premises under CCTV surveillance" sign tells people they are recorded for security. It does not tell them they are identified at the door. Remu requires its own notice: the cashier line, the WhatsApp notice and the standee. A separate door sign is proposed in section 15 and is OPEN.
- Delivery of the notice is not proof the member read it. Remu stores delivered and read statuses separately and gates activation on delivered, because read receipts can be disabled.
- The age gate is an estimate, not identity verification.
- Remu does not claim to be a consent manager under the 13 November 2026 framework.

## 14. DPDP obligation mapping

| Obligation | How Remu meets it |
|---|---|
| Notice | Cashier line spoken at the counter, written WhatsApp notice naming the purpose, and the counter standee. Versioned and stored on each record. A door sign is proposed and OPEN, section 15. |
| Consent | Clear affirmative act (spoken okay or nod) witnessed by a trained cashier, timestamped by the enrol tap, followed by a delivered written notice with an optional thumbs-up. Never pre-ticked, never bundled into bill terms. |
| Purpose limitation | purposes_json lists recognise_at_entry and whatsapp_offers. The hub matches only ACTIVE members. No analytics, no footfall counting, no sharing. |
| Data minimisation | No photos. One 512-dimensional scrambled vector per member. Non-members discarded in seconds. Cloud holds no biometrics. |
| Accuracy | "Not them" button on the alert card records false matches. Threshold 0.45 and 3 of 5 frames tuned by the evaluation harness. Members can ask the cashier to correct name or notes. |
| Security safeguards | Encrypted data directory, root-only shop key, device-bound unlock token, cancelable templates, HTTPS and pinned certificate on the LAN, outbound-only cloud sync, Row Level Security in Supabase, Mumbai region only. Details in SECURITY.md. |
| Breach notification | Remu notifies the shop without delay; the shop notifies the Data Protection Board and affected members. OPEN: the timeline and template, see section 17. |
| Erasure | STOP or Remove member deletes the template within one hour of the withdrawal reaching the hub (section 9 covers the offline case). Nightly expiry after 1095 days unseen. Clips deleted after 30 days. Each deletion logged in the chain. |
| Grievance redressal | OPEN: each shop needs a named contact and Remu needs a grievance officer. See section 17. |
| Children | Age gate at enrolment, under 18 refused, no override. |
| Processor contract | Section 1. OPEN: template to be drafted. |
| Consent withdrawal as easy as giving it | One word, STOP, on the same channel the notice arrived on. No login, no form, no call. |

## 15. Door sign and counter standee

Counter standee, v1.0 (from the brief):
"Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।"
Romanised: "Members ko 5% off. Na card, na app."

Door sign, draft (proposed, not in the brief, not in TASKS.md, OPEN):
"Members are recognised by camera at the door. Members only, with consent. Ask at the counter or reply STOP on WhatsApp to leave."
"मेंबर्स को दरवाज़े पर कैमरे से पहचाना जाता है। सिर्फ़ मेंबर, सहमति के साथ। काउंटर पर पूछें या WhatsApp पर STOP लिखें।"
Romanised: "Members ko darwaaze par camera se pehchaana jaata hai. Sirf member, sahmati ke saath. Counter par poochhein ya WhatsApp par STOP likhein."

Assumption: the brief gives standee text only and says nothing is posted at the gate. DESIGN.md places the standee on the counter, never at the gate. The door sign above is a draft so that recognition can be disclosed at the entrance, separately from the CCTV security sign, if the lawyer requires it. It is not a settled notice element and no task builds it. It gets a version id only if adopted.

OPEN: door sign wording, size and placement. The sign must disclose recognition without undermining the greeting moment. A lawyer should confirm whether a door sign is required or whether the counter notice is enough.

## 16. Staff FAQ

Question from a member: "How did you know me?"
Hindi: "आपने पिछली बार बिल के समय हमारी मेंबरशिप ली थी। हम मेंबर्स को दरवाज़े पर कैमरे से पहचानते हैं। हम आपकी फोटो नहीं रखते। हर विज़िट की एक छोटी क्लिप दुकान के बॉक्स में 30 दिन रहती है, फिर मिट जाती है। अगर नहीं चाहिए तो WhatsApp पर STOP लिखिए, एक घंटे में हट जाएगा।"
Romanised: "Aapne pichhli baar bill ke samay hamari membership li thi. Hum members ko darwaaze par camera se pehchaante hain. Hum aapki photo nahin rakhte. Har visit ki ek chhoti clip dukaan ke box mein 30 din rehti hai, phir mit jaati hai. Agar nahin chahiye to WhatsApp par STOP likhiye, ek ghante mein hat jaayega."
English: "You joined our membership at the bill last time. We recognise members by camera at the door. We do not keep your photo. A short clip of each visit stays on the shop's own box for 30 days and is then deleted. If you don't want this, reply STOP on WhatsApp and it is removed within an hour."

Question from a member: "Delete my data."
Hindi: "ज़रूर। अभी 'Remove member' दबा देते हैं। आपका फ़ेस डेटा एक घंटे में हट जाएगा। आप WhatsApp पर STOP भी लिख सकते हैं। आपका बिल रिकॉर्ड हिसाब के लिए रहेगा।"
Romanised: "Zaroor. Abhi 'Remove member' daba dete hain. Aapka face data ek ghante mein hat jaayega. Aap WhatsApp par STOP bhi likh sakte hain. Aapka bill record hisaab ke liye rahega."
English: "Of course. I'll tap 'Remove member' now. Your face data is deleted within an hour. You can also reply STOP on WhatsApp. Your bill record stays for accounts."

Rules for staff: never argue, never ask why, never offer the discount to change their mind. Tap Remove member while the customer watches.

Why the clip is mentioned: section 10 keeps a 5-second encrypted clip of each matched visit for 30 days. A customer and a regulator would call a video of their face a photo. The script says what is true. PILOT_PLAN.md section 6 reproduces this answer word for word. OPEN: the lawyer approves this answer with the script and notice, and decides whether "within an hour" needs the offline caveat from section 9.

## 17. Lawyer review checklist

- OPEN: wording sign-off for the cashier line hi-IN-v1.0 (Hindi and its English rendering), the WhatsApp notice wa-v1.0 (English and Hindi), the printed bill line v1.0, the counter standee v1.0, the staff FAQ answers in section 16, and the draft door sign in section 15 if adopted.
- OPEN: whether the consent record must name the language of the notice sent, with a per-language id, or whether the shop's notice language setting is enough (section 4).
- OPEN: processor agreement template between Remu and each shop, including sub-processor list and breach notification timeline.
- OPEN: data location. Whether the three flows that leave India (WhatsApp notice through Meta, ids-only push through Google, scrubbed error events to Sentry) are acceptable, and how the contract and the notice describe them (section 1).
- OPEN: offline withdrawal. Whether the notice and the staff scripts must say that deletion can take longer than an hour when the hub is offline (section 9).
- OPEN: the pending window. Whether 7 days is the right time to keep a PENDING_NOTICE template whose notice never delivers before deleting it (section 10).
- OPEN: non-members in clips. Whether incidental presence of a companion or passer-by in a member's 5-second clip is covered by the shop's CCTV notice (section 12).
- OPEN: grievance officer. Who at Remu is named, the contact channel, and the response time. Whether each shop also needs a named contact on the notice.
- OPEN: notice language requirements. Whether the notice must be offered in languages beyond Hindi and English for the pilot city, and whether the member must be able to choose the language.
- OPEN: whether a verbal yes with a delivered written notice satisfies the Rules' notice format, including whether the notice must be available before or at the moment of consent rather than after the tap.
- OPEN: retention periods for consent records after withdrawal and for visit and purchase history.
- OPEN: breach notification timeline to the Data Protection Board and to members, and the template message.
- OPEN: whether the standee's "5% off" creates a conditional-consent problem, given that the discount is tied to membership. Phone members and face members both see offers; only face membership needs the door line. Confirm this separation is enough.
- OPEN: whether the shop's existing CCTV notice must be updated to mention recognition, or whether the separate door sign is sufficient.

Nothing in Remu goes to a pilot shop until every OPEN item above is closed or accepted in writing by the founder and the lawyer.
