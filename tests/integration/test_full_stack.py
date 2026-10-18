"""Hub and cloud together over real HTTP (TASK-073, TASK-087, TASK-088, TASK-095, TASK-096).

Starts Postgres in Docker, applies the cloud migrations, runs the built
Next.js cloud, and a mock WhatsApp Graph API. A hub (fake face engine, real
database, real sync code) pairs, enrols, syncs, receives the delivered
notice, is matched, is stopped by a STOP message, and exports.

Run: cd hub && REMU_FULLSTACK=1 uv run pytest -q ../tests/integration/test_full_stack.py
Needs Docker, Node 20 and `cd cloud && npm ci && npm run build` done first.
"""

from __future__ import annotations

import base64
import csv
import hashlib
import hmac
import http.server
import io
import json
import os
import shutil
import socket
import subprocess
import threading
import time
import uuid
from collections.abc import Iterator
from pathlib import Path
from typing import Any, ClassVar

import httpx
import pytest

from remu_hub.api.tls import ensure_cert, fingerprint
from remu_hub.db import auth
from remu_hub.db.consent import verify_chain
from remu_hub.export import consent_csv
from remu_hub.hub import EnrolIn, FaceIn, Hub
from remu_hub.jobs import anchor
from remu_hub.sync.client import CloudClient
from remu_hub.sync.loop import SyncWorker, hub_serial
from tests.helpers import FakeEngine, face

ROOT = Path(__file__).resolve().parents[2]
CLOUD = ROOT / "cloud"
FIXTURES = CLOUD / "test" / "fixtures" / "webhooks"
JWT_SECRET = "test-jwt-secret-at-least-32-characters-long"
APP_SECRET = "test-app-secret"
OPS_KEY = "test-ops-key"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(os.environ.get("REMU_FULLSTACK") != "1", reason="REMU_FULLSTACK != 1"),
]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def wait_http(url: str, timeout: float = 60) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if httpx.get(url, timeout=2).status_code < 500:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    raise TimeoutError(url)


