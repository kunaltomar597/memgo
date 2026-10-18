# Remu Prompts

Purpose: the exact prompts we give an AI coding session at each step of the loop, filled in for Remu, so every session starts with the same context and ends with the same report. Written 2026-10-18.

## How to use this file

- One task per session, in TASKS.md order. Copy the block you need, fill the square brackets, paste it as the first message of the session.
- The loop is read, understand, plan, implement, test, review, fix, commit, update documentation. Section 1 covers read and understand. Section 2 covers plan and implement, with test and the report built in. Sections 3 and 4 cover review and fix. The commit is a feature branch and a conventional message naming the task, for example `feat(hub): consent hash chain (TASK-031)` on `feature/p2-consent-chain`. Section 5 covers the documentation update. Section 6 is the extra gate for privacy-sensitive changes.
- The brief of 2026-10-18 and docs/RULES.md win over anything a prompt says. If a prompt and a document disagree, fix the prompt.
- File names in the prompts are real paths in this repository. Do not shorten them.
- Never paste a real name, phone number, face, token or key into a prompt. Ids and synthetic data only.

## 1. Project kickoff prompt

Use at the start of every session, and always before the first line of code on a new task.

```text
Read the following files before making any changes:

CLAUDE.md
docs/PRD.md
docs/ARCHITECTURE.md
docs/DESIGN.md
docs/RULES.md
TASKS.md
docs/MEMORY.md

If the current task touches consent, members, templates, clips, webhooks, or anything under hub/remu_hub/db/, hub/remu_hub/sync/, cloud/src/app/api/ or cloud/supabase/, also read:

docs/PRIVACY_AND_CONSENT.md
docs/SECURITY.md

Do not modify anything yet.

First:
1. Understand the product: the core moment, the two member layers (phone member and face member), the consent model and the pilot success criteria in docs/PRD.md.
2. Understand the architecture: the three components (hub, app with Cashier mode and Owner mode, cloud), which side authors each table, the member state machine (pending_notice, active, withdrawn, expired) and the architectural rules in docs/ARCHITECTURE.md section 13.
3. Review the design system in docs/DESIGN.md if the task touches the app.
4. Review the development rules and the ten privacy invariants in docs/RULES.md.
5. Review TASKS.md and docs/MEMORY.md and confirm that the current task is [TASK-XXX]: [one-line title].
6. List missing information: anything the task needs that the documents do not give. For each item propose the simplest option as an assumption, or write "OPEN:" if a human must decide.
7. Explain the implementation plan for [TASK-XXX]: files to create or change, functions and signatures, tests by docs/TEST_PLAN.md id, risks, and which privacy invariants the change touches.

Do not write code yet. Wait for a go.
```

On 2026-10-18 the current task is TASK-001 (Phase 0: Project setup).

## 2. Feature task prompt

### 2.1 Template

