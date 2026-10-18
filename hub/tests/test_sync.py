from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import httpx
import pytest

from remu_hub.db import auth
from remu_hub.db.consent import verify_chain
from remu_hub.hub import EnrolIn, FaceIn, Hub
from remu_hub.sync import inbox, outbox
from remu_hub.sync.client import CloudClient, CloudError
from remu_hub.sync.loop import SyncWorker
from tests.helpers import face, make_hub


class MockCloud:
    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []
        self.inbox: list[dict[str, Any]] = []
        self.fail_next = 0
        self.reject_types: set[str] = set()
        self.claim_ready = False
        self.anchors: list[dict[str, Any]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/devices/claim":
            if not self.claim_ready:
                return httpx.Response(202, json={"status": "pending"})
            return httpx.Response(
                200,
                json={
                    "status": "paired",
                    "device_token": "dev-tok",
                    "device_id": "d_1",
                    "shop_id": "shop-1",
                },
            )
        if request.headers.get("authorization") != "Bearer dev-tok":
            return httpx.Response(401, json={"error": "unauthorized"})
        if path == "/api/hub/events":
            if self.fail_next:
                self.fail_next -= 1
                return httpx.Response(503)
            body = json.loads(request.content)
            out = []
            for e in body["events"]:
                if e["type"] in self.reject_types:
                    out.append({"id": e["id"], "status": "rejected", "reason": "schema"})
                else:
                    self.events.append(e)
                    out.append({"id": e["id"], "status": "acked"})
            return httpx.Response(200, json={"results": out})
        if path == "/api/hub/inbox":
            after = int(request.url.params.get("after") or 0)
            msgs = [m for m in self.inbox if int(m["id"]) > after]
            return httpx.Response(
                200, json={"messages": msgs, "cursor": msgs[-1]["id"] if msgs else str(after)}
            )
        if path == "/api/hub/anchor":
            self.anchors.append(json.loads(request.content))
            return httpx.Response(200, json={"ok": True, "match": True})
        return httpx.Response(404)

    def push(self, kind: str, payload: dict[str, Any]) -> None:
        self.inbox.append({"id": str(len(self.inbox) + 1), "type": kind, "payload": payload})


@pytest.fixture
def cloud() -> MockCloud:
    return MockCloud()


@pytest.fixture
def hub(tmp_path: Path) -> Hub:
    return make_hub(tmp_path)


def client(cloud: MockCloud, token: str | None = "dev-tok") -> CloudClient:
    return CloudClient(
        "https://cloud.test", token, http=httpx.Client(transport=httpx.MockTransport(cloud.handler))
    )


def enrol(hub: Hub, phone: str = "9876543210", value: int = 120) -> str:
    sid = hub.db.one("SELECT id FROM staff LIMIT 1")
    staff = sid["id"] if sid else auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    res = hub.enrol(
        EnrolIn(
            staff_id=staff,
            phone=phone,
            display_name="Rajesh Sharma",
            bill_no="INV-1",
            amount=100,
            faces=[FaceIn(**face(value))],
        )
    )
    return str(res["members"][0]["member_id"])


def test_flush_sends_in_order_and_marks_synced(hub: Hub, cloud: MockCloud) -> None:
    enrol(hub)
    w = SyncWorker(hub, client(cloud))
    sent = w.flush_once()
    assert sent == len(cloud.events) > 0
    ids = [e["id"] for e in cloud.events]
    assert ids == sorted(ids)
    assert outbox.counts(hub.db)["pending"] == 0
    notice = next(e for e in cloud.events if e["type"] == "notice_request")
    assert notice["payload"]["phone_e164"] == "+919876543210"  # unsealed for the send only
    for e in cloud.events:
        assert not re.search(r"vec|crop|jpeg|embedding", json.dumps(e["payload"]))


def test_batches_of_200(hub: Hub, cloud: MockCloud) -> None:
    for i in range(450):
        outbox.enqueue(hub.db, "alert", {"visit_id": f"v{i}", "member_id": "m", "ts": "t"})
    SyncWorker(hub, client(cloud)).flush_once()
    assert len(cloud.events) == 450 and outbox.counts(hub.db)["pending"] == 0


def test_5xx_retries_the_batch_and_4xx_skips_one_event(hub: Hub, cloud: MockCloud) -> None:
    outbox.enqueue(hub.db, "alert", {"visit_id": "v1", "member_id": "m", "ts": "t"})
    outbox.enqueue(hub.db, "health", {"ok": True})
    outbox.enqueue(hub.db, "alert", {"visit_id": "v2", "member_id": "m", "ts": "t"})
    w = SyncWorker(hub, client(cloud))
    cloud.fail_next = 1
    with pytest.raises(CloudError):
        w.flush_once()
    assert outbox.counts(hub.db)["pending"] == 3
    cloud.reject_types = {"health"}
    w.flush_once()
    c = outbox.counts(hub.db)
    assert c == {"pending": 0, "synced": 2, "failed": 1}
    assert hub.health()["outbox_failed"] == 1


def test_claim_poll_before_and_after_pairing(hub: Hub, cloud: MockCloud) -> None:
    (hub.settings.data_dir / "pairing.txt").write_text("K7QM-2XPA\n")
    w = SyncWorker(hub, client(cloud, token=None))
    w.cycle()
    assert w.client.device_token is None
    cloud.claim_ready = True
    w.cycle()
    assert w.client.device_token == "dev-tok"
    token_file = hub.settings.data_dir / "device.token"
    assert token_file.read_text() == "dev-tok" and (token_file.stat().st_mode & 0o777) == 0o600


def test_alert_wakes_the_sync_loop(hub: Hub, cloud: MockCloud) -> None:
    w = SyncWorker(hub, client(cloud))
    outbox.enqueue(hub.db, "alert", {"visit_id": "v", "member_id": "m", "ts": "t"})
    assert w.wake.is_set()


def test_delivered_notice_activates_and_chains(hub: Hub, cloud: MockCloud) -> None:
    mid = enrol(hub)
    assert len(hub.gallery) == 0
    cloud.push(
        "notice_status",
        {
            "member_id": mid,
            "consent_record_id": "c",
            "wamid": "wamid.X",
            "whatsapp_status": "delivered",
            "status_ts": "t",
            "delivered_at": "2026-10-18T17:42:40+05:30",
        },
    )
    cloud.push(
        "member_status",
        {"member_id": mid, "status": "active", "reason": "notice_delivered", "at": "t"},
    )
    assert inbox.poll(hub, client(cloud)) == 2
    assert mid in hub.gallery
    recs = hub.db.all("SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid", (mid,))
    assert len(recs) == 2 and recs[1]["channel"] == "whatsapp_webhook"
    d = json.loads(recs[1]["notice_delivery_json"])
    assert d["whatsapp_status"] == "delivered" and d["whatsapp_msg_id"] == "wamid.X"
    assert hub.db.get_kv(inbox.CURSOR_KEY) == "2"
    assert inbox.poll(hub, client(cloud)) == 0  # cursor advanced: nothing replayed


def test_activation_needs_a_delivered_notice(hub: Hub, cloud: MockCloud) -> None:
    mid = enrol(hub)
    cloud.push(
        "member_status", {"member_id": mid, "status": "active", "reason": "other", "at": "t"}
    )
    inbox.poll(hub, client(cloud))
    assert mid not in hub.gallery
    assert (
        hub.db.one("SELECT status FROM members WHERE id = ?", (mid,))["status"] == "pending_notice"
    )


def test_stop_withdraws_at_once(hub: Hub, cloud: MockCloud) -> None:
    mid = enrol(hub)
    hub.activate(mid)
    assert mid in hub.gallery
    cloud.push(
        "member_status", {"member_id": mid, "status": "withdrawn", "reason": "stop", "at": "t"}
    )
    inbox.poll(hub, client(cloud))
    assert mid not in hub.gallery
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid,)) is None
    last = hub.db.one(
        "SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid DESC", (mid,)
    )
    assert last["channel"] == "whatsapp_stop" and last["template_deleted_at"]
    assert verify_chain(hub.db).intact


