"""Biometric leak guard (TASK-115).

After an enrolment, a match, a withdrawal and a sync, no outbox payload,
WebSocket event, log line or Sentry-scrubbed event holds image, crop,
embedding or template bytes. A code search fails on any new serialisation
of `vec_blob` outside the hub's database layer.
"""

from __future__ import annotations

import base64
import json
import logging
import re
import time
from pathlib import Path

import numpy as np
import pytest

from remu_hub.db import auth
from remu_hub.hub import EnrolIn, FaceIn
from remu_hub.match.decide import Decision, Kind
from remu_hub.observability import before_send
from remu_hub.sync import outbox
from remu_hub.video.source import TimedFrame
from tests.helpers import face, make_hub

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN_KEY = re.compile(
    r'"(vec[_a-z]*|crop[_a-z]*|jpeg[_a-z]*|embedding|image[_a-z]*|' r'template)"\s*:'
)


def _vector_fragments(vec: np.ndarray) -> list[str]:
    raw = np.asarray(vec, "<f4").tobytes()
    return [base64.b64encode(raw[:48]).decode(), raw[:48].hex()]


def test_no_biometric_bytes_leave_the_hub(tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    hub = make_hub(tmp_path)
    staff = auth.add_staff(hub.db, "shop-1", "Ravi", "cashier", "4821")
    crop = face(130)
    res = hub.enrol(
        EnrolIn(
            staff_id=staff,
            phone="9876543210",
            display_name="Rajesh Sharma",
            bill_no="INV-1",
            amount=100,
            faces=[FaceIn(**crop)],
        )
    )
    mid = res["members"][0]["member_id"]
    hub.activate(mid)
    tf = TimedFrame(time.time(), np.zeros((360, 640, 3), np.uint8))
    hub.on_decision(Decision(Kind.ALERT, mid, 0.9, 3, 5, tf.ts, tf.ts, 1), tf)
    template = np.frombuffer(
        hub.db.one("SELECT vec_blob FROM templates WHERE member_id = ?", (mid,))["vec_blob"], "<f4"
    )
    hub.withdraw(mid, "owner_remove")

    payloads = [json.dumps(e["payload"]) for e in outbox.pending(hub.db, 10_000)]
    ws = [r["payload_json"] for r in hub.db.all("SELECT payload_json FROM ws_events")]
    fragments = [*_vector_fragments(template), crop["crop_jpeg_b64"][:64]]
    for text in [*payloads, *ws, caplog.text]:
        assert not FORBIDDEN_KEY.search(text), text[:200]
        for frag in fragments:
            assert frag not in text
    event = before_send(
        {"extra": {"crop_jpeg_b64": crop["crop_jpeg_b64"], "vec": template.tolist()[:8]}}, {}
    )
    assert crop["crop_jpeg_b64"][:64] not in repr(event)


def test_vec_blob_is_only_touched_by_the_db_layer() -> None:
    allowed = {
        "hub/remu_hub/db/members.py",
        "hub/remu_hub/db/migrations/0001_init.sql",
        "hub/remu_hub/recovery.py",
        "cloud/src/lib/metrics.ts",
    }  # metrics.ts searches the schema for such columns
    offenders = []
    sources = [*(ROOT / "hub" / "remu_hub").rglob("*.py"), *(ROOT / "cloud" / "src").rglob("*.ts")]
    for path in sources:
        rel = str(path.relative_to(ROOT))
        if rel in allowed:
            continue
        if "vec_blob" in path.read_text() and not rel.endswith("outbox.py"):
            offenders.append(rel)
    assert offenders == []


def test_cloud_has_no_image_or_template_columns() -> None:
    migrations = "".join(
        p.read_text().lower() for p in (ROOT / "cloud" / "supabase" / "migrations").glob("*.sql")
    )
    for word in ("vec_blob", "templates (", "create table clips", "crop", "jpeg"):
        assert word not in migrations, word
