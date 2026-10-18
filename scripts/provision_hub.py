"""Register a freshly built hub with the cloud and print its sticker label (TASK-105).

Reads the pairing code the hub generated at `remu-hub init` from pairing.txt,
registers the device (`POST /api/ops/devices`, generation 1) and prints the
label for the kit. Run by the Remu team with OPS_API_KEY in the environment.

Usage: python scripts/provision_hub.py --pairing /var/lib/remu/state/pairing.txt \
         --serial <pi serial> --cloud https://remu.example.in
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairing", type=Path, required=True)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--cloud", default=os.environ.get("REMU_CLOUD_URL", ""))
    args = ap.parse_args()
    key = os.environ.get("OPS_API_KEY")
    if not key or not args.cloud:
        print("OPS_API_KEY and --cloud (or REMU_CLOUD_URL) are required", file=sys.stderr)
        return 2
    code = args.pairing.read_text().strip()
    req = urllib.request.Request(  # noqa: S310 - https URL given by the operator
        args.cloud.rstrip("/") + "/api/ops/devices",
        data=json.dumps({"pairing_code": code, "serial": args.serial}).encode(),
        headers={"Content-Type": "application/json", "X-Remu-Ops-Key": key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:  # noqa: S310
        device = json.loads(resp.read())
    print("+--------------------------------+")
    print("|  Remu hub                      |")
    print(f"|  Pairing code: {code:<16}|")
    print(f"|  Device: {device.get('device_id', '?'):<22}|")
    print("|  Owner app > Add hub > code    |")
    print("+--------------------------------+")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
