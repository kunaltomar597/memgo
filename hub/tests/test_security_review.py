"""Regression tests for the TASK-112 security review findings (H1 to H7, M1, M2)."""

from __future__ import annotations

import json
import os
import socket
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from remu_hub.api.app import create_app
from remu_hub.db import auth
from remu_hub.hub import EnrolIn, FaceIn, Hub
from remu_hub.sync import outbox
from remu_hub.sync.client import CloudClient
from remu_hub.sync.loop import SyncWorker
from tests.helpers import deliver_and_activate, face, make_hub


def paired(hub: Hub, client: TestClient, role: str = "owner") -> dict[str, str]:
    if role == "owner":
        code = auth.create_hub_code(hub.db) or ""
    else:
        code, _ = auth.create_invite(hub.db)
    token = client.post("/pair", json={"code": code}).json()["app_token"]
    return {"Authorization": f"Bearer {token}"}


def session(client: TestClient, h: dict[str, str], pin: str) -> dict[str, str]:
    r = client.post("/staff/verify-pin", json={"pin": pin}, headers=h)
    return h | {"X-Remu-Staff-Session": r.json()["staff_session"]}


@pytest.fixture
def setup(tmp_path: Path) -> tuple[Hub, TestClient, dict[str, str], str]:
    hub = make_hub(tmp_path)
    client = TestClient(create_app(hub))
    h = paired(hub, client)
    owner_id = auth.add_staff(hub.db, "shop-1", "Owner", "owner", "9999")
    return hub, client, session(client, h, "9999"), owner_id


# ---- H1: one long text must never freeze sync -----------------------------------------
def test_hub_refuses_what_the_cloud_would_refuse() -> None:
    text = ("a b c d " * 160).strip()  # letters and spaces, over 1,024 characters
    with pytest.raises(outbox.OutboxError):
        outbox.check_payload("member_upsert", {"id": "m", "notes": text})
    outbox.check_payload("member_upsert", {"id": "m", "notes": "Asked about earrings. " * 40})


def test_notes_and_greeted_by_are_capped(
    setup: tuple[Hub, TestClient, dict[str, str], str],
) -> None:
    hub, client, h, owner_id = setup
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    cash = session(client, {"Authorization": h["Authorization"]}, "4821")
    long = {"staff_id": staff, "phone": "9876543210", "faces": [face()], "notes": "x" * 1001}
    assert client.post("/enrol", json=long, headers=cash).status_code == 400
    r = client.patch("/visits/v_1", json={"greeted_by": "a b " * 400}, headers=h)
    assert r.status_code == 400


class SplitCloud:
    """Rejects any batch holding the poisoned event with a 400, like the old cloud."""

    def __init__(self) -> None:
        self.received: list[str] = []
        self.inbox_polls = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/hub/inbox":
            self.inbox_polls += 1
            return httpx.Response(200, json={"messages": [], "cursor": "0"})
        events = json.loads(request.content)["events"]
        if any(e["payload"].get("visit_id") == "poison" for e in events):
            return httpx.Response(400, json={"error": "payload_forbidden"})
        self.received += [e["id"] for e in events]
        return httpx.Response(
            200, json={"results": [{"id": e["id"], "status": "acked"} for e in events]}
        )


