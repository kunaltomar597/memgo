from __future__ import annotations

import sqlite3
from collections.abc import Sequence
from pathlib import Path

import numpy as np
import pytest

from remu_hub.db import members as mdb
from remu_hub.observability import before_send, scrub
from remu_hub.recovery import backup_path, daily_copy, integrity_ok, recover_if_corrupt
from remu_hub.unlock import Volume, passphrase, unlock_at_boot
from tests.helpers import face, make_hub


def test_scrubber_drops_pii_and_binary() -> None:
    event = {
        "message": "enrol failed for +91 9876543210",
        "extra": {
            "display_name": "Rajesh",
            "notes": "likes gold",
            "member_id": "m_1",
            "crop": b"\xff\xd8",
            "blob": "A" * 400,
            "has_template": True,
        },
        "request": {"data": {"phone": "9876543210"}, "url": "https://hub/enrol"},
        "user": {"id": "x"},
    }
    out = before_send(event, {})
    assert out is not None
    text = repr(out)
    assert "9876543210" not in text and "Rajesh" not in text and "likes gold" not in text
    assert "AAAA" * 50 not in text and "user" not in out and "data" not in out["request"]
    assert out["extra"]["member_id"] == "m_1" and out["extra"]["has_template"] is True
    assert scrub(["call 9876543210"]) == ["call [scrubbed]"]


def test_passphrase_needs_both_halves() -> None:
    a = passphrase(b"k" * 32, "tok-1")
    assert len(a) == 32 and a != passphrase(b"k" * 32, "tok-2") != passphrase(b"j" * 32, "tok-1")


class FakeRunner:
    def __init__(self, open_ok: bool = True) -> None:
        self.calls: list[tuple[list[str], bytes | None]] = []
        self.mounted = False
        self.open_ok = open_ok

    def __call__(self, cmd: Sequence[str], stdin: bytes | None) -> int:
        self.calls.append((list(cmd), stdin))
        if cmd[0] == "mountpoint":
            return 0 if self.mounted else 1
        if cmd[:2] == ["cryptsetup", "open"]:
            return 0 if self.open_ok else 2
        if cmd[0] == "mount":
            self.mounted = True
        return 0


def test_unlock_retries_while_offline_then_opens(tmp_path: Path) -> None:
    runner = FakeRunner()
    vol = Volume(tmp_path / "data.luks", tmp_path / "data", runner)
    attempts = {"n": 0}
    states: list[bool] = []
    sleeps: list[float] = []

    def fetch() -> str:
        attempts["n"] += 1
        if attempts["n"] < 3:
            raise ConnectionError("offline")
        return "unlock-token"

    assert unlock_at_boot(vol, b"k" * 32, fetch, sleep=sleeps.append, on_locked=states.append)
    assert sleeps == [60.0, 60.0] and states == [True, True, False] and runner.mounted
    key_inputs = [stdin for cmd, stdin in runner.calls if cmd[:2] == ["cryptsetup", "open"]]
    assert key_inputs == [passphrase(b"k" * 32, "unlock-token")]
    assert not any(p.name.endswith("token") for p in tmp_path.rglob("*"))  # never on disk


def test_revoked_device_stays_locked(tmp_path: Path) -> None:
    vol = Volume(tmp_path / "data.luks", tmp_path / "data", FakeRunner(open_ok=False))
    assert not unlock_at_boot(vol, b"k" * 32, lambda: "t", sleep=lambda s: None, max_tries=2)


def test_daily_copy_never_contains_templates(tmp_path: Path) -> None:
    from remu_hub.db import auth
    from remu_hub.hub import EnrolIn, FaceIn

    hub = make_hub(tmp_path)
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    mids = [
        hub.enrol(EnrolIn(staff_id=staff, phone=f"98765{i:05d}", faces=[FaceIn(**face(100 + i))]))[
            "members"
        ][0]["member_id"]
        for i in range(2)
    ]
    hub.withdraw(mids[0], "owner_remove")
    copy = daily_copy(hub.settings.db_path)
    conn = sqlite3.connect(str(copy))
    assert conn.execute("SELECT COUNT(*) FROM templates").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM members").fetchone()[0] == 2
    conn.close()
    blob = np.asarray(
        mdb.load_template(
            hub.db.one("SELECT vec_blob FROM templates WHERE member_id = ?", (mids[1],))
        ),
        "<f4",
    ).tobytes()
    for f in hub.settings.data_dir.rglob("*"):
        if f.is_file() and f.name.startswith("remu.daily"):
            assert blob[:64] not in f.read_bytes()


def test_corrupt_database_is_restored_from_the_daily_copy(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    path = hub.settings.db_path
    daily_copy(path)
    hub.db.close()
    data = bytearray(path.read_bytes())
    data[100:4096] = b"\x13" * (4096 - 100)
    path.write_bytes(bytes(data))
    assert not integrity_ok(path)
    assert recover_if_corrupt(path)
    assert integrity_ok(path) and path.read_bytes() == backup_path(path).read_bytes()
    assert (path.parent / "remu.sqlite.corrupt").exists()


def test_intact_database_is_left_alone(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    assert not recover_if_corrupt(hub.settings.db_path)


@pytest.mark.parametrize("n", [1])
def test_enrol_limits(tmp_path: Path, n: int) -> None:
    from fastapi.testclient import TestClient

    from remu_hub.api.app import create_app
    from remu_hub.db import auth

    hub = make_hub(tmp_path)
    client = TestClient(create_app(hub))
    token = client.post("/pair", json={"code": auth.create_hub_code(hub.db)}).json()["app_token"]
    h = {"Authorization": f"Bearer {token}"}
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    seven = {"staff_id": staff, "phone": "9876543210", "faces": [face()] * 7}
    assert client.post("/enrol", json=seven, headers=h).status_code == 400
    big = {"staff_id": staff, "phone": "9876543210", "faces": [{"crop_jpeg_b64": "A" * 500_000}]}
    assert client.post("/enrol", json=big, headers=h).status_code == 400
    # Re-pairing the owner phone revokes the old owner token.
    hub.db.execute("DELETE FROM pairing_codes")
    code = auth.create_hub_code(hub.db)
    client.post("/pair", json={"code": code})
    assert client.get("/members", headers=h).status_code == 401 * n
