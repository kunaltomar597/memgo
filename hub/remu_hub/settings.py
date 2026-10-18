"""Hub settings, read only from REMU_* environment variables (docs/RULES.md).

The defaults are fixed by the brief: threshold 0.45, 3 of 5 frames, 10-minute
cooldown, 30-day clips, 1095-day expiry. Changing a default needs an ADR.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DATA_DIR = Path.home() / ".local" / "share" / "remu-hub"


class SettingsError(ValueError):
    """A required variable is missing or a value does not parse."""


@dataclass(frozen=True)
class Settings:
    shop_id: str
    rtsp_gate_url: str
    rtsp_counter_url: str | None
    device_token: str | None
    cloud_url: str | None
    data_dir: Path
    model_pack: str
    match_threshold: float
    frames_required: int
    frames_window: int
    alert_cooldown_minutes: int
    clip_retention_days: int
    member_expiry_days: int
    log_level: str
    sentry_dsn: str | None
    api_host: str
    api_port: int
    min_face_px: int

    @property
    def db_path(self) -> Path:
        return self.data_dir / "db" / "remu.sqlite"

    @property
    def models_dir(self) -> Path:
        return self.data_dir / "models"

    @property
    def clips_dir(self) -> Path:
        return self.data_dir / "clips"

    @property
    def certs_dir(self) -> Path:
        return self.data_dir / "certs"

    @property
    def state_dir(self) -> Path:
        """Files needed before the encrypted data directory opens: device token, pairing code.

        On the Pi this is /var/lib/remu/state (REMU_STATE_DIR), outside the LUKS volume;
        in development it is the data directory.
        """
        explicit = os.environ.get("REMU_STATE_DIR")
        return Path(explicit) if explicit else self.data_dir

    @property
    def shop_key_path(self) -> Path:
        explicit = os.environ.get("REMU_SHOP_KEY_PATH")
        return Path(explicit) if explicit else self.state_dir / "shop.key"


def _get(env: Mapping[str, str], name: str) -> str | None:
    value = env.get(name)
    if value is None or value.strip() == "":
        return None
    return value.strip()


def _int(env: Mapping[str, str], name: str, default: int, minimum: int = 0) -> int:
    raw = _get(env, name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise SettingsError(f"{name} must be an integer, got {raw!r}") from exc
    if value < minimum:
        raise SettingsError(f"{name} must be at least {minimum}, got {value}")
    return value


def _float(env: Mapping[str, str], name: str, default: float) -> float:
    raw = _get(env, name)
    if raw is None:
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise SettingsError(f"{name} must be a number, got {raw!r}") from exc
    if not 0.0 < value < 1.0:
        raise SettingsError(f"{name} must be between 0 and 1, got {value}")
    return value


def load_settings(env: Mapping[str, str] | None = None, *, require_stream: bool = True) -> Settings:
    """Build settings from the environment.

    `require_stream=False` is for commands that do not open a camera
    (`models download`, `init`, `consent verify`); they still need a shop id
    only when they touch shop data, so the id falls back to "dev-shop".
    """
    env = os.environ if env is None else env
    shop_id = _get(env, "REMU_SHOP_ID")
    gate = _get(env, "REMU_RTSP_GATE_URL")
    missing = []
    if require_stream and shop_id is None:
        missing.append("REMU_SHOP_ID")
    if require_stream and gate is None:
        missing.append("REMU_RTSP_GATE_URL")
    if missing:
        raise SettingsError(
            "missing required setting(s): "
            + ", ".join(missing)
            + ". Copy the Hub section of .env.example to hub/.env and fill it in."
        )
    frames_required = _int(env, "REMU_FRAMES_REQUIRED", 3, minimum=1)
    frames_window = _int(env, "REMU_FRAMES_WINDOW", 5, minimum=1)
    if frames_required > frames_window:
        raise SettingsError("REMU_FRAMES_REQUIRED cannot exceed REMU_FRAMES_WINDOW")
    model_pack = _get(env, "REMU_MODEL_PACK") or "buffalo_l"
    if model_pack not in ("buffalo_l", "buffalo_sc"):
        raise SettingsError(f"REMU_MODEL_PACK must be buffalo_l or buffalo_sc, got {model_pack}")
    data_dir = Path(_get(env, "REMU_DATA_DIR") or DEFAULT_DATA_DIR).expanduser()
    return Settings(
        shop_id=shop_id or "dev-shop",
        rtsp_gate_url=gate or "",
        rtsp_counter_url=_get(env, "REMU_RTSP_COUNTER_URL"),
        device_token=_get(env, "REMU_DEVICE_TOKEN"),
        cloud_url=_get(env, "REMU_CLOUD_URL"),
        data_dir=data_dir,
        model_pack=model_pack,
        match_threshold=_float(env, "REMU_MATCH_THRESHOLD", 0.45),
        frames_required=frames_required,
        frames_window=frames_window,
        alert_cooldown_minutes=_int(env, "REMU_ALERT_COOLDOWN_MINUTES", 10),
        clip_retention_days=_int(env, "REMU_CLIP_RETENTION_DAYS", 30, minimum=1),
        member_expiry_days=_int(env, "REMU_MEMBER_EXPIRY_DAYS", 1095, minimum=1),
        log_level=(_get(env, "REMU_LOG_LEVEL") or "INFO").upper(),
        sentry_dsn=_get(env, "SENTRY_DSN"),
        api_host=_get(env, "REMU_API_HOST") or "0.0.0.0",  # noqa: S104 - LAN API by design
        api_port=_int(env, "REMU_API_PORT", 8443, minimum=1),
        min_face_px=_int(env, "REMU_MIN_FACE_PX", 40, minimum=8),
    )
