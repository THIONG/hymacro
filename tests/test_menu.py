"""The interactive menu."""

from __future__ import annotations

import builtins
import json
import re
from collections.abc import Iterator
from pathlib import Path

import pytest

from hymacro import calibration, menu, ui
from hymacro.config import DEFAULTS
from hymacro.ui import BACK

CLEAR = "\x1b[H\x1b[2J\x1b[3J"
ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")


def config_with(tmp_path: Path, **general: object) -> Path:
    data = json.loads(json.dumps(DEFAULTS))
    data["general"].update(general)
    path = tmp_path / "config.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def _answers(monkeypatch: pytest.MonkeyPatch, answers: list[str]) -> None:
    stream: Iterator[str] = iter(answers)

    def fake_input(prompt: str = "") -> str:
        try:
            return next(stream)
        except StopIteration as exc:
            raise EOFError from exc

    monkeypatch.setattr(builtins, "input", fake_input)


def test_exit_returns_zero(monkeypatch: pytest.MonkeyPatch) -> None:
    _answers(monkeypatch, ["esc"])
    assert menu.run() == 0


def test_a_closed_console_does_not_crash(monkeypatch: pytest.MonkeyPatch) -> None:
    _answers(monkeypatch, [])
    assert menu.run() == 0


def test_an_invalid_option_asks_again(monkeypatch: pytest.MonkeyPatch) -> None:
    _answers(monkeypatch, ["99", "banana", "esc"])
    assert menu.run() == 0


def test_enter_starts_the_macro(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []

    class FakeApp:
        def __init__(self, config: object, *, allow_back: bool = False) -> None:
            calls.append("built")
            self.back_to_menu = False

        def run(self) -> int:
            calls.append("ran")
            return 0

    monkeypatch.setattr(menu, "MacroApp", FakeApp)
    _answers(monkeypatch, [""])

    assert menu.run() == 0
    assert calls == ["built", "ran"]


def test_escape_from_the_macro_returns_to_the_menu(monkeypatch: pytest.MonkeyPatch) -> None:
    starts: list[int] = []

    class FakeApp:
        def __init__(self, config: object, *, allow_back: bool = False) -> None:
            starts.append(1)
            self.back_to_menu = len(starts) == 1

        def run(self) -> int:
            return 0

    monkeypatch.setattr(menu, "MacroApp", FakeApp)
    _answers(monkeypatch, ["1", "1"])

    assert menu.run() == 0
    assert len(starts) == 2


def test_calibration_asks_which_macro(monkeypatch: pytest.MonkeyPatch) -> None:
    chosen: list[str] = []
    monkeypatch.setattr(
        calibration,
        "run",
        lambda path, macro: chosen.append(macro) or 0,  # type: ignore[func-returns-value]
    )
    _answers(monkeypatch, ["2", "2", "esc"])

    assert menu.run() == 0
    assert chosen == ["cocoa_beans"]


def test_going_back_from_the_submenu_needs_no_extra_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(calibration, "run", lambda path, macro: pytest.fail("should not calibrate"))
    _answers(monkeypatch, ["2", "esc", "esc"])

    assert menu.run() == 0


def test_the_screen_is_cleared_between_views(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Without clearing, every return stacked another banner underneath."""
    monkeypatch.setattr(calibration, "run", lambda path, macro: 0)
    _answers(monkeypatch, ["2", "1", "esc"])
    menu.run(str(config_with(tmp_path, colors="always")))

    output = capsys.readouterr().out
    assert output.count(CLEAR) >= 2
    for chunk in output.split(CLEAR):
        assert ANSI.sub("", chunk).count("Hypixel Garden Automation Tool") <= 1


def test_the_colour_setting_is_respected(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    _answers(monkeypatch, ["esc"])
    menu.run(str(config_with(tmp_path, colors="never")))

    assert "\x1b" not in capsys.readouterr().out


def test_a_broken_config_still_opens_the_menu(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    broken = tmp_path / "broken.json"
    broken.write_text("{ not json", encoding="utf-8")
    _answers(monkeypatch, ["esc"])

    assert menu.run(str(broken)) == 0


def _banners_registered(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, animate: bool) -> list[object]:
    seen: list[object] = []
    monkeypatch.setattr(menu, "new_screen", lambda flag=True: seen.append(flag))
    _answers(monkeypatch, ["esc"])
    menu.run(str(config_with(tmp_path, colors="always", banner_animation=animate)))
    return seen


def test_banner_animation_off_reaches_the_menu(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """The setting used to be read only by the macro screen."""
    seen = _banners_registered(tmp_path, monkeypatch, animate=False)
    capsys.readouterr()

    assert seen and all(flag is False for flag in seen)


def test_banner_animation_on_reaches_the_menu(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    seen = _banners_registered(tmp_path, monkeypatch, animate=True)
    capsys.readouterr()

    assert seen and all(flag is True for flag in seen)


def test_no_banner_is_left_registered_on_exit(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Otherwise the next wait would repaint a banner that is no longer there."""
    _answers(monkeypatch, ["esc"])
    menu.run(str(config_with(tmp_path, colors="always")))
    capsys.readouterr()

    assert ui.current_banner() is None


def test_escape_is_the_only_way_back() -> None:
    assert menu.MAIN_OPTIONS[-1][0] == BACK
    assert menu.MACRO_OPTIONS[-1][0] == BACK
