# Remu Design System

Purpose: how the Remu app (Cashier mode and Owner mode) and the later web dashboard should look and feel, so every screen is built the same way.

The consent strings in section 7 and the product numbers (5% off, 30-day clips, 1095-day expiry, 10-minute cooldown, 3-second alert) come from the product brief of 2026-10-18. The colours, type sizes, spacing and chip labels are this document's decisions.

## 1. Style

Warm. Plain. Trustworthy. Fast.

Warm: cream, maroon and marigold, round corners, words a shopkeeper would say. Plain: one primary action per screen, no decoration, no animation except progress. Trustworthy: status always visible, destructive steps confirmed in plain words. Fast: two cashier taps per sale (the face, then Make member), and an alert read in two seconds while walking.

## 2. Typography

| Use | Size | Weight |
|---|---|---|
| Alert name | 28sp | Bold |
| Screen title | 24sp | SemiBold |
| Script line prompt (Cashier) | 22sp | Medium |
| Section heading | 20sp | SemiBold |
| Body, inputs, lists | 18sp | Regular |
| Button label | 18sp | SemiBold |
| Chip, label | 16sp | Medium |
| Caption, timestamps | 14sp | Regular |

- Inter for Latin and digits, Noto Sans Devanagari for Hindi, both bundled; the system Devanagari font breaks matras on old phones.
- One Compose FontFamily, Inter first, Noto Sans Devanagari as fallback, so "STOP लिखकर" renders correctly.
- Line height 1.4 for Latin, 1.5 for Devanagari. Nothing under 14sp.
- Body is 18sp and the alert name 28sp because shops are bright and phones are held at arm's length.
- Font scale up to 1.3x without clipping. Wrap, never truncate a name.

## 3. Colour tokens

Cashier mode is always light, because the counter is bright. Owner mode follows the phone's dark mode setting.

| Token | Light | Dark | Used for |
|---|---|---|---|
| primary | #7A1F2E maroon | #F0A0AE rose | app bar, buttons, household box |
| on_primary | #FFFFFF | #3B0A14 | text on primary |
| accent | #E8A317 marigold | #F2B33D | selected box, unread stripe, discount chip |
| on_accent | #1C1917 | #1C1917 | text on accent, never white |
| background | #FBF7F2 cream | #171311 | screens |
| surface | #FFFFFF | #241E1B | cards, sheets, inputs |
| border | #E7DED6 | #3A312C | outlines |
| text | #1C1917 | #F5EFE9 | text |
| muted | #6B5F58 | #B8ACA4 | captions, inactive chips |
| success | #1B7A3E | #6CCF95 | active chip, hub online |
| warning | #B45309 | #F4A261 | pending chip, no-internet banner |
| danger | #B91C1C | #F47C7C | under-18 box, hub banner, Remove member |

Light-mode contrast: text 16:1 and muted 5.8:1 on background; white on primary 10:1; danger 6.5:1, warning 5:1, success 5.4:1 on white.

Colour never carries meaning alone; every state has a word or icon. Warning and accent are both warm, so a warning always has its icon and a sentence. No shadows; bright light hides them.

## 4. Spacing

Scale: 4, 8, 12, 16, 24, 32, 48 dp. Screen edge and card padding 16dp. Gap between cards 12dp, label to input 8dp. Bottom action bar 72dp: one 56dp button with 8dp above and below, above the system bar. Radius: cards, sheets, inputs and buttons 12dp; chips fully round; face boxes 8dp.

## 5. Components

### Buttons

- Minimum height 56dp, full width, 18sp SemiBold label on one line.
- Primary: filled primary. One per screen. Make member, Greeted, Export, Save.
- Secondary: 2dp primary outline on surface. Not them, Cancel.
- Destructive: 2dp danger outline. Only Remove member and Remove staff. Opens a confirm sheet with a filled danger button that says what will happen.
- Working: label becomes "Sending…" with a spinner, disabled until the hub or cloud replies. Disabled: 38 percent opacity.

### Cards

Surface fill, 1dp border, 12dp radius, 16dp padding. No elevation.

### Face box overlay (Cashier camera)

ML Kit draws corner brackets over the live CameraX preview, so the face stays visible.

