"""RTSP discovery on the shop LAN (TASK-102).

Scans the local /24 for hosts answering on port 554, then tries the known
URL patterns of the common Indian DVR brands with the credentials the owner
enters. Sub-streams are tried first because the pipeline wants 720p or lower.
Each working candidate comes with a small JPEG thumbnail for the app; nothing
is stored.

URL patterns (OPEN: confirm on the pilot DVR models in Phase 7):
- Hikvision: /Streaming/Channels/<ch>02 (sub), /Streaming/Channels/<ch>01 (main)
- Dahua and CP Plus (CP Plus is largely Dahua OEM):
  /cam/realmonitor?channel=<ch>&subtype=1 (sub), subtype=0 (main)
- Generic ONVIF-style: /stream1, /h264/ch<ch>/sub/av_stream
"""

from __future__ import annotations

import concurrent.futures as cf
import ipaddress
import socket
from collections.abc import Callable
from dataclasses import dataclass
from urllib.parse import quote

import cv2
import numpy as np
from numpy.typing import NDArray

from remu_hub.video.source import resize

PATTERNS: dict[str, list[str]] = {
    "hikvision": ["/Streaming/Channels/{ch}02", "/Streaming/Channels/{ch}01"],
    "dahua_cpplus": [
        "/cam/realmonitor?channel={ch}&subtype=1",
        "/cam/realmonitor?channel={ch}&subtype=0",
    ],
    "generic": ["/h264/ch{ch}/sub/av_stream", "/stream1"],
}


@dataclass(frozen=True)
class Candidate:
    host: str
    brand: str
    channel: int
    url: str  # with credentials; returned to the owner's app only, never logged
    substream: bool
    width: int
    height: int
    thumbnail_jpeg: bytes


def rtsp_hosts(
    subnet: str,
    port: int = 554,
    timeout: float = 0.3,
    connect: Callable[[str, int, float], bool] | None = None,
) -> list[str]:
    net = ipaddress.ip_network(subnet, strict=False)
    probe = connect or _tcp_open
    hosts = [str(h) for h in net.hosts()]
    with cf.ThreadPoolExecutor(max_workers=64) as pool:
        results = pool.map(lambda h: (h, probe(h, port, timeout)), hosts)
        return [h for h, ok in results if ok]


def _tcp_open(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def build_url(host: str, path: str, user: str, password: str, port: int = 554) -> str:
    creds = f"{quote(user, safe='')}:{quote(password, safe='')}@" if user else ""
    return f"rtsp://{creds}{host}:{port}{path}"


Grabber = Callable[[str], NDArray[np.uint8] | None]


def _grab(url: str) -> NDArray[np.uint8] | None:
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    try:
        if not cap.isOpened():
            return None
        ok, frame = cap.read()
        return np.asarray(frame, np.uint8) if ok and frame is not None else None
    finally:
        cap.release()


def probe_host(
    host: str, user: str, password: str, *, channels: int = 4, grab: Grabber = _grab
) -> list[Candidate]:
    found: list[Candidate] = []
    for brand, paths in PATTERNS.items():
        for ch in range(1, channels + 1):
            for i, pattern in enumerate(paths):
                url = build_url(host, pattern.format(ch=ch), user, password)
                frame = grab(url)
                if frame is None:
                    continue
                small = resize(frame)
                ok, buf = cv2.imencode(
                    ".jpg", cv2.resize(small, (320, 180)), [cv2.IMWRITE_JPEG_QUALITY, 70]
                )
                found.append(
                    Candidate(
                        host,
                        brand,
                        ch,
                        url,
                        i == 0,
                        int(frame.shape[1]),
                        int(frame.shape[0]),
                        buf.tobytes() if ok else b"",
                    )
                )
                break  # the sub-stream works: skip the main stream for this channel
        if found:
            return found  # one brand pattern matched: stop trying others
    return found


def discover(
    subnet: str,
    user: str,
    password: str,
    *,
    grab: Grabber = _grab,
    connect: Callable[[str, int, float], bool] | None = None,
) -> list[Candidate]:
    out: list[Candidate] = []
    for host in rtsp_hosts(subnet, connect=connect):
        out.extend(probe_host(host, user, password, grab=grab))
    return out


def local_subnet() -> str:
    from remu_hub.api.tls import lan_addresses

    lan = [a for a in lan_addresses() if a != "127.0.0.1"]
    return f"{lan[0]}/24" if lan else "127.0.0.1/32"
