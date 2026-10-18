"""The events outbox: every outbound item, written in the same transaction as its change.

Payloads are allow-listed per event type. `templates` and `clips` have no
serialiser. The plain phone number appears only in `notice_request`. Any
forbidden key raises, so a biometric can never be queued by mistake.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from typing import Any

from remu_hub.db import now_iso
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id

ALLOWED: dict[str, frozenset[str]] = {
    "consent_record": frozenset(
        {
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
    ),
    "member_upsert": frozenset(
        {
            "id",
            "shop_id",
            "household_id",
            "display_name",
            "phone_hash",
            "status",
            "member_since",
            "last_seen_at",
            "discount_flag",
            "notes",
            "has_template",
            "created_at",
            "updated_at",
        }
    ),
    "household_upsert": frozenset(
        {"id", "shop_id", "label", "payer_member_id", "member_ids", "created_at"}
    ),
    "staff_upsert": frozenset({"id", "shop_id", "name", "role", "created_at"}),
    "purchase": frozenset({"id", "member_id", "bill_no", "amount", "items_text", "occasion", "ts"}),
    "notice_request": frozenset(
        {
            "member_id",
            "consent_record_id",
            "phone_e164",
            "first_name",
            "shop_name",
            "language",
            "date",
        }
    ),
    "visit_summary": frozenset(
        {
            "id",
            "member_id",
            "ts",
            "camera",
            "score",
            "frames_agreed",
            "greeted",
            "greeted_by",
            "not_them",
        }
    ),
    "visit_feedback": frozenset({"visit_id", "greeted", "greeted_by", "not_them"}),
    "alert": frozenset({"visit_id", "member_id", "ts"}),
    "health": frozenset(
        {
            "ok",
            "version",
            "model_version",
            "uptime_s",
            "gallery_size",
            "locked",
            "data_dir_encrypted",
            "internet",
            "outbox_pending",
            "outbox_failed",
            "clock_synced",
            "fps",
            "stream",
        }
    ),
}
FORBIDDEN_FRAGMENTS = ("vec", "template", "image", "crop", "clip", "jpeg", "embedding", "pin")
PHONE_KEYS = frozenset({"phone_e164"})
SAFE_KEYS = frozenset({"has_template", "template_deleted_at"})  # metadata, not biometrics


class OutboxError(ValueError):
    pass


# The plain phone number in `notice_request` is sealed at rest (AES-GCM under the
# shop key) and unsealed only by the sync client at send time. Hub configures this.
SEALED_PREFIX = "sealed:"
_seal: Callable[[str], str] | None = None
_unseal: Callable[[str], str] | None = None


_listeners: list[Callable[[str], None]] = []


def on_enqueue(listener: Callable[[str], None]) -> None:
    """Wake the sync loop at once after an `alert` or `notice_request` (TASK-058)."""
    _listeners.append(listener)


def configure_phone_sealer(seal: Callable[[str], str], unseal: Callable[[str], str]) -> None:
    global _seal, _unseal
    _seal, _unseal = seal, unseal


def _at_rest(event_type: str, payload: dict[str, Any]) -> dict[str, Any]:
    if event_type != "notice_request" or "phone_e164" not in payload:
        return payload
    if _seal is None:
        raise OutboxError("phone sealer not configured; refusing to store a plain number")
    return payload | {"phone_e164": SEALED_PREFIX + _seal(str(payload["phone_e164"]))}


def _for_send(payload: dict[str, Any]) -> dict[str, Any]:
    value = payload.get("phone_e164")
    if isinstance(value, str) and value.startswith(SEALED_PREFIX):
        if _unseal is None:
            raise OutboxError("phone sealer not configured")
        return payload | {"phone_e164": _unseal(value[len(SEALED_PREFIX) :])}
    return payload


_B64_CHARS = re.compile(r"^[A-Za-z0-9+/_-]+={0,2}$")


def _looks_like_binary(value: str) -> bool:
    """The exact rule of cloud/src/lib/payloadGuard.ts looksLikeBase64Blob, so the hub never
    queues an event the cloud would refuse (H1): over 1,024 characters, and after removing
    whitespace only base64 or base64url characters, of a length that is not 4n+1."""
    if len(value) <= 1024:
        return False
    compact = re.sub(r"\s+", "", value)
    if not _B64_CHARS.match(compact):
        return False
    return len(compact.rstrip("=")) % 4 != 1


def check_payload(event_type: str, payload: dict[str, Any]) -> None:
    allowed = ALLOWED.get(event_type)
    if allowed is None:
        raise OutboxError(f"unknown event type {event_type}")
    for key, value in payload.items():
        if key not in allowed:
            raise OutboxError(f"{event_type}: key {key!r} is not allowed")
        if key not in SAFE_KEYS and any(f in key.lower() for f in FORBIDDEN_FRAGMENTS):
            raise OutboxError(f"{event_type}: key {key!r} is forbidden")
        if key in PHONE_KEYS and event_type != "notice_request":
            raise OutboxError("plain phone numbers travel only in notice_request")
        if isinstance(value, bytes | bytearray | memoryview):
            raise OutboxError(f"{event_type}: binary value in {key!r}")
        if isinstance(value, str) and _looks_like_binary(value):
            raise OutboxError(f"{event_type}: base64 blob in {key!r}")


def enqueue(db: Database, event_type: str, payload: dict[str, Any]) -> str:
    check_payload(event_type, payload)
    stored = _at_rest(event_type, payload)
    event_id = new_id("e")
    db.execute(
        "INSERT INTO events_outbox (id, type, payload_json, created_at) VALUES (?,?,?,?)",
        (event_id, event_type, json.dumps(stored, ensure_ascii=False), now_iso()),
    )
    for listener in list(_listeners):
        listener(event_type)
    return event_id


def pending(db: Database, limit: int = 200) -> list[dict[str, Any]]:
    rows = db.all(
        "SELECT id, type, payload_json, created_at FROM events_outbox "
        "WHERE status = 'pending' ORDER BY id LIMIT ?",
        (limit,),
    )
    return [
        {
            "id": r["id"],
            "type": r["type"],
            "created_at": r["created_at"],
            "payload": _for_send(json.loads(r["payload_json"])),
        }
        for r in rows
    ]


def mark_synced(db: Database, ids: list[str]) -> None:
    if ids:
        ts = now_iso()
        with db.tx():
            for i in ids:
                db.execute(
                    "UPDATE events_outbox SET status='synced', synced_at=? WHERE id=?", (ts, i)
                )
            # Once the cloud has the number, the hub keeps no copy of it in the outbox.
            db.execute(
                "UPDATE events_outbox SET payload_json = json_set(payload_json, "
                "'$.phone_e164', NULL) WHERE type = 'notice_request' AND status = 'synced'"
            )


def mark_failed(db: Database, event_id: str, reason: str) -> None:
    db.execute(
        "UPDATE events_outbox SET status='failed', failure_reason=? WHERE id=?",
        (reason[:500], event_id),
    )


def counts(db: Database) -> dict[str, int]:
    rows = db.all("SELECT status, COUNT(*) AS n FROM events_outbox GROUP BY status")
    out = {"pending": 0, "synced": 0, "failed": 0}
    out.update({r["status"]: int(r["n"]) for r in rows})
    return out
