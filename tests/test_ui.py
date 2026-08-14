"""Menu primitives and key handling."""

from __future__ import annotations

import builtins
import sys
import types
from collections.abc import Iterator

import pytest

from hymacro import console, ui
from hymacro.console import init_colors
from hymacro.ui import BACK, prompt_line, read_option, render_options

ANSI_STRIP = __import__("re").compile(r"\x1b\[[0-9;]*[a-zA-Z]")


class FakeMsvcrt(types.ModuleType):
    """Stands in for msvcrt, replaying a fixed sequence of key presses."""

    def __init__(self, keys: str) -> None:
        super().__init__("msvcrt")
        self._keys: Iterator[str] = iter(keys)
        self._pending: str | None = None

    def kbhit(self) -> bool:
        if self._pending is None:
            self._pending = next(self._keys, None)
        return self._pending is not None

    def getwch(self) -> str:
        if self._pending is not None:
            key, self._pending = self._pending, None
            return key
        following = next(self._keys, None)
        if following is None:
            raise AssertionError("more keys were read than the test provided")
        return following


class NoAnimation:
    def __enter__(self) -> None:
        return None

    def __exit__(self, *exc: object) -> None:
        return None


@pytest.fixture(autouse=True)
def _fake_console(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    previous = console.colors_enabled()
    init_colors("always")
    monkeypatch.setattr(ui, "interactive_console", lambda: True)
    monkeypatch.setattr(ui, "animating", NoAnimation)
    yield
    console._enabled = previous
    ui.set_banner(None)


def _keys(monkeypatch: pytest.MonkeyPatch, keys: str) -> None:
    monkeypatch.setitem(sys.modules, "msvcrt", FakeMsvcrt(keys))


def test_escape_goes_back(monkeypatch: pytest.MonkeyPatch) -> None:
    _keys(monkeypatch, "\x1b")
    assert read_option({BACK, "1", "2"}, "1") == BACK


def test_ctrl_c_also_goes_back(monkeypatch: pytest.MonkeyPatch) -> None:
    _keys(monkeypatch, "\x03")
    assert read_option({BACK, "1"}, "1") == BACK


def test_a_normal_option_is_returned(monkeypatch: pytest.MonkeyPatch) -> None:
    _keys(monkeypatch, "2")
    assert read_option({BACK, "1", "2"}, "1") == "2"


def test_enter_picks_the_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    _keys(monkeypatch, "\r")
    assert read_option({BACK, "1", "2"}, "1") == "1"


def test_arrow_keys_do_not_leak_through(monkeypatch: pytest.MonkeyPatch) -> None:
    """Extended keys arrive as a pair and must not count as an option."""
    _keys(monkeypatch, "\xe0Hz\x1b")
    assert read_option({BACK, "1"}, "1") == BACK


def test_typed_input_accepts_the_back_label(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ui, "interactive_console", lambda: False)
    monkeypatch.setattr(builtins, "input", lambda prompt="": "esc")

    assert read_option({BACK, "1"}, "1") == BACK


def test_typed_input_falls_back_on_end_of_file(monkeypatch: pytest.MonkeyPatch) -> None:
    def raise_eof(prompt: str = "") -> str:
        raise EOFError

    monkeypatch.setattr(builtins, "input", raise_eof)
    assert prompt_line({BACK, "1"}, "1") == BACK


def test_labels_of_different_widths_stay_aligned() -> None:
    text = ANSI_STRIP.sub(
        "",
        render_options(
            "Hotkeys",
            [("F8", "Cocoa Beans", ""), ("F10", "Cobblestone", ""), (BACK, "Back", "")],
        ),
    )
    rows = [line for line in text.splitlines() if ")" in line]
    columns = {
        row.index(name) for row, name in zip(rows, ["Cocoa Beans", "Cobblestone", "Back"], strict=True)
    }

    assert len(columns) == 1, f"names start in different columns: {columns}"


def test_every_key_reader_handles_escape() -> None:
    """A missed Escape branch once shipped unnoticed."""
    import inspect

    from hymacro.app import MacroApp

    needle = chr(92) + "x1b"
    for function in (read_option, MacroApp._back_requested):
        assert needle in inspect.getsource(function), f"{function.__name__} ignores Escape"
