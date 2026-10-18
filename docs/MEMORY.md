# Remu Project Memory

Purpose: the current state of the Remu project, rewritten at the end of every task so the next session starts from the truth, not from memory. Permanent decisions live in docs/DECISIONS.md.

## Current status (2026-10-18, build pass 1)

Every software component of v0.1 is built and tested. 95 of 134 tasks are ticked in TASKS.md. Each of the other 39 has a status line naming what is left; all of them need a Raspberry Pi, a real DVR, an outside account, the lawyer or the pilot shops.

| Component | State | Checks |
|---|---|---|
| hub (`hub/`, Python 3.11) | face pipeline, LAN API, consent chain, sync, jobs, unlock, discovery, preview, recovery | 130 tests, ruff, mypy strict |
| cloud (`cloud/`, Next.js 15) | mirror, pairing, ingest, WhatsApp, FCM, exports, ops, metrics | 142 tests on PGlite, lint, typecheck, build |
| app (`app/`, Kotlin, Compose) | Cashier mode and Owner mode | 128 JVM and Robolectric tests, lint; instrumented tests on an emulator |
| full stack | `tests/integration/test_full_stack.py` | Postgres in Docker, real cloud, mock Graph API, syncing hub |

Repository: https://github.com/kunaltomar597/memgo. Work is on branch `feature/p0-setup`, pull request #1. CI runs hub, cloud and app jobs.

## Demonstrated end to end

- Console demo: `make fake-camera` and `make dev` print `ALERT member=m_demo score=0.977 frames=3/5` within seconds of the face entering the frame.
- Phase 2 integration test against the live RTSP stream: pair, enrol from a frame, match, WebSocket alert, visit row, encrypted clip, intact chain and a clean privacy scan.
- Full-stack test: pairing and claim, enrolment, the WhatsApp notice to the mock Graph API, activation on `delivered` only, thumbs-up, byte-identical hub and cloud consent exports, anchor match, STOP deletion, and owner removal off the shop WiFi.
- Accuracy: on LFW the false accept rate at 0.45 is 0 on every subset, and scrambled templates score exactly like raw ones. Numbers are in docs/RESEARCH.md section 7.

## Bugs found and fixed during the build

- The plain phone number sat in the SQLite outbox until sync. The privacy scanner caught it; it is now sealed at rest and scrubbed after the ack (ADR-028).
- The cloud double-encoded every jsonb parameter on real Postgres, including the consent records' JSON columns. The full-stack test caught it (ADR-035).
- The device token and pairing code were inside the encrypted data directory they are needed to unlock (ADR-029).
- The eval harness took the largest face in benchmark photos instead of the labelled centre face (ADR-031).

## Known issues

- The InsightFace model packs are licensed for non-commercial research only (TASK-123). No shop goes live before this closes.
- The eval harness reports the `age_gap` subset as missing: no licence-clean age-gap set exists yet.
- The cloud has no per-second rate limit on the WhatsApp webhook route (TASK-113 gap).
- The security review (TASK-112) runs after this pass; its findings are listed under TASK-112.

## Current task

TASK-112 security review fixes, then the field tasks.

## Open questions for the founder

- Pilot city, start date, the shops, and which DVR brands to support first.
- Lawyer sign-off of hi-IN-v1.0 and wa-v1.0, the standee, the staff FAQ and the consent record format (TASK-071, TASK-125).
- Model pack licence: a commercial licence, or a different pack plus a new harness run (TASK-123).
- Pairing direction (ADR-033 chose the hub's code typed into the app), lock-screen text (ADR-036) and phone-only members (ADR-032). These are provisional; confirm or supersede them.
- A pending window for notices that never deliver (suggested 7 days, lawyer).
- SSH on pilot hubs and the hub update channel (TASK-098).
- Owner sign-in method (the app ships email and password through Supabase Auth).
- Accounts to create: Supabase (Mumbai), Vercel (bom1), Meta Business with a WhatsApp number and templates, Firebase, Sentry.

## Next step

1. Merge pull request #1 once CI is green.
2. Create the accounts above and fill `cloud/.env.local` and the app's BuildConfig values; deploy the cloud to a preview.
3. Flash a Pi 5 with `scripts/build_pi_image.sh`, provision it, and run TASK-098 to TASK-105 on real hardware.
