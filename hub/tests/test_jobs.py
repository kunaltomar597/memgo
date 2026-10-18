from __future__ import annotations

import datetime as dt
from pathlib import Path

import numpy as np

from remu_hub.db import IST
from remu_hub.db import members as mdb
from remu_hub.db.consent import append_consent_record, verify_chain
from remu_hub.db.visits import record_visit
from remu_hub.hub import Hub
from remu_hub.jobs import cleanup_clips, delete_templates, expire_members
from remu_hub.jobs.scheduler import Scheduler
from tests.helpers import make_hub

NOW = dt.datetime(2026, 10, 18, 12, 0, tzinfo=IST)


def member(
    hub: Hub,
    mid: str,
    status: str = "active",
    since: dt.datetime = NOW,
    seen: dt.datetime | None = None,
) -> str:
    ts = since.isoformat(timespec="seconds")
    hub.db.execute(
        "INSERT INTO members (id, shop_id, phone_hash, phone_enc, status, member_since,"
        " last_seen_at, created_at, updated_at) VALUES (?,'shop-1','h',x'00',?,?,?,?,?)",
        (mid, status, ts, seen.isoformat(timespec="seconds") if seen else None, ts, ts),
    )
    mdb.store_template(hub.db, mid, np.ones(512, np.float32) / np.sqrt(512), "fake")
    append_consent_record(
        hub.db,
        shop_id="shop-1",
        member_id=mid,
        channel="verbal_at_counter",
        script_version="hi-IN-v1.0",
        notice_version="wa-v1.0",
        ts=ts,
    )
    return mid


def test_sweep_deletes_leftover_templates_with_a_record(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    member(hub, "m_left", status="withdrawn")  # simulated crash: status set, template kept
    member(hub, "m_ok")
    assert delete_templates.run(hub, NOW) == 1
    assert not mdb.has_template(hub.db, "m_left") and mdb.has_template(hub.db, "m_ok")
    last = hub.db.one(
        "SELECT * FROM consent_records WHERE member_id = 'm_left' " "ORDER BY rowid DESC"
    )
    assert last["channel"] == "sweep" and last["template_deleted_at"] and last["withdrawn_at"]
    assert delete_templates.run(hub, NOW) == 0
    assert verify_chain(hub.db).intact


def test_sweep_bound_is_inside_one_hour(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    sched = Scheduler(hub, ntp=lambda: True)
    sched.tick(NOW)
    member(hub, "m_left", status="withdrawn")
    t = NOW
    while mdb.has_template(hub.db, "m_left"):
        t += dt.timedelta(seconds=30)
        sched.tick(t)
    assert t - NOW <= dt.timedelta(minutes=5, seconds=30)


def test_expiry_at_1094_and_1095_days(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    member(hub, "m_1094", seen=NOW - dt.timedelta(days=1094))
    member(hub, "m_1095", seen=NOW - dt.timedelta(days=1095))
    member(hub, "m_never", since=NOW - dt.timedelta(days=1200))
    member(hub, "m_pending", status="pending_notice", since=NOW - dt.timedelta(days=1200))
    assert sorted(expire_members.run(hub, NOW)) == ["m_1095", "m_never"]
    status = {r["id"]: r["status"] for r in hub.db.all("SELECT id, status FROM members")}
    assert status == {
        "m_1094": "active",
        "m_1095": "expired",
        "m_never": "expired",
        "m_pending": "pending_notice",
    }
    assert not mdb.has_template(hub.db, "m_1095")
    last = hub.db.one(
        "SELECT * FROM consent_records WHERE member_id = 'm_1095' " "ORDER BY rowid DESC"
    )
    assert last["channel"] == "nightly_expiry" and last["template_deleted_at"]
    assert last["withdrawn_at"] is None


def test_clip_cleanup_29_30_31_days_and_orphans(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    member(hub, "m_c")
    for days in (29, 30, 31):
        vid = record_visit(hub.db, "m_c", score=0.9, frames_agreed=3)
        cid = hub.clips.save(vid, b"mp4")
        created = NOW - dt.timedelta(days=days)
        hub.db.execute(
            "UPDATE clips SET expires_at = ? WHERE id = ?",
            ((created + dt.timedelta(days=30)).isoformat(timespec="seconds"), cid),
        )
    (hub.settings.clips_dir / "k_orphan.enc").write_bytes(b"x")
    assert cleanup_clips.run(hub, NOW) == 3  # 30, 31 and the orphan
    assert hub.db.one("SELECT COUNT(*) AS n FROM clips")["n"] == 1
    assert not (hub.settings.clips_dir / "k_orphan.enc").exists()
    assert hub.db.one("SELECT COUNT(*) AS n FROM visits WHERE clip_id IS NULL")["n"] == 2


class Anchors:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def anchor(self, date: str, chain_head_hash: str) -> dict[str, bool]:
        self.calls.append((date, chain_head_hash))
        return {"ok": True, "match": True}


def test_scheduler_simulated_day(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    client = Anchors()
    sched = Scheduler(hub, client, ntp=lambda: True)
    runs: dict[str, list[dt.datetime]] = {}
    start = dt.datetime(2026, 10, 18, 0, 0, tzinfo=IST)
    t = start
    while t < start + dt.timedelta(days=1):
        for name in sched.tick(t):
            runs.setdefault(name, []).append(t)
        t += dt.timedelta(seconds=30)
    assert len(runs["delete_templates"]) == 288 and len(runs["health"]) == 288
    assert [r.time() for r in runs["expire_members"]] == [dt.time(3, 0)]
    assert [r.time() for r in runs["cleanup_clips"]] == [dt.time(3, 0)]
    assert [r.time() for r in runs["anchor"]] == [dt.time(3, 30)]
    assert client.calls[0][0] == "2026-10-18"


def test_nightly_jobs_wait_for_ntp_and_failures_do_not_stop_others(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    synced = {"v": False}
    sched = Scheduler(hub, ntp=lambda: synced["v"])
    boom = next(j for j in sched.jobs if j.name == "cleanup_clips")
    boom.fn = lambda now: (_ for _ in ()).throw(RuntimeError("disk"))  # type: ignore[assignment]
    t = dt.datetime(2026, 10, 18, 3, 0, 30, tzinfo=IST)
    assert "expire_members" not in sched.tick(t)
    synced["v"] = True
    ran = sched.tick(t + dt.timedelta(seconds=30))
    assert "expire_members" in ran and "cleanup_clips" not in ran
    assert boom.last_error == "disk"
