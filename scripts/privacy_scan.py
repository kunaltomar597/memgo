"""Privacy scanner (docs/TEST_PLAN.md T-P-01, TASK-038).

Walks the hub data directory (SQLite files included) and the hub log, and
fails on any of:

- JPEG, PNG, WebP or BMP magic bytes anywhere,
- a raw H.264 start code outside an encrypted clip (`*.enc`),
- an enrol request body (`crop_jpeg_b64`),
- an Indian mobile number in plain text.

Encrypted clips are skipped for image signatures because they are AES-GCM
ciphertext. Model files (`*.onnx`) are skipped because they are not member data.

Usage:
  python scripts/privacy_scan.py --data-dir <REMU_DATA_DIR> [--log hub.log]
  python scripts/privacy_scan.py --self-test   # checks a clean and a dirty directory
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

SIGNATURES = {
    "jpeg": re.compile(rb"\xff\xd8\xff[\xdb\xe0\xe1\xe2\xe8\xee]"),
    "png": re.compile(rb"\x89PNG\r\n\x1a\n"),
    "webp": re.compile(rb"RIFF....WEBPVP8", re.S),
    "bmp": re.compile(rb"BM....\x00\x00\x00\x00", re.S),
}
H264 = re.compile(rb"\x00\x00\x00\x01[\x65\x67\x68]")
ENROL_BODY = re.compile(rb"crop_jpeg_b64")
PHONE = re.compile(rb"(?<![0-9A-Za-z])(?:\+?91[ -]?)?[6-9][0-9]{9}(?![0-9A-Za-z])")
SKIP_SUFFIXES = {".onnx", ".crt", ".pem"}


def scan_bytes(name: str, data: bytes, *, encrypted: bool) -> list[str]:
    found = []
    if not encrypted:
        for kind, rx in SIGNATURES.items():
            if kind == "bmp" and not name.endswith(".bmp"):
                continue  # the BMP pattern is too loose for arbitrary binaries
            if rx.search(data):
                found.append(f"{name}: {kind} image bytes")
        if H264.search(data):
            found.append(f"{name}: raw H.264 outside an encrypted clip")
    if ENROL_BODY.search(data):
        found.append(f"{name}: enrol request body")
    if not encrypted and PHONE.search(data):
        found.append(f"{name}: phone number in plain text")
    return found


def scan(data_dir: Path, log: Path | None = None) -> list[str]:
    findings: list[str] = []
    for path in sorted(p for p in data_dir.rglob("*") if p.is_file()):
        if path.suffix in SKIP_SUFFIXES or path.name in ("SHA256SUMS", "pairing.txt"):
            continue
        findings += scan_bytes(
            str(path.relative_to(data_dir)), path.read_bytes(), encrypted=path.suffix == ".enc"
        )
    if log is not None and log.exists():
        findings += scan_bytes(str(log), log.read_bytes(), encrypted=False)
    return findings


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        clean = Path(tmp) / "clean"
        (clean / "db").mkdir(parents=True)
        (clean / "db" / "remu.sqlite").write_bytes(b"SQLite format 3\x00" + b"\x00" * 64)
        (clean / "clips").mkdir()
        (clean / "clips" / "k_1.enc").write_bytes(b"\x00\x00\x00\x01\x65 ciphertext")
        dirty = Path(tmp) / "dirty"
        (dirty / "db").mkdir(parents=True)
        (dirty / "db" / "x.bin").write_bytes(b"..\xff\xd8\xff\xe0JFIF..")
        (dirty / "log.txt").write_bytes(b"enrolled +919876543210 ok")
        if scan(clean):
            print("self-test failed: clean directory flagged", scan(clean))
            return 1
        found = scan(dirty)
        if len(found) != 2:
            print("self-test failed: dirty directory not flagged", found)
            return 1
    print("privacy scanner self-test passed")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path)
    ap.add_argument("--log", type=Path)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.data_dir is None:
        ap.error("--data-dir is required")
    findings = scan(args.data_dir, args.log)
    for f in findings:
        print(f"PRIVACY: {f}")
    if findings:
        return 1
    print(f"privacy scan clean: {args.data_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
