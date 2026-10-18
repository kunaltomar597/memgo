"""Encrypted data directory (TASK-101).

On the Pi, REMU_DATA_DIR is a LUKS container (a file-backed volume opened
with cryptsetup). Its passphrase is HKDF(shop_key || unlock_token), so the
container opens only on a hub that has both the shop key (root-only file on
the unencrypted root) and an unlock token the cloud gives only to a paired,
unrevoked device. The token is held in memory just long enough to open the
volume and is never written to disk. A hub that cannot reach the cloud at
boot stays locked and retries every 60 seconds; once open it keeps running
through later internet loss.

Honest limits (docs/SECURITY.md): a powered, unlocked hub is readable by
root; the shop key file sits on the unencrypted root, so a copied SD card plus
a live device token can fetch an unlock token until the device is revoked.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

log = logging.getLogger(__name__)
RETRY_S = 60.0
MAPPER = "remu-data"
INFO = b"remu/luks-passphrase/v1"

Runner = Callable[[Sequence[str], bytes | None], int]


def passphrase(shop_key: bytes, unlock_token: str) -> bytes:
    material = shop_key + unlock_token.encode()
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=INFO).derive(material)


def _run(cmd: Sequence[str], stdin: bytes | None) -> int:
    return subprocess.run(list(cmd), input=stdin, capture_output=True, check=False).returncode


@dataclass
class Volume:
    container: Path  # e.g. /var/lib/remu/data.luks
    mountpoint: Path  # REMU_DATA_DIR
    runner: Runner = _run

    def is_mounted(self) -> bool:
        return self.runner(["mountpoint", "-q", str(self.mountpoint)], None) == 0

    def create(self, key: bytes, size_mb: int = 4096) -> None:
        if self.container.exists():
            raise FileExistsError(self.container)
        if self.runner(["fallocate", "-l", f"{size_mb}M", str(self.container)], None) != 0:
            raise RuntimeError("fallocate failed")
        steps = [
            (
                [
                    "cryptsetup",
                    "luksFormat",
                    "--type",
                    "luks2",
                    "--batch-mode",
                    "--key-file",
                    "-",
                    str(self.container),
                ],
                key,
            ),
            (["cryptsetup", "open", "--key-file", "-", str(self.container), MAPPER], key),
            (["mkfs.ext4", "-q", f"/dev/mapper/{MAPPER}"], None),
        ]
        for cmd, stdin in steps:
            if self.runner(cmd, stdin) != 0:
                raise RuntimeError(f"{cmd[0]} {cmd[1]} failed")
        self.mount()

    def open(self, key: bytes) -> None:
        if self.is_mounted():
            return
        if (
            self.runner(["cryptsetup", "open", "--key-file", "-", str(self.container), MAPPER], key)
            != 0
        ):
            raise RuntimeError("cryptsetup open failed (wrong key or revoked device)")
        self.mount()

    def mount(self) -> None:
        self.mountpoint.mkdir(parents=True, exist_ok=True)
        if self.runner(["mount", f"/dev/mapper/{MAPPER}", str(self.mountpoint)], None) != 0:
            raise RuntimeError("mount failed")

    def close(self) -> None:
        self.runner(["umount", str(self.mountpoint)], None)
        self.runner(["cryptsetup", "close", MAPPER], None)


def unlock_at_boot(
    volume: Volume,
    shop_key: bytes,
    fetch_token: Callable[[], str],
    *,
    sleep: Callable[[float], None] = time.sleep,
    max_tries: int | None = None,
    on_locked: Callable[[bool], None] | None = None,
) -> bool:
    """Fetch the unlock token and open the volume, retrying every 60 s while offline."""
    tries = 0
    while max_tries is None or tries < max_tries:
        tries += 1
        try:
            token = fetch_token()
            key = passphrase(shop_key, token)
            del token
            volume.open(key)
            del key
            if on_locked:
                on_locked(False)
            return True
        except Exception as exc:
            log.warning("data directory locked (%s); retrying in %.0fs", exc, RETRY_S)
            if on_locked:
                on_locked(True)
            sleep(RETRY_S)
    return False


def available() -> bool:
    return shutil.which("cryptsetup") is not None
