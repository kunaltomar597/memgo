"""Console demo: enrol the face at one second of the fixture video, then watch the gate stream.

Phase 1 version: an in-memory gallery and an ephemeral shop key, nothing
written to disk. Prints `ALERT member=<id> score=<s> frames=3/5` when the
enrolled face reappears on the stream.
"""

from __future__ import annotations

import secrets
import threading
import time
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from remu_hub.faces import models
from remu_hub.faces.scramble import Scrambler
from remu_hub.match.decide import Decider
from remu_hub.match.gallery import Gallery
from remu_hub.pipeline import ConsoleSink, FaceEngine, Pipeline
from remu_hub.settings import Settings
from remu_hub.video.source import RtspSource, resize


def frame_at(video: Path, second: float) -> NDArray[np.uint8]:
    cap = cv2.VideoCapture(str(video))
    cap.set(cv2.CAP_PROP_POS_MSEC, second * 1000.0)
    ok, frame = cap.read()
    cap.release()
    if not ok:
        raise SystemExit(f"cannot read second {second} of {video}")
    return np.asarray(frame, np.uint8)


def run_demo(
    settings: Settings, *, video: Path, enrol_at: float, url: str, max_seconds: float
) -> int:
    pack = models.load(settings.models_dir, settings.model_pack)
    engine = FaceEngine(pack, Scrambler(secrets.token_bytes(32)), settings.min_face_px)
    frame = resize(frame_at(video, enrol_at))
    dets = engine.detect(frame)
    if not dets:
        print(f"no face at second {enrol_at} of {video}")
        return 1
    gallery = Gallery(settings.match_threshold)
    member_id = "m_demo"
    gallery.add(member_id, engine.template(frame, dets[0]))
    print(f"enrolled {member_id} from second {enrol_at} ({engine.model_version})")
    decider = Decider(
        settings.frames_required, settings.frames_window, settings.alert_cooldown_minutes * 60.0
    )
    source = RtspSource(url, fps=6.0)
    pipe = Pipeline(source, engine, gallery, decider, [ConsoleSink()])
    timer = threading.Timer(max_seconds, pipe.stop)
    timer.daemon = True
    timer.start()
    print(f"watching {url} for {max_seconds:.0f}s")
    started = time.monotonic()
    pipe.run()
    timer.cancel()
    print(f"stopped after {time.monotonic() - started:.1f}s, {pipe.frames_processed} frames")
    return 0
