"""In-memory gallery of active members' scrambled templates, with cosine matching.

Only `active` members are ever added (db code enforces it on load). Templates
are L2-normalised, so cosine similarity is one matrix-vector product.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from remu_hub.faces.linalg import matmul

FloatArray = NDArray[np.float32]


@dataclass(frozen=True)
class Match:
    member_id: str
    score: float


class Gallery:
    def __init__(self, threshold: float = 0.45, dim: int = 512) -> None:
        self.threshold = threshold
        self.dim = dim
        self._lock = threading.Lock()
        self._vectors: dict[str, FloatArray] = {}
        self._ids: list[str] = []
        self._matrix: FloatArray = np.zeros((0, dim), np.float32)
        self._dirty = False

    def __len__(self) -> int:
        with self._lock:
            return len(self._vectors)

    def __contains__(self, member_id: object) -> bool:
        with self._lock:
            return member_id in self._vectors

    def add(self, member_id: str, vec: FloatArray) -> None:
        v = np.asarray(vec, np.float32).reshape(-1)
        if v.shape[0] != self.dim:
            raise ValueError(f"template must have {self.dim} floats")
        norm = float(np.linalg.norm(v))
        if norm == 0.0:
            raise ValueError("zero template")
        with self._lock:
            self._vectors[member_id] = v / norm
            self._dirty = True

    replace = add

    def remove(self, member_id: str) -> bool:
        with self._lock:
            removed = self._vectors.pop(member_id, None) is not None
            self._dirty = self._dirty or removed
            return removed

    def clear(self) -> None:
        with self._lock:
            self._vectors.clear()
            self._dirty = True

    def ids(self) -> list[str]:
        with self._lock:
            return list(self._vectors)

    def _rebuild(self) -> None:
        self._ids = list(self._vectors)
        self._matrix = (
            np.stack([self._vectors[i] for i in self._ids])
            if self._ids
            else np.zeros((0, self.dim), np.float32)
        )
        self._dirty = False

    def best(self, vec: FloatArray) -> Match | None:
        """Best member and score regardless of threshold (for the eval harness)."""
        with self._lock:
            if self._dirty:
                self._rebuild()
            if not self._ids:
                return None
            scores = matmul(self._matrix, np.asarray(vec, np.float32).reshape(-1))
            i = int(np.argmax(scores))
            return Match(self._ids[i], float(scores[i]))

    def match(self, vec: FloatArray) -> Match | None:
        """Best match at or above the threshold, else None."""
        best = self.best(vec)
        if best is None or best.score < self.threshold:
            return None
        return best
