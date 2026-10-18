# Remu

Purpose: the front door of the repository. What Remu is, who it is for, how the code is laid out, where the documents are, and how we work. Written 2026-10-18. The product brief of 2026-10-18 wins over anything written here.

## What Remu is

Remu is a "shop memory" for Indian retail. A returning customer who opted in as a member walks through the shop entrance. The hub in the shop matches the face from the existing CCTV feed against the shop's members in under a second and pings the owner's phone with a card: name, household, last visit, last purchase, notes. The owner walks over and greets the member by name. The name comes from "remember". Tagline: "Remu remembers."

Remu is three things we build and two services we rent. The hub (`hub/`, Python 3.11) runs in the shop on a Raspberry Pi 5 and does all face work. The app (`app/`, Kotlin and Jetpack Compose) is one Android app with two modes: Cashier mode on a spare phone at the counter, Owner mode on the owner's own phone. The cloud (`cloud/`, Next.js 15, TypeScript, Supabase and Vercel, all in Mumbai) holds accounts, pairing, the consent mirror, the WhatsApp Cloud API integration and the Firebase Cloud Messaging relay. Membership is opt-in at the counter under the Digital Personal Data Protection Act 2023. No face photo is stored anywhere. The only biometric is a cancelable template on the hub, scrambled with a per-shop key, and the cloud never receives a face. Hardware per shop is at or under 15,000 rupees: the shop's existing DVR, a hub of about 10,000 rupees and the owner's phone. Software is 500 to 1,500 rupees per shop per month.

## Who it is for

- The owner of a high-ticket, infrequent, relationship-driven shop: jewellery, sarees and textiles, furniture, electronics showrooms, later salons and barbers. The owner receives the alert, greets the member, owns the customer relationship and is the data fiduciary for member data.
- The cashier, who enrols members at billing, types notes and handles removals.
- The member, the customer who heard the line at the counter and said okay. The member never installs anything and never touches a screen.
- Remu, the company, is the data processor. It supplies the hub, the app and the cloud and never holds a face.

The pilot runs for 60 days in 5 to 10 jewellery or textile shops in one city. OPEN: which city hosts the pilot.

## The core moment

A member walks in after a year. The gate camera feed reaches the hub, and the hub matches the face against the shop's members in under a second. The owner's phone buzzes with a card: "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}", with two buttons, "Greeted" and "Not them". Nothing happens at the door: no screen, no beep, no visible camera reaction. The owner walks over and greets the member by name with specifics. The customer feels remembered by a person, not scanned by a machine. Keeping that surprise is a product requirement.

Consent happens earlier, at billing. The cashier says one line, versioned `hi-IN-v1.0`:

"आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.)

The customer says okay, the cashier taps the face on the counter phone, and the WhatsApp invoice carries the written notice (version `wa-v1.0`) with an optional thumbs-up button. The member becomes `active` for gate recognition only after WhatsApp confirms the notice was delivered. A WhatsApp reply "STOP" or a "Remove member" tap deletes the template within 1 hour. A member unseen for 1095 days is deleted. Match clips are kept 30 days.

## Repository layout

```
Remu/
├── README.md
├── CLAUDE.md                 (Claude Code project context; points to the docs and rules)
├── TASKS.md
├── .env.example
├── .gitignore
├── docs/
│   ├── BRIEF.md               (the product brief; the source of truth)
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
```

## Documents

- `CLAUDE.md`: project context for Claude Code: what Remu is, what to read first, the commands, the ten privacy invariants, how to work a task.
- `TASKS.md`: what to build next, Phase 0 to Phase 9, one TASK id per unit of work small enough for one AI coding session.
- `docs/BRIEF.md`: the product brief of 2026-10-18, the source of truth the other documents follow.
- `docs/PRD.md`: what we build and why: problem, users, the core moment, principles, MVP scope, out of scope, success criteria.
- `docs/ARCHITECTURE.md`: how it works: components and their internals, data model, member state machine, flows, LAN protocol, sync, deployment, architectural rules, performance budgets.
- `docs/DESIGN.md`: how the app looks and behaves in Cashier mode and Owner mode, and the exact strings at version v1.0.
- `docs/RULES.md`: the rulebook for every person and AI session; the privacy invariants live here and win over every other file.
- `docs/TEST_PLAN.md`: what "working" means, flow by flow, with `T-<flow letter>-<number>` ids.
- `docs/SECURITY.md`: assets, threat model, requirements per component, secrets, breach response under DPDP, retention enforcement.
- `docs/PRIVACY_AND_CONSENT.md`: the consent model, the scripts and notices, households, withdrawal, retention, the legal basis.
- `docs/DECISIONS.md`: architecture decision records, ADR-001 onwards. Permanent decisions.
- `docs/MEMORY.md`: the current state: status, completed, current task, known issues, next step. Rewritten at the end of every task.
- `docs/PILOT_PLAN.md`: the 60-day pilot: shop selection, onboarding, training, metrics, iteration.
- `docs/RESEARCH.md`: the legal, market and technical research behind the decisions.
- `docs/PROMPTS.md`: the prompts we use: kickoff, feature task, code review, debugging, documentation update, privacy invariant check.
- `.cursor/rules/*.mdc`: Cursor rules derived from `docs/RULES.md`: general, frontend, backend, testing.

