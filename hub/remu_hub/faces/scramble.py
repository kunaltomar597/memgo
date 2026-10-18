"""Cancelable templates: a per-shop random orthogonal projection.

seed = HKDF-SHA256(shop_key, info="remu/template-projection/v1"); a ChaCha20
stream keyed by the seed fills a 512 by 512 Gaussian matrix; its QR
decomposition with the signs of R's diagonal fixed gives a unique orthogonal
Q. Q is computed once per process and never written to disk.

An orthogonal rotation keeps every angle, so cosine matching is unchanged,
and a template is useless against another shop's (different Q). This is a
safeguard, not a substitute for consent (docs/ARCHITECTURE.md section 8).
"""

from __future__ import annotations

import threading

import numpy as np
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
from numpy.typing import NDArray

from remu_hub.faces.embed import EMBED_DIM
from remu_hub.faces.linalg import matmul
from remu_hub.keys import INFO_PROJECTION, derive

FloatArray = NDArray[np.float32]


def _gaussian(seed: bytes, n: int) -> NDArray[np.float64]:
    if n % 2:
        raise ValueError("n must be even")
    stream = Cipher(algorithms.ChaCha20(seed, b"\x00" * 16), mode=None).encryptor()
    raw = stream.update(b"\x00" * (4 * n))  # n uniforms give n Gaussians (Box-Muller pairs)
    u = np.frombuffer(raw, dtype="<u4").astype(np.float64)
    u = (u + 0.5) / 2**32  # strictly inside (0, 1)
    u1, u2 = u[0::2], u[1::2]
    r = np.sqrt(-2.0 * np.log(u1))
    out: NDArray[np.float64] = np.concatenate(
        [r * np.cos(2 * np.pi * u2), r * np.sin(2 * np.pi * u2)]
    )
    return out


def projection_matrix(shop_key: bytes, dim: int = EMBED_DIM) -> NDArray[np.float64]:
    seed = derive(shop_key, INFO_PROJECTION)
    g = _gaussian(seed, dim * dim).reshape(dim, dim)
    with np.errstate(over="ignore", divide="ignore", invalid="ignore"):
        q, r = np.linalg.qr(g)
    if not np.isfinite(q).all():
        raise FloatingPointError("non-finite projection matrix")
    signs = np.sign(np.diag(r))
    signs[signs == 0] = 1.0
    out: NDArray[np.float64] = q * signs
    return out


class Scrambler:
    def __init__(self, shop_key: bytes) -> None:
        self._q = projection_matrix(shop_key).astype(np.float32)
        self._q.setflags(write=False)

    def scramble(self, embedding: FloatArray) -> FloatArray:
        return matmul(self._q, embedding.astype(np.float32))


_lock = threading.Lock()
_cache: dict[bytes, Scrambler] = {}


def scrambler_for(shop_key: bytes) -> Scrambler:
    with _lock:
        if shop_key not in _cache:
            _cache[shop_key] = Scrambler(shop_key)
        return _cache[shop_key]
