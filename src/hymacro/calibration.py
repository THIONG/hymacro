"""Manual stopwatch used to measure the route timings on the player's plot."""

from __future__ import annotations

import time
from typing import Any

from .config import MACRO_LABELS, Config, ConfigError
from .console import BOLD, CYAN, GREEN, GREY, RED, WHITE, paint
from .ui import animating, read_text

_SAFETY_LIMIT_SECONDS = 900.0


def _wait_for_press(keyboard: Any, key: str) -> float:
    """Wait for a full press and return the instant the key went down.

    The release is awaited too, otherwise a single long press would mark both
    the start and the end of the same leg.
    """
    with animating():
        while not keyboard.is_pressed(key):
            time.sleep(0.01)
        pressed_at = time.perf_counter()
        while keyboard.is_pressed(key):
            time.sleep(0.01)
    return pressed_at


def _time_leg(keyboard: Any, key: str, title: str, instruction: str) -> float:
    """Measure the time between two presses of the stop key."""
    marker = paint(key.upper(), BOLD, CYAN)
    print("")
    print(paint(f"  {title}", BOLD, WHITE))
    print(f"    1. Get in position and press {marker} to start the stopwatch.")
    print(f"    2. {instruction}")
    print(f"    3. Press {marker} again when you are done.")
    print(paint(f"  Waiting for {key.upper()}...", GREY))

    started = _wait_for_press(keyboard, key)
    print(paint("  Timing. Go.", BOLD, GREEN))
    finished = _wait_for_press(keyboard, key)

    elapsed = finished - started
    print(f"  {paint('Measured:', BOLD, GREEN)} {elapsed:.2f} s")
    return elapsed


def run(config_path: str | None = None, macro_type: str = "nether_wart") -> int:
    """Time both legs of a route and print the values ready to be applied."""
    import sys

    if sys.platform != "win32":
        print(paint("  Calibration is only available on Windows.", BOLD, RED))
        return 1

    try:
        config = Config(config_path, auto_create=False)
    except ConfigError as exc:
        print(f"{paint('  Error:', BOLD, RED)} {exc}")
        return 1

    if macro_type not in MACRO_LABELS or macro_type == "cobblestone":
        print(paint(f"  {macro_type} cannot be calibrated.", BOLD, RED))
        return 1

    import keyboard

    label = MACRO_LABELS[macro_type]
    keys = [str(key) for key in config.get("macros", macro_type, "keys")]
    stop_key = config.text("keybinds", "stop").lower()

    print("")
    print(paint(f"  Calibrating {label}", BOLD, WHITE))
    print(paint("  Nothing is automated here: you walk the route and this times it.", GREY))

    row = _time_leg(
        keyboard,
        stop_key,
        f"Step 1 of 2  -  a full row (the macro uses '{keys[0].upper()}')",
        "Walk the row from end to end, exactly as you normally would.",
    )
    step = _time_leg(
        keyboard,
        stop_key,
        f"Step 2 of 2  -  moving to the next row (the macro uses '{keys[1].upper()}')",
        "Move across to the next row and line yourself up with it.",
    )

    if row > _SAFETY_LIMIT_SECONDS or step > _SAFETY_LIMIT_SECONDS:
        print(paint("\n  Those times look wrong. Try again.", BOLD, RED))
        return 1

    print("")
    print(paint(f"  Results for {label}", BOLD, WHITE))
    print(f"    Row     {paint(f'{row:.1f} s', BOLD, GREEN)}")
    print(f"    Step    {paint(f'{step:.2f} s', BOLD, GREEN)}")
    print("")
    print(paint("  Apply them under Settings, or edit config.json:", GREY))
    print(paint(f'      "forward_seconds": {row:.1f},', CYAN))
    print(paint(f'      "return_seconds": {row:.1f},', CYAN))
    print(paint(f'      "step_seconds": {step:.2f}', CYAN))
    print("")
    print(paint("  If the return row is a different length, time it separately.", GREY))

    read_text(paint("\n  Press Enter to continue > ", GREY))
    return 0
