"""Matrix products with a real finiteness check.

numpy 2.x on macOS Accelerate raises spurious floating-point warnings from
float32 matmul even when the result is exact. We silence the warning flags
around the product and check the result instead, so a real overflow or NaN
still raises.
"""

from __future__ import annotations

import numpy as np
from numpy.typing import NDArray


def matmul(a: NDArray[np.float32], b: NDArray[np.float32]) -> NDArray[np.float32]:
    with np.errstate(over="ignore", divide="ignore", invalid="ignore", under="ignore"):
        out: NDArray[np.float32] = np.asarray(a @ b, dtype=np.float32)
    if not np.isfinite(out).all():
        raise FloatingPointError("non-finite matrix product")
    return out
