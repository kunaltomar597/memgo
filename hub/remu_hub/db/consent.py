"""The consent record writer and the hash chain.

`append_consent_record()` is the only code path that writes `consent_records`
(docs/ARCHITECTURE.md section 13). SQLite triggers reject UPDATE and DELETE.
Each record's `record_hash` covers its canonical JSON without `record_hash`;
`prev_hash` is the shop's previous record hash or `sha256:genesis`.
Every record is queued to the outbox as a `consent_record` event in the same
transaction.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from remu_hub.db import now_iso
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id
from remu_hub.sync import outbox

GENESIS = "sha256:genesis"
COLUMNS = (
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
)
JSON_COLUMNS = ("purposes_json", "notice_delivery_json")
DEFAULT_PURPOSES = {"recognise_at_entry": True, "whatsapp_offers": True}
EMPTY_DELIVERY = {
    "printed_on_bill": False,
    "whatsapp_msg_id": None,
    "whatsapp_status": None,
    "delivered_at": None,
    "thumbs_up_at": None,
}


def canonical(obj: Any) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def compute_hash(record: dict[str, Any]) -> str:
    body = {k: v for k, v in record.items() if k != "record_hash"}
    return "sha256:" + hashlib.sha256(canonical(body).encode("utf-8")).hexdigest()


def row_to_record(row: Any) -> dict[str, Any]:
    rec = {k: row[k] for k in COLUMNS}
    for k in JSON_COLUMNS:
        rec[k] = json.loads(rec[k])
    return rec


def head(db: Database, shop_id: str) -> str:
    row = db.one(
        "SELECT record_hash FROM consent_records WHERE shop_id = ? " "ORDER BY rowid DESC LIMIT 1",
        (shop_id,),
    )
    return str(row["record_hash"]) if row else GENESIS


def latest_for_member(db: Database, member_id: str) -> dict[str, Any] | None:
    row = db.one(
        "SELECT * FROM consent_records WHERE member_id = ? ORDER BY rowid DESC LIMIT 1",
        (member_id,),
    )
    return row_to_record(row) if row else None


def append_consent_record(
    db: Database,
    *,
    shop_id: str,
    member_id: str,
    channel: str,
    script_version: str,
    notice_version: str,
    bill_no: str | None = None,
    cashier_id: str | None = None,
    purposes: dict[str, bool] | None = None,
    notice_delivery: dict[str, Any] | None = None,
    adult_check: str = "passed",
    withdrawn_at: str | None = None,
    template_deleted_at: str | None = None,
    ts: str | None = None,
) -> dict[str, Any]:
    with db.tx():
        record: dict[str, Any] = {
            "id": new_id("c"),
            "shop_id": shop_id,
            "member_id": member_id,
            "bill_no": bill_no,
            "ts": ts or now_iso(),
            "cashier_id": cashier_id,
            "channel": channel,
            "script_version": script_version,
            "notice_version": notice_version,
            "purposes_json": dict(purposes or DEFAULT_PURPOSES),
            "notice_delivery_json": dict(EMPTY_DELIVERY) | dict(notice_delivery or {}),
            "adult_check": adult_check,
            "withdrawn_at": withdrawn_at,
            "template_deleted_at": template_deleted_at,
            "prev_hash": head(db, shop_id),
        }
        record["record_hash"] = compute_hash(record)
        stored = {k: (canonical(v) if k in JSON_COLUMNS else v) for k, v in record.items()}
        db.execute(
            f"INSERT INTO consent_records ({', '.join(COLUMNS)}) "  # noqa: S608 - fixed columns
            f"VALUES ({', '.join('?' * len(COLUMNS))})",
            [stored[k] for k in COLUMNS],
        )
        outbox.enqueue(db, "consent_record", record)
    return record


def append_followup(
    db: Database,
    member_id: str,
    *,
    channel: str,
    ts: str | None = None,
    notice_delivery: dict[str, Any] | None = None,
    withdrawn_at: str | None = None,
    template_deleted_at: str | None = None,
) -> dict[str, Any]:
    """Append a record for an existing member, carrying the last record's fields forward."""
    with db.tx():
        last = latest_for_member(db, member_id)
        if last is None:
            raise LookupError(f"no consent record for {member_id}")
        delivery = dict(last["notice_delivery_json"]) | dict(notice_delivery or {})
        return append_consent_record(
            db,
            shop_id=last["shop_id"],
            member_id=member_id,
            channel=channel,
            script_version=last["script_version"],
            notice_version=last["notice_version"],
            bill_no=last["bill_no"],
            cashier_id=last["cashier_id"],
            purposes=last["purposes_json"],
            notice_delivery=delivery,
            adult_check=last["adult_check"],
            withdrawn_at=withdrawn_at or last["withdrawn_at"],
            template_deleted_at=template_deleted_at or last["template_deleted_at"],
            ts=ts,
        )


@dataclass(frozen=True)
class ChainReport:
    records: int
    intact: bool
    first_break: str | None
    reason: str | None
    head: str


def verify_chain(db: Database, shop_id: str | None = None) -> ChainReport:
    where = "WHERE shop_id = ? " if shop_id else ""  # fixed fragment, value bound below
    rows = db.all(
        f"SELECT * FROM consent_records {where}ORDER BY rowid",  # noqa: S608
        (shop_id,) if shop_id else (),
    )
    prev: dict[str, str] = {}
    last = GENESIS
    for i, row in enumerate(rows):
        rec = row_to_record(row)
        expected_prev = prev.get(rec["shop_id"], GENESIS)
        if rec["prev_hash"] != expected_prev:
            return ChainReport(i, False, rec["id"], "prev_hash does not link", last)
        if compute_hash(rec) != rec["record_hash"]:
            return ChainReport(i, False, rec["id"], "record_hash does not match content", last)
        prev[rec["shop_id"]] = rec["record_hash"]
        last = rec["record_hash"]
    return ChainReport(len(rows), True, None, None, last)