def test_a_rejected_batch_is_split_and_the_inbox_still_polled(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    for vid in ("v1", "poison", "v2"):
        outbox.enqueue(hub.db, "alert", {"visit_id": vid, "member_id": "m", "ts": "t"})
    cloud = SplitCloud()
    client = CloudClient(
        "https://c", "tok", http=httpx.Client(transport=httpx.MockTransport(cloud.handler))
    )
    SyncWorker(hub, client).cycle()
    assert len(cloud.received) == 2 and cloud.inbox_polls == 1
    assert outbox.counts(hub.db) == {"pending": 0, "synced": 2, "failed": 1}


def test_inbox_is_polled_when_the_flush_fails(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    outbox.enqueue(hub.db, "alert", {"visit_id": "v", "member_id": "m", "ts": "t"})
    polls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/hub/inbox":
            polls["n"] += 1
            return httpx.Response(200, json={"messages": [], "cursor": "0"})
        return httpx.Response(403, json={"error": "forbidden"})

    client = CloudClient(
        "https://c", "tok", http=httpx.Client(transport=httpx.MockTransport(handler))
    )
    with pytest.raises(Exception):  # noqa: B017 - the flush error still surfaces
        SyncWorker(hub, client).cycle()
    assert polls["n"] == 1


# ---- H2: revoke is a real kill switch ----------------------------------------------------
def test_revoke_stops_matching_and_routes_across_a_restart(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    mid = hub.enrol(EnrolIn(staff_id=staff, phone="9876543210", faces=[FaceIn(**face())]))[
        "members"
    ][0]["member_id"]
    deliver_and_activate(hub, mid)
    assert mid in hub.gallery
    hub.revoke()
    assert hub.refresh_gallery() == 0 and len(hub.gallery) == 0
    client = TestClient(create_app(hub))
    assert client.get("/members").status_code == 423
    assert client.get("/health").json()["locked"] is True
    hub.db.close()
    again = Hub(hub.settings, engine_factory=type(hub.engine))
    assert again.locked and again.refresh_gallery() == 0


# ---- H3: Sentry never serialises local variables ----------------------------------------
def test_sentry_init_turns_off_local_variables(monkeypatch: pytest.MonkeyPatch) -> None:
    import sentry_sdk

    from remu_hub.observability import init_sentry

    seen: dict[str, Any] = {}
    monkeypatch.setattr(sentry_sdk, "init", lambda **kw: seen.update(kw))
    monkeypatch.setattr(sentry_sdk, "set_tag", lambda *a: None)
    assert init_sentry("https://k@o.ingest.sentry.io/1", "shop-1")
    assert seen["include_local_variables"] is False and seen["send_default_pii"] is False


# ---- H4: the service tells systemd it is ready -------------------------------------------
def test_watchdog_sends_ready(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import tempfile

    from remu_hub.systemd import start_watchdog

    path = os.path.join(tempfile.mkdtemp(prefix="rn", dir="/tmp"), "n.sock")  # AF_UNIX limit
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    sock.bind(path)
    monkeypatch.setenv("NOTIFY_SOCKET", path)
    monkeypatch.delenv("WATCHDOG_USEC", raising=False)
    start_watchdog()
    assert sock.recv(64) == b"READY=1"
    sock.close()
    runtime = (Path(__file__).resolve().parents[1] / "remu_hub" / "runtime.py").read_text()
    assert "on_startup.append(lambda: start_watchdog(alive))" in runtime


# ---- H6: no access log ---------------------------------------------------------------------
def test_uvicorn_access_log_is_off() -> None:
    runtime = (Path(__file__).resolve().parents[1] / "remu_hub" / "runtime.py").read_text()
    assert "access_log=False" in runtime


# ---- H7: clips and alerts are Owner mode only ---------------------------------------------
def test_counter_token_cannot_read_clips_or_alerts(
    setup: tuple[Hub, TestClient, dict[str, str], str],
) -> None:
    hub, client, _, _ = setup
    counter = paired(hub, client, "counter")
    assert client.get("/clips/k_x", headers=counter).status_code == 403
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"token": counter["Authorization"][7:]}))
        with pytest.raises(Exception):  # noqa: B017 - closed by the hub
            ws.receive_json()


# ---- M1: writes need a PIN session and are attributed to it --------------------------------
def test_writes_need_a_staff_session(setup: tuple[Hub, TestClient, dict[str, str], str]) -> None:
    hub, client, h, owner_id = setup
    bare = {"Authorization": h["Authorization"]}
    body = {"phone": "9876543210", "faces": []}
    r = client.post("/enrol", json=body, headers=bare)
    assert r.status_code == 401 and r.json()["error"] == "staff_session_required"
    other = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    r = client.post("/enrol", json=body | {"staff_id": other}, headers=h)
    assert r.status_code == 400  # cannot enrol "as" someone else
    assert client.post("/enrol", json=body, headers=h).status_code == 201


# ---- M2: tokens can be listed and revoked; invites do not pile up --------------------------
def test_tokens_list_and_revoke(setup: tuple[Hub, TestClient, dict[str, str], str]) -> None:
    hub, client, h, _ = setup
    counter = paired(hub, client, "counter")
    rows = client.get("/tokens", headers=h).json()
    counter_row = next(t for t in rows if t["role"] == "counter")
    assert client.post(f"/tokens/{counter_row['id']}/revoke", headers=h).status_code == 200
    assert client.get("/members", headers=counter).status_code == 401


def test_a_new_invite_cancels_the_old_one(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    old, _ = auth.create_invite(hub.db)
    new, _ = auth.create_invite(hub.db)
    assert auth.redeem_code(hub.db, old) is None and auth.redeem_code(hub.db, new) == "counter"


def test_no_debug_bypass_env() -> None:
    assert "REMU_DEV_PLAINTEXT" not in os.environ


# ---- Low findings -------------------------------------------------------------------------
def test_jpeg_header_size_is_read_before_decoding() -> None:
    import cv2
    import numpy as np

    from remu_hub.hub import jpeg_size

    ok, buf = cv2.imencode(".jpg", np.zeros((90, 160, 3), np.uint8))
    assert ok and jpeg_size(buf.tobytes()) == (160, 90)
    bomb = bytearray(buf.tobytes())
    i = bomb.index(b"\xff\xc0")
    bomb[i + 5 : i + 9] = (60000).to_bytes(2, "big") + (60000).to_bytes(2, "big")
    assert jpeg_size(bytes(bomb)) == (60000, 60000)  # refused by MAX_CROP_PIXELS in enrol
    assert jpeg_size(b"not a jpeg") is None


def test_oversized_bodies_are_refused(setup: tuple[Hub, TestClient, dict[str, str], str]) -> None:
    _, client, h, _ = setup
    r = client.post(
        "/pair",
        content=b"{" + b" " * 2_700_000 + b"}",
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413


def test_model_files_are_checked_at_load(tmp_path: Path) -> None:
    from remu_hub.faces import models

    d = tmp_path / "models" / "buffalo_sc"
    d.mkdir(parents=True)
    for name in ("det_500m.onnx", "w600k_mbf.onnx", "genderage.onnx"):
        (d / name).write_bytes(b"tampered")
    with pytest.raises(models.ModelError, match="pinned hash"):
        models.load(tmp_path / "models", "buffalo_sc")


def test_discovery_refuses_wide_or_public_ranges() -> None:
    from remu_hub.video.discover import rtsp_hosts

    for bad in ("10.0.0.0/8", "8.8.8.0/24"):
        with pytest.raises(ValueError):
            rtsp_hosts(bad, connect=lambda h, p, t: False)


def test_dvr_password_is_sealed_at_rest(
    setup: tuple[Hub, TestClient, dict[str, str], str], monkeypatch: pytest.MonkeyPatch
) -> None:
    from remu_hub.video import discover as disc

    hub, client, h, _ = setup
    monkeypatch.setattr(disc, "discover", lambda *a, **k: [])
    client.post(
        "/discover",
        json={"user": "admin", "password": "s3cret-dvr", "subnet": "10.0.0.0/30"},
        headers=h,
    )
    stored = hub.db.get_kv("dvr_password") or ""
    assert "s3cret-dvr" not in stored and hub.open_secret(stored) == "s3cret-dvr"


# ---- H5: the encrypted data directory flow --------------------------------------------------
def test_state_only_init_then_first_start_registers_the_code(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from remu_hub.cli import main
    from remu_hub.settings import load_settings
    from tests.helpers import FakeEngine

    state, data = tmp_path / "state", tmp_path / "data"
    monkeypatch.setenv("REMU_STATE_DIR", str(state))
    monkeypatch.setenv("REMU_DATA_DIR", str(data))
    monkeypatch.setenv("REMU_SHOP_ID", "shop-1")
    assert main(["init", "--state-only"]) == 0
    assert not data.exists()  # nothing touches the data directory before the volume opens
    code = (state / "pairing.txt").read_text().strip()
    assert (state / "shop.key").exists() and (state / "certs" / "hub.crt").exists()
    hub = Hub(load_settings(require_stream=False), engine_factory=FakeEngine)
    client = TestClient(create_app(hub))
    assert client.post("/pair", json={"code": code}).json()["role"] == "owner"


def test_unlock_fails_closed_without_cryptsetup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from remu_hub import unlock
    from remu_hub.cli import main

    monkeypatch.setenv("REMU_DATA_DIR", str(tmp_path))
    monkeypatch.setattr(unlock, "available", lambda: False)
    monkeypatch.delenv("REMU_DEV_PLAINTEXT", raising=False)
    assert main(["unlock"]) == 1
    monkeypatch.setenv("REMU_DEV_PLAINTEXT", "1")
    assert main(["unlock"]) == 0


def test_serve_refuses_a_plain_folder_when_encryption_is_required(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from remu_hub.runtime import serve
    from remu_hub.settings import load_settings

    monkeypatch.setenv("REMU_REQUIRE_ENCRYPTED", "1")
    settings = load_settings(
        {"REMU_DATA_DIR": str(tmp_path), "REMU_SHOP_ID": "s", "REMU_RTSP_GATE_URL": "rtsp://x"}
    )
    assert serve(settings) == 1
