"""The in-app settings editor."""

from __future__ import annotations

import builtins
import json
from collections.abc import Iterator
from pathlib import Path

import pytest

from hymacro import settings
from hymacro.config import DEFAULTS, Config
from hymacro.settings import Field, _format, _parse, run


@pytest.fixture
def config_file(tmp_path: Path) -> Path:
    path = tmp_path / "config.json"
    path.write_text(json.dumps(DEFAULTS), encoding="utf-8")
    return path


def _answers(monkeypatch: pytest.MonkeyPatch, answers: list[str]) -> None:
    stream: Iterator[str] = iter(answers)

    def fake_input(prompt: str = "") -> str:
        try:
            return next(stream)
        except StopIteration as exc:
            raise EOFError from exc

    monkeypatch.setattr(builtins, "input", fake_input)
    monkeypatch.setattr(settings, "read_option", lambda options, fallback: fake_input())


def test_a_decimal_comma_is_accepted() -> None:
    field = Field(("x",), "x", "seconds")
    assert _parse("12,5", field) == 12.5
    assert _parse("12.5", field) == 12.5


def test_numbers_fail_with_a_readable_message() -> None:
    with pytest.raises(ValueError, match="not a number of seconds"):
        _parse("banana", Field(("x",), "x", "seconds"))


def test_a_route_needs_four_keys() -> None:
    field = Field(("x",), "x", "keys")
    assert _parse("d w a w", field) == ["d", "w", "a", "w"]
    assert _parse("d,w,a,w", field) == ["d", "w", "a", "w"]
    with pytest.raises(ValueError, match="four keys"):
        _parse("d w a", field)


def test_values_are_formatted_for_reading() -> None:
    assert _format(True, "boolean") == "on"
    assert _format(["d", "w"], "keys") == "D W"
    assert _format(120.0, "seconds") == "120s"
    assert _format(None, "integer") == "not set"


def test_a_valid_value_is_saved(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _answers(monkeypatch, ["1", "2", "95,5", "", "ESC", "ESC"])
    run(config_file)

    saved = json.loads(config_file.read_text(encoding="utf-8"))
    assert saved["macros"]["nether_wart"]["forward_seconds"] == 95.5
    Config(config_file, auto_create=False)


def test_an_invalid_value_never_reaches_the_file(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """This is the whole point: never leave a config the program cannot load."""
    before = config_file.read_text(encoding="utf-8")
    _answers(monkeypatch, ["1", "1", "d w a banana", "", "ESC", "ESC"])
    run(config_file)

    assert config_file.read_text(encoding="utf-8") == before


def test_a_duplicated_hotkey_is_refused(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    before = json.loads(config_file.read_text(encoding="utf-8"))
    _answers(monkeypatch, ["4", "4", "f8", "", "ESC", "ESC"])
    run(config_file)

    assert json.loads(config_file.read_text(encoding="utf-8")) == before


def test_an_empty_answer_leaves_the_value_alone(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    before = json.loads(config_file.read_text(encoding="utf-8"))
    _answers(monkeypatch, ["1", "2", "", "", "ESC", "ESC"])
    run(config_file)

    assert json.loads(config_file.read_text(encoding="utf-8")) == before


def test_only_the_edited_key_is_written(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The user's file stays theirs; defaults are not dumped into it."""
    config_file.write_text(json.dumps({"macros": {"nether_wart": {"forward_seconds": 10}}}), encoding="utf-8")
    _answers(monkeypatch, ["1", "3", "60", "", "ESC", "ESC"])
    run(config_file)

    saved = json.loads(config_file.read_text(encoding="utf-8"))
    assert saved["macros"]["nether_wart"]["return_seconds"] == 60.0
    assert saved["macros"]["nether_wart"]["forward_seconds"] == 10
    assert "keybinds" not in saved


def test_an_unreadable_file_does_not_crash(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    broken = tmp_path / "broken.json"
    broken.write_text("{ not json", encoding="utf-8")
    _answers(monkeypatch, [])

    run(broken)


def test_the_screen_is_redrawn_for_every_view(config_file: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Without this the menus stacked up and two banners appeared."""
    redraws: list[int] = []
    _answers(monkeypatch, ["1", "2", "95", "", "ESC", "ESC"])

    run(config_file, lambda: redraws.append(1))

    assert len(redraws) >= 5
