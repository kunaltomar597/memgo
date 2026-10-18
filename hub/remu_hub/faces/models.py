"""Model pack download and loading (InsightFace packs on ONNX Runtime CPU).

Licence: the InsightFace library is MIT, but the `buffalo_l` and `buffalo_sc`
model packs are for non-commercial research only. They are fine for
development; TASK-123 must close the licence before any shop goes live.

`buffalo_sc` has no age model, so the age gate always uses `genderage.onnx`
from `buffalo_l`.
"""

from __future__ import annotations

import hashlib
import logging
import shutil
import threading
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import onnxruntime as ort

log = logging.getLogger(__name__)

RELEASE = "https://github.com/deepinsight/insightface/releases/download/v0.7"
ZIP_SHA256 = {
    "buffalo_l": "80ffe37d8a5940d59a7384c201a2a38d4741f2f3c51eef46ebb28218a7b0ca2f",
    "buffalo_sc": "57d31b56b6ffa911c8a73cfc1707c73cab76efe7f13b675a05223bf42de47c72",
}
PACK_FILES = {
    "buffalo_l": {"det": "det_10g.onnx", "rec": "w600k_r50.onnx"},
    "buffalo_sc": {"det": "det_500m.onnx", "rec": "w600k_mbf.onnx"},
}
AGE_FILE = "genderage.onnx"  # always from buffalo_l
CACHE = Path.home() / ".cache" / "remu" / "models"


class ModelError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _fetch_zip(pack: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / f"{pack}.zip"
    if not target.exists() or _sha256(target) != ZIP_SHA256[pack]:
        url = f"{RELEASE}/{pack}.zip"
        log.info("downloading %s", url)
        tmp = target.with_suffix(".part")
        urllib.request.urlretrieve(url, tmp)  # noqa: S310 - pinned https release URL
        tmp.replace(target)
    digest = _sha256(target)
    if digest != ZIP_SHA256[pack]:
        target.unlink(missing_ok=True)
        raise ModelError(f"{pack}.zip checksum mismatch: {digest}")
    return target


def download(models_dir: Path, pack: str) -> Path:
    """Fetch `pack` (and the age model) into models_dir/pack. Returns the pack directory."""
    if pack not in PACK_FILES:
        raise ModelError(f"unknown model pack {pack}")
    dest = models_dir / pack
    dest.mkdir(parents=True, exist_ok=True)
    wanted = {pack: list(PACK_FILES[pack].values())}
    wanted.setdefault("buffalo_l", []).append(AGE_FILE)
    for zip_pack, names in wanted.items():
        missing = [n for n in names if not (dest / n).exists()]
        if not missing:
            continue
        archive = _fetch_zip(zip_pack)
        with zipfile.ZipFile(archive) as zf:
            members = {Path(m).name: m for m in zf.namelist()}
            for name in missing:
                with zf.open(members[name]) as src, (dest / name).open("wb") as out:
                    shutil.copyfileobj(src, out)
    manifest = {n: _sha256(dest / n) for n in sorted(p.name for p in dest.glob("*.onnx"))}
    (dest / "SHA256SUMS").write_text("".join(f"{v}  {k}\n" for k, v in manifest.items()))
    return dest


@dataclass
class ModelPack:
    pack: str
    det: Any  # onnxruntime.InferenceSession
    rec: Any
    age: Any
    model_version: str


_lock = threading.Lock()
_loaded: dict[tuple[str, str], ModelPack] = {}


def _session(path: Path) -> Any:
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    return ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])


def load(models_dir: Path, pack: str) -> ModelPack:
    """Load the pack once per process (singleton per directory and pack name)."""
    key = (str(models_dir.resolve()), pack)
    with _lock:
        if key in _loaded:
            return _loaded[key]
        dest = models_dir / pack
        files = PACK_FILES.get(pack)
        if files is None:
            raise ModelError(f"unknown model pack {pack}")
        paths = {k: dest / v for k, v in files.items()} | {"age": dest / AGE_FILE}
        absent = [str(p) for p in paths.values() if not p.exists()]
        if absent:
            raise ModelError(
                "model files missing, run `remu-hub models download`: " + ", ".join(absent)
            )
        version = f"{pack}:{_sha256(paths['rec'])[:8]}"
        mp = ModelPack(
            pack, _session(paths["det"]), _session(paths["rec"]), _session(paths["age"]), version
        )
        _loaded[key] = mp
        return mp


def available(models_dir: Path, pack: str) -> bool:
    files = PACK_FILES.get(pack, {})
    dest = models_dir / pack
    return bool(files) and all((dest / f).exists() for f in [*files.values(), AGE_FILE])
