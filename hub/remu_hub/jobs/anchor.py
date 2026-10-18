"""Nightly hash anchor (TASK-061): post today's chain head to the cloud."""

from __future__ import annotations

import datetime as dt
from typing import Any

from remu_hub.db import IST
from remu_hub.db.consent import head
from remu_hub.hub import Hub


def run(hub: Hub, client: Any, now: dt.datetime | None = None) -> dict[str, Any]:
    date = (now or dt.datetime.now(IST)).astimezone(IST).date().isoformat()
    chain_head = head(hub.db, hub.settings.shop_id)
    result: dict[str, Any] = client.anchor(date, chain_head)
    hub.db.set_kv("anchor_last", f"{date} {chain_head} match={result.get('match')}")
    return result
