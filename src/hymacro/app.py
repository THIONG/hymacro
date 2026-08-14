"""The running-macro screen: global hotkeys and live status."""

from __future__ import annotations

import logging
import sys
import threading
import time
from collections.abc import Callable
from typing import Any

from .config import MACRO_LABELS, MACRO_TYPES, Config
from .console import BOLD, CYAN, GREEN, GREY, MAGENTA, RED, WHITE, YELLOW, Banner, paint
from .controller import MacroController, MacroEvent
from .screen import BANNER, header, new_screen
from .ui import BACK, Option, interactive_console, render_options, set_banner

logger = logging.getLogger(__name__)

_EVENT_STYLES = {
    "info": ("  >", CYAN),
    "warn": ("  !", YELLOW),
    "cycle": ("  .", GREEN),
    "stop": ("STOP", YELLOW),
    "stats": ("DONE", MAGENTA),
}


def setup_logging(verbose: bool = False) -> None:
    """Warnings on the console only; nothing is written to disk."""
    handler = logging.StreamHandler()
    handler.setLevel(logging.DEBUG if verbose else logging.WARNING)
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.WARNING,
        format="%(levelname)s: %(message)s",
        handlers=[handler],
        force=True,
    )


class MacroApp:
    """Runs the macro and reports what it is doing."""

    def __init__(self, config: Config, *, allow_back: bool = False) -> None:
        self.config = config
        self.controller = MacroController(config, on_event=self._handle_event)
        self.back_to_menu = False

        self._print_lock = threading.Lock()
        self._alive = True
        self._keyboard: Any = None
        self._allow_back = allow_back and interactive_console()
        self._animate = config.flag("general", "banner_animation", default=True)
        self._banner: Banner | None = None
        self._lines_below = 0

    def _say(self, message: str) -> None:
        with self._print_lock:
            print(message, flush=True)
            self._lines_below += message.count("\n") + 1

    def _handle_event(self, event: MacroEvent) -> None:
        prefix, color = _EVENT_STYLES.get(event.level, ("   ", GREY))
        self._say(f"  {paint(prefix, BOLD, color)} {event.message}")

    def _refresh_banner(self) -> None:
        if self._banner is None:
            return
        with self._print_lock:
            self._banner.refresh_above(self._lines_below)

    def _back_requested(self) -> bool:
        """True when Escape was pressed in the HyMacro window.

        Read from the console rather than a global hook, so pressing Escape
        inside Minecraft only opens the game menu.
        """
        if not self._allow_back:
            return False
        import msvcrt

        requested = False
        while msvcrt.kbhit():
            key = msvcrt.getwch()
            if key in ("\x00", "\xe0"):
                msvcrt.getwch()
                continue
            if key == "\x1b":
                requested = True
        return requested

    def display(self) -> None:
        banner = new_screen(animate=False, with_header=False)
        set_banner(None)
        self._banner = banner if self._animate else None
        self._lines_below = 0
        self._say(header())

        if self.config.created_default:
            self._say(paint("  A new configuration was created with the defaults.", GREY))

        binds = self.config.get("keybinds")
        hotkeys: list[Option] = [
            (str(binds[macro]).upper(), MACRO_LABELS[macro], "") for macro in MACRO_TYPES
        ]
        hotkeys.append((str(binds["stop"]).upper(), "Stop the macro", ""))
        if self._allow_back:
            hotkeys.append((BACK, "Back to the menu", ""))
        self._say(render_options("Hotkeys", hotkeys))
        self._say("")

        safety = self.config.get("safety")
        active = []
        if safety.get("require_window_focus"):
            active.append(f"window focus on {safety.get('window_title_contains')!r}")
        if safety.get("mouse_failsafe"):
            active.append(f"mouse failsafe ({safety.get('mouse_failsafe_px')} px)")
        if float(safety.get("max_session_seconds") or 0) > 0:
            active.append(f"session limit {float(safety['max_session_seconds']) / 60:.0f} min")
        detail = ", ".join(active) if active else "none enabled"
        self._say(f"  {paint('Failsafes:', BOLD, GREEN if active else RED)} {paint(detail, GREY)}")

        if self.controller.input_mode == "background":
            self._say(
                f"  {paint('Input:', BOLD, YELLOW)} " + paint("background, posted to the game window", GREY)
            )
        self._say("")

    def _register_hotkeys(self) -> None:
        import keyboard

        self._keyboard = keyboard
        binds = self.config.get("keybinds")
        suppress = self.config.flag("general", "suppress_hotkeys", default=True)

        try:
            for macro in MACRO_TYPES:
                keyboard.add_hotkey(str(binds[macro]), self._start_callback(macro), suppress=suppress)
            keyboard.add_hotkey(str(binds["stop"]), self._on_stop, suppress=suppress)
        except (OSError, ValueError) as exc:
            raise RuntimeError(
                f"Could not register the hotkeys ({exc}). "
                "On Windows this usually means HyMacro needs to run as administrator."
            ) from exc

    def _start_callback(self, macro: str) -> Callable[[], None]:
        def callback() -> None:
            try:
                started, reason = self.controller.start(macro)
                if started:
                    self._say(f"  {paint('RUN ', BOLD, GREEN)} {paint(MACRO_LABELS[macro], WHITE)}")
                else:
                    self._say(f"  {paint('HELD', BOLD, RED)} {MACRO_LABELS[macro]}: {reason}")
            except Exception:
                logger.exception("Hotkey for %s failed", macro)

        return callback

    def _on_stop(self) -> None:
        try:
            if self.controller.is_running:
                self.controller.request_stop("stopped manually")
            else:
                self._say(f"  {paint('STOP', BOLD, YELLOW)} No macro is running")
        except Exception:
            logger.exception("Stop hotkey failed")

    def run(self) -> int:
        self.display()

        try:
            self._register_hotkeys()
        except RuntimeError as exc:
            self._say(f"  {paint('Error:', BOLD, RED)} {exc}")
            return 1

        interval = self.config.number("general", "idle_poll_seconds", default=0.05)
        interval = min(max(0.01, interval), 1 / 15) if self._banner else max(0.01, interval)

        try:
            while self._alive:
                if self._back_requested():
                    self.back_to_menu = True
                    break
                self._refresh_banner()
                time.sleep(interval)
        except KeyboardInterrupt:
            self._say("")
        finally:
            self._shutdown()
        return 0

    def _shutdown(self) -> None:
        self._alive = False
        self.controller.request_stop("shutting down")
        self.controller.join(timeout=5.0)
        self.controller.input.release_all()
        if self._keyboard is not None:
            try:
                self._keyboard.unhook_all()
            except Exception:
                logger.exception("Failed to release the keyboard hooks")


def run_headless(config: Config) -> int:
    """Run the macro screen without the menu around it."""
    if sys.platform != "win32":
        print("HyMacro only runs on Windows.", file=sys.stderr)
        return 1
    return MacroApp(config).run()


__all__ = ["BANNER", "MacroApp", "header", "run_headless", "setup_logging"]