class GraphMock(http.server.BaseHTTPRequestHandler):
    sent: ClassVar[list[dict[str, Any]]] = []

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        GraphMock.sent.append(body)
        out = json.dumps(
            {
                "messaging_product": "whatsapp",
                "contacts": [{"input": body.get("to"), "wa_id": body.get("to")}],
                "messages": [{"id": f"wamid.TEST{len(GraphMock.sent)}"}],
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args: Any) -> None:
        pass


def jwt(sub: str) -> str:
    def b64(d: dict[str, Any]) -> str:
        return base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()

    head = b64({"alg": "HS256", "typ": "JWT"})
    body = b64(
        {
            "sub": sub,
            "aud": "authenticated",
            "role": "authenticated",
            "exp": int(time.time()) + 3600,
            "iat": int(time.time()),
        }
    )
    sig = hmac.new(JWT_SECRET.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


@pytest.fixture(scope="module")
def stack() -> Iterator[dict[str, Any]]:
    if shutil.which("docker") is None or not (CLOUD / ".next").exists():
        pytest.skip("needs docker and a built cloud (`cd cloud && npm run build`)")
    pg_port, cloud_port, graph_port = free_port(), free_port(), free_port()
    name = f"remu-pg-{uuid.uuid4().hex[:6]}"
    subprocess.run(
        [
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            name,
            "-e",
            "POSTGRES_PASSWORD=pg",
            "-p",
            f"127.0.0.1:{pg_port}:5432",
            "postgres:16-alpine",
        ],
        check=True,
        capture_output=True,
    )
    graph = http.server.ThreadingHTTPServer(("127.0.0.1", graph_port), GraphMock)
    threading.Thread(target=graph.serve_forever, daemon=True).start()
    cloud: subprocess.Popen[bytes] | None = None
    try:
        db_url = f"postgres://postgres:pg@127.0.0.1:{pg_port}/postgres"
        for _ in range(60):
            ready = subprocess.run(
                ["docker", "exec", name, "pg_isready", "-U", "postgres"],
                capture_output=True,
                check=False,
            )
            if ready.returncode == 0:
                break
            time.sleep(1)
        time.sleep(1)
        subprocess.run(
            ["node", "scripts/db-reset.mjs"],
            cwd=CLOUD,
            check=True,
            env=os.environ | {"DB_RESET_URL": db_url},
            capture_output=True,
        )
        env = os.environ | {
            "DATABASE_URL": db_url,
            "SUPABASE_JWT_SECRET": JWT_SECRET,
            "OPS_API_KEY": OPS_KEY,
            "HUB_PAIRING_SECRET": "test-pairing-secret",
            "CONSENT_EXPORT_SIGNING_KEY": base64.b64encode(b"\x07" * 32).decode(),
            "WHATSAPP_PHONE_NUMBER_ID": "100000000000001",
            "WHATSAPP_ACCESS_TOKEN": "tok",
            "WHATSAPP_VERIFY_TOKEN": "verify",
            "WHATSAPP_APP_SECRET": APP_SECRET,
            "WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN": "member_notice_en",
            "WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI": "member_notice_hi",
            "WHATSAPP_GRAPH_BASE_URL": f"http://127.0.0.1:{graph_port}",
            "PORT": str(cloud_port),
        }
        log = open(os.environ.get("REMU_CLOUD_LOG", os.devnull), "wb")  # noqa: SIM115
        cloud = subprocess.Popen(
            ["npx", "next", "start", "-p", str(cloud_port)],
            cwd=CLOUD,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        base = f"http://127.0.0.1:{cloud_port}"
        wait_http(base + "/api/health")
        yield {"base": base, "pg": name}
    finally:
        if cloud is not None:
            cloud.terminate()
            cloud.wait(timeout=10)
        graph.shutdown()
        subprocess.run(["docker", "stop", name], capture_output=True, check=False)


def signed_webhook(base: str, fixture: str, **vars: str) -> httpx.Response:
    raw = (FIXTURES / fixture).read_text()
    for k, v in vars.items():
        raw = raw.replace("{{" + k + "}}", v)
    body = raw.encode()
    sig = "sha256=" + hmac.new(APP_SECRET.encode(), body, hashlib.sha256).hexdigest()
    return httpx.post(
        base + "/api/webhooks/whatsapp",
        content=body,
        headers={"Content-Type": "application/json", "X-Hub-Signature-256": sig},
        timeout=20,
    )


def data_rows(text: str) -> list[list[str]]:
    lines = [ln for ln in text.splitlines() if ln and not ln.startswith("#")]
    return list(csv.reader(io.StringIO("\n".join(lines))))


def test_notice_to_active_stop_and_export(stack: dict[str, Any], tmp_path: Path) -> None:
    base = stack["base"]
    owner_user = str(uuid.uuid4())
    owner = {"Authorization": f"Bearer {jwt(owner_user)}"}
    # Supabase Auth creates this row at sign-up; the local auth shim needs it by hand.
    subprocess.run(
        [
            "docker",
            "exec",
            stack["pg"],
            "psql",
            "-U",
            "postgres",
            "-c",
            f"insert into auth.users (id, email) values ('{owner_user}', 'o@test.in')",
        ],
        check=True,
        capture_output=True,
    )

    shop = httpx.post(
        base + "/api/shops",
        json={"name": "Sharma Jewellers", "city": "Jaipur", "notice_language": "en"},
        headers=owner,
    )
    assert shop.status_code == 201, shop.text
    shop_id = shop.json()["id"]

    from remu_hub.settings import load_settings

    settings = load_settings(
        {
            "REMU_DATA_DIR": str(tmp_path / "data"),
            "REMU_SHOP_ID": shop_id,
            "REMU_RTSP_GATE_URL": "rtsp://x/gate",
            "REMU_CLOUD_URL": base,
        }
    )
    hub = Hub(settings, engine_factory=FakeEngine)
    hub.db.execute("UPDATE shops SET name = 'Sharma Jewellers' WHERE id = ?", (shop_id,))
    cert, _ = ensure_cert(settings.certs_dir)
    code = auth.create_hub_code(hub.db)
    assert code
    (settings.state_dir / "pairing.txt").write_text(code + "\n")

    reg = httpx.post(
        base + "/api/ops/devices",
        json={"pairing_code": code, "serial": hub_serial()},
        headers={"X-Remu-Ops-Key": OPS_KEY},
    )
    assert reg.status_code == 201, reg.text
    worker = SyncWorker(hub, CloudClient(base, None))
    worker.cycle()  # claim before the owner pairs: still pending
    assert worker.client.device_token is None
    paired = httpx.post(
        base + "/api/devices/pair", json={"shop_id": shop_id, "pairing_code": code}, headers=owner
    )
    assert paired.status_code in (200, 201), paired.text
    worker.cycle()
    assert worker.client.device_token, "hub did not receive its device token"
    devices = httpx.get(base + f"/api/shops/{shop_id}/devices", headers=owner).json()
    assert devices[0]["cert_sha256"] == fingerprint(cert)  # review M3: Owner mode can compare
    assert hub.health()["cloud_shop_id"] == shop_id  # review M7

    staff = auth.add_staff(hub.db, shop_id, "Ravi", "cashier", "4821")
    mid = hub.enrol(
        EnrolIn(
            staff_id=staff,
            phone="9876543210",
            display_name="Rajesh Sharma",
            bill_no="INV-1",
            amount=20000,
            items="Gold necklace",
            faces=[FaceIn(**face(120))],
        )
    )["members"][0]["member_id"]
    worker.cycle()
    assert len(GraphMock.sent) == 1, "the cloud did not send the member notice"
    msg = GraphMock.sent[0]
    assert msg["to"] in ("919876543210", "+919876543210")
    assert msg["template"]["name"] == "member_notice_en"
    assert mid not in hub.gallery  # pending until delivery

    r = signed_webhook(
        base,
        "status_delivered.json",
        wamid="wamid.TEST1",
        ts=str(int(time.time())),
        recipient="919876543210",
    )
    assert r.status_code == 200, r.text
    worker.cycle()
    assert mid in hub.gallery, "delivered notice did not activate the member on the hub"
    recs = hub.db.all("SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid", (mid,))
    assert json.loads(recs[-1]["notice_delivery_json"])["whatsapp_status"] == "delivered"

    r = signed_webhook(
        base,
        "button_thumbs_up.json",
        context_wamid="wamid.TEST1",
        msg_id="wamid.IN1",
        ts=str(int(time.time())),
        **{"from": "919876543210"},
    )
    assert r.status_code == 200
    worker.cycle()
    last = hub.db.one(
        "SELECT notice_delivery_json FROM consent_records WHERE member_id = ? "
        "ORDER BY rowid DESC",
        (mid,),
    )
    assert json.loads(last["notice_delivery_json"])["thumbs_up_at"]

    worker.cycle()  # the chained records reach the mirror
    exp = httpx.post(
        base + f"/api/shops/{shop_id}/consent-export?format=csv", headers=owner, timeout=30
    )
    assert exp.status_code == 200, exp.text
    assert exp.headers.get("x-remu-signature")
    assert data_rows(exp.text) == data_rows(consent_csv(hub.db, shop_id))  # TASK-095

    result = anchor.run(hub, worker.client)
    assert result.get("match") is True

    # STOP from the member's WhatsApp: withdrawn in the cloud, template gone on the hub.
    started = time.monotonic()
    r = signed_webhook(
        base,
        "text_message.json",
        body="STOP",
        msg_id="wamid.IN2",
        ts=str(int(time.time())),
        **{"from": "919876543210"},
    )
    assert r.status_code == 200
    worker.cycle()
    assert mid not in hub.gallery
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid,)) is None
    assert time.monotonic() - started < 3600
    deleted = hub.db.one(
        "SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid DESC", (mid,)
    )
    assert deleted["channel"] == "whatsapp_stop" and deleted["template_deleted_at"]

    # Owner removes a second member off the shop WiFi (cloud route, then hub inbox).
    mid2 = hub.enrol(
        EnrolIn(
            staff_id=staff, phone="9123456780", display_name="Meena", faces=[FaceIn(**face(150))]
        )
    )["members"][0]["member_id"]
    worker.cycle()
    w = httpx.post(base + f"/api/members/{mid2}/withdraw", headers=owner)
    assert w.status_code == 202, w.text
    worker.cycle()
    assert hub.db.one("SELECT status FROM members WHERE id = ?", (mid2,))["status"] == "withdrawn"
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid2,)) is None

    worker.cycle()
    final = httpx.post(
        base + f"/api/shops/{shop_id}/consent-export?format=csv", headers=owner, timeout=30
    )
    assert data_rows(final.text) == data_rows(consent_csv(hub.db, shop_id))
    assert verify_chain(hub.db).intact
    assert final.headers.get("x-remu-chain-verified") in ("true", "1", None)
