"""Offline mode (TASK-116): two hours without internet at the hub.

Matching and LAN alerts continue, enrolments succeed and stay pending, the
outbox grows and nothing is lost; when internet returns everything syncs in
order, the notice is sent and the activation arrives. Runs on every
`make test` with a network toggle on the mock cloud.
"""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np

from remu_hub.db import auth
from remu_hub.hub import EnrolIn, FaceIn
from remu_hub.match.decide import Decision, Kind
from remu_hub.sync import outbox
from remu_hub.sync.client import CloudError
from remu_hub.sync.loop import SyncWorker
from remu_hub.video.source import TimedFrame
from tests.helpers import face, make_hub
from tests.test_sync import MockCloud, client


class FlakyCloud(MockCloud):
    def __init__(self) -> None:
        super().__init__()
        self.online = False

    def handler(self, request):  # type: ignore[no-untyped-def]
        import httpx

        if not self.online:
            raise httpx.ConnectError("network down")
        return super().handler(request)


def test_two_hours_offline_then_sync(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    cloud = FlakyCloud()
    worker = SyncWorker(hub, client(cloud))
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")

    # An active member enrolled earlier keeps matching while offline.
    old = hub.enrol(EnrolIn(staff_id=staff, phone="9111100001", faces=[FaceIn(**face(110))]))[
        "members"
    ][0]["member_id"]
    hub.activate(old)
    for _ in range(4):  # 2 hours of 30-second cycles, sampled
        try:
            worker.cycle()
        except CloudError:
            hub.internet = False
    new_ids = [
        hub.enrol(EnrolIn(staff_id=staff, phone=f"98765{i:05d}", faces=[FaceIn(**face(120 + i))]))[
            "members"
        ][0]["member_id"]
        for i in range(3)
    ]
    tf = TimedFrame(time.time(), np.zeros((360, 640, 3), np.uint8))
    hub.on_decision(Decision(Kind.ALERT, old, 0.9, 3, 5, tf.ts, tf.ts, 1), tf)
    assert hub.db.one("SELECT COUNT(*) AS n FROM visits")["n"] == 1  # LAN alert still works
    statuses = {
        r["status"]
        for r in hub.db.all("SELECT status FROM members WHERE id IN " "(?,?,?)", new_ids)
    }
    assert statuses == {"pending_notice"}
    queued = outbox.counts(hub.db)["pending"]
    assert queued > 10 and cloud.events == []

    cloud.online = True
    worker.cycle()
    assert outbox.counts(hub.db)["pending"] == 0 and len(cloud.events) == queued
    ids = [e["id"] for e in cloud.events]
    assert ids == sorted(ids)  # in order, nothing lost
    notices = [e for e in cloud.events if e["type"] == "notice_request"]
    assert {n["payload"]["member_id"] for n in notices} >= set(new_ids)

    for mid in new_ids:  # the cloud delivers the notices and activates the members
        cloud.push(
            "notice_status",
            {
                "member_id": mid,
                "consent_record_id": "c",
                "wamid": "w",
                "whatsapp_status": "delivered",
                "status_ts": "t",
                "delivered_at": "2026-10-18T12:00:00+05:30",
            },
        )
        cloud.push(
            "member_status",
            {"member_id": mid, "status": "active", "reason": "notice_delivered", "at": "t"},
        )
    worker.cycle()
    assert all(mid in hub.gallery for mid in new_ids)
