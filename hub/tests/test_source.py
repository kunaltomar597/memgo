from __future__ import annotations

import itertools
import threading
import time

import numpy as np

from remu_hub.video.source import FileSource, RtspSource


def _frames(n: int, w: int = 1280, h: int = 720) -> list[np.ndarray]:
    return [np.full((h, w, 3), i % 255, np.uint8) for i in range(n)]


def test_file_source_resizes_and_timestamps() -> None:
    out = list(FileSource(frames=_frames(4), fps=6.0).frames())
    assert len(out) == 4
    assert all(f.frame.shape == (360, 640, 3) for f in out)
    assert [round(b.ts - a.ts, 3) for a, b in itertools.pairwise(out)] == [0.167] * 3


class FakeCapture:
    def __init__(self, frames: int, opened: bool = True) -> None:
        self.left = frames
        self.opened = opened

    def isOpened(self) -> bool:
        return self.opened

    def read(self) -> tuple[bool, np.ndarray | None]:
        if self.left <= 0:
            return False, None
        self.left -= 1
        time.sleep(0.01)
        return True, np.zeros((720, 1280, 3), np.uint8)

    def release(self) -> None:
        pass


def test_rtsp_source_reconnects_after_a_drop() -> None:
    opens: list[int] = []

    def opener(url: str) -> FakeCapture:
        opens.append(1)
        if len(opens) == 2:
            return FakeCapture(0, opened=False)  # server down once
        return FakeCapture(30)

    src = RtspSource(
        "rtsp://x/gate",
        fps=8.0,
        open_capture=opener,  # type: ignore[arg-type]
        backoff_initial=0.01,
        backoff_max=0.02,
    )
    got = []

    def consume() -> None:
        for tf in src.frames():
            got.append(tf)
            if len(got) >= 6:
                break

    t = threading.Thread(target=consume)
    t.start()
    t.join(timeout=10)
    src.close()
    assert len(got) >= 6
    assert all(f.frame.shape == (360, 640, 3) for f in got)
    assert len(opens) >= 2


def test_rtsp_source_rejects_out_of_range_fps() -> None:
    import pytest

    with pytest.raises(ValueError):
        RtspSource("rtsp://x", fps=15)
