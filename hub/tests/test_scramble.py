from __future__ import annotations

import os
from pathlib import Path

import numpy as np

from remu_hub.faces.scramble import Scrambler, projection_matrix


def _unit(seed: int) -> np.ndarray:
    v = np.random.default_rng(seed).standard_normal(512).astype(np.float32)
    return v / np.linalg.norm(v)


def test_matrix_is_orthogonal_and_deterministic(shop_key: bytes) -> None:
    q = projection_matrix(shop_key)
    with np.errstate(all="ignore"):  # spurious Accelerate BLAS flags, see faces/linalg.py
        err = np.abs(q @ q.T - np.eye(512)).max()
    assert err < 1e-10
    assert np.array_equal(q, projection_matrix(shop_key))


def test_cosine_similarity_is_preserved(shop_key: bytes) -> None:
    s = Scrambler(shop_key)
    for i in range(20):
        a, b = _unit(i), _unit(i + 100)
        assert abs(float(a @ b) - float(s.scramble(a) @ s.scramble(b))) < 1e-6


def test_different_keys_give_unrelated_templates(shop_key: bytes) -> None:
    other = bytes(reversed(shop_key))
    a, b = Scrambler(shop_key), Scrambler(other)
    sims = [abs(float(a.scramble(_unit(i)) @ b.scramble(_unit(i)))) for i in range(50)]
    assert max(sims) < 0.25
    assert float(np.mean(sims)) < 0.08


def test_matrix_is_never_written_to_disk(tmp_path: Path, shop_key: bytes) -> None:
    cwd = os.getcwd()
    os.chdir(tmp_path)
    try:
        Scrambler(shop_key).scramble(_unit(1))
    finally:
        os.chdir(cwd)
    assert list(tmp_path.iterdir()) == []
