"""The macro screen and its banner bookkeeping."""

from __future__ import annotations

import json
import os
import re
import shutil
from collections.abc import Iterator
from pathlib import Path

import pytest

from hymacro import console
from hymacro.app import MacroApp
from hymacro.config import DEFAULTS, Config
from hymacro.console import init_colors
from hymacro.screen import TAGLINE

ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")


@pytest.fixture(autouse=True)
def _tall_terminal(monkeypatch: pytest.MonkeyPatch) -> None:
    """A short terminal cannot pin the header, which is a separate path."""
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((120, 60)))


@pytest.fixture(autouse=True)
def _colors() -> Iterator[None]:
    previous = console.colors_enabled()
    init_colors("always")
    yield
    console._enabled = previous


@pytest.fixture
def app(tmp_path: Path) -> MacroApp:
    path = tmp_path / "config.json"
    path.write_text(json.dumps(DEFAULTS), encoding="utf-8")
    return MacroApp(Config(path, auto_create=False))


def test_the_header_is_shown(app: MacroApp, capsys: pytest.CaptureFixture[str]) -> None:
    app.display()

    assert TAGLINE in ANSI.sub("", capsys.readouterr().out)


def test_the_banner_is_pinned_so_it_cannot_scroll_away(
    app: MacroApp, capsys: pytest.CaptureFixture[str]
) -> None:
    """Counting the lines below the banner caused three separate bugs.

    Freezing the top rows removes the arithmetic entirely: output scrolls in the
    region underneath and the banner stays on row one, however much is printed.
    """
    app.display()
    output = capsys.readouterr().out

    assert app._pinned, "the scroll region was not set"
    assert app._banner is not None
    region = re.search(r"\x1b\[(\d+);(\d+)r", output)
    assert region is not None, "no scroll region escape was emitted"

    printed_rows = ANSI.sub("", output).count("\n")
    assert int(region.group(1)) == printed_rows + 1, (
        "the region must begin right below everything that was printed"
    )


def test_unpinning_gives_the_screen_back(app: MacroApp, capsys: pytest.CaptureFixture[str]) -> None:
    app.display()
    capsys.readouterr()
    app._shutdown()

    assert "\x1b[r" in capsys.readouterr().out
    assert not app._pinned


def test_the_hotkeys_are_listed(app: MacroApp, capsys: pytest.CaptureFixture[str]) -> None:
    app.display()
    text = ANSI.sub("", capsys.readouterr().out)

    for expected in ("F8)", "F9)", "F10)", "F12)", "Cocoa Beans", "Stop the macro"):
        assert expected in text


def test_failsafes_are_reported(app: MacroApp, capsys: pytest.CaptureFixture[str]) -> None:
    app.display()
    text = ANSI.sub("", capsys.readouterr().out)

    assert "Failsafes:" in text
    assert "window focus" in text


def _screen_text(config_path: Path, capsys: pytest.CaptureFixture[str]) -> str:
    MacroApp(Config(config_path, auto_create=False)).display()
    return ANSI.sub("", capsys.readouterr().out)


def test_foreground_mode_still_lists_them(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    path = tmp_path / "config.json"
    path.write_text(json.dumps(DEFAULTS), encoding="utf-8")

    text = _screen_text(path, capsys)

    assert "window focus" in text
    assert "mouse failsafe" in text
    assert "Alt+Tab" not in text


def test_a_short_terminal_simply_does_not_pin(
    app: MacroApp, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """The header will not fit, so the screen scrolls as it always did."""
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((80, 20)))
    app.display()
    capsys.readouterr()

    assert not app._pinned
    assert app._banner is None
