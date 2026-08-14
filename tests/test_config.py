"""Configuration loading, merging and validation."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import pytest

import hymacro.config as config_module
from hymacro.config import (
    DEFAULTS,
    Config,
    ConfigError,
    assign,
    ensure_config_exists,
    lookup,
    merge_defaults,
    resolve_config_path,
    validate,
)

REPO_ROOT = Path(__file__).resolve().parents[1]


def write_config(tmp_path: Path, data: dict[str, Any]) -> Path:
    path = tmp_path / "config.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_merge_does_not_mutate_the_defaults(tmp_path: Path) -> None:
    original = json.loads(json.dumps(DEFAULTS))
    merge_defaults({"general": {"chat_key": "y"}})
    assert original == DEFAULTS


def test_partial_config_inherits_the_defaults(tmp_path: Path) -> None:
    path = write_config(tmp_path, {"macros": {"nether_wart": {"forward_seconds": 42}}})
    config = Config(path, auto_create=False)

    assert config.number("macros", "nether_wart", "forward_seconds") == 42
    assert config.text("keybinds", "stop") == "f12"
    assert config.flag("safety", "require_window_focus") is True


def test_default_is_keyword_only(tmp_path: Path) -> None:
    config = Config(write_config(tmp_path, DEFAULTS), auto_create=False)

    assert config.number("general", "chat_open_seconds", default=99) == 0.12
    assert config.get("general", "missing", default=42) == 42


def test_missing_key_without_default_raises(tmp_path: Path) -> None:
    config = Config(write_config(tmp_path, DEFAULTS), auto_create=False)

    with pytest.raises(ConfigError, match="Missing configuration key"):
        config.get("general", "missing")


def test_duplicated_keybinds_are_rejected(tmp_path: Path) -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["keybinds"]["stop"] = "f8"

    with pytest.raises(ConfigError, match="Duplicated keybinds"):
        Config(write_config(tmp_path, data), auto_create=False)


def test_unknown_key_is_rejected(tmp_path: Path) -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["macros"]["cocoa_beans"]["keys"] = ["w", "d", "s", "banana"]

    with pytest.raises(ConfigError, match="unsupported key"):
        Config(write_config(tmp_path, data), auto_create=False)


def test_broken_json_reports_the_line(tmp_path: Path) -> None:
    path = tmp_path / "config.json"
    path.write_text('{"macros": }', encoding="utf-8")

    with pytest.raises(ConfigError, match="Invalid JSON"):
        Config(path, auto_create=False)


def test_frozen_never_falls_back_to_the_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    exe_dir = tmp_path / "app"
    exe_dir.mkdir()
    other = tmp_path / "other"
    other.mkdir()
    (other / "config.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(exe_dir / "HyMacro.exe"))
    monkeypatch.chdir(other)
    monkeypatch.delenv("HYMACRO_CONFIG", raising=False)

    assert resolve_config_path() == exe_dir / "config.json"


def test_the_app_directory_wins_over_the_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    app = tmp_path / "app"
    app.mkdir()
    (app / "config.json").write_text("{}", encoding="utf-8")
    other = tmp_path / "other"
    other.mkdir()
    (other / "config.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", False, raising=False)
    monkeypatch.setattr(config_module, "app_dir", lambda: app)
    monkeypatch.chdir(other)
    monkeypatch.delenv("HYMACRO_CONFIG", raising=False)

    assert resolve_config_path() == app / "config.json"


def test_the_environment_variable_wins(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    external = tmp_path / "elsewhere.json"
    external.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("HYMACRO_CONFIG", str(external))

    assert resolve_config_path() == external.resolve()


def test_missing_config_is_created_from_the_template(tmp_path: Path) -> None:
    target = tmp_path / "config.json"

    assert ensure_config_exists(target) is True
    assert ensure_config_exists(target) is False
    Config(target, auto_create=False)


def test_the_shipped_config_is_valid() -> None:
    Config(REPO_ROOT / "config.json", auto_create=False)


def test_the_packaged_template_matches_the_shipped_config() -> None:
    shipped = json.loads((REPO_ROOT / "config.json").read_text(encoding="utf-8"))
    packaged = json.loads(
        (REPO_ROOT / "src" / "hymacro" / "data" / "config.default.json").read_text(encoding="utf-8")
    )
    assert shipped == packaged


def test_every_duration_is_expressed_in_seconds() -> None:
    """Mixing milliseconds and seconds in one file invites mistakes."""
    text = (REPO_ROOT / "config.json").read_text(encoding="utf-8")
    assert "_ms" not in text
    assert "_minutes" not in text


def test_lookup_and_assign_walk_nested_keys() -> None:
    data: dict[str, Any] = {}
    assign(data, ("a", "b", "c"), 3)

    assert data == {"a": {"b": {"c": 3}}}
    assert lookup(data, "a", "b", "c") == 3
    assert lookup(data, "a", "missing") is None


def test_validate_rejects_a_zero_step() -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["macros"]["cocoa_beans"]["step_seconds"] = 0

    with pytest.raises(ConfigError, match="step_seconds"):
        validate(data)


def test_both_route_macros_actually_move() -> None:
    """A zero outward leg means the macro twitches without going anywhere."""
    config = Config(REPO_ROOT / "config.json", auto_create=False)
    for macro in ("nether_wart",):
        assert config.number("macros", macro, "forward_seconds") > 0
