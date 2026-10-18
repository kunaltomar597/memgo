"""Phase 2 integration test (TASK-037): pair, enrol from a frame, match on the fake camera.

Runs when REMU_INTEGRATION=1 and the fake camera is serving
rtsp://127.0.0.1:8554/gate (`cd hub && make fake-camera`). The test code,
not the hub, sets the member active, because the cloud is not in this phase.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import cv2
import pytest
from fastapi.testclient import TestClient

from remu_hub.api.app import create_app
from remu_hub.api.tls import ensure_cert
from remu_hub.db import auth
from remu_hub.db.consent import verify_chain
from remu_hub.hub import Hub
from remu_hub.pipeline import Pipeline
from remu_hub.runtime import HubSink
from remu_hub.settings import load_settings
from remu_hub.video.source import RtspSource, resize

ROOT = Path(__file__).resolve().parents[2]
VIDEO = ROOT / "tests" / "eval" / "samples" / "gate_fixture.mp4"
URL = os.environ.get("REMU_TEST_RTSP", "rtsp://127.0.0.1:8554/gate")

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(os.environ.get("REMU_INTEGRATION") != "1", reason="REMU_INTEGRATION != 1"),
]


def crop_at(second: float) -> tuple[str, list[list[float]]]:
    cap = cv2.VideoCapture(str(VIDEO))
    cap.set(cv2.CAP_PROP_POS_MSEC, second * 1000)
    ok, frame = cap.read()
    assert ok
    frame = resize(frame)
    h, w = frame.shape[:2]
    # Generous margin crop around the centre third, as the counter phone would send.
    crop = frame[0:h, w // 4 : 3 * w // 4]
    ok, buf = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, 90])
    assert ok
    return base64.b64encode(buf.tobytes()).decode(), [[0, 0]] * 5


def test_enrol_then_match(tmp_path: Path) -> None:
    models_dir = load_settings({}, require_stream=False).models_dir
    env = {
        "REMU_DATA_DIR": str(tmp_path / "data"),
        "REMU_SHOP_ID": "shop-int",
        "REMU_RTSP_GATE_URL": URL,
    }
    settings = load_settings(env)
    (settings.data_dir / "models").parent.mkdir(parents=True, exist_ok=True)
    (settings.data_dir / "models").symlink_to(models_dir)
    hub = Hub(settings)
    cert, _ = ensure_cert(settings.certs_dir)
    client = TestClient(create_app(hub, cert))

    code = auth.create_hub_code(hub.db)
    token = client.post("/pair", json={"code": code}).json()["app_token"]
    h = {"Authorization": f"Bearer {token}"}
    staff = auth.add_staff(hub.db, "shop-int", "Ravi", "cashier", "4821")
    crop, lm = crop_at(6.0)
    r = client.post(
        "/enrol",
        json={
            "staff_id": staff,
            "phone": "9876543210",
            "display_name": "Test Volunteer",
            "bill_no": "INV-1",
            "amount": 20000,
            "items": "Necklace",
            "faces": [{"crop_jpeg_b64": crop, "landmarks": lm}],
        },
        headers=h,
    )
    assert r.status_code == 201, r.text
    mid = r.json()["members"][0]["member_id"]
    hub.db.execute("UPDATE members SET status = 'active' WHERE id = ?", (mid,))  # test fixture
    assert hub.refresh_gallery() == 1

    source = RtspSource(URL, fps=6.0)
    pipe = Pipeline(source, hub.engine, hub.gallery, hub.decider, [HubSink(hub)], [hub.recorder])
    runner = threading.Thread(target=pipe.run, daemon=True)
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"token": token}))
        assert ws.receive_json()["type"] == "hello"
        runner.start()
        alert = None
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            msg = ws.receive_json()
            if msg["type"] == "alert":
                alert = msg["payload"]
                break
        assert alert is not None, "no alert within 40 seconds"
        assert alert["member_id"] == mid and alert["display_name"] == "Test Volunteer"
    time.sleep(6)  # let the 5-second clip finish
    pipe.stop()
    hub.recorder.flush()
    visit = hub.db.one("SELECT * FROM visits WHERE id = ?", (alert["visit_id"],))
    assert visit is not None and visit["frames_agreed"] >= 3
    assert visit["clip_id"], "clip not saved"
    clip = hub.db.one("SELECT path_enc FROM clips WHERE id = ?", (visit["clip_id"],))
    assert (settings.clips_dir / clip["path_enc"]).exists()
    assert hub.clips.read(visit["clip_id"])[4:8] == b"ftyp"
    assert verify_chain(hub.db).intact
    scan = subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "privacy_scan.py"),
            "--data-dir",
            str(settings.data_dir),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert scan.returncode == 0, scan.stdout
