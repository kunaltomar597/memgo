"""The gate loop: source, detect, embed, scramble, match, decide.

Non-member discard (docs/ARCHITECTURE.md section 7): an embedding lives only
inside one loop iteration. Tracks keep a member id (or None) and a score per
frame, never a vector or a crop, and expire 2 seconds after the face leaves.
Nothing about a non-matching face is written anywhere.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

import numpy as np
from numpy.typing import NDArray

from remu_hub.faces.detect import Detection, detect
from remu_hub.faces.embed import embed
from remu_hub.faces.models import ModelPack
from remu_hub.faces.scramble import Scrambler
from remu_hub.match.decide import Decider, Decision
from remu_hub.match.gallery import Gallery, Match
from remu_hub.video.source import FrameSource, TimedFrame

log = logging.getLogger(__name__)


class FaceEngine:
    """Detect, embed and scramble with one model pack and one shop key."""

    def __init__(self, models: ModelPack, scrambler: Scrambler, min_face_px: int = 40) -> None:
        self.models = models
        self.scrambler = scrambler
        self.min_face_px = min_face_px

    @property
    def model_version(self) -> str:
        return self.models.model_version

    def detect(
        self, image: NDArray[np.uint8], *, min_face_px: int | None = None
    ) -> list[Detection]:
        px = self.min_face_px if min_face_px is None else min_face_px
        return detect(self.models.det, image, min_face_px=px)

    def template(self, image: NDArray[np.uint8], det: Detection) -> NDArray[np.float32]:
        return self.scrambler.scramble(embed(self.models.rec, image, det.landmarks))


def iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


@dataclass
class _Box:
    box: tuple[float, float, float, float]
    ts: float


class Tracker:
    """Assigns detections to tracks by box overlap with the previous frame."""

    def __init__(self, min_iou: float = 0.3, ttl_s: float = 2.0) -> None:
        self.min_iou = min_iou
        self.ttl_s = ttl_s
        self._tracks: dict[int, _Box] = {}
        self._next = 1

    def assign(self, boxes: list[tuple[float, float, float, float]], ts: float) -> list[int]:
        for tid in [t for t, b in self._tracks.items() if ts - b.ts > self.ttl_s]:
            del self._tracks[tid]
        ids: list[int] = []
        taken: set[int] = set()
        for box in boxes:
            best, best_iou = None, self.min_iou
            for tid, prev in self._tracks.items():
                if tid in taken:
                    continue
                o = iou(box, prev.box)
                if o >= best_iou:
                    best, best_iou = tid, o
            if best is None:
                best = self._next
                self._next += 1
            self._tracks[best] = _Box(box, ts)
            taken.add(best)
            ids.append(best)
        return ids

    def __len__(self) -> int:
        return len(self._tracks)


class AlertSink(Protocol):
    def on_decision(self, decision: Decision, frame: TimedFrame) -> None: ...


class ConsoleSink:
    def __init__(self, printer: Callable[[str], None] = print) -> None:
        self.printer = printer
        self.decisions: list[Decision] = []

    def on_decision(self, decision: Decision, frame: TimedFrame) -> None:
        self.decisions.append(decision)
        if decision.kind.value == "alert":
            self.printer(
                f"ALERT member={decision.member_id} score={decision.score:.3f} "
                f"frames={decision.frames_agreed}/{decision.window}"
            )


FrameListener = Callable[[TimedFrame], None]


class Pipeline:
    def __init__(
        self,
        source: FrameSource,
        engine: FaceEngine,
        gallery: Gallery,
        decider: Decider,
        sinks: list[AlertSink],
        frame_listeners: list[FrameListener] | None = None,
    ) -> None:
        self.source = source
        self.engine = engine
        self.gallery = gallery
        self.decider = decider
        self.sinks = sinks
        self.frame_listeners = frame_listeners or []
        self.tracker = Tracker()
        self.frames_processed = 0
        self.fps = 0.0
        self._stop = False

    def process(self, tf: TimedFrame) -> list[Decision]:
        for listener in self.frame_listeners:
            listener(tf)
        dets = self.engine.detect(tf.frame)
        track_ids = self.tracker.assign([d.box for d in dets], tf.ts)
        decisions: list[Decision] = []
        for det, tid in zip(dets, track_ids, strict=True):
            match: Match | None = None
            if len(self.gallery):
                template = self.engine.template(tf.frame, det)
                match = self.gallery.match(template)
                del template  # never kept beyond this iteration
            decision = self.decider.observe(tid, match, tf.ts)
            if decision is not None:
                decisions.append(decision)
                for sink in self.sinks:
                    try:
                        sink.on_decision(decision, tf)
                    except Exception:
                        log.exception("alert sink failed")
        self.frames_processed += 1
        return decisions

    def run(self, max_frames: int | None = None) -> None:
        started = time.monotonic()
        window_start, window_frames = started, 0
        for tf in self.source.frames():
            if self._stop:
                break
            self.process(tf)
            window_frames += 1
            now = time.monotonic()
            if now - window_start >= 5.0:
                self.fps = window_frames / (now - window_start)
                window_start, window_frames = now, 0
            if max_frames is not None and self.frames_processed >= max_frames:
                break

    def stop(self) -> None:
        self._stop = True
        self.source.close()
