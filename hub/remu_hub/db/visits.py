"""Visits (one row per alert, never per frame) and clip rows."""

from __future__ import annotations

from typing import Any

from remu_hub.db import now_iso
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id
from remu_hub.sync import outbox


def visit_payload(db: Database, visit_id: str) -> dict[str, Any]:
    r = db.one("SELECT * FROM visits WHERE id = ?", (visit_id,))
    if r is None:
        raise LookupError(visit_id)
    return {
        "id": r["id"],
        "member_id": r["member_id"],
        "ts": r["ts"],
        "camera": r["camera"],
        "score": round(float(r["score"]), 4),
        "frames_agreed": r["frames_agreed"],
        "greeted": bool(r["greeted"]),
        "greeted_by": r["greeted_by"],
        "not_them": bool(r["not_them"]),
    }


def record_visit(
    db: Database,
    member_id: str,
    *,
    score: float,
    frames_agreed: int,
    ts: str | None = None,
    camera: str = "gate",
) -> str:
    ts = ts or now_iso()
    vid = new_id("v")
    with db.tx():
        db.execute(
            "INSERT INTO visits (id, member_id, ts, camera, score, frames_agreed) "
            "VALUES (?,?,?,?,?,?)",
            (vid, member_id, ts, camera, score, frames_agreed),
        )
        db.execute(
            "UPDATE members SET last_seen_at = ?, updated_at = ? WHERE id = ?", (ts, ts, member_id)
        )
        outbox.enqueue(db, "visit_summary", visit_payload(db, vid))
        outbox.enqueue(db, "alert", {"visit_id": vid, "member_id": member_id, "ts": ts})
    return vid


def touch_seen(db: Database, member_id: str, ts: str | None = None) -> None:
    ts = ts or now_iso()
    db.execute("UPDATE members SET last_seen_at = ? WHERE id = ?", (ts, member_id))


def set_feedback(
    db: Database,
    visit_id: str,
    *,
    greeted: bool | None = None,
    greeted_by: str | None = None,
    not_them: bool | None = None,
    queue: bool = True,
) -> dict[str, Any]:
    with db.tx():
        if db.one("SELECT 1 FROM visits WHERE id = ?", (visit_id,)) is None:
            raise LookupError(visit_id)
        if greeted is not None:
            db.execute("UPDATE visits SET greeted = ? WHERE id = ?", (int(greeted), visit_id))
        if greeted_by is not None:
            db.execute("UPDATE visits SET greeted_by = ? WHERE id = ?", (greeted_by, visit_id))
        if not_them is not None:
            db.execute("UPDATE visits SET not_them = ? WHERE id = ?", (int(not_them), visit_id))
        p = visit_payload(db, visit_id)
        fb = {
            "visit_id": visit_id,
            "greeted": p["greeted"],
            "greeted_by": p["greeted_by"],
            "not_them": p["not_them"],
        }
        if queue:
            outbox.enqueue(db, "visit_feedback", fb)
    return fb


def list_visits(db: Database, member_id: str) -> list[dict[str, Any]]:
    rows = db.all(
        "SELECT * FROM visits WHERE member_id = ? ORDER BY ts DESC, id DESC", (member_id,)
    )
    return [
        visit_payload(db, r["id"]) | {"has_clip": r["clip_id"] is not None, "clip_id": r["clip_id"]}
        for r in rows
    ]


def add_clip(db: Database, visit_id: str, path_enc: str, expires_at: str) -> str:
    cid = new_id("k")
    with db.tx():
        db.execute(
            "INSERT INTO clips (id, visit_id, path_enc, created_at, expires_at) "
            "VALUES (?,?,?,?,?)",
            (cid, visit_id, path_enc, now_iso(), expires_at),
        )
        db.execute("UPDATE visits SET clip_id = ? WHERE id = ?", (cid, visit_id))
    return cid
