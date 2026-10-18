"""Phase 2 and later subcommands."""

from __future__ import annotations

import argparse
import getpass
import sys
from pathlib import Path
from typing import Any

from remu_hub.cli import _settings, command


def register(sub: Any) -> None:
    init = sub.add_parser("init", help="create the data directory, shop key, certificate and code")
    init.add_argument(
        "--state-only",
        action="store_true",
        help="Pi first boot: shop key, pairing code and certificate only",
    )
    db = sub.add_parser("db", help="database").add_subparsers(dest="sub")
    db.add_parser("migrate", help="apply migrations (idempotent)")
    staff = sub.add_parser("staff", help="staff").add_subparsers(dest="sub")
    add = staff.add_parser("add", help="create a staff row with a PIN")
    add.add_argument("--name", required=True)
    add.add_argument("--role", choices=["owner", "cashier"], required=True)
    add.add_argument("--pin", help="PIN (prompted when omitted)")
    consent = sub.add_parser("consent", help="consent records").add_subparsers(dest="sub")
    consent.add_parser("verify", help="walk the hash chain")
    exp = consent.add_parser("export", help="write the chain as CSV")
    exp.add_argument("--csv", required=True, type=Path)
    serve = sub.add_parser("serve", help="run the LAN API, the gate pipeline and sync")
    serve.add_argument("--no-pipeline", action="store_true")
    serve.add_argument("--no-sync", action="store_true")
    sub.add_parser("status", help="show health and the last log lines")
    unlock = sub.add_parser("unlock", help="open the encrypted data directory (systemd)")
    unlock.add_argument("--container", type=Path, default=Path("/var/lib/remu/data.luks"))
    unlock.add_argument("--create", action="store_true", help="(kept for scripts; automatic)")
    jobs = sub.add_parser("jobs", help="scheduled jobs").add_subparsers(dest="sub")
    jobs.add_parser("run", help="run the job schedule forever")
    jobs.add_parser("delete-templates", help="run the template deletion sweep once")
    jobs.add_parser("expire", help="run member expiry once")
    jobs.add_parser("cleanup-clips", help="run clip cleanup once")
    jobs.add_parser("anchor", help="post the chain head to the cloud once")


def _open_db() -> Any:
    from remu_hub.db.database import Database

    return Database(_settings().db_path)


@command("init")
def cmd_init(args: argparse.Namespace) -> int:
    from remu_hub.api.tls import ensure_cert, fingerprint
    from remu_hub.db import auth
    from remu_hub.db.database import Database
    from remu_hub.keys import create_shop_key

    s = _settings()
    if args.state_only:
        # Pi first boot: nothing may touch the data directory before the volume exists.
        s.state_dir.mkdir(parents=True, exist_ok=True)
        create_shop_key(s.shop_key_path)
        cert, _ = ensure_cert(s.certs_dir)
        pairing = s.state_dir / "pairing.txt"
        if not pairing.exists():
            pairing.write_text(auth.make_code() + "\n")
            pairing.chmod(0o600)
        print(f"certificate sha256 {fingerprint(cert)}")
        print(f"pairing code {pairing.read_text().strip()}")
        return 0
    for d in ("db", "clips", "models", "certs"):
        (s.data_dir / d).mkdir(parents=True, exist_ok=True)
    create_shop_key(s.shop_key_path)
    print(f"data directory {s.data_dir} (not encrypted until Phase 7; development only)")
    db = Database(s.db_path)
    if db.one("SELECT 1 FROM shops WHERE id = ?", (s.shop_id,)) is None:
        db.execute(
            "INSERT INTO shops (id, shop_key_ref) VALUES (?, ?)", (s.shop_id, str(s.shop_key_path))
        )
    cert, _ = ensure_cert(s.certs_dir)
    code = auth.create_hub_code(db)
    pairing = s.state_dir / "pairing.txt"
    if code:
        pairing.write_text(code + "\n")
        pairing.chmod(0o600)
    shown = pairing.read_text().strip() if pairing.exists() else "(already used)"
    print(f"certificate {cert} sha256 {fingerprint(cert)}")
    print(f"pairing code {shown}")
    return 0


@command("db", "migrate")
def cmd_migrate(_: argparse.Namespace) -> int:
    from remu_hub.db import connect, migrate

    applied = migrate(connect(_settings().db_path))
    print("applied " + (", ".join(map(str, applied)) if applied else "nothing (up to date)"))
    return 0


@command("staff", "add")
def cmd_staff_add(args: argparse.Namespace) -> int:
    from remu_hub.db import auth

    s = _settings()
    db = _open_db()
    if db.one("SELECT 1 FROM shops WHERE id = ?", (s.shop_id,)) is None:
        print("run `remu-hub init` first", file=sys.stderr)
        return 1
    pin = args.pin or getpass.getpass("PIN (4 to 6 digits): ")
    try:
        sid = auth.add_staff(db, s.shop_id, args.name, args.role, pin)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"staff {sid} ({args.role})")
    return 0


