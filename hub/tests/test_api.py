from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from remu_hub.api.app import create_app
from remu_hub.api.tls import ensure_cert
from remu_hub.db import auth
from remu_hub.db.consent import verify_chain
from remu_hub.hub import Hub
from remu_hub.match.decide import Decision, Kind
from remu_hub.video.source import TimedFrame
from tests.helpers import face, make_hub


@pytest.fixture
def hub(tmp_path: Path) -> Hub:
    return make_hub(tmp_path)


@pytest.fixture
def client(hub: Hub) -> TestClient:
    cert, _ = ensure_cert(hub.settings.certs_dir)
    return TestClient(create_app(hub, cert))


OWNER_PIN = "9999"


def with_session(client: TestClient, h: dict[str, str], pin: str) -> dict[str, str]:
    """App headers plus the staff session from a verified PIN, as the app sends them."""
    r = client.post("/staff/verify-pin", json={"pin": pin}, headers=h)
    assert r.status_code == 200, r.text
    base = {"Authorization": h["Authorization"]}
    return base | {"X-Remu-Staff-Session": r.json()["staff_session"]}


@pytest.fixture
def owner(hub: Hub, client: TestClient) -> dict[str, str]:
    code = auth.create_hub_code(hub.db)
    r = client.post("/pair", json={"code": code})
    assert r.status_code == 200
    h = {"Authorization": f"Bearer {r.json()['app_token']}"}
    created = client.post(
        "/staff", json={"name": "Owner", "role": "owner", "pin": OWNER_PIN}, headers=h
    )  # first owner: allowed without a session
    assert created.status_code == 201, created.text
    return with_session(client, h, OWNER_PIN)


def owner_staff_id(hub: Hub) -> str:
    return str(hub.db.one("SELECT id FROM staff WHERE name = 'Owner'")["id"])


@pytest.fixture
def cashier_id(hub: Hub) -> str:
    return auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")


def enrol(client: TestClient, h: dict[str, str], staff: str, **body: Any) -> Any:
    h = with_session(client, h, "4821")  # the cashier fixture's PIN: the write is theirs
    payload = {
        "staff_id": staff,
        "phone": "9876543210",
        "display_name": "Rajesh Sharma",
        "bill_no": "INV-1",
        "amount": 20000,
        "items": "Gold necklace",
        "occasion": "Engagement",
        "faces": [face()],
    } | body
    return client.post("/enrol", json=payload, headers=h)


def test_health_needs_no_token(client: TestClient) -> None:
    body = client.get("/health").json()
    assert body["ok"] is True and body["version"] == "0.1.0" and body["gallery_size"] == 0
    assert body["model_version"] == "fake:00000000"