def test_thumbs_up_feedback_config_and_revoke(hub: Hub, cloud: MockCloud) -> None:
    mid = enrol(hub)
    from remu_hub.db.visits import record_visit

    vid = record_visit(hub.db, mid, score=0.9, frames_agreed=3)
    cloud.push("thumbs_up", {"member_id": mid, "at": "2026-10-18T18:00:00+05:30"})
    cloud.push(
        "visit_feedback", {"visit_id": vid, "greeted": True, "greeted_by": "s_1", "not_them": False}
    )
    cloud.push(
        "config",
        {
            "shop_name": "Sharma Jewellers",
            "notice_language": "en",
            "script_version": "hi-IN-v1.0",
            "notice_version": "wa-v1.0",
        },
    )
    inbox.poll(hub, client(cloud))
    last = hub.db.one(
        "SELECT notice_delivery_json FROM consent_records WHERE member_id = ? "
        "ORDER BY rowid DESC",
        (mid,),
    )
    assert json.loads(last["notice_delivery_json"])["thumbs_up_at"] == "2026-10-18T18:00:00+05:30"
    assert hub.db.one("SELECT greeted FROM visits WHERE id = ?", (vid,))["greeted"] == 1
    assert hub.shop()["settings"]["notice_language"] == "en"
    cloud.push("device_revoked", {})
    inbox.poll(hub, client(cloud))
    assert hub.locked and hub.health()["ok"] is False


def test_sync_code_opens_no_listener() -> None:
    root = Path(__file__).resolve().parents[1] / "remu_hub" / "sync"
    text = "".join(p.read_text() for p in root.rglob("*.py"))
    assert not re.search(r"\.bind\(|\.listen\(|uvicorn|serve_forever", text)
