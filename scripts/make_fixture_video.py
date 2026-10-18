"""Generate the fake gate video from the public-domain test portrait.

The video is 12 seconds at 15 frames per second, 1280 by 720, H.264:
seconds 0 to 3 show an empty doorway, seconds 3 to 9 show the face walking
slowly across the frame, seconds 9 to 12 are empty again. It is generated,
never downloaded and never committed (`*.mp4` is gitignored).

Usage: python scripts/make_fixture_video.py [output.mp4]
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
FACE = ROOT / "hub" / "tests" / "fixtures" / "face_astronaut.jpg"
DEFAULT_OUT = ROOT / "tests" / "eval" / "samples" / "gate_fixture.mp4"
W, H, FPS, SECONDS = 1280, 720, 15, 12
FACE_START, FACE_END = 3.0, 9.0


def background(t: float) -> np.ndarray:
    frame = np.full((H, W, 3), (92, 98, 104), np.uint8)
    cv2.rectangle(frame, (440, 60), (840, 700), (60, 64, 70), -1)  # doorway
    cv2.putText(
        frame,
        f"REMU FAKE GATE {t:5.2f}s",
        (24, 40),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.8,
        (220, 220, 220),
        2,
    )
    return frame


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        print("ffmpeg is required (brew install ffmpeg or apt install ffmpeg)", file=sys.stderr)
        return 1
    portrait = cv2.imread(str(FACE))
    if portrait is None:
        print(f"missing {FACE}", file=sys.stderr)
        return 1
    face = portrait[0:270, 100:360]  # head and shoulders, so the face is doorway-sized
    proc = subprocess.Popen(
        [
            ffmpeg,
            "-loglevel",
            "error",
            "-y",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "bgr24",
            "-s",
            f"{W}x{H}",
            "-r",
            str(FPS),
            "-i",
            "-",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-g",
            str(FPS),
            "-bf",
            "0",
            str(out),
        ],
        stdin=subprocess.PIPE,
    )
    assert proc.stdin is not None
    for i in range(FPS * SECONDS):
        t = i / FPS
        frame = background(t)
        if FACE_START <= t < FACE_END:
            p = (t - FACE_START) / (FACE_END - FACE_START)
            fw = int(300 + 60 * p)
            fh = int(fw * face.shape[0] / face.shape[1])
            img = cv2.resize(face, (fw, fh))
            x = int(420 + 160 * p)
            y = 120
            frame[y : y + fh, x : x + fw] = img
        proc.stdin.write(frame.tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        return 1
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