```text
CONTEXT
We are building Remu, a shop memory for Indian retail. Read CLAUDE.md, docs/PRD.md, docs/ARCHITECTURE.md and docs/RULES.md. [One or two sentences on where the project is: what the previous task left in place, taken from docs/MEMORY.md.]

TASK
[TASK-XXX] ([Phase N: name]): [One paragraph that says exactly what to build and what not to build, using the names from docs/ARCHITECTURE.md: module paths, table names, status values, endpoint paths, event types, REMU_* variables.]

FILES
[Exact paths to create or change, one per line, with "(new)" where the file does not exist yet.]
Do not touch: [paths that stay untouched].

CONSTRAINTS
- Follow docs/RULES.md for the [hub | app | cloud], the ten privacy invariants, and docs/ARCHITECTURE.md section 13.
- Reuse [the existing module that already does part of the job]. Do not add a second [data layer | HTTP client | settings object | string table].
- Do not modify unrelated files. Do not widen scope past docs/PRD.md; the later-phase list stays out.
- No new dependency without an ADR in docs/DECISIONS.md.
- [Task-specific constraints: the numbers from the brief that apply, which states are allowed, what must never be written to disk or logged.]
- Where the documents do not give a detail, choose the simplest option and write it down as an assumption in the report. If a human must decide, write "OPEN:".

ACCEPTANCE CRITERIA
- [Observable results, one per line, each testable. Use the numbers from the brief: threshold 0.45, 3 of 5 frames, 10-minute cooldown, 30-day clips, 1095-day expiry, 1-hour deletion, 5 percent discount.]
- [What must not happen: no file under REMU_DATA_DIR, no row in a table, no log line with a value, no network call.]

TESTING
- Add or update tests under [hub/tests/ | app/app/src/test/ and app/app/src/androidTest/ | cloud/**/*.test.ts]. Name tests after the docs/TEST_PLAN.md ids they cover, for example T-B-05.
- Synthetic data only. No real name, phone number or face. Fake embeddings are random unit vectors.
- Run [cd hub && make lint, make typecheck, make test | cd app && ./gradlew lint, ./gradlew test | cd cloud && npm run lint, npm run typecheck, npm test, npm run build], then make check at the root.
- [If the task changes REMU_MODEL_PACK, the model version, REMU_MATCH_THRESHOLD, REMU_FRAMES_REQUIRED or REMU_FRAMES_WINDOW: run the evaluation harness in tests/eval/ and paste the report.]

After implementation, report:
1. Files changed
2. What was implemented
3. Tests executed, with the commands and their result
4. Remaining issues
5. Documents that must change (TASKS.md, docs/MEMORY.md, docs/DECISIONS.md, docs/ARCHITECTURE.md, README.md, .env.example)
6. Assumptions made, and any OPEN: items
```

### 2.2 Plan first

For a change that touches more than three files, or any schema, API, job or member status transition, send the template with this line after TASK and stop there:

```text
Before editing, write the plan: files, functions and signatures, tests by docs/TEST_PLAN.md id, risks, and the privacy invariants touched. Wait for a go.
```

### 2.3 Filled example: the first Phase 1 hub task

Phase 1 starts once Phase 0 has put the `hub/` skeleton (TASK-002) and the fake camera script (TASK-007) in place, and TASK-010 has added the settings module. The first pipeline task, TASK-011, reads the stream and nothing more. Ids and paths come from TASKS.md.

