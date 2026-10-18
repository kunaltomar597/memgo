"""Similarity-transform helpers shared by alignment and the age model."""

from __future__ import annotations

import numpy as np
from numpy.typing import NDArray

# The standard ArcFace 112 by 112 landmark template.
ARCFACE_DST = np.array(
    [
        [38.2946, 51.6963],
        [73.5318, 51.5014],
        [56.0252, 71.7366],
        [41.5493, 92.3655],
        [70.7299, 92.2041],
    ],
    dtype=np.float64,
)


def similarity_transform(src: NDArray[np.float64], dst: NDArray[np.float64]) -> NDArray[np.float64]:
    """Least-squares similarity transform (Umeyama, 1991) mapping src points onto dst.

    Returns the 2 by 3 affine matrix for cv2.warpAffine.
    """
    n = src.shape[0]
    src_mean = src.mean(axis=0)
    dst_mean = dst.mean(axis=0)
    src_c = src - src_mean
    dst_c = dst - dst_mean
    cov = dst_c.T @ src_c / n
    d = np.ones(2)
    if np.linalg.det(cov) < 0:
        d[-1] = -1.0
    u, s, vt = np.linalg.svd(cov)
    r = u @ np.diag(d) @ vt
    var_src = (src_c**2).sum() / n
    scale = float((s * d).sum() / var_src) if var_src > 0 else 1.0
    t = dst_mean - scale * (r @ src_mean)
    m = np.zeros((2, 3), dtype=np.float64)
    m[:, :2] = scale * r
    m[:, 2] = t
    return m


def center_scale_transform(
    center: tuple[float, float], out_size: int, scale: float
) -> NDArray[np.float64]:
    """Scale about `center` and move it to the middle of an out_size square."""
    cx, cy = center
    return np.array(
        [[scale, 0.0, out_size / 2 - scale * cx], [0.0, scale, out_size / 2 - scale * cy]],
        dtype=np.float64,
    )
