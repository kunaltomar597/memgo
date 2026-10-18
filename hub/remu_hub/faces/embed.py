"""Alignment and ArcFace embedding.

This is the only module in the codebase that runs the recognition model
(docs/RULES.md: only the hub computes embeddings, and only here). A test
fails if `.rec.run(` appears anywhere else.
"""

from __future__ import annotations

from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from remu_hub.faces.align import ARCFACE_DST, similarity_transform

EMBED_DIM = 512


def align(image: NDArray[np.uint8], landmarks: NDArray[np.float32]) -> NDArray[np.uint8]:
    """Warp a face to the standard 112 by 112 ArcFace crop by its 5 landmarks."""
    m = similarity_transform(landmarks.astype(np.float64), ARCFACE_DST)
    out = cv2.warpAffine(image, m, (112, 112), borderValue=(0.0, 0.0, 0.0))
    return np.asarray(out, np.uint8)


def embed_aligned(rec_session: Any, aligned: NDArray[np.uint8]) -> NDArray[np.float32]:
    blob = cv2.dnn.blobFromImage(
        aligned, 1.0 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True
    )
    name = rec_session.get_inputs()[0].name
    raw = rec_session.run(None, {name: blob})[0].reshape(-1).astype(np.float32)
    if raw.shape[0] != EMBED_DIM:
        raise ValueError(f"expected {EMBED_DIM} floats, got {raw.shape[0]}")
    norm = float(np.linalg.norm(raw))
    if norm == 0.0:
        raise ValueError("zero embedding")
    out: NDArray[np.float32] = (raw / norm).astype(np.float32)
    return out


def embed(
    rec_session: Any, image: NDArray[np.uint8], landmarks: NDArray[np.float32]
) -> NDArray[np.float32]:
    """Align by landmarks and return the L2-normalised 512-float embedding."""
    return embed_aligned(rec_session, align(image, landmarks))