```text
CONTEXT
We are building Remu, a shop memory for Indian retail. Read CLAUDE.md, docs/PRD.md, docs/ARCHITECTURE.md (sections 3.1, 7, 13, 14 and 15) and docs/RULES.md (Hub rules and Privacy invariants). TASK-002 is done: hub/ has pyproject.toml, a Makefile and a passing make check. TASK-007 is done: make fake-camera runs mediamtx looping the sample video on rtsp://127.0.0.1:8554/gate. TASK-010 is done: remu_hub/settings.py loads the REMU_* variables into one typed settings object.

TASK
TASK-011 (Phase 1: Hub core pipeline on the dev machine plus the eval harness): implement the RTSP frame source in hub/remu_hub/video/source.py. It opens REMU_RTSP_GATE_URL with OpenCV's FFmpeg backend, prefers the sub-stream where the DVR exposes one, resizes every frame to 640 by 360 and throttles to between 5 and 8 frames per second. It yields (timestamp, frame) pairs; a frame older than 500 ms is dropped, never yielded. When no decoded frame arrives for 5 seconds the source marks the stream lost and reconnects with 1, 2, 4, 8 second backoff, and emits a stream.state change on every transition. Nothing else: no detection, no embedding, no storage, no network beyond the stream itself.

FILES
hub/remu_hub/settings.py (read REMU_RTSP_GATE_URL and the optional REMU_RTSP_COUNTER_URL; add no other setting)
hub/remu_hub/video/__init__.py (new)
hub/remu_hub/video/source.py (new)
hub/tests/test_video_source.py (new)
hub/pyproject.toml (add opencv-python-headless and numpy, pinned)
Do not touch: hub/remu_hub/faces/, hub/remu_hub/db/, hub/remu_hub/api/, scripts/.

CONSTRAINTS
- Follow the Hub rules in docs/RULES.md: every function typed, no Any in public signatures, no bare except, no global mutable state outside the settings object. ruff and mypy strict must pass.
- The frame source makes no network call other than reading the stream (docs/ARCHITECTURE.md section 13).
- Privacy invariant 1: never write a frame to disk, a log, a crash report or a temporary file. Log stream state, frame counters and durations only.
- Read configuration only through the settings object. No os.environ anywhere else.
- OpenCV and numpy are named in the brief, so they need no ADR; pin their versions in pyproject.toml. Nothing else new.
- Keep it small. Throttling, the drop rule and reconnect logic all live in source.py.

ACCEPTANCE CRITERIA
- With make fake-camera running, make dev logs one structured line per second with stream state live and a sampled rate between 5 and 8 frames per second on the developer laptop.
- The source yields numpy arrays of shape (360, 640, 3) with a capture timestamp.
- A frame older than 500 ms when yielded is dropped and counted.
- Stopping the fake camera moves the state to lost within 5 seconds. Starting it again moves the state back to live within 10 seconds. The waits between attempts are 1, 2, 4 and 8 seconds, then 8 seconds repeated.
- Process memory does not grow while the stream is lost.
- No file is created under REMU_DATA_DIR by this task.

TESTING
- Unit tests in hub/tests/test_video_source.py with a file-backed fake source and a fake clock, no mediamtx: throttling to 5 to 8 frames per second, resize to 640 by 360, the 500 ms drop rule, the backoff sequence, the transitions live to lost to live. docs/TEST_PLAN.md has no id for the frame source alone (T-D-01 covers the whole pipeline later), so name the tests test_video_source_* and add frame source ids to docs/TEST_PLAN.md in the documentation update.
- One integration test marked rtsp that runs only when the fake camera is reachable and is skipped with a reason otherwise.
- Run cd hub && make lint, make typecheck, make test, then make check at the root.
- Report in the standard format, and include the measured frame rate and CPU percentage on the laptop so docs/MEMORY.md can record them against the budgets in docs/ARCHITECTURE.md section 14.
```

## 3. Code review prompt

Use after implementation and before the commit. Green checks do not mean correct.

```text
Review the implementation of [TASK-XXX] against:

docs/PRD.md
docs/ARCHITECTURE.md (section 13 Architectural rules, section 14 Performance budgets, section 15 Observability)
docs/DESIGN.md (app tasks)
docs/RULES.md (the component rules and the ten privacy invariants)
docs/TEST_PLAN.md
docs/SECURITY.md
docs/PRIVACY_AND_CONSENT.md
docs/DECISIONS.md (no ADR is contradicted)

Check:
- Correctness against every acceptance criterion in the task prompt.
- Architecture: the hub dependency direction (api, jobs and cli call db, match, faces and sync; faces knows nothing about the database; db knows nothing about HTTP); one data layer module with no SQL strings elsewhere; every member status change through db/members.py transition() on the hub or src/lib/memberState.ts in the cloud; every consent_records write through append_consent_record(); app screens observe ViewModels and never touch the network layer.
- Privacy: invariants 1 to 10 in docs/RULES.md, one by one, with evidence. For storage, networking or face pipeline changes, run section 6 of docs/PROMPTS.md as well.
- Security: no secret in code, tests or logs; webhook signatures checked before parsing; Row Level Security on every new table; the pinned certificate honoured; bad input rejected, not cleaned.
- Error handling: every failure path, the offline path (hub unreachable, internet down, cloud 5xx), and the four screen states loading, empty, error and offline in the app.
- Strings: every user-facing string in res/values/strings.xml and res/values-hi/strings.xml, none in composables. Consent texts word for word at version hi-IN-v1.0 and wa-v1.0, for example the cashier line "आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।" (Aap ab hamare member ho gaye. Agli baar aate hi hum aapko pehchaan ke welcome karenge, aur 5% off milega.)
- Accessibility: tap targets, contrast and content descriptions per docs/DESIGN.md section 8 (app tasks).
- Performance: the budgets in docs/ARCHITECTURE.md section 14 that this change can affect.
- Duplication: logic that already exists in this component or in another one.
- Tests: they exist, cover the acceptance criteria, carry docs/TEST_PLAN.md ids, use synthetic data, and never touch the internet.
- Numbers: threshold 0.45, 3 of 5 frames, 10-minute cooldown, 30-day clips, 1095-day expiry, 1-hour deletion, 5 percent discount, wherever the change mentions them.
- The surprise: nothing in the change makes anything react at the gate.

Do not modify anything yet.
Report all issues first, ranked blocker, should fix, nit, each with file and line, what is wrong, and the document it breaks.
Then fix them one by one, smallest first, one commit each, re-running the component checks after each fix.
```

