"""ULIDs with a kind prefix (docs/API_CONTRACT.md conventions)."""

from __future__ import annotations

import os
import threading
import time

_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_lock = threading.Lock()
_last = (0, 0)


def ulid(now_ms: int | None = None) -> str:
    """Monotonic ULID: ids generated in the same millisecond still sort in order."""
    global _last
    with _lock:
        ms = int(time.time() * 1000) if now_ms is None else now_ms
        last_ms, last_rand = _last
        if ms <= last_ms:
            ms, rand = last_ms, last_rand + 1
        else:
            rand = int.from_bytes(os.urandom(10), "big") >> 1  # leave headroom for increments
        _last = (ms, rand)
    value = (ms << 80) | (rand & ((1 << 80) - 1))
    out = []
    for _ in range(26):
        out.append(_ALPHABET[value & 31])
        value >>= 5
    return "".join(reversed(out))


def new_id(prefix: str) -> str:
    return f"{prefix}_{ulid()}"
