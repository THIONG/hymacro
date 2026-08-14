"""Failsafes."""

from __future__ import annotations

import threading
import time

import pytest

import hymacro.safety as safety_module
from hymacro.safety import SafetyGuard, SafetyLimits, window_matches


def make_limits(**overrides: object) -> SafetyLimits:
    base: dict[str, object] = {
        "require_focus": False,
        "title_contains": "Minecraft",
        "mouse_failsafe": False,
        "mouse_threshold_px": 100,
        "max_session_seconds": 0.0,
        "interval_seconds": 0.01,
        "stop_key": "",
    }
    base.update(overrides)
    return SafetyLimits(**base)  # type: ignore[arg-type]


def test_window_matching_ignores_case() -> None:
    assert window_matches("Minecraft 1.8.9", "minecraft")
    assert window_matches("anything", "")
    assert not window_matches("Google Chrome", "Minecraft")
    assert not window_matches("", "Minecraft")


def test_the_session_limit_fires() -> None:
    fired = threading.Event()
    reasons: list[str] = []

    def on_violation(why: str) -> None:
        reasons.append(why)
        fired.set()

    guard = SafetyGuard(make_limits(max_session_seconds=0.05), on_violation)
    guard.arm()
    try:
        assert fired.wait(timeout=2.0)
    finally:
        guard.disarm()

    assert "session limit" in reasons[0]


def test_the_stop_key_is_polled_independently(monkeypatch: pytest.MonkeyPatch) -> None:
    """The hotkey hook can stop delivering; the state query cannot."""
    pressed = {"f12": False}
    monkeypatch.setattr(safety_module, "is_key_held", lambda key: pressed.get(key, False))

    fired = threading.Event()
    reasons: list[str] = []

    def on_violation(why: str) -> None:
        reasons.append(why)
        fired.set()

    guard = SafetyGuard(make_limits(stop_key="f12"), on_violation)
    guard.arm()
    try:
        assert not fired.wait(timeout=0.2)
        pressed["f12"] = True
        assert fired.wait(timeout=2.0)
    finally:
        guard.disarm()

    assert "stopped manually" in reasons[0]


def test_without_a_stop_key_the_keyboard_is_not_queried(monkeypatch: pytest.MonkeyPatch) -> None:
    queried: list[str] = []

    def spy(key: str) -> bool:
        queried.append(key)
        return False

    monkeypatch.setattr(safety_module, "is_key_held", spy)
    guard = SafetyGuard(make_limits(stop_key=""), lambda why: None)
    guard.arm()
    time.sleep(0.15)
    guard.disarm()

    assert queried == []


def test_disarming_without_arming_is_safe() -> None:
    SafetyGuard(make_limits(), lambda why: None).disarm()
