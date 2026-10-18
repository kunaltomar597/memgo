"""Event bus for the LAN WebSocket.

Events are kept for 24 hours in `ws_events` so a reconnecting app passes
`since` and misses nothing. Publishing is thread-safe: the pipeline thread
publishes, asyncio subscribers receive through `loop.call_soon_threadsafe`.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import json
import threading
from typing import Any

from remu_hub.db import IST, now_iso
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id

RETENTION = dt.timedelta(hours=24)


class EventBus:
    def __init__(self, db: Database) -> None:
        self.db = db
        self._lock = threading.Lock()
        self._subs: list[tuple[asyncio.AbstractEventLoop, asyncio.Queue[dict[str, Any]]]] = []

    def publish(self, event_type: str, payload: dict[str, Any]) -> dict[str, Any]:
        event = {"type": event_type, "id": new_id("w"), "ts": now_iso(), "payload": payload}
        self.db.execute(
            "INSERT INTO ws_events (id, type, ts, payload_json) VALUES (?,?,?,?)",
            (event["id"], event_type, event["ts"], json.dumps(payload, ensure_ascii=False)),
        )
        with self._lock:
            subs = list(self._subs)
        for loop, queue in subs:
            try:
                loop.call_soon_threadsafe(queue.put_nowait, event)
            except RuntimeError:
                self.unsubscribe(queue)
        return event

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=1000)
        with self._lock:
            self._subs.append((asyncio.get_running_loop(), queue))
        return queue

    def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        with self._lock:
            self._subs = [(lp, q) for lp, q in self._subs if q is not queue]

    def since(self, event_id: str | None) -> list[dict[str, Any]]:
        cutoff = (dt.datetime.now(IST) - RETENTION).isoformat(timespec="seconds")
        if event_id:
            rows = self.db.all(
                "SELECT * FROM ws_events WHERE id > ? AND ts >= ? ORDER BY id", (event_id, cutoff)
            )
        else:
            rows = []
        return [
            {
                "type": r["type"],
                "id": r["id"],
                "ts": r["ts"],
                "payload": json.loads(r["payload_json"]),
            }
            for r in rows
        ]

    def prune(self) -> int:
        cutoff = (dt.datetime.now(IST) - RETENTION).isoformat(timespec="seconds")
        cur = self.db.execute("DELETE FROM ws_events WHERE ts < ?", (cutoff,))
        return int(cur.rowcount or 0)

    @property
    def subscribers(self) -> int:
        with self._lock:
            return len(self._subs)
