"""Shared menu primitives, so every screen looks and behaves the same."""

from __future__ import annotations

import sys
import time
from collections.abc import Iterator

from .console import BOLD, CYAN, DIM, GREY, WHITE, Animation, Banner, colors_enabled, paint

Option = tuple[str, str, str]

BACK = "ESC"

_banner: Banner | None = None


def set_banner(banner: Banner | None) -> None:
    """Register the banner of the current screen so waits can animate it."""
    global _banner
    _banner = banner


def current_banner() -> Banner | None:
    return _banner


def animating() -> Animation:
    return Animation(_banner)


def interactive_console() -> bool:
    """True when single key presses can be read without blocking redraws."""
    if sys.platform != "win32" or not colors_enabled():
        return False
    stream = sys.stdin
    return bool(stream is not None and getattr(stream, "isatty", lambda: False)())


def render_options(title: str, options: list[Option]) -> str:
    """Render a list of options with a consistent style."""
    name_width = max(len(name) for _, name, _ in options)
    label_width = max(len(label) for label, _, _ in options) + 1
    lines = ["", paint(f"  {title}", BOLD, WHITE), ""]
    for label, name, detail in options:
        muted = label == BACK
        text = name.ljust(name_width) if detail else name
        padding = " " * (label_width - len(label) - 1)
        row = f"    {paint(label + ')', BOLD, GREY if muted else CYAN)}{padding} "
        row += paint(text, GREY if muted else WHITE)
        if detail:
            row += f"  {paint(detail, DIM, GREY)}"
        lines.append(row)
    return "\n".join(lines)


def prompt_line(options: set[str], fallback: str) -> str:
    """Read an option as a typed line, for non interactive consoles."""
    while True:
        try:
            with animating():
                answer = input("  > ").strip()
        except (EOFError, KeyboardInterrupt):
            print("")
            return BACK if BACK in options else fallback
        if not answer:
            return fallback
        if answer.upper() in options:
            return answer.upper()
        if answer in options:
            return answer
        print(f"  Not a valid option. Choose one of: {', '.join(sorted(options))}")


def read_option(options: set[str], fallback: str) -> str:
    """Read a single key press, falling back to a typed line when needed."""
    if not interactive_console():
        return prompt_line(options, fallback)

    import msvcrt

    sys.stdout.write("  > ")
    sys.stdout.flush()
    with animating():
        for key in _key_presses(msvcrt):
            if key in ("\r", "\n"):
                print(fallback)
                return fallback
            if key in ("\x1b", "\x03") and BACK in options:
                print(BACK)
                return BACK
            if key.upper() in options:
                print(key.upper())
                return key.upper()
    return fallback


def read_text(message: str) -> str | None:
    """Read a typed value. None when cancelled with an empty line or Ctrl+C."""
    try:
        with animating():
            answer = input(message).strip()
    except (EOFError, KeyboardInterrupt):
        print("")
        return None
    return answer or None


def _key_presses(msvcrt: object, poll_seconds: float = 0.02) -> Iterator[str]:
    """Yield key presses without blocking, so animations keep running."""
    kbhit = msvcrt.kbhit  # type: ignore[attr-defined]
    getwch = msvcrt.getwch  # type: ignore[attr-defined]
    while True:
        if not kbhit():
            time.sleep(poll_seconds)
            continue
        key = getwch()
        if key in ("\x00", "\xe0"):
            getwch()
            continue
        yield key
