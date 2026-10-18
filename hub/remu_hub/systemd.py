"""Minimal sd_notify for the systemd watchdog (TASK-099). No dependency on libsystemd."""

from __future__ import annotations

import os
import socket
import threading
import time
from collections.abc import Callable


def notify(message: str) -> bool:
    address = os.environ.get("NOTIFY_SOCKET")
    if not address:
        return False
    if address.startswith("@"):
        address = "\0" + address[1:]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as sock:
        sock.connect(address)
        sock.sendall(message.encode())
    return True


def start_watchdog(alive: Callable[[], bool] | None = None) -> None:
    usec = os.environ.get("WATCHDOG_USEC")
    notify("READY=1")
    if not usec:
        return
    interval = int(usec) / 1_000_000 / 3

    def loop() -> None:
        while True:
            if alive is None or alive():
                notify("WATCHDOG=1")
            time.sleep(interval)

    threading.Thread(target=loop, name="sd-watchdog", daemon=True).start()
