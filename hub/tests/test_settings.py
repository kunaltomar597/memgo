from pathlib import Path

import pytest

from remu_hub.settings import SettingsError, load_settings

BASE = {"REMU_SHOP_ID": "shop-1", "REMU_RTSP_GATE_URL": "rtsp://127.0.0.1:8554/gate"}


def test_defaults_match_the_brief() -> None:
    s = load_settings(BASE)
    assert s.match_threshold == 0.45
    assert (s.frames_required, s.frames_window) == (3, 5)
    assert s.alert_cooldown_minutes == 10
    assert s.clip_retention_days == 30
    assert s.member_expiry_days == 1095
    assert s.model_pack == "buffalo_l"
    assert s.log_level == "INFO"


def test_overrides(tmp_path: Path) -> None:
    s = load_settings(
        BASE
        | {
            "REMU_MATCH_THRESHOLD": "0.5",
            "REMU_FRAMES_REQUIRED": "2",
            "REMU_DATA_DIR": str(tmp_path),
            "REMU_MODEL_PACK": "buffalo_sc",
        }
    )
    assert s.match_threshold == 0.5
    assert s.frames_required == 2
    assert s.db_path == tmp_path / "db" / "remu.sqlite"
    assert s.model_pack == "buffalo_sc"


@pytest.mark.parametrize("missing", ["REMU_SHOP_ID", "REMU_RTSP_GATE_URL"])
def test_missing_required_fails_fast(missing: str) -> None:
    env = {k: v for k, v in BASE.items() if k != missing}
    with pytest.raises(SettingsError, match=missing):
        load_settings(env)


@pytest.mark.parametrize(
    "env",
    [
        {"REMU_MATCH_THRESHOLD": "1.5"},
        {"REMU_FRAMES_REQUIRED": "x"},
        {"REMU_FRAMES_REQUIRED": "6"},
        {"REMU_MODEL_PACK": "other"},
    ],
)
def test_bad_values(env: dict[str, str]) -> None:
    with pytest.raises(SettingsError):
        load_settings(BASE | env)


def test_commands_without_a_stream_do_not_need_one() -> None:
    assert load_settings({}, require_stream=False).shop_id == "dev-shop"
