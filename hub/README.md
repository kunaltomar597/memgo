# hub

Purpose: the Remu hub, the Python 3.11 service that runs in the shop on a Raspberry Pi 5 and does all face work. Nothing here yet; TASK-002 (Phase 0) creates the scaffold.

## What will live here

The `remu_hub` package, its tests, `pyproject.toml` (ruff, mypy strict and pytest configuration) and a Makefile.

Pipeline: the hub decodes the gate RTSP stream, then detects, aligns and embeds faces with InsightFace `buffalo_l` on ONNX Runtime CPU. It scrambles embeddings into per-shop templates and matches against `active` members only (threshold 0.45, 3 of 5 frames, 10-minute cooldown).
API and records: it writes visits and 5-second encrypted clips, serves the LAN API on port 8443 and writes the hash-chained `consent_records`.
Sync and jobs: it syncs outbound through `events_outbox` and runs the nightly jobs (1095-day expiry, 30-day clip cleanup, hash anchor, outbox flush, health).
No face photo is ever written. Templates and clips never leave the hub.

## Planned structure

Module names follow TASKS.md and docs/ARCHITECTURE.md section 3.1.

    hub/                    pyproject.toml, Makefile, models/ (gitignored), tests/, remu_hub/
    remu_hub/settings.py    REMU_* variables, defaults, validation, one typed settings object
    remu_hub/video/         RTSP frame source (OpenCV, reconnect, throttle)
    remu_hub/faces/         models.py, detect.py, embed.py, scramble.py, age.py
    remu_hub/match/         gallery.py (cosine), decide.py (3 of 5, cooldown)
    remu_hub/pipeline.py    wires source, detect, embed, scramble, match and decide into one loop
    remu_hub/db/, api/      SQLite schema and migrations, chain, state machine; LAN FastAPI and WebSocket
    remu_hub/sync/, jobs/   outbox, client, inbox; expiry, template deletion, clip cleanup, anchor, health
    remu_hub/cli.py         remu-hub init | db migrate | models download | staff add | consent verify | consent export | discover | demo | jobs run | status

Dependency direction: `api`, `jobs` and `cli` call `db`, `match`, `faces` and `sync`. `faces` knows nothing about the database. `db` knows nothing about HTTP.

## Commands

`cd hub && make setup`, `make dev`, `make fake-camera`, `make test`, `make lint`, `make typecheck`, `make eval`. Configuration comes only from the Hub section of `.env.example`, copied to `hub/.env` in development (never committed) and to `/etc/remu/hub.env` on the Pi.

## Created by

TASK-002 (Phase 0: Project setup) creates `pyproject.toml`, the Makefile and a passing `make setup`, `make lint`, `make typecheck` and `make test`. TASK-005 adds the root `make check`. Phase 1 (TASK-010 to TASK-024) fills `settings`, `video/`, `faces/`, `match/` and `pipeline` against the fake camera. Phase 2 fills `db/` and `api/`. Phase 7 puts it on the Pi 5.