@command("consent", "verify")
def cmd_consent_verify(_: argparse.Namespace) -> int:
    from remu_hub.db.consent import verify_chain

    report = verify_chain(_open_db())
    if report.intact:
        print(f"chain intact: {report.records} records, head {report.head}")
        return 0
    print(f"chain BROKEN at record {report.records + 1} ({report.first_break}): {report.reason}")
    return 1


@command("consent", "export")
def cmd_consent_export(args: argparse.Namespace) -> int:
    from remu_hub.export import consent_csv

    args.csv.write_text(consent_csv(_open_db()))
    print(f"wrote {args.csv}")
    return 0


@command("serve")
def cmd_serve(args: argparse.Namespace) -> int:
    from remu_hub.runtime import serve

    return serve(
        _settings(require_stream=not args.no_pipeline),
        with_pipeline=not args.no_pipeline,
        with_sync=not args.no_sync,
    )


@command("status")
def cmd_status(_: argparse.Namespace) -> int:
    import json
    import shutil
    import subprocess

    from remu_hub.hub import Hub

    print(json.dumps(Hub(_settings()).health(), indent=2))
    if shutil.which("systemctl"):
        for unit in ("remu-hub.service", "remu-hub-jobs.service"):
            subprocess.run(["systemctl", "--no-pager", "status", unit], check=False)
        subprocess.run(["journalctl", "--no-pager", "-n", "20", "-u", "remu-hub"], check=False)
    return 0


def _hub() -> Any:
    from remu_hub.hub import Hub

    return Hub(_settings())


def _client() -> Any:
    from remu_hub.sync.client import CloudClient
    from remu_hub.sync.loop import load_device_token

    s = _settings()
    if not s.cloud_url:
        return None
    return CloudClient(s.cloud_url, load_device_token(s))


@command("jobs", "run")
def cmd_jobs_run(_: argparse.Namespace) -> int:
    from remu_hub.jobs.scheduler import Scheduler

    Scheduler(_hub(), _client()).run_forever()
    return 0


@command("jobs", "delete-templates")
def cmd_jobs_delete(_: argparse.Namespace) -> int:
    from remu_hub.jobs import delete_templates

    print(f"deleted {delete_templates.run(_hub())} leftover templates")
    return 0


@command("jobs", "expire")
def cmd_jobs_expire(_: argparse.Namespace) -> int:
    from remu_hub.jobs import expire_members

    print(f"expired {len(expire_members.run(_hub()))} members")
    return 0


@command("jobs", "cleanup-clips")
def cmd_jobs_clips(_: argparse.Namespace) -> int:
    from remu_hub.jobs import cleanup_clips

    print(f"removed {cleanup_clips.run(_hub())} clips")
    return 0


@command("jobs", "anchor")
def cmd_jobs_anchor(_: argparse.Namespace) -> int:
    from remu_hub.jobs import anchor

    client = _client()
    if client is None:
        print("REMU_CLOUD_URL is not set", file=sys.stderr)
        return 1
    print(anchor.run(_hub(), client))
    return 0


@command("unlock")
def cmd_unlock(args: argparse.Namespace) -> int:
    """systemd ExecStartPre (root): claim, fetch the unlock token, create or open the volume.

    Fails closed: without cryptsetup the hub does not start, unless REMU_DEV_PLAINTEXT=1 says
    this is a development machine (review H5).
    """
    import os
    import pwd
    import time as _time

    from remu_hub.api.tls import ensure_cert
    from remu_hub.keys import read_shop_key
    from remu_hub.sync.loop import claim
    from remu_hub.unlock import Volume, available, passphrase, unlock_at_boot

    s = _settings()
    if not available():
        if os.environ.get("REMU_DEV_PLAINTEXT") == "1":
            print("development: cryptsetup missing, using a plain directory", file=sys.stderr)
            return 0
        print(
            "cryptsetup not found; refusing to serve member data from a plain folder",
            file=sys.stderr,
        )
        return 1
    client = _client()
    if client is None:
        print("REMU_CLOUD_URL is not set", file=sys.stderr)
        return 1
    ensure_cert(s.certs_dir)  # the claim carries its fingerprint
    code_file = s.state_dir / "pairing.txt"
    while not client.device_token:
        try:
            if code_file.exists() and claim(s, client, code_file.read_text().strip()):
                break
        except Exception as exc:  # offline or not yet registered: keep waiting
            print(f"waiting for pairing: {exc}", file=sys.stderr)
        print("waiting for the owner to pair this hub in the app", file=sys.stderr)
        _time.sleep(30)
    key = read_shop_key(s.shop_key_path)
    volume = Volume(args.container, s.data_dir)
    if not args.container.exists():
        volume.create(passphrase(key, client.unlock()))
    elif not unlock_at_boot(volume, key, client.unlock):
        return 1
    if os.geteuid() == 0:
        try:
            user = pwd.getpwnam("remu")
        except KeyError:
            return 0
        for path in (s.data_dir, s.state_dir / "device.token", s.state_dir / "cloud_shop_id"):
            if path.exists():
                os.chown(path, user.pw_uid, user.pw_gid)
    return 0
