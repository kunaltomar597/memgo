from __future__ import annotations

import base64
import logging
import os
from pathlib import Path

import pytest

from remu_hub.db.database import Database
from remu_hub.keys import KeyError_, create_shop_key, read_shop_key
from remu_hub.phone import PhoneError, PhoneVault, mask, normalise, phone_hash
from remu_hub.sync import outbox


@pytest.mark.parametrize(
    ("raw", "e164"),
    [
        ("9876543210", "+919876543210"),
        ("+91 98765 43210", "+919876543210"),
        ("919876543210", "+919876543210"),
        ("09876543210", "+919876543210"),
        ("+44 7700 900123", "+447700900123"),
    ],
)
def test_normalise(raw: str, e164: str) -> None:
    assert normalise(raw) == e164


@pytest.mark.parametrize("raw", ["12345", "5876543210", "", "+91 12345 67890", "abcdefghij"])
def test_bad_numbers(raw: str) -> None:
    with pytest.raises(PhoneError):
        normalise(raw)


def test_hash_vector_matches_the_contract() -> None:
    assert phone_hash("+919876543210") == (
        "f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e"
    )


def test_round_trip_and_mask(shop_key: bytes) -> None:
    vault = PhoneVault(shop_key)
    blob = vault.encrypt("+919876543210")
    assert b"9876543210" not in blob
    assert vault.decrypt(blob) == "+919876543210"
    assert mask("+919876543210") == "+91******3210"


def test_shop_key_file_is_0600_and_checked(tmp_path: Path) -> None:
    path = tmp_path / "shop.key"
    key = create_shop_key(path)
    assert len(key) == 32 and (path.stat().st_mode & 0o777) == 0o600
    assert read_shop_key(path) == key
    assert create_shop_key(path) == key  # never regenerated
    os.chmod(path, 0o644)
    with pytest.raises(KeyError_, match="0600"):
        read_shop_key(path)


@pytest.mark.parametrize(
    ("etype", "payload"),
    [
        ("member_upsert", {"id": "m1", "vec_blob": "x"}),
        ("visit_summary", {"id": "v1", "clip_id": "k1"}),
        ("member_upsert", {"id": "m1", "phone_e164": "+919876543210"}),
        ("alert", {"visit_id": "v", "member_id": "m", "ts": "t", "crop": "x"}),
        ("nonsense", {}),
    ],
)
def test_forbidden_payloads_are_refused(tmp_path: Path, etype: str, payload: dict) -> None:
    db = Database(tmp_path / "x.sqlite")
    with pytest.raises(outbox.OutboxError):
        outbox.enqueue(db, etype, payload)


def test_base64_blobs_are_refused(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    blob = base64.b64encode(os.urandom(2000)).decode()
    with pytest.raises(outbox.OutboxError, match="base64"):
        outbox.enqueue(db, "member_upsert", {"id": "m1", "notes": blob})


def test_phone_only_in_notice_request_and_sealed_at_rest(tmp_path: Path, shop_key: bytes) -> None:
    vault = PhoneVault(shop_key)
    outbox.configure_phone_sealer(
        lambda n: base64.b64encode(vault.encrypt(n)).decode(),
        lambda b: vault.decrypt(base64.b64decode(b)),
    )
    db = Database(tmp_path / "x.sqlite")
    eid = outbox.enqueue(db, "notice_request", {"member_id": "m", "phone_e164": "+919876543210"})
    stored = db.one("SELECT payload_json FROM events_outbox WHERE id = ?", (eid,))["payload_json"]
    assert "9876543210" not in stored and "sealed:" in stored
    assert outbox.pending(db)[0]["payload"]["phone_e164"] == "+919876543210"
    outbox.mark_synced(db, [eid])
    after = db.one("SELECT payload_json FROM events_outbox WHERE id = ?", (eid,))["payload_json"]
    assert '"phone_e164": null' in after or '"phone_e164":null' in after


def test_shop_key_never_reaches_logs_or_outbox(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    from tests.helpers import face, make_hub

    caplog.set_level(logging.DEBUG)
    hub = make_hub(tmp_path)
    from remu_hub.db import auth

    sid = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    from remu_hub.hub import EnrolIn, FaceIn

    hub.enrol(
        EnrolIn(
            staff_id=sid, phone="9876543210", display_name="Rajesh Sharma", faces=[FaceIn(**face())]
        )
    )
    key = hub.shop_key
    rows = hub.db.all("SELECT payload_json FROM events_outbox")
    hexes = {key.hex(), base64.b64encode(key).decode()}
    assert not any(h in r["payload_json"] for r in rows for h in hexes)
    assert not any(h in caplog.text for h in hexes)
