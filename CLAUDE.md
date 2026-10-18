# Remu

Project context for Claude Code. Read this, then the documents listed below, before changing anything. Written 2026-10-18. docs/BRIEF.md is the source of truth every other document follows.

## What Remu is

Remu is a "shop memory" for Indian retail: when an opted-in returning customer (a member) walks in, the hub recognises them at the gate and the owner's phone shows a card with name, household, last visit, last purchase and notes, so the owner can greet them by name. Tagline: "Remu remembers."
Three components: the hub (`hub/`, Python 3.11 on a Raspberry Pi 5 in the shop), the app (`app/`, Kotlin and Jetpack Compose, one Android app with Cashier mode and Owner mode) and the cloud (`cloud/`, Next.js 15, TypeScript, Supabase in Mumbai, Vercel bom1).
Consent is opt-in at the counter under the DPDP Act 2023, the WhatsApp notice is the written record, and no face photo is stored anywhere. Nothing at the gate reacts; the owner's greeting is the product.

## Read these first

1. docs/PRD.md (what we build and why)
2. docs/ARCHITECTURE.md (how it works: modules, data model, flows)
3. docs/DESIGN.md (how the app looks and behaves)
4. docs/RULES.md (the full rulebook; this file is the short version)
5. TASKS.md (what to build next, by phase and TASK id)
6. docs/MEMORY.md (current state, current task, known issues)

When the task touches consent, members, templates, clips or webhooks, also read docs/PRIVACY_AND_CONSENT.md and docs/SECURITY.md. docs/PROMPTS.md has the prompt shape. docs/DECISIONS.md has the ADRs. docs/TEST_PLAN.md says what "working" means.

## Commands

- hub: `cd hub && make setup`, `make dev`, `make fake-camera`, `make test`, `make lint`, `make typecheck`
- app: `cd app && ./gradlew assembleDebug`, `./gradlew test`, `./gradlew lint`, `./gradlew connectedAndroidTest`
- cloud: `cd cloud && npm run dev`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`, `npx playwright test`
- root: `make check` runs lint, typecheck and tests for every component

## Privacy invariants

These have no feature flag, no debug mode and no temporary bypass. If a task cannot be done without breaking one, stop and raise it.

1. No face photo is ever written to disk, database, log, crash report or cloud. Crops live in memory until embedded. The only footage is the 5-second match clip: encrypted on the hub, deleted after 30 days, LAN only, never uploaded.
2. Only the hub computes embeddings. The app sends an aligned crop with landmarks. Templates are scrambled with the per-shop key and never leave the hub.
3. The cloud never accepts or stores image bytes, templates or clips.
4. Members with status `pending_notice` are never matched. Only `active` templates are loaded into the matcher.
5. `consent_records` is append-only, written only through the chain function. Withdrawal, deletion and expiry are new chained records, never edits.
6. "STOP" or "Remove member" deletes the template within one hour and logs `template_deleted_at`.
7. The nightly job deletes templates unseen for 1095 days and clips older than 30 days, and logs it.
8. An age estimate under 18 refuses enrolment. There is no override.
9. Unenrolled faces at the gate are discarded within seconds. Nothing is stored about non-members; a companion or passer-by may appear incidentally in a member's encrypted 5-second clip (LAN only, deleted after 30 days) and is never detected or indexed (docs/PRIVACY_AND_CONSENT.md section 12).
10. No PII in logs or Sentry: no names, phone numbers, notes, items, message text, crops or vectors. Log ids only.

## How to work a task

1. Open TASKS.md and pick the next unchecked task in the current phase. One task per session, in order.
2. Build the prompt from docs/PROMPTS.md: CONTEXT, TASK, FILES, CONSTRAINTS, ACCEPTANCE CRITERIA, TESTING.
3. Read the files the task touches before changing anything. For more than three files, or any schema, API or status change, write the plan first.
4. Implement with tests. Run the component's lint, typecheck and tests, then `make check` at the root. The evaluation harness in `tests/eval/` must pass before any model pack or threshold change.
5. Commit on a feature branch with a conventional message that names the task, for example `feat(hub): consent hash chain (TASK-031)`.
6. Tick the task in TASKS.md. Update docs/MEMORY.md (status, current task, known issues, next step). Add an ADR to docs/DECISIONS.md if you made a decision.
7. Mark anything that needs a human decision with "OPEN:". Do not widen scope beyond docs/PRD.md.

## Rulebook

docs/RULES.md is the full rulebook and wins over this file. The Cursor rules in `.cursor/rules/` are derived from it and must stay consistent with it.
