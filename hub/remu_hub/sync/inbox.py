"""Apply cloud inbox messages to the hub (TASK-067).

`notice_status` (delivered) and `thumbs_up` append chained consent records
through the writer; `member_status` (withdrawn) runs the same deletion as
"Remove member" at once; `member_status` (active) is the only path that
activates a member, and only for a delivered notice.
"""

from __future__ import annotations

import logging
from typing import Any

from remu_hub.db import members as mdb
from remu_hub.db import visits as vdb
from remu_hub.db.consent import append_followup, latest_for_member
from remu_hub.hub import Hub

log = logging.getLogger(__name__)
CURSOR_KEY = "inbox_cursor"


def apply(hub: Hub, message: dict[str, Any]) -> None:
    kind = message.get("type")
    p: dict[str, Any] = message.get("payload") or {}
    if kind == "member_status":
        mid = str(p["member_id"])
        if p.get("status") == "active":
            if p.get("reason") == "notice_delivered":
                hub.activate(mid)
            else:
                log.warning("activation without a delivered notice ignored for %s", mid)
        elif p.get("status") == "withdrawn":
            row = hub.db.one("SELECT status FROM members WHERE id = ?", (mid,))
            if row and row["status"] not in mdb.TERMINAL:
                hub.withdraw(mid, "whatsapp_stop" if p.get("reason") == "stop" else "owner_remove")
    elif kind == "notice_status":
        mid = str(p["member_id"])
        status = p.get("whatsapp_status")
        last = latest_for_member(hub.db, mid)
        if last is None or last["withdrawn_at"] or last["template_deleted_at"]:
            return
        if status in ("delivered", "failed"):
            delivery = last["notice_delivery_json"]
            if delivery.get("whatsapp_status") == status and delivery.get(
                "whatsapp_msg_id"
            ) == p.get("wamid"):
                return  # already recorded
            append_followup(
                hub.db,
                mid,
                channel="whatsapp_webhook",
                notice_delivery={
                    "whatsapp_msg_id": p.get("wamid"),
                    "whatsapp_status": status,
                    "delivered_at": p.get("delivered_at") if status == "delivered" else None,
                },
            )
            if status == "delivered" and hub.db.get_kv(f"awaiting_delivery:{mid}"):
                hub.activate(mid)  # the activation message came first
    elif kind == "thumbs_up":
        mid = str(p["member_id"])
        last = latest_for_member(hub.db, mid)
        if (
            last
            and not last["notice_delivery_json"].get("thumbs_up_at")
            and not last["withdrawn_at"]
        ):
            append_followup(
                hub.db,
                mid,
                channel="whatsapp_webhook",
                notice_delivery={"thumbs_up_at": p.get("at")},
            )
    elif kind == "visit_feedback":
        fb = vdb.set_feedback(
            hub.db,
            str(p["visit_id"]),
            greeted=p.get("greeted"),
            greeted_by=p.get("greeted_by"),
            not_them=p.get("not_them"),
            queue=False,
        )
        hub.bus.publish("visit_feedback", fb)
    elif kind == "config":
        hub.apply_config(p)
    elif kind == "device_revoked":
        hub.revoke()
        log.error("device revoked by the cloud: matching stopped")
    else:
        log.warning("unknown inbox message type %s", kind)


def poll(hub: Hub, client: Any) -> int:
    """Fetch and apply messages after the stored cursor. Returns the number applied."""
    cursor = hub.db.get_kv(CURSOR_KEY)
    body = client.inbox(cursor)
    applied = 0
    for msg in body.get("messages", []):
        try:
            apply(hub, msg)
        except LookupError:
            log.warning("inbox message for an unknown row skipped: %s", msg.get("type"))
        except mdb.InvalidTransition as exc:
            log.info("inbox transition ignored: %s", exc)
        hub.db.set_kv(CURSOR_KEY, str(msg["id"]))  # advance only after the apply
        applied += 1
    if body.get("cursor") and not body.get("messages"):
        hub.db.set_kv(CURSOR_KEY, str(body["cursor"]))
    return applied
