"""Convert an LFW download into the harness pair list.

LFW (Labeled Faces in the Wild, UMass Amherst) is used for development only,
locally and uncommitted. OPEN: confirm the LFW terms allow this use before
any report is shared outside the team (TASK-021).

Writes tests/eval/data/pairs.csv with columns a,b,same,tags. Paths are
relative to --root. Synthetic subsets are derived from the same pairs:
`low_light` darkens and adds noise to image b; `angle` rotates image b by 20
degrees and squeezes it horizontally to imitate a turned head. `age_gap` has
no source in LFW and is left out; the harness reports it as missing.

Usage: python tests/eval/prepare_lfw.py --root ~/.cache/remu/lfw
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

OUT = Path(__file__).resolve().parent / "data" / "pairs.csv"


def img(name: str, idx: str) -> str:
    return f"lfw/{name}/{name}_{int(idx):04d}.jpg"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, required=True, help="folder with pairs.txt and lfw/")
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()
    lines = (args.root / "pairs.txt").read_text().splitlines()
    rows: list[tuple[str, str, int, str]] = []
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) == 3:
            rows.append((img(parts[0], parts[1]), img(parts[0], parts[2]), 1, "base"))
        elif len(parts) == 4:
            rows.append((img(parts[0], parts[1]), img(parts[2], parts[3]), 0, "base"))
    base = list(rows)
    for a, b, same, _ in base[::5]:
        rows.append((a, b, same, "low_light"))
        rows.append((a, b, same, "angle"))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["a", "b", "same", "tags"])
        w.writerows(rows)
    print(f"wrote {len(rows)} pairs to {args.out} (root {args.root})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
