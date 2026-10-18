# Remu Project Memory

Purpose: the current state of the Remu project, rewritten at the end of every task so the next session starts from the truth, not from memory.

## Current status

As of 2026-10-18: the documentation set is complete and reviewed. The repository folder layout exists and git is initialised on `main` with no commits. The first commit is the next step. No code has been written yet.

## Completed

- docs/BRIEF.md, the product brief dated 2026-10-18, the source of truth for every document.
- Root: README.md, CLAUDE.md, TASKS.md, .env.example, .gitignore.
- docs/: BRIEF.md, PRD.md, ARCHITECTURE.md, DESIGN.md, RULES.md, TEST_PLAN.md, SECURITY.md, PRIVACY_AND_CONSENT.md, DECISIONS.md, MEMORY.md, PILOT_PLAN.md, RESEARCH.md, PROMPTS.md.
- .cursor/rules/: general.mdc, frontend.mdc, backend.mdc, testing.mdc.
- Component READMEs: hub/README.md, app/README.md, cloud/README.md, tests/README.md, scripts/README.md.
- Folder skeleton: hub/, app/, cloud/, tests/unit, tests/integration, tests/e2e, tests/eval, scripts/.

## Current task

TASK-001 [all] Initialise the monorepo (Phase 0: Project setup). From TASKS.md:

- The layout from docs/ARCHITECTURE.md exists: `hub/`, `app/`, `cloud/`, `tests/unit/`, `tests/integration/`, `tests/e2e/`, `tests/eval/`, `scripts/`, `docs/`, `.cursor/rules/`.
- `.gitignore` excludes `.env`, `.env.local`, `google-services.json`, hub data directories, downloaded model packs, sample videos, `node_modules/`, `.venv/`, Gradle and Next.js build output.
- `git init` done on `main`, first commit made, GitHub repository created and pushed.

The hub tooling scaffold is TASK-002 and the root `make check` target is TASK-005. They come after this task.

## Known issues

- InsightFace model packs (`buffalo_l`, `buffalo_sc`) are non-commercial. Obtain a commercial licence or switch packs before Phase 9 (see docs/RESEARCH.md section 3 and ADR-003). No code issues yet.

## Open questions

Blocking items first. The full list lives in each document under the tag "OPEN:".

- OPEN: InsightFace model packs (`buffalo_l`, `buffalo_sc`) are licensed for non-commercial research only. Obtain a commercial licence or switch packs and re-run `tests/eval/` before the pilot. Blocks Phase 9. See docs/RESEARCH.md section 3.
- OPEN: Pairing direction. The brief describes both the hub showing a code typed into the app and the app showing a code. TASKS.md and docs/ARCHITECTURE.md flow A keep the hub-generated code typed into the app; confirm with the founder before TASK-028 in Phase 2.
- OPEN: Pending window. A PENDING_NOTICE member whose notice never delivers keeps a template indefinitely; a 7-day window after which the template is deleted is a proposal only. The lawyer decides. See docs/ARCHITECTURE.md section 5 and docs/PRIVACY_AND_CONSENT.md section 10.
- OPEN: FCM payload. The push carries only `shop_id` and `visit_id`; decide whether the lock-screen notification shows the first name or only "Member at the gate". See docs/SECURITY.md.
- OPEN: Lost owner phone. The app keeps only in-memory state plus DataStore (no database), but a single-phone owner has no way to revoke a lost phone without calling Remu support in MVP. See docs/SECURITY.md.
- OPEN: SSH on pilot hubs. The shipped image has no SSH; decide the hub update channel (signed wheel versus manual SSH) and whether an owner-enabled time-limited shell exists for pilot support. See docs/ARCHITECTURE.md and docs/SECURITY.md.
- OPEN: Which city hosts the pilot.
- OPEN: Lawyer sign-off on the exact cashier script and notice wording (hi-IN-v1.0, wa-v1.0) before the pilot.
- OPEN: WhatsApp Business Account and template approval: business entity, dedicated number, and who submits `WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN` and `WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI` to Meta.
- OPEN: Pi 5 sourcing for the pilot: supplier and landed price within the 9,000 to 10,000 rupee kit budget.
- OPEN: Which DVR brands to support first (CP Plus, Hikvision, Dahua).
- OPEN: Discount policy per shop; the v1.0 scripts promise 5 percent off the next visit.
- OPEN: The exact monthly software price within 500 to 1,500 rupees, set after the pilot.

## Next step

Start TASK-001. Before writing code, read CLAUDE.md, docs/PRD.md, docs/ARCHITECTURE.md, docs/RULES.md and TASKS.md, explain the plan for TASK-001, and wait for a go. Rewrite this file when TASK-001 is done. Note: TASKS.md TASK-008 expects this file to name TASK-009 as the current task at that point; that criterion is checked in TASKS.md, not here.