def test_routes_need_a_token(client: TestClient) -> None:
    assert client.get("/members").status_code == 401
    r = client.get("/members", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401 and r.json()["error"] == "unauthorized"


def test_pairing_success_reuse_wrong_and_rate_limit(hub: Hub, client: TestClient) -> None:
    code = auth.create_hub_code(hub.db)
    assert auth.create_hub_code(hub.db) is None  # one hub code only
    ok = client.post("/pair", json={"code": code.lower()})
    body = ok.json()
    assert ok.status_code == 200 and body["role"] == "owner" and body["shop_id"] == "shop-1"
    assert len(body["cert_sha256"].split(":")) == 32
    assert client.post("/pair", json={"code": code}).status_code == 401  # reuse
    for _ in range(4):
        assert client.post("/pair", json={"code": "WRNG-CODE"}).status_code == 401
    assert client.post("/pair", json={"code": "WRNG-CODE"}).status_code == 429


def test_invite_gives_a_counter_token(client: TestClient, owner: dict[str, str]) -> None:
    inv = client.post("/pair/invite", headers=owner).json()
    assert len(inv["invite_code"]) == 6
    r = client.post("/pair", json={"code": inv["invite_code"]}).json()
    assert r["role"] == "counter"
    counter = {"Authorization": f"Bearer {r['app_token']}"}
    assert client.post("/pair/invite", headers=counter).status_code == 403
    assert client.get("/members", headers=counter).status_code == 200


def test_staff_and_pin(client: TestClient, owner: dict[str, str]) -> None:
    r = client.post(
        "/staff", json={"name": "Sunita", "role": "cashier", "pin": "5566"}, headers=owner
    )
    assert r.status_code == 201
    assert (
        client.post(
            "/staff", json={"name": "X", "role": "cashier", "pin": "12"}, headers=owner
        ).status_code
        == 400
    )
    ok = client.post("/staff/verify-pin", json={"pin": "5566"}, headers=owner).json()
    assert ok["name"] == "Sunita" and ok["role"] == "cashier"
    assert client.post("/staff/verify-pin", json={"pin": "0000"}, headers=owner).status_code == 401
    assert [s["name"] for s in client.get("/staff", headers=owner).json()] == ["Owner", "Sunita"]


def test_enrol_one_adult(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    r = enrol(client, owner, cashier_id)
    assert r.status_code == 201, r.text
    body = r.json()
    m = body["members"][0]
    assert body["phone_member"] is False and m["status"] == "pending_notice"
    assert body["refused"] == []
    row = hub.db.one("SELECT * FROM members WHERE id = ?", (m["member_id"],))
    assert row["discount_flag"] == 1 and row["status"] == "pending_notice"
    assert (
        hub.db.one("SELECT model_version FROM templates WHERE member_id = ?", (m["member_id"],))[
            "model_version"
        ]
        == "fake:00000000"
    )
    rec = hub.db.one("SELECT * FROM consent_records WHERE member_id = ?", (m["member_id"],))
    assert rec["channel"] == "verbal_at_counter" and rec["cashier_id"] == cashier_id
    assert rec["script_version"] == "hi-IN-v1.0" and rec["notice_version"] == "wa-v1.0"
    assert rec["bill_no"] == "INV-1" and rec["adult_check"] == "passed"
    types = [r["type"] for r in hub.db.all("SELECT type FROM events_outbox ORDER BY id")]
    assert {"consent_record", "member_upsert", "purchase", "notice_request"} <= set(types)
    raw = hub.db.one("SELECT payload_json FROM events_outbox WHERE type = 'notice_request'")
    assert "9876543210" not in raw["payload_json"]  # sealed at rest
    from remu_hub.sync import outbox

    notice = next(e["payload"] for e in outbox.pending(hub.db) if e["type"] == "notice_request")
    assert notice["phone_e164"] == "+919876543210" and notice["first_name"] == "Rajesh"
    assert notice["shop_name"] == "Sharma Jewellers" and notice["language"] == "hi"
    assert len(hub.gallery) == 0  # pending members are never matched


def test_minor_is_refused_with_no_override(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    hub.engine.models.age.years = 15.0  # type: ignore[attr-defined]
    r = enrol(client, owner, cashier_id)
    assert r.status_code == 422 and r.json()["error"] == "under_18"
    assert hub.db.one("SELECT COUNT(*) AS n FROM members")["n"] == 0
    assert hub.db.one("SELECT COUNT(*) AS n FROM templates")["n"] == 0
    assert enrol(client, owner, cashier_id, override=True).status_code == 400


def test_invalid_crop_and_missing_phone(
    client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    bad = enrol(client, owner, cashier_id, faces=[{"crop_jpeg_b64": "bm90IGEganBlZw=="}])
    assert bad.status_code == 400 and "JPEG" in bad.json()["detail"]
    payload = {"faces": [face()]}
    r = client.post("/enrol", json=payload, headers=owner)
    assert r.status_code == 400 and r.json()["error"] == "invalid"
    assert enrol(client, owner, cashier_id, phone="123").status_code == 400


def test_zero_faces_records_a_phone_member(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    body = enrol(client, owner, cashier_id, faces=[]).json()
    assert body["phone_member"] is True
    mid = body["members"][0]["member_id"]
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid,)) is None
    assert hub.db.one("SELECT 1 FROM consent_records WHERE member_id = ?", (mid,)) is None
    assert hub.db.one("SELECT COUNT(*) AS n FROM purchases WHERE member_id = ?", (mid,))["n"] == 1
    # The same phone later gets a face: same member, template and consent record added.
    up = enrol(client, owner, cashier_id).json()
    assert up["members"][0]["member_id"] == mid
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid,)) is not None
    # A phone with a template already: 409 with the member id.
    again = enrol(client, owner, cashier_id)
    assert again.status_code == 409 and again.json()["member_id"] == mid


def test_household_of_two(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    faces = [
        face(120, display_name="Rajesh Sharma"),
        face(140, display_name="Meena Sharma", phone="9876500022"),
    ]
    body = enrol(
        client, owner, cashier_id, faces=faces, household_label="Sharma family", payer_index=0
    ).json()
    assert len(body["members"]) == 2 and body["household_id"]
    h = hub.db.one("SELECT * FROM households WHERE id = ?", (body["household_id"],))
    assert h["label"] == "Sharma family"
    assert h["payer_member_id"] == body["members"][0]["member_id"]
    assert hub.db.one("SELECT COUNT(*) AS n FROM consent_records")["n"] == 2
    assert (
        hub.db.one("SELECT COUNT(*) AS n FROM events_outbox WHERE type='notice_request'")["n"] == 2
    )
    assert hub.db.one("SELECT COUNT(*) AS n FROM purchases")["n"] == 1


def test_one_refusal_in_a_household(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    calls = {"n": 0}
    stub = hub.engine.models.age  # type: ignore[attr-defined]
    original = stub.run

    def run(a: Any, b: Any) -> Any:
        calls["n"] += 1
        stub.years = 12.0 if calls["n"] == 2 else 35.0
        return original(a, b)

    stub.run = run
    body = enrol(client, owner, cashier_id, faces=[face(120), face(140, phone="9876500033")]).json()
    assert len(body["members"]) == 1 and body["refused"] == [
        {"face_index": 1, "reason": "under_18"}
    ]
    assert body["household_id"] is None


def test_member_search_patch_remove(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    mid = enrol(client, owner, cashier_id).json()["members"][0]["member_id"]
    hub.db.execute("UPDATE members SET status = 'active' WHERE id = ?", (mid,))  # fixture only
    hub.refresh_gallery()
    assert mid in hub.gallery
    assert client.get("/members?q=Rajesh", headers=owner).json()[0]["id"] == mid
    found = client.get("/members?phone=+919876543210", headers=owner).json()
    assert found[0]["phone_masked"] == "+91******3210"
    card = client.get(f"/members/{mid}", headers=owner).json()
    assert card["phone"] == "+919876543210" and card["purchases"][0]["amount"] == 20000
    assert (
        client.patch(f"/members/{mid}", json={"status": "active"}, headers=owner).status_code == 400
    )
    assert (
        client.patch(f"/members/{mid}", json={"notes": "Likes kundan"}, headers=owner).json()[
            "status"
        ]
        == "active"
    )
    gone = client.post(
        f"/members/{mid}/remove", json={"staff_id": owner_staff_id(hub)}, headers=owner
    ).json()
    assert gone["status"] == "withdrawn" and gone["template_deleted_at"]
    assert hub.db.one("SELECT 1 FROM templates WHERE member_id = ?", (mid,)) is None
    assert mid not in hub.gallery
    recs = hub.db.all("SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid", (mid,))
    assert len(recs) == 2 and recs[1]["withdrawn_at"] and recs[1]["template_deleted_at"]
    assert recs[1]["channel"] == "owner_remove" and recs[1]["prev_hash"] == recs[0]["record_hash"]
    assert recs[0]["cashier_id"] == cashier_id  # enrolled by the cashier
    assert recs[1]["cashier_id"] == owner_staff_id(hub)  # removed by the owner, not the enroller
    assert verify_chain(hub.db).intact
    assert client.get("/members/m_nope", headers=owner).status_code == 404


def test_add_purchase_and_households(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    a = enrol(client, owner, cashier_id).json()["members"][0]["member_id"]
    b = enrol(client, owner, cashier_id, phone="9876500000", faces=[face(150)]).json()["members"][
        0
    ]["member_id"]
    p = client.post(
        f"/members/{a}/purchases",
        json={"bill_no": "INV-2", "amount": 5000, "items": "Earrings", "occasion": ""},
        headers=owner,
    )
    assert p.status_code == 201 and p.json()["amount"] == 5000
    h = client.post(
        "/households",
        json={"label": "Sharma family", "member_ids": [a, b], "payer_member_id": a},
        headers=owner,
    )
    assert h.status_code == 201
    hid = h.json()["id"]
    assert (
        client.post(
            "/households", json={"label": "Other", "member_ids": [a]}, headers=owner
        ).status_code
        == 409
    )
    patched = client.patch(
        f"/households/{hid}", json={"label": "Sharma parivar", "payer_member_id": b}, headers=owner
    ).json()
    assert patched["label"] == "Sharma parivar" and patched["payer_member_id"] == b
    patched = client.patch(
        f"/households/{hid}", json={"remove_member_ids": [b]}, headers=owner
    ).json()
    assert patched["member_ids"] == [a] and patched["payer_member_id"] == a


def _active_member(hub: Hub, client: TestClient, owner: dict[str, str], staff: str) -> str:
    mid = enrol(client, owner, staff).json()["members"][0]["member_id"]
    hub.db.execute("UPDATE members SET status = 'active' WHERE id = ?", (mid,))
    hub.refresh_gallery()
    return mid


def test_decision_writes_visit_and_alert(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    import numpy as np

    mid = _active_member(hub, client, owner, cashier_id)
    frame = TimedFrame(time.time(), np.zeros((360, 640, 3), np.uint8))
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"token": owner["Authorization"][7:]}))
        assert ws.receive_json()["type"] == "hello"
        started = time.monotonic()
        hub.on_decision(Decision(Kind.ALERT, mid, 0.91, 3, 5, frame.ts, frame.ts, 1), frame)
        msg = ws.receive_json()
        while msg["type"] != "alert":
            msg = ws.receive_json()
        assert time.monotonic() - started < 1.0
    p = msg["payload"]
    assert p["member_id"] == mid and p["display_name"] == "Rajesh Sharma"
    assert p["last_purchase"] == {"amount": 20000, "items": "Gold necklace"}
    visits = client.get(f"/visits?member_id={mid}", headers=owner).json()
    assert visits[0]["id"] == p["visit_id"] and visits[0]["frames_agreed"] == 3
    fb = client.patch(
        f"/visits/{p['visit_id']}",
        json={"greeted": True, "greeted_by": owner_staff_id(hub)},
        headers=owner,
    ).json()
    assert fb["greeted"] is True
    types = {r["type"] for r in hub.db.all("SELECT type FROM events_outbox")}
    assert {"alert", "visit_summary", "visit_feedback"} <= types


def test_ws_rejects_bad_token_and_replays(
    hub: Hub, client: TestClient, owner: dict[str, str]
) -> None:
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"token": "bad"}))
        with pytest.raises(Exception):  # noqa: B017 - closed by the server
            ws.receive_json()
    first = hub.bus.publish("member_updated", {"id": "m_x"})
    hub.bus.publish("member_updated", {"id": "m_y"})
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"token": owner["Authorization"][7:], "since": first["id"]}))
        assert ws.receive_json()["type"] == "hello"
        assert ws.receive_json()["payload"] == {"id": "m_y"}