- Detected: 3dp white brackets with a 1dp dark outer line. No label. Tappable.
- Selected: 4dp accent brackets with a check badge. The largest, closest face is selected on its own; tapping another face moves the selection.
- In household: 4dp primary brackets with a numbered badge (1, 2, 3) and the household label below. Tap a selected face again to add the next adult.
- Refused under 18: 4dp danger brackets labelled "18 से कम (18 se kam) / Under 18". Appears only after the hub replies; the phone never guesses age. Assumption: the other adults in a household still enrol, and the card says "One face was refused (under 18). The others are members."
- Tap target is the whole bracket, minimum 64dp. Boxes never show a name; nothing is recognised at the counter.

### Alert card (Owner mode)

One card per visit, from the brief line "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}".

- Name line: 28sp Bold.
- Household line: 18sp muted. Hidden if none.
- Purchase line: last visit date, amount and items.
- Note: up to 3 lines, then "More".
- Caption: time since alert ("2 min ago") and "Gate".
- Buttons: two 56dp buttons in a row, Greeted (primary) and Not them (secondary).
- Stripe and chip: unactioned cards carry a 4dp accent stripe; after a tap the choice shows as a chip.
- Clip button: "Play clip" appears only on the shop WiFi while the clip is under 30 days old.
- No match score is ever shown.

### Member card

display_name, household label, status chip, "Member since 18 Oct 2026", "Last seen 2 days ago", a "5% off next visit" chip when discount_flag is set, phone as +91 •••• 4321 in lists and in full on member detail.

### Visit timeline item

Newest first, visits and purchases mixed. Visit: "18 Oct 2026, 11:42 am · Gate · Greeted by Rajesh", or "· Not greeted", or "· Not them", plus Play clip under the same rule. Purchase: "18 Oct 2026 · ₹20,000 · gold bangles · Diwali". Door icon for visits, receipt icon for purchases.

### Status chips

32dp tall, 16sp label, dot icon, 15 percent tint fill, text in the state colour.

- pending_notice: warning. "Notice pending / सूचना बाकी (Soochna baaki)"
- active: success. "Active / चालू (Chaalu)"
- withdrawn: muted. "Removed / हटाया गया (Hataya gaya)"
- expired: muted. "Expired / खत्म (Khatam)", after 1095 days unseen.

Chip labels use words a shopkeeper would say, as section 7 asks. OPEN: whether chip labels join the lawyer-approved strings list in section 7.

### Banners

Full width under the app bar, 56dp minimum, icon plus one sentence, shown while the condition holds.

- No internet (warning, both modes): "No internet. Billing continues. Notices will go when internet returns." / "इंटरनेट नहीं है। बिलिंग चालू है। इंटरनेट आने पर सूचना जाएगी।" (Internet nahin hai. Billing chaalu hai. Internet aane par soochna jaayegi.)
- Hub not reachable (danger, Cashier mode): "Hub not reachable. Make member is paused. Billing continues." / "हब नहीं मिल रहा। Make member रुका है। बिलिंग चालू है।" (Hub nahin mil raha. Make member ruka hai. Billing chaalu hai.) Face crops are never queued on the phone, so enrolment waits for the hub.

### Loading, empty, error

- Loading: skeleton rows for lists, spinner inside the button for actions, "Starting camera…" over a dark preview. Never a full-screen spinner.
- Empty: one sentence and the next step. Members: "No members yet. Make the first one at billing." Alerts: "No visits yet today."
- Error: inline, plain, with a Retry button. "Hub did not reply. Check the hub light and the WiFi, then try again." Never a code; Sentry gets those.

## 6. Screens

Login: staff PIN, checked against the hub's `staff.pin_hash` offline, then pick a mode. Owner mode additionally signs in with Supabase Auth. The counter phone never holds the owner's cloud login. This is the one login flow; docs/SECURITY.md 3.2, docs/ARCHITECTURE.md section 9, TASK-040 and TASK-075 follow it.

### Cashier mode

