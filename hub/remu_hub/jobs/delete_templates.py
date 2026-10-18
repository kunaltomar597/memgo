"""Template deletion safety sweep (TASK-086).

The normal path deletes a template at once inside the withdrawal. This sweep
closes any miss (a crash between the status change and the commit, or a
restored copy): any `withdrawn` or `expired` member that still has a
template loses it, with a new chained record. It runs every 5 minutes, well
inside the 1-hour bound.
"""

from __future__ import annotations

import datetime as dt

from remu_hub.db import IST, now_iso
from remu_hub.db import members as mdb
from remu_hub.db.consent import append_followup, latest_for_member
from remu_hub.hub import Hub


def run(hub: Hub, now: dt.datetime | None = None) -> int:
    ts = now_iso(now or dt.datetime.now(IST))
    rows = hub.db.all(
        "SELECT m.id, m.status FROM members m JOIN templates t ON t.member_id = m.id"
        " WHERE m.status IN ('withdrawn', 'expired')"
    )
    for r in rows:
        with hub.db.tx():
            mdb.delete_template(hub.db, r["id"])
            last = latest_for_member(hub.db, r["id"])
            if last is not None:
                append_followup(
                    hub.db,
                    r["id"],
                    channel="sweep",
                    ts=ts,
                    withdrawn_at=ts
                    if r["status"] == "withdrawn" and not last["withdrawn_at"]
                    else None,
                    template_deleted_at=ts,
                )
            mdb.queue_member(hub.db, r["id"])
        hub.gallery.remove(r["id"])
    # Clips of withdrawn or expired members are removed too (review M6).
    for r in hub.db.all(
        "SELECT DISTINCT v.member_id FROM clips c JOIN visits v ON v.id = c.visit_id"
        " JOIN members m ON m.id = v.member_id"
        " WHERE m.status IN ('withdrawn', 'expired')"
    ):
        hub.clips.delete_for_member(r["member_id"])
    hub.db.set_kv("sweep_last_deleted", str(len(rows)))
    return len(rows)
