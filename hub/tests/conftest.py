from __future__ import annotations

import os
from pathlib import Path

import cv2
import numpy as np
import pytest
from numpy.typing import NDArray

from remu_hub.faces import models
from remu_hub.settings import load_settings

FIXTURES = Path(__file__).parent / "fixtures"


def _models_dir() -> Path:
    explicit = os.environ.get("REMU_TEST_MODELS")
    if explicit:
        return Path(explicit)
    return load_settings({}, require_stream=False).models_dir


@pytest.fixture(scope="session")
def models_dir() -> Path:
    d = _models_dir()
    if not models.available(d, "buffalo_l"):
        pytest.skip(f"model pack not downloaded in {d} (run `make setup`)")
    return d


@pytest.fixture(scope="session")
def pack(models_dir: Path) -> models.ModelPack:
    return models.load(models_dir, "buffalo_l")


@pytest.fixture(scope="session")
def face_image() -> NDArray[np.uint8]:
    img = cv2.imread(str(FIXTURES / "face_astronaut.jpg"))
    assert img is not None
    return img


@pytest.fixture
def shop_key() -> bytes:
    return bytes(range(32))
