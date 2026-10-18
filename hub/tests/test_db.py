from __future__ import annotations

import itertools
import sqlite3
from pathlib import Path

import pytest

from remu_hub.db import connect, migrate
from remu_hub.db.consent import GENESIS, append_consent_record, compute_hash, verify_chain
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id, ulid

TABLES = {
    "shops",
    "staff",
    "households",
    "members",
    "templates",
    "consent_records",
    "purchases",
    "visits",
    "clips",
    "events_outbox",
}


def test_migrate_twice_is_idempotent(tmp_path: Path) -> None:
    conn = connect(tmp_path / "x.sqlite")
    assert migrate(conn) == [1]
    assert migrate(conn) == []
    names = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert names >= TABLES


def test_one_row_per_table(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    ts = "2026-10-18T17:42:11+05:30"
    rows = {
        "shops": "INSERT INTO shops (id, shop_key_ref) VALUES ('s1', '/k')",
        "staff": f"INSERT INTO staff VALUES ('st1','s1','A','owner','h','{ts}')",
        "households": f"INSERT INTO households VALUES ('h1','s1','F',NULL,'{ts}')",
        "members": "INSERT INTO members (id, shop_id, phone_hash, phone_enc, status, member_since,"
        f" created_at, updated_at) VALUES ('m1','s1','ph',x'00','active','{ts}','{ts}',"
        f"'{ts}')",
        "templates": f"INSERT INTO templates VALUES ('t1','m1',x'00','fake','{ts}')",
        "purchases": f"INSERT INTO purchases VALUES ('p1','m1','B1',10.5,'ring','','{ts}')",
        "visits": "INSERT INTO visits (id, member_id, ts, score, frames_agreed) "
        f"VALUES ('v1','m1','{ts}',0.9,3)",
        "clips": f"INSERT INTO clips VALUES ('k1','v1','k1.enc','{ts}','{ts}')",
        "events_outbox": f"INSERT INTO events_outbox (id,type,payload_json,created_at) "
        f"VALUES ('e1','alert','{{}}','{ts}')",
    }
    for sql in rows.values():
        db.execute(sql)
    append_consent_record(
        db,
        shop_id="s1",
        member_id="m1",
        channel="verbal_at_counter",
        script_version="hi-IN-v1.0",
        notice_version="wa-v1.0",
    )
    for t in TABLES:
        assert db.one(f"SELECT COUNT(*) AS n FROM {t}")["n"] >= 1


def test_status_is_constrained(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    db.execute("INSERT INTO shops (id, shop_key_ref) VALUES ('s1', '/k')")
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO members (id, shop_id, phone_hash, phone_enc, status, member_since,"
            " created_at, updated_at) VALUES ('m1','s1','p',x'00','vip','t','t','t')"
        )


def _chain(db: Database, n: int) -> list[dict]:
    db.execute("INSERT OR IGNORE INTO shops (id, shop_key_ref) VALUES ('s1', '/k')")
    return [
        append_consent_record(
            db,
            shop_id="s1",
            member_id=f"m{i}",
            channel="verbal_at_counter",
            script_version="hi-IN-v1.0",
            notice_version="wa-v1.0",
            bill_no=f"INV-{i}",
            cashier_id="s_1",
        )
        for i in range(n)
    ]


def test_chain_links_and_verifies_after_10(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    recs = _chain(db, 10)
    assert recs[0]["prev_hash"] == GENESIS
    assert all(b["prev_hash"] == a["record_hash"] for a, b in itertools.pairwise(recs))
    assert all(compute_hash(r) == r["record_hash"] for r in recs)
    report = verify_chain(db)
    assert report.intact and report.records == 10 and report.head == recs[-1]["record_hash"]


def test_records_cannot_be_updated_or_deleted(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    rec = _chain(db, 1)[0]
    with pytest.raises(sqlite3.IntegrityError, match="append-only"):
        db.execute("UPDATE consent_records SET bill_no = 'X' WHERE id = ?", (rec["id"],))
    with pytest.raises(sqlite3.IntegrityError, match="append-only"):
        db.execute("DELETE FROM consent_records WHERE id = ?", (rec["id"],))


def test_tampering_is_detected(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    recs = _chain(db, 5)
    db.execute("DROP TRIGGER consent_records_no_update")
    db.execute("UPDATE consent_records SET bill_no = 'FORGED' WHERE id = ?", (recs[2]["id"],))
    report = verify_chain(db)
    assert not report.intact and report.first_break == recs[2]["id"]


def test_consent_record_matches_the_documented_shape(tmp_path: Path) -> None:
    db = Database(tmp_path / "x.sqlite")
    rec = _chain(db, 1)[0]
    assert set(rec) == {
        "id",
        "shop_id",
        "member_id",
        "bill_no",
        "ts",
        "cashier_id",
        "channel",
        "script_version",
        "notice_version",
        "purposes_json",
        "notice_delivery_json",
        "adult_check",
        "withdrawn_at",
        "template_deleted_at",
        "prev_hash",
        "record_hash",
    }
    assert rec["purposes_json"] == {"recognise_at_entry": True, "whatsapp_offers": True}
    assert rec["notice_delivery_json"] == {
        "printed_on_bill": False,
        "whatsapp_msg_id": None,
        "whatsapp_status": None,
        "delivered_at": None,
        "thumbs_up_at": None,
    }
    assert rec["id"].startswith("c_") and rec["record_hash"].startswith("sha256:")


def test_consent_hash_cross_language_vector() -> None:
    """The cloud recomputes this hash in TypeScript; this fixed vector pins the format."""
    record = {
        "id": "c_01",
        "shop_id": "sharma-jewellers-jaipur",
        "member_id": "m_2041",
        "bill_no": "INV-2026-10-18-0417",
        "ts": "2026-10-18T17:42:11+05:30",
        "cashier_id": "staff_03",
        "channel": "verbal_at_counter",
        "script_version": "hi-IN-v1.0",
        "notice_version": "wa-v1.0",
        "purposes_json": {"recognise_at_entry": True, "whatsapp_offers": True},
        "notice_delivery_json": {
            "printed_on_bill": False,
            "whatsapp_msg_id": None,
            "whatsapp_status": None,
            "delivered_at": None,
            "thumbs_up_at": None,
        },
        "adult_check": "passed",
        "withdrawn_at": None,
        "template_deleted_at": None,
        "prev_hash": "sha256:genesis",
    }
    assert compute_hash(record) == (
        "sha256:"
        + __import__("hashlib")
        .sha256(
            __import__("json")
            .dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            .encode()
        )
        .hexdigest()
    )


def test_ulids_are_monotonic() -> None:
    ids = [ulid() for _ in range(2000)]
    assert ids == sorted(ids) and len(set(ids)) == 2000
    assert new_id("m").startswith("m_") and len(new_id("m")) == 28
