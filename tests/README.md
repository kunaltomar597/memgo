# tests

Purpose: the cross-component tests of Remu: shared fixtures, hub-against-cloud integration, fake-camera-to-app end-to-end, and the evaluation harness. Component tests live with their component (`hub/tests/`, `app/app/src/test/`, `app/app/src/androidTest/`, `cloud/**/*.test.ts`). Nothing here yet beyond the four empty folders.

## What will live here

- `unit/`: shared fixtures: synthetic members, random unit-vector embeddings, signed sample WhatsApp webhook payloads. No real name, phone number or face, ever.
- `integration/`: the hub against a local cloud: outbox flush and inbox pull, activation after a mocked `delivered` webhook, withdrawal, the nightly anchor check.
- `e2e/`: fake camera to hub to local cloud to the app on an emulator: enrol, notice, match, alert, "Greeted", "Not them", remove member.
- `eval/`: the evaluation harness. A labelled face-pair set (a public benchmark subset for development, a consented pilot set later) reporting true accept rate at a false accept rate of 0.1 percent, picking the threshold (0.45 today) and testing ageing, low light and angle. It must pass before any change to `REMU_MODEL_PACK`, the model version, `REMU_MATCH_THRESHOLD`, `REMU_FRAMES_REQUIRED` or `REMU_FRAMES_WINDOW` is merged. Benchmark images stay on the developer machine under the gitignored `tests/eval/data/` and never go on a hub.

Test ids follow docs/TEST_PLAN.md: `T-<flow letter>-<number>`, so `grep T-B-05` finds the test.

## Commands

`make check` at the root runs lint, typecheck and tests for every component. `cd hub && make eval` runs the harness; TASKS.md defines it in TASK-021.

## Created by

`eval/` is built in TASK-021 and TASK-022 (Phase 1). `unit/` fixtures start in Phase 2 with the enrolment endpoint. `integration/` starts in Phase 4 with the cloud. `e2e/` starts in Phase 5 with Owner mode. OPEN: which public face-pair benchmark to use, and whether its licence allows this use.
