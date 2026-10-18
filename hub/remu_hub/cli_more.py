"""Subcommands added in Phase 2 and later register here."""

from __future__ import annotations

import argparse
from typing import Any


def register(sub: Any) -> None:
    del sub


def unused(_: argparse.Namespace) -> int:
    return 0
