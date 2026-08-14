"""Colour output and the animated banner."""

from __future__ import annotations

import os
import re
import shutil
import sys
import time
from collections.abc import Iterator

import pytest

from hymacro import console
from hymacro.console import BOLD, GREEN, RESET, Banner, hue, init_colors, paint

ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")
TOP_LEFT = "\x1b[1;1H"
SAMPLE = "line one\nline two\nline three"


@pytest.fixture(autouse=True)
def _restore_state() -> Iterator[None]:
    previous = console.colors_enabled()
    yield
    console._enabled = previous


def test_plain_text_when_colour_is_off() -> None:
    init_colors("never")
    assert paint("hello", BOLD, GREEN) == "hello"


def test_colour_wraps_and_always_resets() -> None:
    init_colors("always")
    painted = paint("hello", GREEN)

    assert painted.startswith(GREEN)
    assert painted.endswith(RESET), "without a reset the colour bleeds into the rest of the console"


def test_no_color_beats_always(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NO_COLOR", "1")
    assert init_colors("always") is False


def test_auto_needs_a_real_console(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("NO_COLOR", raising=False)
    monkeypatch.setattr(sys.stdout, "isatty", lambda: False, raising=False)
    assert init_colors("auto") is False


def test_the_rainbow_is_a_full_cycle() -> None:
    codes = [hue(index / 12) for index in range(12)]

    assert len(set(codes)) == 12
    assert hue(0.0) == hue(1.0)


def test_the_wave_varies_across_the_columns() -> None:
    init_colors("always")
    painted = console._wave_line("#" * 60, 0, 0.0, 0.05)

    assert len(set(ANSI.findall(painted))) > 5


def test_the_wave_travels_to_the_right() -> None:
    init_colors("always")
    pattern = re.compile(r"\x1b\[38;2;(\d+;\d+;\d+)m")

    first = pattern.search(console._wave_line("#" * 60, 0, 0.0, 0.05))
    assert first is not None
    tone = first.group(1)

    later = console._wave_line("#" * 60, 0, 0.10, 0.05)
    position = 0
    found = None
    remainder = later
    while (match := pattern.search(remainder)) is not None:
        position += match.start()
        if match.group(1) == tone:
            found = position
            break
        remainder = remainder[match.end() :]

    assert found is not None and found > 0, "the wave is frozen or moving the wrong way"


def test_refresh_returns_the_cursor(capsys: pytest.CaptureFixture[str]) -> None:
    init_colors("always")
    Banner(SAMPLE).refresh()

    output = capsys.readouterr().out
    assert output.startswith("\x1b[s")
    assert output.endswith("\x1b[u"), "the prompt would end up somewhere else"
    assert TOP_LEFT in output


def test_nothing_is_written_without_colour(capsys: pytest.CaptureFixture[str]) -> None:
    init_colors("never")
    Banner(SAMPLE).refresh()

    assert capsys.readouterr().out == ""


def test_the_phase_advances_over_time() -> None:
    init_colors("always")
    banner = Banner(SAMPLE, speed=10.0)
    first = banner._phase()
    time.sleep(0.05)

    assert banner._phase() > first


def test_pinning_needs_room_for_the_block(monkeypatch: pytest.MonkeyPatch) -> None:
    """Freezing almost the whole screen would leave nowhere for the log."""
    init_colors("always")
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((80, 24)))

    assert console.pin_top(10) is True
    assert console.pin_top(22) is False
    assert console.pin_top(0) is False


def test_pinning_writes_the_region_and_unpinning_clears_it(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    init_colors("always")
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((80, 40)))

    console.pin_top(14)
    console.unpin()

    output = capsys.readouterr().out
    assert "\x1b[15;40r" in output
    assert output.endswith("\x1b[r")


def test_nothing_is_pinned_without_colour(capsys: pytest.CaptureFixture[str]) -> None:
    init_colors("never")

    assert console.pin_top(10) is False
    console.unpin()
    assert capsys.readouterr().out == ""
