"""Encrypted 5-second match clips.

The recorder keeps the frames of the current agreement window (about one
second at 6 frames per second) so a clip can start at the first agreeing
frame; it keeps no longer buffer and opens no second decoder. A clip is
encoded to H.264 MP4 by ffmpeg through pipes, sealed with AES-GCM under a
key derived from the shop key, and written as `<clip id>.enc`. Plain video
never touches the disk. Clips expire after REMU_CLIP_RETENTION_DAYS (30).
"""

from __future__ import annotations

import datetime as dt
import logging
import shutil
import subprocess
import threading
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from remu_hub.db import IST
from remu_hub.db.database import Database
from remu_hub.db.ids import new_id
from remu_hub.keys import INFO_CLIPS, Sealer
from remu_hub.video.source import OUT_H, OUT_W, Frame, TimedFrame

log = logging.getLogger(__name__)
CLIP_SECONDS = 5.0
RING_SECONDS = 1.5


@dataclass
class _Job:
    visit_id: str
    start_ts: float
    frames: list[Frame] = field(default_factory=list)


class ClipStore:
    def __init__(
        self, db: Database, clips_dir: Path, shop_key: bytes, retention_days: int = 30
    ) -> None:
        self.db = db
        self.dir = clips_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        self.sealer = Sealer(shop_key, INFO_CLIPS)
        self.retention_days = retention_days

    def save(self, visit_id: str, mp4: bytes) -> str:
        row = self.db.one(
            "SELECT m.status FROM visits v JOIN members m ON m.id = v.member_id " "WHERE v.id = ?",
            (visit_id,),
        )
        if row is not None and row["status"] in ("withdrawn", "expired"):
            raise LookupError("member withdrew before the clip finished; clip not saved")
        clip_id = new_id("k")
        path = self.dir / f"{clip_id}.enc"
        path.write_bytes(self.sealer.seal(mp4, clip_id.encode()))
        expires = dt.datetime.now(IST) + dt.timedelta(days=self.retention_days)
        with self.db.tx():
            self.db.execute(
                "INSERT INTO clips (id, visit_id, path_enc, created_at, expires_at) "
                "VALUES (?,?,?,?,?)",
                (
                    clip_id,
                    visit_id,
                    path.name,
                    dt.datetime.now(IST).isoformat(timespec="seconds"),
                    expires.isoformat(timespec="seconds"),
                ),
            )
            self.db.execute("UPDATE visits SET clip_id = ? WHERE id = ?", (clip_id, visit_id))
        return clip_id

    def read(self, clip_id: str) -> bytes:
        row = self.db.one("SELECT path_enc FROM clips WHERE id = ?", (clip_id,))
        if row is None:
            raise LookupError(clip_id)
        return self.sealer.open((self.dir / row["path_enc"]).read_bytes(), clip_id.encode())

    def delete(self, clip_id: str) -> None:
        row = self.db.one("SELECT path_enc FROM clips WHERE id = ?", (clip_id,))
        if row is None:
            return
        (self.dir / row["path_enc"]).unlink(missing_ok=True)
        with self.db.tx():
            self.db.execute("UPDATE visits SET clip_id = NULL WHERE clip_id = ?", (clip_id,))
            self.db.execute("DELETE FROM clips WHERE id = ?", (clip_id,))

    def delete_for_member(self, member_id: str) -> int:
        rows = self.db.all(
            "SELECT c.id FROM clips c JOIN visits v ON v.id = c.visit_id " "WHERE v.member_id = ?",
            (member_id,),
        )
        for r in rows:
            self.delete(r["id"])
        return len(rows)


def encode_mp4(frames: list[Frame], fps: float) -> bytes:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg not installed")
    proc = subprocess.run(
        [
            ffmpeg,
            "-loglevel",
            "error",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "bgr24",
            "-s",
            f"{OUT_W}x{OUT_H}",
            "-r",
            f"{fps:.3f}",
            "-i",
            "-",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "frag_keyframe+empty_moov",
            "-f",
            "mp4",
            "pipe:1",
        ],
        input=b"".join(f.tobytes() for f in frames),
        capture_output=True,
        check=False,
        timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode(errors="replace")[:300])
    return proc.stdout


class ClipRecorder:
    """Frame listener that turns each alert into one encrypted clip."""

    def __init__(
        self,
        store: ClipStore,
        *,
        seconds: float = CLIP_SECONDS,
        encoder: Callable[[list[Frame], float], bytes] = encode_mp4,
        on_saved: Callable[[str, str], None] | None = None,
    ) -> None:
        self.store = store
        self.seconds = seconds
        self.encoder = encoder
        self.on_saved = on_saved
        self._ring: deque[TimedFrame] = deque()
        self._jobs: list[_Job] = []
        self._lock = threading.Lock()
        self.threads: list[threading.Thread] = []

    def __call__(self, tf: TimedFrame) -> None:
        with self._lock:
            self._ring.append(tf)
            while self._ring and tf.ts - self._ring[0].ts > RING_SECONDS:
                self._ring.popleft()
            done = []
            for job in self._jobs:
                if tf.ts >= job.start_ts:
                    job.frames.append(tf.frame)
                if tf.ts - job.start_ts >= self.seconds:
                    done.append(job)
            for job in done:
                self._jobs.remove(job)
                self._finish(job)

    def start(self, visit_id: str, first_ts: float) -> None:
        with self._lock:
            job = _Job(visit_id, first_ts, [f.frame for f in self._ring if f.ts >= first_ts])
            self._jobs.append(job)

    def _finish(self, job: _Job) -> None:
        frames = job.frames

        def work() -> None:
            try:
                fps = max(1.0, len(frames) / self.seconds)
                clip_id = self.store.save(job.visit_id, self.encoder(frames, fps))
                if self.on_saved:
                    self.on_saved(job.visit_id, clip_id)
            except Exception:
                log.exception("clip for %s not saved", job.visit_id)

        t = threading.Thread(target=work, name="clip-encode", daemon=True)
        self.threads.append(t)
        t.start()

    def flush(self, timeout: float = 30.0) -> None:
        """Finish open jobs with the frames they have (shutdown and tests)."""
        with self._lock:
            jobs, self._jobs = self._jobs, []
        for job in jobs:
            if job.frames:
                self._finish(job)
        for t in self.threads:
            t.join(timeout)
        self.threads = [t for t in self.threads if t.is_alive()]
