"""Failsafes that stop the macro when the situation is no longer expected."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from .config import Config
from .winput import cursor_position, foreground_window_title, is_key_held

_FOCUS_GRACE_SECONDS = 1.0


@dataclass(frozen=True)
class SafetyLimits:
    """Limits configured for a macro session."""

    require_focus: bool
    title_contains: str
    mouse_failsafe: bool
    mouse_threshold_px: int
    max_session_seconds: float
    interval_seconds: float
    stop_key: str = ""

    @classmethod
    def from_config(cls, config: Config) -> SafetyLimits:
        return cls(
            require_focus=config.flag("safety", "require_window_focus", default=True),
            title_contains=config.text("safety", "window_title_contains", default="Minecraft"),
            mouse_failsafe=config.flag("safety", "mouse_failsafe", default=True),
            mouse_threshold_px=config.integer("safety", "mouse_failsafe_px", default=100),
            max_session_seconds=config.number("safety", "max_session_seconds", default=0.0),
            interval_seconds=config.number("safety", "watchdog_seconds", default=0.1),
            stop_key=config.text("keybinds", "stop", default=""),
        )


def window_matches(title: str, needle: str) -> bool:
    """True when the window title contains the needle, ignoring case."""
    if not needle:
        return True
    return needle.casefold() in title.casefold()


class SafetyGuard:
    """Watches window focus, mouse movement, the stop key and session length.

    It runs on its own thread so the checks stay responsive during the long
    sleeps a route spends holding a key down.
    """

    def __init__(self, limits: SafetyLimits, on_violation: Callable[[str], None]) -> None:
        self._limits = limits
        self._on_violation = on_violation
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._origin: tuple[int, int] = (0, 0)
        self._started_at: float = 0.0

    def arm(self) -> None:
        self._stop.clear()
        self._origin = cursor_position()
        self._started_at = time.monotonic()
        self._thread = threading.Thread(target=self._watch, name="hymacro-watchdog", daemon=True)
        self._thread.start()

    def disarm(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)
        self._thread = None

    def preflight(self) -> str | None:
        """Reason the macro should not start, or None when it is safe to."""
        if self._limits.require_focus:
            title = foreground_window_title()
            if not window_matches(title, self._limits.title_contains):
                shown = title or "(no title)"
                return f"the active window is {shown!r}, not {self._limits.title_contains!r}"
        return None

    def _watch(self) -> None:
        while not self._stop.wait(self._limits.interval_seconds):
            reason = self._check()
            if reason is not None:
                self._on_violation(reason)
                return

    def _check(self) -> str | None:
        elapsed = time.monotonic() - self._started_at

        if self._limits.stop_key and is_key_held(self._limits.stop_key):
            return f"stopped manually ({self._limits.stop_key.upper()})"

        if self._limits.max_session_seconds > 0 and elapsed >= self._limits.max_session_seconds:
            minutes = self._limits.max_session_seconds / 60
            return f"session limit reached ({minutes:.0f} min)"

        if self._limits.require_focus and elapsed >= _FOCUS_GRACE_SECONDS:
            title = foreground_window_title()
            if not window_matches(title, self._limits.title_contains):
                shown = title or "(no title)"
                return f"Minecraft lost focus (active window: {shown!r})"

        if self._limits.mouse_failsafe:
            x, y = cursor_position()
            drift = max(abs(x - self._origin[0]), abs(y - self._origin[1]))
            if drift > self._limits.mouse_threshold_px:
                return f"mouse moved {drift} px (limit: {self._limits.mouse_threshold_px})"

        return None
