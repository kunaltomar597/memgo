"""Nightly member expiry (TASK-089): unseen for REMU_MEMBER_EXPIRY_DAYS (1095) days.

Age is measured from `last_seen_at`, or `member_since` when the member was
never seen. Only `active` members expire (the state machine allows
active to expired only). The template is deleted and a chained record written.
"""

from __future__ import annotations

import datetime as dt

from remu_hub.db import IST, now_iso
from remu_hub.db import members as mdb
from remu_hub.hub import Hub


def run(hub: Hub, now: dt.datetime | None = None) -> list[str]:
    now = now or dt.datetime.now(IST)
    cutoff = now - dt.timedelta(days=hub.settings.member_expiry_days)
    expired = []
    for r in hub.db.all(
        "SELECT id, last_seen_at, member_since FROM members " "WHERE status = 'active'"
    ):
        seen = dt.datetime.fromisoformat(r["last_seen_at"] or r["member_since"])
        if seen <= cutoff:
            mdb.transition(hub.db, r["id"], mdb.EXPIRED, channel="nightly_expiry", ts=now_iso(now))
            hub.gallery.remove(r["id"])
            hub.clips.delete_for_member(r["id"])
            expired.append(r["id"])
    return expired
