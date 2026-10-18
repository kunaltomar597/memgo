"""Clip cleanup (TASK-090): delete clips past `expires_at` (30 days) and orphan files."""

from __future__ import annotations

import datetime as dt

from remu_hub.db import IST
from remu_hub.hub import Hub


def run(hub: Hub, now: dt.datetime | None = None) -> int:
    now = now or dt.datetime.now(IST)
    removed = 0
    for r in hub.db.all("SELECT id, expires_at FROM clips"):
        if dt.datetime.fromisoformat(r["expires_at"]) <= now:
            hub.clips.delete(r["id"])
            removed += 1
    known = {r["path_enc"] for r in hub.db.all("SELECT path_enc FROM clips")}
    for f in hub.settings.clips_dir.glob("*.enc"):
        if f.name not in known:
            f.unlink(missing_ok=True)
            removed += 1
    return removed