## 4. Debugging prompt

Never say "fix this". Give the structure, then ask for the root cause before any change.

```text
ERROR
[The exact error, stack trace or failing test output. Ids only; never a phone number, name, token or key.]

EXPECTED
[What docs/PRD.md, docs/ARCHITECTURE.md or docs/TEST_PLAN.md says should happen, with the test id if there is one, for example T-B-05.]

ACTUAL
[What happens.]

STEPS
1. [for example: cd hub && make fake-camera]
2. [for example: make dev]
3. [for example: POST /enrol with the fixture crop and a synthetic phone number]
4. [where it fails]

CONSTRAINT
[What must not change. For example: do not change the SQLite schema; do not touch append_consent_record(); do not lower REMU_MATCH_THRESHOLD; do not skip, weaken or delete a test.]

Do not modify code yet. Find the root cause. Explain:
1. What is failing?
2. Why is it failing?
3. Which file and function is responsible?
4. What is the smallest fix?
5. How will we test the fix, and which docs/TEST_PLAN.md id covers it?
```

When the root cause is agreed:

```text
Implement the smallest fix. Do not refactor unrelated code. Add or update the test that would have caught this. Run the component checks and make check at the root. Report in the standard format.
```

A privacy invariant is never the thing to relax to make a bug go away. If the only fix would relax one, stop and write "OPEN:".

## 5. Documentation update prompt

Use at the end of every task, after the commit. A task is not complete until the documents match the code.

```text
[TASK-XXX] is implemented, reviewed, tested and committed on branch [feature/pN-name] as [commit]. Update the documents so they describe the code as it is now. Do not touch code.

1. TASKS.md: tick [TASK-XXX]. Add a follow-up task only for work the review found that this task must not absorb.
2. docs/MEMORY.md: rewrite it. Current status, completed (add this task), current task (the next unchecked TASK id in TASKS.md order), known issues, open questions, next step.
3. docs/DECISIONS.md: add an ADR, numbered after the last one, for any decision made in this task on a library, schema, API, threshold, wording or region. Decision, reason, alternatives considered, consequences.
4. docs/ARCHITECTURE.md and README.md: update when a module, table, endpoint, event type, command or flow changed. Keep the trees and tables exact.
5. docs/TEST_PLAN.md: add ids for new tests. Never remove one.
6. docs/PRIVACY_AND_CONSENT.md and docs/SECURITY.md: update when storage, retention, networking, consent wording or a secret changed.
7. .env.example: add any new variable with one comment line above it and its default.

Conventions: one H1 per document, a one-line purpose statement, then sections. Plain English, short sentences, no em dashes, no marketing. Component names hub, app (Cashier mode, Owner mode) and cloud; roles member, owner, cashier. Hindi in Devanagari with the romanised version beside it. Absolute dates; today is [YYYY-MM-DD]. Commands, variables, tables, files and phases named exactly as in docs/ARCHITECTURE.md. Numbers consistent with the brief: 0.45, 3 of 5, 10 minutes, 30 days, 1095 days, 1 hour, 5 percent, 60-day pilot, 5 to 10 shops, 15,000 rupees, 500 to 1,500 rupees per month. Anything that needs a human decision starts with "OPEN:".

Report: files changed, ADRs added, OPEN items added or closed.
```

