"""The macro screen and its banner bookkeeping."""

from __future__ import annotations

import json
import re
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


def test_the_line_count_matches_what_was_printed(app: MacroApp, capsys: pytest.CaptureFixture[str]) -> None:
    """The repaint jumps banner height plus this count.

    When the header was printed before the counter was reset, the jump came out
    one row short and the repaint landed on top of the header.
    """
    app.display()
    output = capsys.readouterr().out

    banner_height = len(app._banner._lines) if app._banner else 0
    printed_below = output.count("\n") - banner_height

    assert app._lines_below == printed_below


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
