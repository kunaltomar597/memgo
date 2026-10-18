"""Sentry error tracking with a before-send scrubber (TASK-118).

No PII leaves the hub in an error event: names, phone numbers, notes, items,
message text, crops and vectors are dropped or masked, request bodies are
removed, and any binary or long base64 value is replaced.
"""

from __future__ import annotations

import re
from typing import Any

from remu_hub import __version__

PHONE = re.compile(r"(?<!\d)(?:\+?91[ -]?)?[6-9]\d{9}(?!\d)")
B64 = re.compile(r"[A-Za-z0-9+/]{200,}={0,2}")
SENSITIVE = (
    "name",
    "phone",
    "notes",
    "note",
    "items",
    "crop",
    "vec",
    "jpeg",
    "embedding",
    "template",
    "pin",
    "token",
    "key",
    "password",
    "message",
    "body",
    "payload",
)
MASK = "[scrubbed]"


def _scrub_str(value: str) -> str:
    return B64.sub(MASK, PHONE.sub(MASK, value))


def scrub(value: Any, key: str = "") -> Any:
    lowered = key.lower()
    if (
        lowered
        and any(s in lowered for s in SENSITIVE)
        and lowered not in ("has_template", "template_deleted_at")
    ):
        return MASK
    if isinstance(value, bytes | bytearray | memoryview):
        return MASK
    if isinstance(value, str):
        return _scrub_str(value)
    if isinstance(value, dict):
        return {k: scrub(v, str(k)) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [scrub(v, key) for v in value]
    return value


def before_send(event: dict[str, Any], hint: dict[str, Any]) -> dict[str, Any] | None:
    del hint
    event = dict(event)
    request = event.get("request")
    if isinstance(request, dict):
        request.pop("data", None)
        request.pop("cookies", None)
        request.pop("headers", None)
    event.pop("user", None)
    return scrub(event)  # type: ignore[no-any-return]


def init_sentry(dsn: str | None, shop_id: str) -> bool:
    if not dsn:
        return False
    import sentry_sdk

    sentry_sdk.init(
        dsn=dsn,
        release=f"remu-hub@{__version__}",
        before_send=before_send,  # type: ignore[arg-type]
        send_default_pii=False,
        max_request_body_size="never",
        traces_sample_rate=0.0,
    )
    sentry_sdk.set_tag("shop_id", shop_id)
    return True