## 6. Privacy invariant check prompt

Run before any merge that touches storage, networking or the face pipeline. In practice: anything under hub/remu_hub/video/, faces/, match/, db/, clips/, sync/, api/ or jobs/, or hub/remu_hub/pipeline.py; cloud/src/app/api/, cloud/src/lib/ or cloud/supabase/migrations/; the app's network/ or feature/enrol/ packages; any migration; any logging or Sentry change; any new dependency that reads or writes files or sockets. When in doubt, run it. It is read-only: it reports, it does not fix.

```text
Check the diff of [branch or pull request] for [TASK-XXX] against the ten privacy invariants in docs/RULES.md and the architectural rules in docs/ARCHITECTURE.md section 13. For each item give evidence (file and line, or test id) and a verdict: PASS, FAIL or NOT TOUCHED. Do not modify anything.

1. No face photo is written anywhere. Search hub/ for imwrite, imencode, tofile, PIL, Pillow, pickled arrays and tempfile use with image data; search app/ for Bitmap.compress, FileOutputStream and cache writes of camera frames. Confirm crops are freed after embedding and that the Sentry before_send scrubber drops crop, image, landmarks and vec fields. The only footage allowed is the 5-second match clip: encrypted at rest, LAN only, deleted after 30 days.
2. Only the hub computes embeddings. No onnxruntime, insightface or ArcFace code under app/ or cloud/. The app sends an aligned crop with landmarks and nothing else. templates.vec_blob never appears in any payload that leaves the hub.
3. The cloud never accepts or stores image bytes, templates or clips. Every route rejects multipart/form-data and image content types with 415, every schema rejects unknown fields, and no column or type could hold them. The test that asserts this for every route still passes.
4. pending_notice is never matched. The matcher loads only templates whose member is active, and the test for it (T-D-04) still passes. Only the cloud moves a member to active, and only on a WhatsApp delivered webhook.
5. consent_records is append-only. Every write goes through append_consent_record(); the SQLite triggers rejecting UPDATE and DELETE still exist after any migration; chain verification over existing records passes; the cloud policy is insert-only.
6. STOP or Remove member deletes the template within 1 hour and appends a record with template_deleted_at. The consent record itself is never deleted.
7. The nightly jobs delete templates unseen for 1095 days and clips older than 30 days, append the records, and are idempotent.
8. Age gate: an estimate under 18 refuses enrolment with no override field, flag, setting or role, stores nothing, and runs on every face in a household.
9. Non-members at the gate are discarded within seconds. Track state and embeddings are dropped at the end of the 5-frame window. No row, file, counter by face or log line with a vector exists for a non-member (T-D-05).
10. No PII in logs, Sentry or FCM payloads: no names, phone numbers, notes, items, message text, crops, landmarks, vectors, tokens, keys, pairing codes or raw webhook bodies. Ids only. FCM carries shop_id, visit_id and member_id only.

Also confirm:
- No sync serialiser exists for templates or clips; sync/outbox.py builds payloads from allow-listed columns only.
- The hub opens no inbound port except 8443 on the LAN; all internet traffic is outbound; the cloud never calls the hub.
- No service, queue, cache or log sink outside India was added without an ADR.
- No secret, env file, google-services.json, model pack, key, token, clip or fixture video is in the diff.
- The privacy scanner (after make fake-camera, one enrolment and one match: no image file under REMU_DATA_DIR except an encrypted clip, no phone number in the log) still passes.

Output a table: invariant, evidence, verdict. Any FAIL blocks the merge. If an invariant cannot be met by this task, say so. The answer is to stop and write "OPEN:", never to add a flag.
```

OPEN: whether this check runs as a required CI job on every pull request that touches the paths above, or as a manual step by the founder before merge. Until decided it is manual, and the pull request description links the output.