def test_gallery_loads_active_members_only(
    hub: Hub, client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    ids = [
        enrol(client, owner, cashier_id, phone=f"98765{i:05d}", faces=[face(100 + i)]).json()[
            "members"
        ][0]["member_id"]
        for i in range(4)
    ]
    for mid, status in zip(ids, ["active", "pending_notice", "withdrawn", "expired"], strict=True):
        hub.db.execute("UPDATE members SET status = ? WHERE id = ?", (status, mid))
    assert hub.refresh_gallery() == 1 and hub.gallery.ids() == [ids[0]]


def test_consent_export_csv(client: TestClient, owner: dict[str, str], cashier_id: str) -> None:
    enrol(client, owner, cashier_id)
    csv_text = client.get("/consent/export", headers=owner).text
    lines = csv_text.strip().splitlines()
    assert lines[0].startswith("id,shop_id,member_id,bill_no,ts")
    assert len(lines) == 2


def test_clips_are_lan_only(hub: Hub, owner: dict[str, str]) -> None:
    from remu_hub.api.app import is_lan

    assert is_lan("192.168.1.20") and is_lan("10.0.0.5") and is_lan("127.0.0.1")
    assert not is_lan("8.8.8.8") and not is_lan(None)


def test_enrol_without_models_is_503(tmp_path: Path) -> None:
    from remu_hub.faces.models import ModelError
    from remu_hub.settings import load_settings

    def broken(_: bytes) -> Any:
        raise ModelError("model files missing")

    settings = load_settings(
        {
            "REMU_DATA_DIR": str(tmp_path / "d"),
            "REMU_SHOP_ID": "shop-1",
            "REMU_RTSP_GATE_URL": "rtsp://x",
        }
    )
    hub = Hub(settings, engine_factory=broken)
    client = TestClient(create_app(hub))
    token = client.post("/pair", json={"code": auth.create_hub_code(hub.db)}).json()["app_token"]
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    r = enrol(client, {"Authorization": f"Bearer {token}"}, staff)
    assert r.status_code == 503 and r.json()["error"] == "unavailable"


def test_household_faces_need_their_own_phone(
    client: TestClient, owner: dict[str, str], cashier_id: str
) -> None:
    r = enrol(client, owner, cashier_id, faces=[face(120), face(140)])
    assert r.status_code == 422 and r.json() == {
        "error": "phone_required",
        "detail": "face 1 needs its own WhatsApp number",
        "face_index": 1,
    }
