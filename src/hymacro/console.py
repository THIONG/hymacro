"""ANSI colour output, the animated banner and console window tweaks.

Windows does not interpret ANSI escapes in the classic console unless asked to.
When that fails, or the output is redirected, everything degrades to plain text
rather than leaking escape sequences into a file.
"""

from __future__ import annotations

import contextlib
import ctypes
import os
import shutil
import sys
import threading
import time

_STD_OUTPUT_HANDLE = -11
_ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
_WM_SETICON = 0x0080

_HUE_STEPS = 48
_CYCLES_PER_LINE = 0.75

RESET = "\x1b[0m"
BOLD = "\x1b[1m"
DIM = "\x1b[2m"

RED = "\x1b[38;5;203m"
GREEN = "\x1b[38;5;114m"
YELLOW = "\x1b[38;5;221m"
BLUE = "\x1b[38;5;75m"
MAGENTA = "\x1b[38;5;176m"
CYAN = "\x1b[38;5;80m"
GREY = "\x1b[38;5;245m"
WHITE = "\x1b[38;5;255m"

_enabled = False


def enable_ansi() -> bool:
    """Ask Windows to interpret ANSI escape sequences."""
    if sys.platform != "win32":
        return True
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        handle = kernel32.GetStdHandle(_STD_OUTPUT_HANDLE)
        mode = ctypes.c_ulong()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        return bool(kernel32.SetConsoleMode(handle, mode.value | _ENABLE_VIRTUAL_TERMINAL_PROCESSING))
    except (OSError, AttributeError):
        return False


def init_colors(mode: str = "auto") -> bool:
    """Decide whether output is colourised. 'auto' requires a real console."""
    global _enabled
    if mode == "never" or os.environ.get("NO_COLOR"):
        _enabled = False
    elif mode == "always":
        enable_ansi()
        _enabled = True
    else:
        interactive = bool(getattr(sys.stdout, "isatty", lambda: False)())
        _enabled = interactive and enable_ansi()
    return _enabled


def colors_enabled() -> bool:
    return _enabled


def paint(text: str, *codes: str) -> str:
    """Wrap text in ANSI codes, or return it untouched when colour is off."""
    if not _enabled or not codes:
        return text
    return f"{''.join(codes)}{text}{RESET}"


def set_console_title(title: str) -> None:
    """Set the console window title."""
    if sys.platform != "win32":
        return
    with contextlib.suppress(OSError, AttributeError):
        ctypes.WinDLL("kernel32", use_last_error=True).SetConsoleTitleW(title)


def set_console_icon() -> None:
    """Apply the executable's icon to the console window.

    Only the classic console honours this. Windows Terminal draws the icon of
    its own profile and cannot be overridden by the application.
    """
    if sys.platform != "win32" or not getattr(sys, "frozen", False):
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        shell32 = ctypes.WinDLL("shell32", use_last_error=True)

        kernel32.GetConsoleWindow.restype = ctypes.c_void_p
        shell32.ExtractIconW.restype = ctypes.c_void_p
        shell32.ExtractIconW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_uint]
        user32.SendMessageW.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint,
            ctypes.c_void_p,
            ctypes.c_void_p,
        ]

        window = kernel32.GetConsoleWindow()
        icon = shell32.ExtractIconW(None, sys.executable, 0) if window else None
        if not window or not icon:
            return
        for size in (0, 1):
            user32.SendMessageW(
                ctypes.c_void_p(window),
                _WM_SETICON,
                ctypes.c_void_p(size),
                ctypes.c_void_p(icon),
            )
    except (OSError, AttributeError):
        return


def clear_screen() -> None:
    """Clear the screen and the scrollback, then home the cursor."""
    if not _enabled:
        return
    sys.stdout.write("\x1b[H\x1b[2J\x1b[3J")
    sys.stdout.flush()


def terminal_rows() -> int:
    return shutil.get_terminal_size(fallback=(80, 24)).lines


