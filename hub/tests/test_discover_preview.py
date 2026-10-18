from __future__ import annotations

import numpy as np

from remu_hub.faces.detect import Detection
from remu_hub.video.discover import build_url, discover, probe_host
from remu_hub.video.preview import mjpeg

FRAME = np.full((720, 1280, 3), 100, np.uint8)


def test_hikvision_substream_is_preferred() -> None:
    tried: list[str] = []

    def grab(url: str) -> np.ndarray | None:
        tried.append(url)
        return FRAME if "/Streaming/Channels/102" in url else None

    found = probe_host("192.168.1.64", "admin", "p@ss word", channels=1, grab=grab)
    assert len(found) == 1
    c = found[0]
    assert c.brand == "hikvision" and c.substream and (c.width, c.height) == (1280, 720)
    assert c.url == "rtsp://admin:p%40ss%20word@192.168.1.64:554/Streaming/Channels/102"
    assert c.thumbnail_jpeg[:2] == b"\xff\xd8"


def test_dahua_cpplus_pattern_falls_back_to_main_stream() -> None:
    def grab(url: str) -> np.ndarray | None:
        return FRAME if url.endswith("channel=1&subtype=0") else None

    found = probe_host("192.168.1.108", "admin", "x", channels=1, grab=grab)
    assert [(c.brand, c.substream) for c in found] == [("dahua_cpplus", False)]


def test_discover_only_probes_hosts_with_port_554() -> None:
    probed: list[str] = []

    def grab(url: str) -> np.ndarray | None:
        probed.append(url.split("@")[1].split(":")[0])
        return None

    discover("10.0.0.0/29", "u", "p", grab=grab, connect=lambda h, p, t: h == "10.0.0.3")
    assert set(probed) == {"10.0.0.3"}


def test_url_without_credentials() -> None:
    assert build_url("10.0.0.2", "/stream1", "", "") == "rtsp://10.0.0.2:554/stream1"


def test_preview_stops_after_ten_minutes() -> None:
    t = {"now": 0.0}

    def clock() -> float:
        t["now"] += 120.0
        return t["now"]

    frames = (np.zeros((360, 640, 3), np.uint8) for _ in range(100))
    det = Detection((10, 10, 90, 100), 0.9, np.zeros((5, 2), np.float32))
    parts = list(mjpeg(frames, lambda f: [det], max_seconds=600, clock=clock))
    assert 1 <= len(parts) <= 5
    assert parts[0].startswith(b"--remuframe\r\nContent-Type: image/jpeg")
