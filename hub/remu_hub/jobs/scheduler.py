"""The job schedule (TASK-091).

- template deletion sweep every 5 minutes,
- health report every 5 minutes,
- member expiry and clip cleanup nightly at 03:00 Asia/Kolkata,
- hash anchor at 03:30 Asia/Kolkata,
- WebSocket event pruning nightly.

Nightly jobs run only when the clock is NTP-synchronised. A failing job is
logged and retried on the next tick without stopping the others. The outbox
flush runs continuously in the sync loop.
"""

from __future__ import annotations

import datetime as dt
import logging
import shutil
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from remu_hub.db import IST
from remu_hub.hub import Hub
from remu_hub.jobs import anchor, cleanup_clips, delete_templates, expire_members
from remu_hub.recovery import daily_copy
from remu_hub.sync import outbox

log = logging.getLogger(__name__)
TICK_S = 30.0


def ntp_synchronised() -> bool:
    if shutil.which("timedatectl") is None:
        return True  # development machines (macOS) sync on their own
    out = subprocess.run(
        ["timedatectl", "show", "-p", "NTPSynchronized", "--value"],
        capture_output=True,
        text=True,
        check=False,
    )
    return out.stdout.strip() == "yes"


@dataclass
class Job:
    name: str
    fn: Callable[[dt.datetime], Any]
    every: dt.timedelta | None = None  # interval jobs
    at: dt.time | None = None  # nightly jobs, IST wall clock
    needs_ntp: bool = False
    last_run: dt.datetime | None = None
    last_error: str | None = None

    def due(self, now: dt.datetime) -> bool:
        if self.every is not None:
            return self.last_run is None or now - self.last_run >= self.every
        assert self.at is not None
        local = now.astimezone(IST)
        slot = dt.datetime.combine(local.date(), self.at, IST)
        return local >= slot and (self.last_run is None or self.last_run < slot)


class Scheduler:
    def __init__(
        self, hub: Hub, client: Any | None = None, ntp: Callable[[], bool] = ntp_synchronised
    ) -> None:
        self.hub = hub
        self.ntp = ntp
        five = dt.timedelta(minutes=5)
        self.jobs = [
            Job("delete_templates", lambda now: delete_templates.run(hub, now), every=five),
            Job("health", lambda now: outbox.enqueue(hub.db, "health", hub.health()), every=five),
            Job(
                "expire_members",
                lambda now: expire_members.run(hub, now),
                at=dt.time(3, 0),
                needs_ntp=True,
            ),
            Job(
                "cleanup_clips",
                lambda now: cleanup_clips.run(hub, now),
                at=dt.time(3, 0),
                needs_ntp=True,
            ),
            Job("prune_ws", lambda now: hub.bus.prune(), at=dt.time(3, 0)),
            Job("daily_copy", lambda now: daily_copy(hub.settings.db_path), at=dt.time(3, 15)),
        ]
        if client is not None:
            self.jobs.append(
                Job(
                    "anchor",
                    lambda now: anchor.run(hub, client, now),
                    at=dt.time(3, 30),
                    needs_ntp=True,
                )
            )

    def tick(self, now: dt.datetime | None = None) -> list[str]:
        now = now or dt.datetime.now(IST)
        ran = []
        synced: bool | None = None
        for job in self.jobs:
            if not job.due(now):
                continue
            if job.needs_ntp:
                synced = self.ntp() if synced is None else synced
                if not synced:
                    log.warning("clock not NTP-synchronised, %s postponed", job.name)
                    continue
            try:
                job.fn(now)
                job.last_error = None
            except Exception as exc:
                job.last_error = str(exc)
                log.exception("job %s failed, retrying next tick", job.name)
                continue
            job.last_run = now
            ran.append(job.name)
        return ran

    def run_forever(self) -> None:
        while True:
            self.tick()
            time.sleep(TICK_S)
