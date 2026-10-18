from __future__ import annotations

import datetime as dt
import shutil
import time
from pathlib import Path

import numpy as np
import pytest

from remu_hub.clips import ClipRecorder, encode_mp4
from remu_hub.db import IST
from remu_hub.video.source import TimedFrame
from tests.helpers import make_hub


def _visit(hub, mid: str = "m_x") -> str:  # type: ignore[no-untyped-def]
    ts = "2026-10-18T10:00:00+05:30"
    hub.db.execute(
        "INSERT INTO members (id, shop_id, phone_hash, phone_enc, status, member_since,"
        " created_at, updated_at) VALUES (?, 'shop-1', 'h', x'00', 'active', ?, ?, ?)",
        (mid, ts, ts, ts),
    )
    from remu_hub.db.visits import record_visit

    return record_visit(hub.db, mid, score=0.9, frames_agreed=3)


def test_store_round_trip_and_expiry(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    vid = _visit(hub)
    clip_id = hub.clips.save(vid, b"\x00\x00\x00\x18ftypmp42fake-mp4-bytes")
    row = hub.db.one("SELECT * FROM clips WHERE id = ?", (clip_id,))
    raw = (hub.settings.clips_dir / row["path_enc"]).read_bytes()
    assert b"ftyp" not in raw  # encrypted at rest
    assert hub.clips.read(clip_id).startswith(b"\x00\x00\x00\x18ftyp")
    expires = dt.datetime.fromisoformat(row["expires_at"])
    assert abs((expires - dt.datetime.now(IST)) - dt.timedelta(days=30)) < dt.timedelta(minutes=1)
    assert hub.db.one("SELECT clip_id FROM visits WHERE id = ?", (vid,))["clip_id"] == clip_id


def test_recorder_takes_five_seconds_from_the_first_agreeing_frame(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    vid = _visit(hub)
    got: list[tuple[int, float]] = []

    def encoder(frames: list[np.ndarray], fps: float) -> bytes:
        got.append((len(frames), fps))
        return b"mp4"

    rec = ClipRecorder(hub.clips, encoder=encoder)
    frames = [TimedFrame(100.0 + i / 6, np.full((360, 640, 3), i, np.uint8)) for i in range(60)]
    for tf in frames[:5]:
        rec(tf)
    rec.start(vid, first_ts=frames[2].ts)  # decided at frame 4, agreement began at frame 2
    for tf in frames[5:]:
        rec(tf)
    rec.flush()
    assert got and got[0][0] == 31  # frames 2..32 inclusive: 5 seconds at 6 fps
    assert hub.db.one("SELECT clip_id FROM visits WHERE id = ?", (vid,))["clip_id"]


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg missing")
def test_real_h264_encode() -> None:
    frames = [np.full((360, 640, 3), i * 8 % 255, np.uint8) for i in range(12)]
    mp4 = encode_mp4(frames, 6.0)
    assert mp4[4:8] == b"ftyp" and len(mp4) > 500


def test_withdrawal_deletes_clips(tmp_path: Path) -> None:
    hub = make_hub(tmp_path)
    vid = _visit(hub, "m_y")
    clip_id = hub.clips.save(vid, b"mp4")
    path = hub.settings.clips_dir / f"{clip_id}.enc"
    assert path.exists()
    assert hub.clips.delete_for_member("m_y") == 1
    assert not path.exists()
    assert hub.db.one("SELECT clip_id FROM visits WHERE id = ?", (vid,))["clip_id"] is None


def test_gallery_refresh_within_5_seconds(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from remu_hub import runtime
    from remu_hub.db import members as mdb

    hub = make_hub(tmp_path)
    mid = "m_z"
    _visit(hub, mid)
    mdb.store_template(hub.db, mid, np.ones(512, np.float32) / np.sqrt(512), "fake")
    hub.db.execute("UPDATE members SET status = 'pending_notice' WHERE id = ?", (mid,))
    hub.refresh_gallery()
    assert mid not in hub.gallery
    monkeypatch.setattr(runtime, "GALLERY_REFRESH_S", 0.2)
    runtime.start_gallery_refresh(hub)
    hub.db.execute("UPDATE members SET status = 'active' WHERE id = ?", (mid,))
    deadline = time.monotonic() + 5
    while mid not in hub.gallery and time.monotonic() < deadline:
        time.sleep(0.05)
    assert mid in hub.gallery


def test_no_clip_is_saved_after_a_withdrawal_and_the_sweep_removes_leftovers(
    tmp_path: Path,
) -> None:
    from remu_hub.jobs import delete_templates

    hub = make_hub(tmp_path)
    vid = _visit(hub, "m_w")
    kept = hub.clips.save(vid, b"mp4")  # saved while active
    hub.db.execute("UPDATE members SET status = 'withdrawn' WHERE id = 'm_w'")
    with pytest.raises(LookupError):
        hub.clips.save(vid, b"mp4")  # finished after the withdrawal: refused
    delete_templates.run(hub)
    assert hub.db.one("SELECT 1 FROM clips WHERE id = ?", (kept,)) is None