def pin_top(rows: int) -> bool:
    """Freeze the first rows and let everything below them scroll.

    Output printed afterwards scrolls only inside the lower region, so a banner
    at the top stays put however much is written. Without this the banner is
    pushed off screen by the first few lines of output and cannot be animated.
    """
    if not _enabled:
        return False
    height = terminal_rows()
    if rows <= 0 or rows >= height - 2:
        return False
    sys.stdout.write(f"\x1b[{rows + 1};{height}r\x1b[{rows + 1};1H")
    sys.stdout.flush()
    return True


def unpin() -> None:
    """Give the whole screen back to scrolling."""
    if not _enabled:
        return
    sys.stdout.write("\x1b[r")
    sys.stdout.flush()


def _hue_rgb(fraction: float) -> tuple[int, int, int]:
    hue = fraction % 1.0
    sector = int(hue * 6)
    remainder = hue * 6 - sector
    rising = int(255 * remainder)
    falling = 255 - rising
    return [
        (255, rising, 0),
        (falling, 255, 0),
        (0, 255, rising),
        (0, falling, 255),
        (rising, 0, 255),
        (255, 0, falling),
    ][sector % 6]


def hue(fraction: float) -> str:
    """Truecolour escape for a point on the rainbow."""
    red, green, blue = _hue_rgb(fraction)
    return f"\x1b[38;2;{red};{green};{blue}m"


def _wave_line(line: str, row: int, phase: float, row_offset: float) -> str:
    """Colourise one line with the hue varying along the columns.

    Subtracting the phase makes the pattern travel to the right: a fixed hue
    shows up at ever larger columns as time advances. The hue is quantised so a
    frame does not carry one escape sequence per character.
    """
    width = max(1, len(line))
    parts: list[str] = []
    previous = -1
    for column, character in enumerate(line):
        tone = (column / width) * _CYCLES_PER_LINE - phase + row * row_offset
        quantised = int(tone % 1.0 * _HUE_STEPS)
        if quantised != previous:
            parts.append(hue(quantised / _HUE_STEPS))
            previous = quantised
        parts.append(character)
    parts.append(RESET)
    return "".join(parts)


class Banner:
    """A banner whose rainbow travels to the right as time passes."""

    def __init__(self, text: str, *, speed: float = 0.7, row_offset: float = 0.05) -> None:
        self._lines = text.splitlines()
        self._speed = speed
        self._row_offset = row_offset
        self._start = time.monotonic()

    @property
    def height(self) -> int:
        return len(self._lines)

    def _phase(self) -> float:
        return (time.monotonic() - self._start) * self._speed

    def draw(self) -> None:
        """Paint the banner, leaving the cursor below it."""
        if not _enabled:
            print("\n".join(self._lines), flush=True)
            return
        phase = self._phase()
        for row, line in enumerate(self._lines):
            sys.stdout.write(f"{_wave_line(line, row, phase, self._row_offset)}\x1b[K\n")
        sys.stdout.flush()

    def refresh(self) -> None:
        """Repaint in place, assuming the banner starts on the first row.

        Every screen is drawn right after clearing, so the banner is always at
        the top and no line counting is needed.
        """
        if not _enabled:
            return
        phase = self._phase()
        parts = ["\x1b[s\x1b[1;1H"]
        for row, line in enumerate(self._lines):
            parts.append(f"\r{_wave_line(line, row, phase, self._row_offset)}\x1b[K\n")
        parts.append("\x1b[u")
        sys.stdout.write("".join(parts))
        sys.stdout.flush()


class Animation:
    """Keeps a banner moving while the main thread waits for input.

    Only safe while nothing else writes to the screen, so it runs strictly
    around a blocking read.
    """

    def __init__(self, banner: Banner | None, fps: int = 15) -> None:
        self._banner = banner if _enabled else None
        self._interval = 1.0 / max(1, fps)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def __enter__(self) -> Animation:
        if self._banner is not None:
            self._stop.clear()
            self._thread = threading.Thread(target=self._loop, daemon=True, name="hymacro-banner")
            self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None

    def _loop(self) -> None:
        assert self._banner is not None
        while not self._stop.wait(self._interval):
            self._banner.refresh()
