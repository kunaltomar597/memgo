"""Evaluation harness: true accept rate at a false accept rate of 0.1 percent.

Reads tests/eval/data/pairs.csv (columns a,b,same,tags, paths relative to
--root), embeds every image with the hub's own modules, and reports for the
whole set and per subset tag:

- TAR at FAR 0.1 percent and the threshold that gives it,
- TAR and FAR at the deployed threshold (REMU_MATCH_THRESHOLD, 0.45),
- the same with scrambled templates, which must match raw within 0.1 points.

Pass bars (docs/TEST_PLAN.md section 10, TASK-021 and TASK-022):
- at 0.45: FAR <= 0.1 percent and TAR >= 95 percent on `base`,
- scrambled TAR within 0.1 points of raw,
- `age_gap` TAR >= 85 percent at 0.45,
- no subset more than 0.5 points below tests/eval/baseline.json.

Exit codes: 0 pass, 1 a bar failed, 3 a required subset is missing (the
report is still written). No image, crop or embedding is ever written; the
report holds numbers only.

Usage: cd hub && uv run python ../tests/eval/run_eval.py --root ~/.cache/remu/lfw [--smoke]
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import secrets
import sys
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from remu_hub.faces import models
from remu_hub.faces.scramble import Scrambler
from remu_hub.pipeline import FaceEngine
from remu_hub.settings import load_settings

HERE = Path(__file__).resolve().parent
REQUIRED_SUBSETS = ("base", "age_gap")
FAR_TARGET = 0.001


def transform(img: np.ndarray, tag: str) -> np.ndarray:
    if tag == "low_light":
        dark = cv2.convertScaleAbs(img, alpha=0.35, beta=-5)
        noise = np.random.default_rng(0).normal(0, 6, dark.shape)
        return np.clip(dark.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    if tag == "angle":
        h, w = img.shape[:2]
        m = cv2.getRotationMatrix2D((w / 2, h / 2), 20, 1.0)
        rotated = cv2.warpAffine(img, m, (w, h), borderMode=cv2.BORDER_REFLECT)
        squeezed = cv2.resize(rotated, (int(w * 0.8), h))
        pad = (w - squeezed.shape[1]) // 2
        return cv2.copyMakeBorder(
            squeezed, 0, 0, pad, w - squeezed.shape[1] - pad, cv2.BORDER_REFLECT
        )
    return img


def tar_at_far(gen: np.ndarray, imp: np.ndarray, far: float) -> tuple[float, float]:
    if len(imp) == 0 or len(gen) == 0:
        return float("nan"), float("nan")
    desc = np.sort(imp)[::-1]
    allowed = int(np.floor(far * len(desc)))
    thr = float(desc[allowed]) + 1e-9 if allowed < len(desc) else -1.0
    return float(np.mean(gen >= thr)), thr


def metrics(gen: np.ndarray, imp: np.ndarray, threshold: float) -> dict[str, float | int]:
    tar_far, thr_far = tar_at_far(gen, imp, FAR_TARGET)
    return {
        "genuine_pairs": len(gen),
        "impostor_pairs": len(imp),
        "tar_at_far_0_1pct": round(tar_far, 4),
        "threshold_at_far_0_1pct": round(thr_far, 4),
        "tar_at_deployed": round(float(np.mean(gen >= threshold)) if len(gen) else float("nan"), 4),
        "far_at_deployed": round(float(np.mean(imp >= threshold)) if len(imp) else float("nan"), 6),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path(os.environ.get("REMU_EVAL_ROOT", "")))
    ap.add_argument("--pairs", type=Path, default=HERE / "data" / "pairs.csv")
    ap.add_argument("--smoke", action="store_true", help="first 100 base pairs only")
    ap.add_argument("--baseline", type=Path, default=HERE / "baseline.json")
    ap.add_argument("--out", type=Path, default=HERE / "reports")
    args = ap.parse_args()
    if not args.pairs.exists():
        print(f"{args.pairs} missing; run tests/eval/prepare_lfw.py first", file=sys.stderr)
        return 3
    settings = load_settings(require_stream=False)
    pack = models.load(settings.models_dir, settings.model_pack)
    engine = FaceEngine(pack, Scrambler(secrets.token_bytes(32)), min_face_px=20)
    threshold = settings.match_threshold

    with args.pairs.open() as fh:
        rows = list(csv.DictReader(fh))
    if args.smoke:
        same = [r for r in rows if r["tags"] == "base" and r["same"] == "1"][:50]
        diff = [r for r in rows if r["tags"] == "base" and r["same"] == "0"][:50]
        rows = same + diff

    cache: dict[tuple[str, str], tuple[np.ndarray, np.ndarray] | None] = {}

    def vectors(rel: str, tag: str) -> tuple[np.ndarray, np.ndarray] | None:
        key = (rel, tag)
        if key not in cache:
            img = cv2.imread(str(args.root / rel))
            if img is None:
                cache[key] = None
            else:
                img = transform(img, tag)
                dets = engine.detect(img, min_face_px=20)
                if not dets:
                    cache[key] = None
                else:
                    from remu_hub.faces.embed import embed

                    raw = embed(pack.rec, img, dets[0].landmarks)
                    cache[key] = (raw, engine.scrambler.scramble(raw))
        return cache[key]

    scores: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    no_face = 0
    for r in rows:
        tag = r["tags"]
        va = vectors(r["a"], "base")
        vb = vectors(r["b"], tag)
        kind = "gen" if r["same"] == "1" else "imp"
        if va is None or vb is None:
            no_face += 1
            raw = scr = -1.0
        else:
            raw = float(va[0] @ vb[0])
            scr = float(va[1] @ vb[1])
        scores[tag][f"{kind}_raw"].append(raw)
        scores[tag][f"{kind}_scr"].append(scr)
    cache.clear()

    report: dict[str, object] = {
        "date": dt.date.today().isoformat(),
        "model_version": pack.model_version,
        "deployed_threshold": threshold,
        "pairs": len(rows),
        "failed_to_detect": no_face,
        "smoke": args.smoke,
        "subsets": {},
    }
    failures: list[str] = []
    subsets: dict[str, dict[str, object]] = {}
    for tag, s in sorted(scores.items()):
        raw = metrics(np.array(s["gen_raw"]), np.array(s["imp_raw"]), threshold)
        scr = metrics(np.array(s["gen_scr"]), np.array(s["imp_scr"]), threshold)
        subsets[tag] = {"raw": raw, "scrambled": scr}
        diff = abs(float(raw["tar_at_deployed"]) - float(scr["tar_at_deployed"])) * 100
        if diff > 0.1:
            failures.append(f"{tag}: scrambled TAR differs from raw by {diff:.2f} points")
    report["subsets"] = subsets
    base = subsets.get("base", {}).get("raw") if "base" in subsets else None
    if isinstance(base, dict):
        if float(base["far_at_deployed"]) > FAR_TARGET:
            failures.append(f"base: FAR at {threshold} is {base['far_at_deployed']}")
        if float(base["tar_at_deployed"]) < 0.95:
            failures.append(f"base: TAR at {threshold} is {base['tar_at_deployed']}")
    age = subsets.get("age_gap", {}).get("raw") if "age_gap" in subsets else None
    if isinstance(age, dict) and float(age["tar_at_deployed"]) < 0.85:
        failures.append(f"age_gap: TAR at {threshold} is {age['tar_at_deployed']}")
    if args.baseline.exists() and not args.smoke:
        old = json.loads(args.baseline.read_text()).get("subsets", {})
        for tag, cur in subsets.items():
            prev = old.get(tag, {}).get("raw", {}).get("tar_at_deployed")
            now = cur["raw"]["tar_at_deployed"]  # type: ignore[index]
            if prev is not None and (prev - now) * 100 > 0.5:
                failures.append(f"{tag}: TAR dropped {(prev - now) * 100:.2f} points vs baseline")
    missing = [t for t in REQUIRED_SUBSETS if t not in subsets and not args.smoke]
    report["failures"] = failures
    report["missing_subsets"] = missing
    report["status"] = "fail" if failures else ("incomplete" if missing else "pass")
    args.out.mkdir(parents=True, exist_ok=True)
    out = args.out / f"{report['date']}{'-smoke' if args.smoke else ''}.json"
    out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"report: {out}")
    if failures:
        return 1
    return 3 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
