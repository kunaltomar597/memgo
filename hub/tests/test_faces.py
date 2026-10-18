from __future__ import annotations

import inspect
import re
from pathlib import Path

import cv2
import numpy as np
import pytest

from remu_hub.faces import age, models
from remu_hub.faces.detect import detect
from remu_hub.faces.embed import embed

pytestmark = pytest.mark.models


def test_loader_is_a_singleton_with_a_stable_version(models_dir: Path) -> None:
    a = models.load(models_dir, "buffalo_l")
    b = models.load(models_dir, "buffalo_l")
    assert a is b
    assert re.fullmatch(r"buffalo_l:[0-9a-f]{8}", a.model_version)
    assert a.model_version == models.load(models_dir, "buffalo_l").model_version


def test_detects_the_one_fixture_face(pack: models.ModelPack, face_image: np.ndarray) -> None:
    dets = detect(pack.det, face_image)
    assert len(dets) == 1
    d = dets[0]
    assert d.score > 0.6
    assert d.landmarks.shape == (5, 2)
    x1, y1, x2, y2 = d.box
    lx, rx = d.landmarks[0][0], d.landmarks[1][0]
    assert x1 < lx < rx < x2 and y1 < d.landmarks[2][1] < y2


def test_small_faces_are_dropped(pack: models.ModelPack, face_image: np.ndarray) -> None:
    assert detect(pack.det, face_image, min_face_px=200) == []


def test_empty_frame_has_no_faces(pack: models.ModelPack) -> None:
    assert detect(pack.det, np.full((360, 640, 3), 90, np.uint8)) == []


def test_embedding_is_unit_length_and_deterministic(
    pack: models.ModelPack, face_image: np.ndarray
) -> None:
    d = detect(pack.det, face_image)[0]
    a = embed(pack.rec, face_image, d.landmarks)
    b = embed(pack.rec, face_image, d.landmarks)
    assert a.shape == (512,)
    assert abs(float(np.linalg.norm(a)) - 1.0) < 1e-5
    assert np.array_equal(a, b)


def test_same_person_scores_high(pack: models.ModelPack, face_image: np.ndarray) -> None:
    flipped = cv2.flip(face_image, 1)
    darker = cv2.convertScaleAbs(face_image, alpha=0.7, beta=-10)
    base = embed(pack.rec, face_image, detect(pack.det, face_image)[0].landmarks)
    for img in (flipped, darker):
        e = embed(pack.rec, img, detect(pack.det, img)[0].landmarks)
        assert float(base @ e) > 0.8


def test_adult_passes_the_age_gate(pack: models.ModelPack, face_image: np.ndarray) -> None:
    d = detect(pack.det, face_image)[0]
    result, estimate = age.check_adult(pack.age, face_image, d.box)
    assert result is age.AgeCheck.PASSED
    assert estimate >= 18


class _StubAge:
    def __init__(self, years: float) -> None:
        self.years = years

    def get_inputs(self) -> list[object]:
        return [type("I", (), {"name": "data"})()]

    def run(self, _: object, __: object) -> list[np.ndarray]:
        return [np.array([[0.0, 1.0, self.years / 100.0]], np.float32)]


@pytest.mark.parametrize(
    ("years", "expected"),
    [(12.0, age.AgeCheck.REFUSED), (17.9, age.AgeCheck.REFUSED), (18.0, age.AgeCheck.PASSED)],
)
def test_under_18_is_refused(years: float, expected: age.AgeCheck) -> None:
    img = np.zeros((200, 200, 3), np.uint8)
    assert age.check_adult(_StubAge(years), img, (50, 50, 150, 150))[0] is expected


def test_age_gate_has_no_override() -> None:
    params = inspect.signature(age.check_adult).parameters
    assert set(params) == {"age_session", "image", "box"}
    assert "refuse" in (age.__doc__ or "") and "no override" in (age.__doc__ or "").lower()


def test_only_embed_py_runs_the_recognition_model() -> None:
    root = Path(__file__).resolve().parents[1] / "remu_hub"
    offenders = []
    for path in root.rglob("*.py"):
        if path.name == "embed.py":
            continue
        text = path.read_text()
        if re.search(r"\brec(_session)?\.run\(", text) or "embed_aligned(" in text:
            offenders.append(str(path))
    assert offenders == []
