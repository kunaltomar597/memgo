"""Serve the fixture video as a looping RTSP stream on rtsp://127.0.0.1:8554/gate.

Downloads a pinned mediamtx release into ~/.cache/remu/mediamtx on first run,
starts it, then pushes the fixture video with ffmpeg in a loop. Ctrl+C stops both.

Usage: python scripts/fake_camera.py [--video path] [--path gate] [--port 8554]
"""

from __future__ import annotations

import argparse
import hashlib
import platform
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "v1.9.3"
CACHE = Path.home() / ".cache" / "remu" / "mediamtx" / VERSION
DEFAULT_VIDEO = ROOT / "tests" / "eval" / "samples" / "gate_fixture.mp4"


def asset_name() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    arch = {"x86_64": "amd64", "amd64": "amd64", "arm64": "arm64", "aarch64": "arm64v8"}.get(
        machine
    )
    if system not in ("darwin", "linux") or arch is None:
        raise SystemExit(f"unsupported platform {system}/{machine}")
    if system == "darwin":
        arch = "arm64" if arch.startswith("arm64") else "amd64"
    return f"mediamtx_{VERSION}_{system}_{arch}.tar.gz"


def ensure_mediamtx() -> Path:
    binary = CACHE / "mediamtx"
    if binary.exists():
        return binary
    CACHE.mkdir(parents=True, exist_ok=True)
    name = asset_name()
    url = f"https://github.com/bluenviron/mediamtx/releases/download/{VERSION}/{name}"
    archive = CACHE / name
    print(f"downloading {url}")
    urllib.request.urlretrieve(url, archive)  # noqa: S310 - pinned https URL
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    print(f"sha256 {digest}")
    with tarfile.open(archive) as tar:
        member = tar.getmember("mediamtx")
        tar.extract(member, CACHE, filter="data")
    binary.chmod(0o755)
    return binary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=Path, default=DEFAULT_VIDEO)
    parser.add_argument("--path", default="gate")
    parser.add_argument("--port", type=int, default=8554)
    args = parser.parse_args()
    if not args.video.exists():
        print(f"{args.video} is missing; run `cd hub && make setup` first", file=sys.stderr)
        return 1
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        print("ffmpeg is required", file=sys.stderr)
        return 1
    binary = ensure_mediamtx()
    config = CACHE / "remu-mediamtx.yml"
    config.write_text(
        f"rtspAddress: :{args.port}\nrtmp: no\nhls: no\nwebrtc: no\nsrt: no\n"
        "paths:\n  all_others:\n"
    )
    server = subprocess.Popen([str(binary), str(config)])
    time.sleep(1.0)
    url = f"rtsp://127.0.0.1:{args.port}/{args.path}"
    pusher = subprocess.Popen(
        [
            ffmpeg,
            "-loglevel",
            "error",
            "-re",
            "-stream_loop",
            "-1",
            "-i",
            str(args.video),
            "-c",
            "copy",
            "-f",
            "rtsp",
            "-rtsp_transport",
            "tcp",
            url,
        ]
    )
    print(f"serving {url} (Ctrl+C to stop)")
    try:
        while pusher.poll() is None and server.poll() is None:
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        for proc in (pusher, server):
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=5)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
