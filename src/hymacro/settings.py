"""In-app settings editor.

Every value is validated before anything is written, so the file on disk is
never left in a state the program cannot load.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import (
    ConfigError,
    assign,
    lookup,
    merge_defaults,
    read_raw,
    validate,
    write_raw,
)
from .console import BOLD, CYAN, GREEN, GREY, RED, WHITE, paint
from .ui import BACK, Option, read_option, read_text, render_options

Redraw = Callable[[], None]


@dataclass(frozen=True)
class Field:
    """One editable setting."""

    path: tuple[str, ...]
    label: str
    kind: str
    hint: str = ""
    choices: tuple[str, ...] = ()


def _route_fields(macro: str) -> list[Field]:
    return [
        Field(("macros", macro, "keys"), "Route", "keys", "out, step, back, step"),
        Field(("macros", macro, "forward_seconds"), "Outward leg", "seconds", "a full row"),
        Field(("macros", macro, "return_seconds"), "Return leg", "seconds", "0 for one way only"),
        Field(("macros", macro, "step_seconds"), "Step between rows", "seconds"),
        Field(("macros", macro, "routes_per_warp"), "Laps per warp", "integer"),
    ]


SECTIONS: list[tuple[str, list[Field]]] = [
    ("Nether Wart", _route_fields("nether_wart")),
    ("Cocoa Beans", _route_fields("cocoa_beans")),
    (
        "Cobblestone",
        [
            Field(("macros", "cobblestone", "key"), "Movement key", "key"),
            Field(("macros", "cobblestone", "mining_seconds"), "Mining per cycle", "seconds"),
            Field(("macros", "cobblestone", "hub_wait_seconds"), "Wait at hub", "seconds"),
        ],
    ),
    (
        "Hotkeys",
        [
            Field(("keybinds", "cocoa_beans"), "Start Cocoa Beans", "key"),
            Field(("keybinds", "nether_wart"), "Start Nether Wart", "key"),
            Field(("keybinds", "cobblestone"), "Start Cobblestone", "key"),
            Field(("keybinds", "stop"), "Stop the macro", "key"),
            Field(("general", "chat_key"), "Open chat", "key"),
        ],
    ),
    (
        "Safety",
        [
            Field(("safety", "require_window_focus"), "Stop when focus is lost", "boolean"),
            Field(("safety", "window_title_contains"), "Window title", "text"),
            Field(("safety", "mouse_failsafe"), "Stop when the mouse moves", "boolean"),
            Field(("safety", "mouse_failsafe_px"), "Mouse tolerance (px)", "integer"),
            Field(("safety", "max_session_seconds"), "Session limit", "seconds", "0 for no limit"),
        ],
    ),
    (
        "Appearance",
        [
            Field(("general", "colors"), "Colours", "choice", choices=("auto", "always", "never")),
            Field(("general", "banner_animation"), "Animate the banner", "boolean"),
        ],
    ),
]


def _format(value: Any, kind: str) -> str:
    if value is None:
        return "not set"
    if kind == "boolean":
        return "on" if value else "off"
    if kind == "keys":
        return " ".join(str(key).upper() for key in value)
    if kind == "key":
        return str(value).upper()
    if kind == "seconds":
        return f"{float(value):g}s"
    return str(value)


def _parse(text: str, field: Field) -> Any:
    """Convert typed input to the field's type, raising ValueError if it cannot."""
    if field.kind == "seconds":
        try:
            return float(text.replace(",", "."))
        except ValueError:
            raise ValueError(f"{text!r} is not a number of seconds") from None
    if field.kind == "integer":
        try:
            return int(float(text.replace(",", ".")))
        except ValueError:
            raise ValueError(f"{text!r} is not a number") from None
    if field.kind == "boolean":
        lowered = text.lower()
        if lowered in ("on", "yes", "y", "true", "1", "si"):
            return True
        if lowered in ("off", "no", "n", "false", "0"):
            return False
        raise ValueError("answer on or off")
    if field.kind == "keys":
        parts = text.replace(",", " ").split()
        if len(parts) != 4:
            raise ValueError("four keys are required, for example: d w a w")
        return [part.lower() for part in parts]
    if field.kind == "key":
        return text.strip().lower()
    if field.kind == "choice":
        if text.lower() not in field.choices:
            raise ValueError(f"choose one of: {', '.join(field.choices)}")
        return text.lower()
    return text


def _edit_field(path: Path, raw: dict[str, Any], field: Field, redraw: Redraw) -> bool:
    """Ask for a new value, validate it and save. True when it was written."""
    redraw()
    current = _format(lookup(merge_defaults(raw), *field.path), field.kind)

    print("")
    print(f"  {paint(field.label, BOLD, WHITE)}   {paint('currently ' + current, GREY)}")
    if field.hint:
        print(paint(f"  {field.hint}", GREY))
    if field.kind == "choice":
        print(paint(f"  Options: {', '.join(field.choices)}", GREY))
    print(paint("  Press Enter without typing to leave it unchanged.", GREY))

    text = read_text(paint("  New value > ", BOLD, CYAN))
    if text is None:
        return False

    try:
        value = _parse(text, field)
    except ValueError as exc:
        print(f"{paint('  Rejected:', BOLD, RED)} {exc}")
        return False

    candidate = json.loads(json.dumps(raw))
    assign(candidate, field.path, value)
    try:
        validate(merge_defaults(candidate))
    except ConfigError as exc:
        print(f"{paint('  Rejected:', BOLD, RED)} {exc}")
        return False

    assign(raw, field.path, value)
    try:
        write_raw(path, raw)
    except OSError as exc:
        print(f"{paint('  Rejected:', BOLD, RED)} could not write {path}: {exc}")
        return False

    saved = _format(lookup(merge_defaults(raw), *field.path), field.kind)
    print(f"{paint('  Saved:', BOLD, GREEN)} {field.label} is now {saved}")
    return True


def _section_menu(path: Path, raw: dict[str, Any], title: str, fields: list[Field], redraw: Redraw) -> None:
    while True:
        redraw()
        merged = merge_defaults(raw)
        options: list[Option] = [
            (str(index + 1), field.label, _format(lookup(merged, *field.path), field.kind))
            for index, field in enumerate(fields)
        ]
        options.append((BACK, "Back", ""))

        print(render_options(title, options))
        print("")
        choice = read_option({option[0] for option in options}, BACK)
        if choice == BACK:
            return

        if _edit_field(path, raw, fields[int(choice) - 1], redraw):
            print(paint("  Restart the macro for the change to take effect.", GREY))
        read_text(paint("\n  Press Enter to continue > ", GREY))


def run(path: Path, redraw: Redraw = lambda: None) -> None:
    """Settings menu. `redraw` repaints the screen between views."""
    try:
        raw = read_raw(path)
    except ConfigError as exc:
        redraw()
        print(f"{paint('  Error:', BOLD, RED)} {exc}")
        print(paint("  Delete the file and it will be recreated with the defaults.", GREY))
        read_text(paint("\n  Press Enter to continue > ", GREY))
        return

    while True:
        redraw()
        options: list[Option] = [(str(index + 1), title, "") for index, (title, _) in enumerate(SECTIONS)]
        options.append((BACK, "Back", ""))

        print(render_options("Settings", options))
        print("")
        choice = read_option({option[0] for option in options}, BACK)
        if choice == BACK:
            return

        title, fields = SECTIONS[int(choice) - 1]
        _section_menu(path, raw, title, fields, redraw)
