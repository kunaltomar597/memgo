"""Age gate for enrolment.

The pack's genderage model estimates age from the face box. The estimate is
approximate (several years either way), and the policy is to refuse when in
doubt: any estimate under 18 is refused. There is no override parameter,
by design (docs/BRIEF.md section 5, principle 9).
"""

from __future__ import annotations

from enum import Enum
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from remu_hub.faces.align import center_scale_transform

ADULT_AGE = 18
INPUT = 96


class AgeCheck(str, Enum):
    PASSED = "passed"
    REFUSED = "refused"


def estimate_age(
    age_session: Any, image: NDArray[np.uint8], box: tuple[float, float, float, float]
) -> float:
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    center = ((x1 + x2) / 2, (y1 + y2) / 2)
    scale = INPUT / (max(w, h) * 1.5)
    m = center_scale_transform(center, INPUT, scale)
    crop = cv2.warpAffine(image, m, (INPUT, INPUT), borderValue=(0.0, 0.0, 0.0))
    blob = cv2.dnn.blobFromImage(crop, 1.0, (INPUT, INPUT), (0.0, 0.0, 0.0), swapRB=True)
    name = age_session.get_inputs()[0].name
    pred = age_session.run(None, {name: blob})[0].reshape(-1)
    return float(pred[2] * 100.0)


def check_adult(
    age_session: Any, image: NDArray[np.uint8], box: tuple[float, float, float, float]
) -> tuple[AgeCheck, float]:
    """Return passed or refused with the estimate. Under 18 is refused. No override."""
    age = estimate_age(age_session, image, box)
    return (AgeCheck.PASSED if age >= ADULT_AGE else AgeCheck.REFUSED), age
