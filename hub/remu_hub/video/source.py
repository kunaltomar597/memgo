"""Frame sources for the gate pipeline.

`RtspSource` follows docs/ARCHITECTURE.md section 7: a reader thread decodes
every frame at the stream's native rate and keeps only the newest one. The
consumer takes that newest frame at 5 to 8 frames per second, resizes it to
640 by 360 and drops anything older than 500 ms. The source reconnects with
exponential backoff when the stream drops.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import Protocol

import cv2
import numpy as np
from numpy.typing import NDArray

log = logging.getLogger(__name__)

Frame = NDArray[np.uint8]
OUT_W, OUT_H = 640, 360
MAX_FRAME_AGE_S = 0.5


@dataclass(frozen=True)
class TimedFrame:
    ts: float  # wall clock seconds
    frame: Frame  # BGR, 640 by 360


class FrameSource(Protocol):
    def frames(self) -> Iterator[TimedFrame]: ...

    def close(self) -> None: ...


def resize(frame: Frame) -> Frame:
    if frame.shape[1] == OUT_W and frame.shape[0] == OUT_H:
        return frame
    return np.asarray(cv2.resize(frame, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA), np.uint8)


class RtspSource:
    """Newest-frame RTSP reader with reconnect and a detection-rate throttle."""

    def __init__(
        self,
        url: str,
        fps: float = 6.0,
        *,
        open_capture: Callable[[str], cv2.VideoCapture] | None = None,
        backoff_initial: float = 1.0,
        backoff_max: float = 10.0,
        clock: Callable[[], float] = time.time,
    ) -> None:
        if not 5.0 <= fps <= 8.0:
            raise ValueError("detection fps must be between 5 and 8")
        self.url = url
        self.interval = 1.0 / fps
        self._open = open_capture or _open_ffmpeg
        self._backoff_initial = backoff_initial
        self._backoff_max = backoff_max
        self._clock = clock
        self._lock = threading.Lock()
        self._latest: TimedFrame | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.state = "connecting"
        self.reconnects = 0

    def _reader(self) -> None:
        backoff = self._backoff_initial
        while not self._stop.is_set():
            cap = self._open(self.url)
            if not cap.isOpened():
                self.state = "down"
                log.warning("stream not reachable, retrying in %.1fs", backoff)
                self._stop.wait(backoff)
                backoff = min(backoff * 2, self._backoff_max)
                continue
            self.state = "ok"
            backoff = self._backoff_initial
            while not self._stop.is_set():
                ok, frame = cap.read()
                if not ok or frame is None:
                    break
                with self._lock:
                    self._latest = TimedFrame(self._clock(), np.asarray(frame, np.uint8))
            cap.release()
            if not self._stop.is_set():
                self.state = "down"
                self.reconnects += 1
                log.warning("stream dropped, reconnecting in %.1fs", backoff)
                self._stop.wait(backoff)
                backoff = min(backoff * 2, self._backoff_max)

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._reader, name="rtsp-reader", daemon=True)
            self._thread.start()

    def frames(self) -> Iterator[TimedFrame]:
        self.start()
        last_ts = 0.0
        while not self._stop.is_set():
            started = self._clock()
            with self._lock:
                latest = self._latest
            fresh = latest is not None and self._clock() - latest.ts <= MAX_FRAME_AGE_S
            if latest is not None and fresh and latest.ts != last_ts:
                last_ts = latest.ts
                yield TimedFrame(latest.ts, resize(latest.frame))
            elapsed = self._clock() - started
            self._stop.wait(max(0.0, self.interval - elapsed))

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)


class FileSource:
    """Plays a video file (or a list of frames) at a fixed rate. For tests and the demo."""

    def __init__(
        self,
        path: str | None = None,
        *,
        frames: list[Frame] | None = None,
        fps: float = 6.0,
        realtime: bool = False,
        loop: bool = False,
        start_ts: float = 1_000_000.0,
    ) -> None:
        if (path is None) == (frames is None):
            raise ValueError("give a path or frames")
        self.path = path
        self._frames = frames
        self.fps = fps
        self.realtime = realtime
        self.loop = loop
        self.start_ts = start_ts
        self._closed = False

    def _raw(self) -> Iterator[tuple[float, Frame]]:
        if self._frames is not None:
            for i, f in enumerate(self._frames):
                yield i / self.fps, f
            return
        assert self.path is not None
        cap = cv2.VideoCapture(self.path)
        src_fps = cap.get(cv2.CAP_PROP_FPS) or 15.0
        step = max(1, round(src_fps / self.fps))
        i = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if i % step == 0:
                yield i / src_fps, np.asarray(frame, np.uint8)
            i += 1
        cap.release()

    def frames(self) -> Iterator[TimedFrame]:
        offset = 0.0
        while not self._closed:
            last = 0.0
            for t, f in self._raw():
                if self._closed:
                    return
                if self.realtime:
                    time.sleep(1.0 / self.fps)
                last = t
                yield TimedFrame(self.start_ts + offset + t, resize(f))
            if not self.loop:
                return
            offset += last + 1.0 / self.fps

    def close(self) -> None:
        self._closed = True


def _open_ffmpeg(url: str) -> cv2.VideoCapture:
    os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "rtsp_transport;tcp")
    return cv2.VideoCapture(url, cv2.CAP_FFMPEG)