- Camera and enrol (home): live preview with face boxes; sheet with phone number, bill amount, items, occasion, notes; the script line in Hindi and English at 22sp above the button. Primary: Make member (मेंबर बनाएँ / Member banayein). After the tap: "Member added. Notice pending on WhatsApp." / "मेंबर बन गए। WhatsApp पर सूचना बाकी।" (Member ban gaye. WhatsApp par soochna baaki.) with the pending chip, back to the camera in 5 seconds. The hub only queues the notice at this point; the chip changes to "Notice delivered" when the cloud confirms delivery. The same string is used in TASK-046 and docs/ARCHITECTURE.md flow B. Screen stays on (wall-powered, on a stand).
- Member search: phone number or name; member cards as results. Primary: Open member.
- Member detail: member card, purchases, editable notes, household, consent status, Remove member. Primary: Add note.
- Household edit: label ("Sharma family"), payer picker, member names (no photos exist), "Add adult at counter" opens the camera. Primary: Save.
- Settings and PIN: PIN pad for staff login (4 digits, assumption), hub connection, language, front or back camera. Primary: Lock.

### Owner mode

- Alerts (home): today's alert cards, unactioned on top; "3 visits today, 2 greeted". Primary: Greeted.
- Alert detail: full alert card, the member's timeline, clip player on LAN. Primary: Greeted or Not them.
- Members: search, status filter chips, sort by last seen. Primary: Open member.
- Member detail: member card, timeline with clips, purchases, notes, consent record, Remove member. Primary: Add note.
- Consent export: date range, PDF or CSV, last export date, chain head hash. Primary: Export.
- Device status: hub online or offline, last seen, hub version, gate stream preview on LAN, internet, outbox count. Primary: Pair hub.
- Staff: staff with role chips (owner, cashier), add, reset PIN, remove. Primary: Add staff.
- Settings: shop name and city, language, vibrate or quiet tone, script and notice versions (v1.0, read only), cooldown 10 minutes (read only), Print standee, log out. Primary: Save.

Alerts arrive as a heads-up notification. The FCM data message carries `shop_id`, `visit_id` and `member_id` only, never a name, purchase or note, because Google's push servers are outside India. The app fetches the card from the hub on the shop WiFi, or from the cloud otherwise, inside the 1.5-second cloud-plus-fetch budget in docs/ARCHITECTURE.md section 14, and builds the notification text locally. OPEN in docs/SECURITY.md 3.2: whether the lock-screen text shows the first name or only "Member at the gate". Vibrate only by default (assumption); no loud ring on the shop floor.

### Web dashboard (later phase)

Same tokens as CSS variables, same fonts from Google Fonts, 12px radius, same chips and banners, breakpoints 768px and 1,440px. Read-only members, consent records and device status. No clips; they stay on the LAN.

## 7. Copy and tone

- Every string exists in Hindi (Devanagari) and English; the user picks in Settings. Spoken loanwords stay as spoken: मेंबर, ऑफ, बिल.
- Big words: short labels, verbs first, one idea per line.
- Warm: "welcome", "remember", "member". Never "scan", "detect", "track", "surveillance", "biometric", "template", "embedding", "match score". "RTSP" appears only in Device status during setup.
- Say what happens next: "Notice pending on WhatsApp", not "Request queued". Say "sent" or "delivered" only after the cloud confirms it.
- Money: ₹20,000 and ₹1,20,000. Dates: 18 Oct 2026, with "Today" and "Yesterday" inside 7 days. Time: 11:42 am.
- Confirmations say the consequence: "Remove Rajesh? Face data is deleted within one hour. The consent record stays, as proof." / "राजेश को हटाएँ? चेहरे का डेटा एक घंटे में मिट जाएगा। सहमति का रिकॉर्ड सबूत के तौर पर रहेगा।" (Rajesh ko hataayein? Chehre ka data ek ghante mein mit jaayega. Sahmati ka record saboot ke taur par rahega.)

### Exact strings, version v1.0

The app must not edit these. A change means a new script_version or notice_version and lawyer sign-off before pilot.

Cashier line, Hindi: "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।"
Cashier line, romanised: "Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega."
Cashier line, English: "You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off."

