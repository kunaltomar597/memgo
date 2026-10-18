# scripts

Purpose: helper scripts for development, provisioning and release that are not part of any shipped component. Nothing here yet; TASK-007 (Phase 0) creates the first script.

## What will live here

- `fake_camera.py`: downloads a pinned mediamtx release for macOS and Linux and loops a sample video on `rtsp://127.0.0.1:8554/gate` for `make fake-camera`, so a face in the sample video gets enrolled and then matched on the developer laptop. The sample video shows only volunteers who signed a consent form, is never committed, and is dropped into `tests/eval/samples/` by hand (see its README). The model pack is fetched by `remu-hub models download`.
- `privacy_scan.py`: the privacy scanner run in CI (TASK-038). After an enrolment and a match against the fake camera it asserts that no image file exists under `REMU_DATA_DIR` except an encrypted clip and that the log contains no phone number. The biometric leak guard in `tests/unit/` (TASK-115) is a separate check on outbound payloads, logs and Sentry events; both run in CI.
- `build_pi_image.sh`: builds the Raspberry Pi OS Lite 64-bit image for the hub: system packages, Python 3.11 venv, `remu_hub` installed, the model pack, a first-boot script. systemd units (`remu-hub.service`, `remu-hub-jobs.service`), `/etc/remu/hub.env` and the encrypted data directory follow in TASK-099 and TASK-101.
- `provision_hub.py`: registers a new device in the cloud (pairing code, unlock secret, device record), writes the code to `pairing.txt` and prints a sticker label.
- `standee/standee.pdf`: the counter standee print file (A5 and A4, Hindi and English, Devanagari font embedded).

Names follow TASKS.md, docs/ARCHITECTURE.md section 12 and docs/SECURITY.md section 10. Scripts read the `REMU_*` variables from the environment and never contain a secret.

## Commands

Scripts run through the Makefiles, not directly: `make fake-camera` and `make setup` in `hub/`, and `make check` at the root, which runs the privacy scanner once it exists.

## Created by

TASK-007 (Phase 0: Project setup) creates `fake_camera.py`. `privacy_scan.py` is TASK-038 (Phase 2). `build_pi_image.sh` is TASK-098, `provision_hub.py` is TASK-105 and `standee/standee.pdf` is TASK-109, all Phase 7. Release v0.1 (TASK-124, Phase 8) names no script; the hub wheel build has no task yet. OPEN: whether SSH stays enabled on pilot hubs, and whether hub updates ship as a signed wheel pulled by the hub or by manual SSH (docs/ARCHITECTURE.md section 12).
