"""Camera aim preview (TASK-107): low-resolution MJPEG with detection boxes.

Streams for at most 10 minutes, then stops by itself. Frames are encoded in
memory and sent; nothing from the preview is stored.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Iterator

import cv2

from remu_hub.faces.detect import Detection
from remu_hub.video.source import Frame

MAX_SECONDS = 600.0
BOUNDARY = "remuframe"


def annotate(frame: Frame, dets: list[Detection], min_face_px: int) -> Frame:
    out = frame.copy()
    for d in dets:
        x1, y1, x2, y2 = (int(v) for v in d.box)
        good = d.width >= 60  # the working minimum face size from docs/PILOT_PLAN.md
        colour = (80, 200, 80) if good else (40, 160, 230)
        cv2.rectangle(out, (x1, y1), (x2, y2), colour, 2)
        cv2.putText(
            out,
            f"{int(d.width)}px",
            (x1, max(12, y1 - 4)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.45,
            colour,
            1,
        )
    if not dets:
        cv2.putText(out, "no face", (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (230, 230, 230), 1)
    return out


def mjpeg(
    frames: Iterator[Frame],
    detect: Callable[[Frame], list[Detection]],
    *,
    min_face_px: int = 40,
    max_seconds: float = MAX_SECONDS,
    clock: Callable[[], float] = time.monotonic,
) -> Iterator[bytes]:
    start = clock()
    for frame in frames:
        if clock() - start > max_seconds:
            return
        small = cv2.resize(annotate(frame, detect(frame), min_face_px), (480, 270))
        ok, buf = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 60])
        if ok:
            yield (
                (
                    f"--{BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: {len(buf)}"
                    "\r\n\r\n"
                ).encode()
                + buf.tobytes()
                + b"\r\n"
            )
