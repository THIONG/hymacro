"""Background input: messages posted to the game window."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from hymacro.background import BackgroundBackend, _key_lparam
from hymacro.config import DEFAULTS, Config
from hymacro.controller import MacroController
from hymacro.safety import SafetyLimits
from hymacro.winput import InputBackend


def config_with_mode(tmp_path: Path, mode: str) -> Config:
    data = json.loads(json.dumps(DEFAULTS))
    data["general"]["input_mode"] = mode
    path = tmp_path / "config.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return Config(path, auto_create=False)


def test_the_mode_picks_the_backend(tmp_path: Path) -> None:
    assert isinstance(MacroController(config_with_mode(tmp_path, "foreground")).input, InputBackend)
    assert isinstance(MacroController(config_with_mode(tmp_path, "background")).input, BackgroundBackend)


def test_an_unknown_mode_is_rejected(tmp_path: Path) -> None:
    from hymacro.config import ConfigError

    with pytest.raises(ConfigError, match="input_mode"):
        config_with_mode(tmp_path, "telepathy")


def test_background_turns_off_the_checks_that_would_always_fire(tmp_path: Path) -> None:
    """In background mode Minecraft is not in front and the mouse is in use."""
    limits = SafetyLimits.from_config(config_with_mode(tmp_path, "background"))

    assert limits.require_focus is False
    assert limits.mouse_failsafe is False
    assert limits.stop_key == "f12", "the stop key must keep working"


def test_foreground_keeps_the_checks(tmp_path: Path) -> None:
    limits = SafetyLimits.from_config(config_with_mode(tmp_path, "foreground"))

    assert limits.require_focus is True
    assert limits.mouse_failsafe is True


def test_the_key_lparam_follows_the_windows_layout() -> None:
    """Bits 16-23 carry the scancode; 30 and 31 mark a release."""
    down = _key_lparam(0x11, up=False, extended=False)
    up = _key_lparam(0x11, up=True, extended=False)

    assert down & 0xFFFF == 1
    assert (down >> 16) & 0xFF == 0x11
    assert down >> 30 == 0
    assert (up >> 30) & 0b11 == 0b11
    assert (_key_lparam(0x11, up=False, extended=True) >> 24) & 1 == 1


def test_a_missing_window_is_reported_clearly() -> None:
    from hymacro.winput import InputError

    backend = BackgroundBackend("a window that does not exist anywhere")
    with pytest.raises(InputError, match="No visible window"):
        backend.key_down("w")


def test_nothing_is_left_held_after_a_failure() -> None:
    from hymacro.winput import InputError

    backend = BackgroundBackend("a window that does not exist anywhere")
    with pytest.raises(InputError):
        backend.key_down("w")

    assert not backend.has_pending_input
    backend.release_all()