WhatsApp notice, English: "Welcome to {shop_name} members, {first_name}. Member since {date}. No card needed, we recognise you at the door. 5% off your next visit. Reply STOP any time to leave." Quick-reply button label: "👍"
WhatsApp notice, Hindi: "{shop_name} के मेंबर बनने के लिए धन्यवाद, {first_name} जी। मेंबर since {date}। कार्ड की ज़रूरत नहीं, हम आपको दरवाज़े पर पहचान लेंगे। अगली विज़िट पर 5% ऑफ। कभी भी STOP लिखकर हट सकते हैं।"
(Romanised, for reading only, not sent: "{shop_name} ke member banne ke liye dhanyavaad, {first_name} ji. Member since {date}. Card ki zaroorat nahin, hum aapko darwaaze par pehchaan lenge. Agli visit par 5% off. Kabhi bhi STOP likhkar hat sakte hain.")

Printed bill line (later phase): "Member since {date}. No card needed, we recognise you at the door. 5% off next visit. Reply STOP on WhatsApp to leave."

Counter standee: "Members get 5% off. No card, no app." / "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।" (Romanised: "Members ko 5% off. Na card, na app.")

Owner alert card: "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}". Two buttons: "Greeted" and "Not them".

OPEN: Hindi labels for the two alert buttons are not in the brief. Proposed: "स्वागत किया (Swagat kiya)" for Greeted and "ये नहीं (Ye nahin)" for Not them. Founder to confirm.

## 8. Accessibility

- Contrast: text at least 4.5:1; alert name, button labels and banners at least 7:1.
- Touch targets: buttons 56dp, everything else at least 48dp, 12dp between neighbours.
- One-hand use: the owner reads alerts while walking, so Greeted and Not them sit at the bottom. No swipe-only or long-press-only actions.
- Gloves off only: capacitive touch needs a bare finger; there is no glove mode. The only required touches per sale are the face tap and Make member.
- Readable at arm's length: body 18sp, alert name 28sp, nothing under 14sp, tested at 70 cm.
- Every icon button has a TalkBack description. Devanagari checked on a low-end Android 8 phone (minimum SDK 26).

## 9. UX requirements

- Phone widths 360dp to 430dp, portrait only (assumption: the counter phone stands upright).
- Loading, empty, error and the two offline banners as in section 5, on every screen.
- Never block billing: no modal during a sale, every wait cancellable, a failed Make member leaves the camera live with an inline error, notice sending always in the background, the hub reply or a 5-second timeout returns the cashier to the camera.
- No face image is ever shown except the live preview. No thumbnails.

## 10. What the customer must never see

- No customer-facing screen. The counter phone faces the cashier; only its camera faces the customer. No second display, no mirrored preview.
- Nothing at the door: no screen, no beep, no light, no sound, no camera movement. The alert goes to the owner's phone only, on vibrate.
- Face boxes, household groups, notes, alerts, clips and consent records are never turned towards the customer.
- The customer never taps, signs or types. Consent is the spoken line and the cashier's tap.
- The only customer-facing text is the standee, the WhatsApp notice and, later, the bill line, exactly as in section 7.

## 11. Counter standee

- Size: A5 tent card, 148 by 210 mm, laminated, about 200 rupees. Printed from Owner mode Settings as a PDF, two per A4 sheet.
- Text, verbatim: "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।" on top in Noto Sans Devanagari Bold 40pt, "Members get 5% off. No card, no app." below in Inter SemiBold 36pt. Optional shop name at the top in 20pt. No other text.
- Colours: background #FBF7F2, text #1C1917, "5%" in primary #7A1F2E. No photos, no camera or face icons.
- Placement: on the counter, facing the customer, within 1 m of where they pay, beside the billing phone. Never at the gate.

OPEN: Standee language order (Hindi or English first) per pilot city, and whether the lawyer wants more notice wording on it before pilot.

## 12. Open items

OPEN: No wordmark or app icon exists yet; the app bar shows "Remu" in Inter SemiBold until one does.
Phone-only members: TASK-048 builds the "Skip face, save bill only" action in Cashier mode; the screen shows "Saved without face membership". OPEN: the members table has no phone-only status; TASK-048 proposes a row with no template as the marker.
OPEN: Whether cashiers see full phone numbers on member detail or only the last 4 digits; for docs/PRIVACY_AND_CONSENT.md to settle.
