"""Members, templates, households and purchases, and the member state machine.

Every status change goes through `transition()`, which writes the member row,
the chained consent record, the template deletion where the target state
needs it, and the outbox event in one SQLite transaction
(docs/ARCHITECTURE.md section 5).
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from typing import Any

import numpy as np
from numpy.typing import NDArray

from remu_hub.db import now_iso
from remu_hub.db.consent import append_followup
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id
from remu_hub.phone import PhoneVault, mask
from remu_hub.sync import outbox

PENDING, ACTIVE, WITHDRAWN, EXPIRED = "pending_notice", "active", "withdrawn", "expired"
TERMINAL = frozenset({WITHDRAWN, EXPIRED})
ALLOWED = {
    (PENDING, ACTIVE),
    (PENDING, WITHDRAWN),
    (ACTIVE, WITHDRAWN),
    (ACTIVE, EXPIRED),
}


class InvalidTransition(ValueError):
    pass


@dataclass(frozen=True)
class TransitionResult:
    member_id: str
    old: str
    new: str
    template_deleted_at: str | None
    changed: bool


def has_template(db: Database, member_id: str) -> bool:
    return db.one("SELECT 1 FROM templates WHERE member_id = ?", (member_id,)) is not None


def member_payload(db: Database, member_id: str) -> dict[str, Any]:
    r = db.one("SELECT * FROM members WHERE id = ?", (member_id,))
    if r is None:
        raise LookupError(member_id)
    return {
        "id": r["id"],
        "shop_id": r["shop_id"],
        "household_id": r["household_id"],
        "display_name": r["display_name"],
        "phone_hash": r["phone_hash"],
        "status": r["status"],
        "member_since": r["member_since"],
        "last_seen_at": r["last_seen_at"],
        "discount_flag": bool(r["discount_flag"]),
        "notes": r["notes"],
        "has_template": has_template(db, member_id),
        "created_at": r["created_at"],
        "updated_at": r["updated_at"],
    }


def queue_member(db: Database, member_id: str) -> None:
    outbox.enqueue(db, "member_upsert", member_payload(db, member_id))


def insert_member(
    db: Database,
    *,
    shop_id: str,
    display_name: str,
    e164: str,
    phone_hash: str,
    vault: PhoneVault,
    status: str = PENDING,
    household_id: str | None = None,
    ts: str | None = None,
) -> str:
    ts = ts or now_iso()
    member_id = new_id("m")
    db.execute(
        "INSERT INTO members (id, shop_id, household_id, display_name, phone_hash, phone_enc,"
        " status, member_since, discount_flag, notes, created_at, updated_at)"
        " VALUES (?,?,?,?,?,?,?,?,1,'',?,?)",
        (
            member_id,
            shop_id,
            household_id,
            display_name,
            phone_hash,
            vault.encrypt(e164),
            status,
            ts,
            ts,
            ts,
        ),
    )
    return member_id


def store_template(
    db: Database, member_id: str, vec: NDArray[np.float32], model_version: str
) -> str:
    blob = np.asarray(vec, "<f4").reshape(-1).tobytes()
    if len(blob) != 512 * 4:
        raise ValueError("template must be 512 float32")
    tid = new_id("t")
    db.execute(
        "INSERT INTO templates (id, member_id, vec_blob, model_version, created_at) "
        "VALUES (?,?,?,?,?)",
        (tid, member_id, blob, model_version, now_iso()),
    )
    return tid


def delete_template(db: Database, member_id: str) -> bool:
    """Overwrite the blob with zeros, then delete the row (secure_delete zeroes the page)."""
    row = db.one(
        "SELECT id, length(vec_blob) AS n FROM templates WHERE member_id = ?", (member_id,)
    )
    if row is None:
        return False
    db.execute("UPDATE templates SET vec_blob = zeroblob(?) WHERE id = ?", (row["n"], row["id"]))
    db.execute("DELETE FROM templates WHERE id = ?", (row["id"],))
    return True


def load_template(row: sqlite3.Row) -> NDArray[np.float32]:
    return np.frombuffer(row["vec_blob"], dtype="<f4").astype(np.float32)


def active_templates(db: Database) -> list[tuple[str, NDArray[np.float32]]]:
    rows = db.all(
        "SELECT t.member_id, t.vec_blob FROM templates t JOIN members m "
        "ON m.id = t.member_id WHERE m.status = 'active'"
    )
    return [(r["member_id"], load_template(r)) for r in rows]


def transition(
    db: Database,
    member_id: str,
    to: str,
    *,
    channel: str,
    ts: str | None = None,
    actor: str | None = None,
) -> TransitionResult:
    """Move a member to `to`. Terminal states are never left. Idempotent for a repeat."""
    ts = ts or now_iso()
    with db.tx():
        row = db.one("SELECT status FROM members WHERE id = ?", (member_id,))
        if row is None:
            raise LookupError(member_id)
        old = str(row["status"])
        if old == to:
            return TransitionResult(member_id, old, to, None, False)
        if (old, to) not in ALLOWED:
            raise InvalidTransition(f"{old} -> {to} is not allowed")
        db.execute(
            "UPDATE members SET status = ?, updated_at = ? WHERE id = ?", (to, ts, member_id)
        )
        deleted_at = None
        if to in TERMINAL:
            had = delete_template(db, member_id)
            deleted_at = ts
            if had or db.one("SELECT 1 FROM consent_records WHERE member_id = ?", (member_id,)):
                append_followup(
                    db,
                    member_id,
                    channel=channel,
                    ts=ts,
                    withdrawn_at=ts if to == WITHDRAWN else None,
                    template_deleted_at=deleted_at,
                    actor=actor,
                )
        queue_member(db, member_id)
    return TransitionResult(member_id, old, to, deleted_at, True)


def summary(db: Database, row: sqlite3.Row, vault: PhoneVault | None = None) -> dict[str, Any]:
    label = None
    if row["household_id"]:
        h = db.one("SELECT label FROM households WHERE id = ?", (row["household_id"],))
        label = h["label"] if h else None
    phone_masked = mask(vault.decrypt(row["phone_enc"])) if vault else None
    return {
        "id": row["id"],
        "display_name": row["display_name"],
        "status": row["status"],
        "member_since": row["member_since"],
        "last_seen_at": row["last_seen_at"],
        "household_label": label,
        "has_template": has_template(db, row["id"]),
        "phone_masked": phone_masked,
    }


def purchases(db: Database, member_id: str) -> list[dict[str, Any]]:
    rows = db.all(
        "SELECT * FROM purchases WHERE member_id = ? ORDER BY ts DESC, id DESC", (member_id,)
    )
    return [
        {
            "id": r["id"],
            "bill_no": r["bill_no"],
            "amount": r["amount"],
            "items": r["items_text"],
            "occasion": r["occasion"],
            "ts": r["ts"],
        }
        for r in rows
    ]


def add_purchase(
    db: Database,
    member_id: str,
    *,
    bill_no: str,
    amount: float,
    items: str,
    occasion: str,
    ts: str | None = None,
) -> dict[str, Any]:
    ts = ts or now_iso()
    pid = new_id("p")
    with db.tx():
        db.execute(
            "INSERT INTO purchases (id, member_id, bill_no, amount, items_text, occasion,"
            " ts) VALUES (?,?,?,?,?,?,?)",
            (pid, member_id, bill_no, round(float(amount), 2), items, occasion, ts),
        )
        outbox.enqueue(
            db,
            "purchase",
            {
                "id": pid,
                "member_id": member_id,
                "bill_no": bill_no,
                "amount": round(float(amount), 2),
                "items_text": items,
                "occasion": occasion,
                "ts": ts,
            },
        )
    return {
        "id": pid,
        "bill_no": bill_no,
        "amount": round(float(amount), 2),
        "items": items,
        "occasion": occasion,
        "ts": ts,
    }


def household_payload(db: Database, household_id: str) -> dict[str, Any]:
    h = db.one("SELECT * FROM households WHERE id = ?", (household_id,))
    if h is None:
        raise LookupError(household_id)
    ids = [
        r["id"]
        for r in db.all(
            "SELECT id FROM members WHERE household_id = ? ORDER BY id", (household_id,)
        )
    ]
    return {
        "id": h["id"],
        "shop_id": h["shop_id"],
        "label": h["label"],
        "payer_member_id": h["payer_member_id"],
        "member_ids": ids,
        "created_at": h["created_at"],
    }


def queue_household(db: Database, household_id: str) -> None:
    outbox.enqueue(db, "household_upsert", household_payload(db, household_id))


def create_household(
    db: Database, shop_id: str, label: str, member_ids: list[str], payer_member_id: str | None
) -> str:
    hid = new_id("h")
    with db.tx():
        for mid in member_ids:
            r = db.one("SELECT household_id FROM members WHERE id = ?", (mid,))
            if r is None:
                raise LookupError(mid)
            if r["household_id"]:
                raise InvalidTransition(f"{mid} already belongs to a household")
        if payer_member_id is not None and payer_member_id not in member_ids:
            raise ValueError("payer must be one of the members")
        db.execute(
            "INSERT INTO households (id, shop_id, label, payer_member_id, created_at) "
            "VALUES (?,?,?,?,?)",
            (hid, shop_id, label, payer_member_id, now_iso()),
        )
        for mid in member_ids:
            db.execute(
                "UPDATE members SET household_id = ?, updated_at = ? WHERE id = ?",
                (hid, now_iso(), mid),
            )
            queue_member(db, mid)
        queue_household(db, hid)
    return hid