## Quickstart

Requirements: Python via `uv` (it installs 3.11), ffmpeg, Node 20, JDK 17 or newer and the Android SDK. Docker only for the full-stack test.

Hub (`hub/`):

```
cd hub && make setup       # Python 3.11 env, model pack (about 300 MB), fixture video
make fake-camera           # terminal 1: RTSP stream of the fixture video on rtsp://127.0.0.1:8554/gate
make dev                   # terminal 2: console demo, prints ALERT member=m_demo score=0.977 frames=3/5
make lint typecheck test   # 130 tests
REMU_INTEGRATION=1 uv run pytest ../tests/integration/test_enrol_then_match.py   # needs make fake-camera
make eval                  # accuracy harness (needs tests/eval/prepare_lfw.py first)
```

Run the real hub service on a laptop:

```
export REMU_SHOP_ID=dev-shop REMU_RTSP_GATE_URL=rtsp://127.0.0.1:8554/gate
uv run remu-hub init                       # shop key, certificate, pairing code
uv run remu-hub staff add --name Owner --role owner
uv run remu-hub serve                      # HTTPS LAN API on :8443, gate pipeline, sync if REMU_CLOUD_URL is set
```

Cloud (`cloud/`):

```
cd cloud && npm ci
npm run lint && npm run typecheck && npm test && npm run build   # 142 tests on in-process Postgres (PGlite)
npm run dev                                                      # http://localhost:3000
```

App (`app/`):

```
cd app && ./gradlew assembleDebug test lint      # 128 JVM and Robolectric tests
./gradlew connectedAndroidTest                   # needs an emulator or a device
```

`google-services.json` (Firebase) goes in `app/app/` and is never committed; without it the app builds with push disabled.

Everything at once: `make check` at the root. Hub and cloud together over real HTTP:

```
cd cloud && npm run build && cd ../hub
REMU_FULLSTACK=1 uv run pytest ../tests/integration/test_full_stack.py   # Postgres in Docker, mock WhatsApp
```

On the Pi: `scripts/build_pi_image.sh` (see hub/deploy/ for the systemd units), then `scripts/provision_hub.py`.

## Status

Build pass 1 finished on 2026-10-18. Every software component of v0.1 exists and is tested. The remaining tasks need hardware, accounts or people; `TASKS.md` marks each with a status line, and `docs/MEMORY.md` lists them.

## How we work

One task per session, in `TASKS.md` order. Every task follows the same nine steps:

1. Read: `CLAUDE.md`, the documents it points to, and the files the task touches.
2. Understand: the product, the architecture, the rules, and what the task must not change.
3. Plan: files, functions, tests, risks. For more than three files, or any schema, API, job or member status change, the plan is written and accepted before editing.
4. Implement: the smallest change that meets the acceptance criteria, with tests.
5. Test: the component's lint, typecheck and tests, then `make check` at the root. The evaluation harness in `tests/eval/` before any model pack or threshold change.
6. Review: the code review prompt in `docs/PROMPTS.md`, plus the privacy invariant check for anything that touches storage, networking or the face pipeline.
7. Fix: every issue from the review, one at a time, smallest fix first.
8. Commit: a feature branch, a small conventional commit that names the task, for example `feat(hub): consent hash chain (TASK-031)`, one pull request per task, CI green.
9. Update documentation: tick `TASKS.md`, rewrite `docs/MEMORY.md`, add an ADR to `docs/DECISIONS.md` for any decision, update `docs/ARCHITECTURE.md` and this file when structure, commands or flows change.

Build order, from the brief, one phase per line:

1. The hub on the developer laptop with the fake camera. A face in the sample video gets enrolled, then matched, and an alert prints to the console.
2. The hub API and consent records.
3. Cashier mode.
4. The cloud with WhatsApp.
5. Owner mode alerts.
6. Withdrawal, expiry and export.
7. The Pi image and a real DVR.
8. Hardening and QA.
9. The pilot.

Anything that still needs a human decision is written as a line starting with "OPEN:" so one search finds them all.

## Licence

OPEN: no licence has been chosen. Until the founder decides, the repository is private and all rights are reserved.
