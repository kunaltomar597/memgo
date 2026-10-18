from __future__ import annotations

import gc
import tracemalloc
from pathlib import Path

import numpy as np
import pytest

from remu_hub.faces.detect import Detection
from remu_hub.faces.scramble import Scrambler
from remu_hub.match.decide import Decider, Kind
from remu_hub.match.gallery import Gallery
from remu_hub.pipeline import ConsoleSink, FaceEngine, Pipeline, Tracker
from remu_hub.video.source import FileSource, TimedFrame

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_VIDEO = ROOT / "tests" / "eval" / "samples" / "gate_fixture.mp4"


class FakeEngine:
    """Detects one face per frame and returns a fresh random template each time."""

    model_version = "fake:00000000"

    def __init__(self) -> None:
        self.rng = np.random.default_rng(0)

    def detect(self, image: np.ndarray, *, min_face_px: int | None = None) -> list[Detection]:
        return [Detection((100.0, 100.0, 180.0, 190.0), 0.9, np.zeros((5, 2), np.float32))]

    def template(self, image: np.ndarray, det: Detection) -> np.ndarray:
        v = self.rng.standard_normal(512).astype(np.float32)
        return v / np.linalg.norm(v)


def _dir_size(p: Path) -> int:
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def test_1000_non_matching_frames_leave_no_trace(tmp_path: Path) -> None:
    gallery = Gallery(0.45)
    gallery.add("m_other", FakeEngine().template(np.zeros(1), None))  # type: ignore[arg-type]
    frame = np.zeros((360, 640, 3), np.uint8)
    sink = ConsoleSink(printer=lambda _: None)
    pipe = Pipeline(
        FileSource(frames=[frame]),
        FakeEngine(),
        gallery,  # type: ignore[arg-type]
        Decider(3, 5, 600),
        [sink],
    )
    before_files = _dir_size(tmp_path)
    for i in range(100):  # warm up
        pipe.process(TimedFrame(float(i), frame))
    gc.collect()
    tracemalloc.start()
    base, _ = tracemalloc.get_traced_memory()
    for i in range(100, 1100):
        pipe.process(TimedFrame(i * 0.2, frame))
    gc.collect()
    current, _ = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    assert sink.decisions == []
    assert current - base < 64 * 1024, f"memory grew by {current - base} bytes"
    assert _dir_size(tmp_path) == before_files
    assert pipe.decider.active_tracks <= 1 and len(pipe.tracker) <= 1


def test_tracker_follows_overlapping_boxes() -> None:
    t = Tracker()
    a = t.assign([(0, 0, 100, 100)], 0.0)
    b = t.assign([(5, 5, 105, 105), (300, 300, 380, 380)], 0.2)
    c = t.assign([(10, 10, 110, 110)], 3.0)  # after the 2 s ttl the old tracks are gone
    assert b[0] == a[0] and b[1] != a[0]
    assert c[0] not in (a[0], b[1])


@pytest.mark.models
def test_fixture_video_enrol_then_alert(pack: object, shop_key: bytes) -> None:
    if not FIXTURE_VIDEO.exists():
        pytest.skip("fixture video missing (run `make setup`)")
    engine = FaceEngine(pack, Scrambler(shop_key))  # type: ignore[arg-type]
    frames = list(FileSource(str(FIXTURE_VIDEO), fps=6.0).frames())
    enrol_frame = frames[int(6.0 * 6)].frame
    dets = engine.detect(enrol_frame)
    assert dets, "no face at second 6 of the fixture video"
    gallery = Gallery(0.45)
    gallery.add("m_fixture", engine.template(enrol_frame, dets[0]))
    lines: list[str] = []
    sink = ConsoleSink(printer=lines.append)
    pipe = Pipeline(
        FileSource(str(FIXTURE_VIDEO), fps=6.0), engine, gallery, Decider(3, 5, 600), [sink]
    )
    pipe.run()
    alerts = [d for d in sink.decisions if d.kind is Kind.ALERT]
    assert len(alerts) == 1
    assert alerts[0].member_id == "m_fixture" and alerts[0].frames_agreed == 3
    assert alerts[0].first_ts - frames[0].ts < 4.5  # face enters at second 3
    assert lines == [f"ALERT member=m_fixture score={alerts[0].score:.3f} frames=3/5"]
