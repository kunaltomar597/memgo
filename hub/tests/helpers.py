"""Shared test helpers: a fake face engine and a hub factory."""

from __future__ import annotations

import base64
import hashlib
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from remu_hub.faces.detect import Detection
from remu_hub.faces.scramble import Scrambler
from remu_hub.hub import Hub
from remu_hub.settings import load_settings


class StubAge:
    def __init__(self, years: float = 35.0) -> None:
        self.years = years

    def get_inputs(self) -> list[Any]:
        return [type("I", (), {"name": "data"})()]

    def run(self, _: Any, __: Any) -> list[np.ndarray]:
        return [np.array([[0.0, 1.0, self.years / 100.0]], np.float32)]


class StubModels:
    def __init__(self) -> None:
        self.age = StubAge()


class FakeEngine:
    """Deterministic engine: the template depends only on the image's mean colour."""

    model_version = "fake:00000000"

    def __init__(self, shop_key: bytes) -> None:
        self.models = StubModels()
        self.scrambler = Scrambler(shop_key)
        self.min_face_px = 40
        self.no_face = False

    def detect(self, image: np.ndarray, *, min_face_px: int | None = None) -> list[Detection]:
        if self.no_face or float(image.mean()) < 1.0:
            return []
        h, w = image.shape[:2]
        lm = np.array(
            [
                [w * 0.35, h * 0.4],
                [w * 0.65, h * 0.4],
                [w * 0.5, h * 0.55],
                [w * 0.38, h * 0.7],
                [w * 0.62, h * 0.7],
            ],
            np.float32,
        )
        return [Detection((w * 0.2, h * 0.2, w * 0.8, h * 0.8), 0.9, lm)]

    def template(self, image: np.ndarray, det: Detection) -> np.ndarray:
        seed = int(hashlib.sha256(str(int(image.mean())).encode()).hexdigest()[:8], 16)
        v = np.random.default_rng(seed).standard_normal(512).astype(np.float32)
        return self.scrambler.scramble(v / np.linalg.norm(v))


def jpeg_b64(value: int = 120, size: int = 160) -> str:
    img = np.full((size, size, 3), value, np.uint8)
    ok, buf = cv2.imencode(".jpg", img)
    assert ok
    return base64.b64encode(buf.tobytes()).decode()


def make_hub(tmp_path: Path, **env: str) -> Hub:
    settings = load_settings(
        {
            "REMU_DATA_DIR": str(tmp_path / "data"),
            "REMU_SHOP_ID": "shop-1",
            "REMU_RTSP_GATE_URL": "rtsp://127.0.0.1:8554/gate",
            **env,
        }
    )
    hub = Hub(settings, engine_factory=FakeEngine)
    hub.db.execute("UPDATE shops SET name = 'Sharma Jewellers' WHERE id = 'shop-1'")
    return hub


def face(value: int = 120, **extra: Any) -> dict[str, Any]:
    lm = [[56, 64], [104, 64], [80, 88], [61, 112], [99, 112]]
    return {"crop_jpeg_b64": jpeg_b64(value), "landmarks": lm, **extra}
