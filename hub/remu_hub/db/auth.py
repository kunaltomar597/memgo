"""Staff PINs (Argon2id), app tokens and pairing codes. Hub-only secrets live here hashed."""

from __future__ import annotations

import datetime as dt
import hashlib
import secrets
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, VerifyMismatchError

from remu_hub.db import IST, now_iso
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id
from remu_hub.sync import outbox

_ph = PasswordHasher(time_cost=2, memory_cost=19_456, parallelism=1)
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I/L


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def valid_pin(pin: str) -> bool:
    return pin.isdigit() and 4 <= len(pin) <= 6


def staff_payload(db: Database, staff_id: str) -> dict[str, Any]:
    r = db.one("SELECT * FROM staff WHERE id = ?", (staff_id,))
    if r is None:
        raise LookupError(staff_id)
    return {
        "id": r["id"],
        "shop_id": r["shop_id"],
        "name": r["name"],
        "role": r["role"],
        "created_at": r["created_at"],
    }


def add_staff(db: Database, shop_id: str, name: str, role: str, pin: str) -> str:
    if role not in ("owner", "cashier"):
        raise ValueError("role must be owner or cashier")
    if not valid_pin(pin):
        raise ValueError("PIN must be 4 to 6 digits")
    for r in db.all("SELECT pin_hash FROM staff WHERE shop_id = ?", (shop_id,)):
        if _verify(r["pin_hash"], pin):
            raise ValueError("this PIN is already used by another staff member")
    sid = new_id("s")
    with db.tx():
        db.execute(
            "INSERT INTO staff (id, shop_id, name, role, pin_hash, created_at) "
            "VALUES (?,?,?,?,?,?)",
            (sid, shop_id, name.strip(), role, _ph.hash(pin), now_iso()),
        )
        outbox.enqueue(db, "staff_upsert", staff_payload(db, sid))
    return sid


def _verify(pin_hash: str, pin: str) -> bool:
    try:
        return _ph.verify(pin_hash, pin)
    except (VerifyMismatchError, VerificationError):
        return False


def verify_pin(db: Database, pin: str) -> dict[str, Any] | None:
    if not valid_pin(pin):
        return None
    for r in db.all("SELECT * FROM staff"):
        if _verify(r["pin_hash"], pin):
            return {"staff_id": r["id"], "name": r["name"], "role": r["role"]}
    return None


def list_staff(db: Database) -> list[dict[str, Any]]:
    return [
        {"id": r["id"], "name": r["name"], "role": r["role"], "created_at": r["created_at"]}
        for r in db.all("SELECT * FROM staff ORDER BY created_at, id")
    ]


def issue_token(db: Database, role: str) -> str:
    token = secrets.token_urlsafe(32)
    db.execute(
        "INSERT INTO app_tokens (token_hash, role, created_at) VALUES (?,?,?)",
        (sha256(token), role, now_iso()),
    )
    return token


def token_role(db: Database, token: str) -> str | None:
    r = db.one(
        "SELECT role FROM app_tokens WHERE token_hash = ? AND revoked_at IS NULL", (sha256(token),)
    )
    return str(r["role"]) if r else None


def make_code(groups: int = 2, size: int = 4) -> str:
    return "-".join(
        "".join(secrets.choice(CODE_ALPHABET) for _ in range(size)) for _ in range(groups)
    )


def normalise_code(code: str) -> str:
    return code.strip().upper().replace(" ", "")


def create_hub_code(db: Database) -> str | None:
    """Create the hub pairing code once. Returns it, or None when one already exists."""
    if db.one("SELECT 1 FROM pairing_codes WHERE kind = 'hub'"):
        return None
    code = make_code()
    db.execute(
        "INSERT INTO pairing_codes (code_hash, kind, role, created_at) VALUES (?,?,?,?)",
        (sha256(normalise_code(code)), "hub", "owner", now_iso()),
    )
    return code


def create_invite(db: Database, minutes: int = 10) -> tuple[str, str]:
    code = "".join(secrets.choice("0123456789") for _ in range(6))
    expires = (dt.datetime.now(IST) + dt.timedelta(minutes=minutes)).isoformat(timespec="seconds")
    db.execute(
        "INSERT INTO pairing_codes (code_hash, kind, role, created_at, expires_at) "
        "VALUES (?,?,?,?,?)",
        (sha256(code), "invite", "counter", now_iso(), expires),
    )
    return code, expires


def redeem_code(db: Database, code: str) -> str | None:
    """Mark a valid unused code used and return the role it grants, else None."""
    h = sha256(normalise_code(code))
    with db.tx():
        r = db.one("SELECT * FROM pairing_codes WHERE code_hash = ? AND used_at IS NULL", (h,))
        if r is None:
            return None
        if r["expires_at"] and dt.datetime.fromisoformat(r["expires_at"]) < dt.datetime.now(IST):
            return None
        db.execute("UPDATE pairing_codes SET used_at = ? WHERE code_hash = ?", (now_iso(), h))
        return str(r["role"])


def revoke_role(db: Database, role: str) -> int:
    cur = db.execute(
        "UPDATE app_tokens SET revoked_at = ? WHERE role = ? AND revoked_at IS NULL",
        (now_iso(), role),
    )
    return int(cur.rowcount or 0)
