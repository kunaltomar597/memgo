"""The `remu-hub` command line."""

from __future__ import annotations

import argparse
import logging
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from remu_hub import __version__
from remu_hub.settings import Settings, SettingsError, load_settings

log = logging.getLogger("remu_hub")

Handler = Callable[[argparse.Namespace], int]
_COMMANDS: dict[tuple[str, ...], Handler] = {}


def command(*path: str) -> Callable[[Handler], Handler]:
    def wrap(fn: Handler) -> Handler:
        _COMMANDS[path] = fn
        return fn

    return wrap


def _settings(require_stream: bool = False) -> Settings:
    return load_settings(require_stream=require_stream)


@command("models", "download")
def cmd_models_download(args: argparse.Namespace) -> int:
    from remu_hub.faces import models

    s = _settings()
    packs = [s.model_pack] if not args.all else ["buffalo_l", "buffalo_sc"]
    for pack in packs:
        dest = models.download(s.models_dir, pack)
        mp = models.load(s.models_dir, pack)
        print(f"{pack}: {dest} ({mp.model_version})")
    return 0


@command("demo")
def cmd_demo(args: argparse.Namespace) -> int:
    from remu_hub.demo import run_demo

    return run_demo(
        _settings(),
        video=Path(args.video),
        enrol_at=args.enrol_at,
        url=args.url,
        max_seconds=args.seconds,
    )


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="remu-hub", description="Remu hub")
    p.add_argument("--version", action="version", version=f"remu-hub {__version__}")
    sub = p.add_subparsers(dest="cmd")

    models = sub.add_parser("models", help="model pack management").add_subparsers(dest="sub")
    dl = models.add_parser("download", help="download and verify the model pack")
    dl.add_argument("--all", action="store_true", help="download buffalo_l and buffalo_sc")

    demo = sub.add_parser("demo", help="console demo against the fake camera")
    root = Path(__file__).resolve().parents[2]
    demo.add_argument("--video", default=str(root / "tests/eval/samples/gate_fixture.mp4"))
    demo.add_argument("--enrol-at", type=float, default=6.0, help="second of the video to enrol")
    demo.add_argument("--url", default="rtsp://127.0.0.1:8554/gate")
    demo.add_argument("--seconds", type=float, default=60.0, help="stop after this long")

    from remu_hub import cli_more

    cli_more.register(sub)
    return p


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    key = tuple(v for v in (getattr(args, "cmd", None), getattr(args, "sub", None)) if v)
    handler = _COMMANDS.get(key)
    if handler is None:
        parser.print_help()
        return 2
    try:
        return handler(args)
    except SettingsError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
