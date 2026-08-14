"""Macro execution: runs the routes on a worker thread and knows how to stop."""

from __future__ import annotations

import logging
import random
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from .background import BackgroundBackend
from .config import Config
from .safety import SafetyGuard, SafetyLimits
from .winput import InputBackend, InputError

logger = logging.getLogger(__name__)

_COMMAND_SETTLE_SECONDS = 0.35


@dataclass
class SessionStats:
    """Counters for a macro session."""

    macro_type: str = ""
    started_at: float = 0.0
    finished_at: float = 0.0
    routes: int = 0
    cycles: int = 0
    warps: int = 0
    commands: int = 0

    @property
    def elapsed_seconds(self) -> float:
        if not self.started_at:
            return 0.0
        end = self.finished_at or time.monotonic()
        return max(0.0, end - self.started_at)

    def summary(self) -> str:
        minutes, seconds = divmod(int(self.elapsed_seconds), 60)
        hours, minutes = divmod(minutes, 60)
        parts = [f"runtime {hours:d}:{minutes:02d}:{seconds:02d}", f"cycles {self.cycles}"]
        parts.append(f"warps {self.warps}")
        if self.routes:
            parts.append(f"routes {self.routes}")
        return " | ".join(parts)


@dataclass(frozen=True)
class MacroEvent:
    """Message sent from the controller to the interface."""

    level: str
    message: str


EventHandler = Callable[[MacroEvent], None]


class MacroController:
    """Starts, runs and stops the different macro types."""

    def __init__(self, config: Config, on_event: EventHandler | None = None) -> None:
        self.config = config
        self.input_mode = config.text("general", "input_mode", default="foreground")
        self.input = self._make_backend()
        self.stats = SessionStats()

        self._on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._stop_reason: str | None = None
        self._guard = SafetyGuard(SafetyLimits.from_config(config), self._on_guard_violation)

        self._mouse_button = config.text("general", "mouse_button", default="left")
        self._chat_key = config.text("general", "chat_key", default="t")
        self._chat_open = config.number("general", "chat_open_seconds", default=0.12)
        self._command_mode = config.text("general", "command_input_mode", default="unicode")
        self._step_jitter = config.number("general", "step_jitter_seconds", default=0.008)
        self._wait_jitter_percent = config.number("general", "wait_jitter_percent", default=5.0)
        self._wait_jitter_max = config.number("general", "wait_jitter_max_seconds", default=0.5)

    def _make_backend(self) -> InputBackend | BackgroundBackend:
        if self.input_mode == "background":
            title = self.config.text("safety", "window_title_contains", default="Minecraft")
            return BackgroundBackend(title)
        return InputBackend()

    @property
    def is_running(self) -> bool:
        thread = self._thread
        return thread is not None and thread.is_alive()

    def start(self, macro_type: str) -> tuple[bool, str]:
        """Start a macro. Returns whether it started and why it did not."""
        with self._lock:
            if self.is_running:
                return False, "a macro is already running"

            rejection = self._guard.preflight()
            if rejection is not None:
                return False, rejection

            self._stop.clear()
            self._stop_reason = None
            self.stats = SessionStats(macro_type=macro_type, started_at=time.monotonic())
            self._thread = threading.Thread(
                target=self._run,
                args=(macro_type,),
                name=f"hymacro-{macro_type}",
                daemon=True,
            )
            self._thread.start()
        return True, ""

    def request_stop(self, reason: str = "stopped manually") -> None:
        """Ask the macro to stop. Safe to call from any thread."""
        with self._lock:
            if self._stop_reason is None:
                self._stop_reason = reason
            self._stop.set()

    def join(self, timeout: float | None = None) -> None:
        thread = self._thread
        if thread is not None:
            thread.join(timeout=timeout)

    def _on_guard_violation(self, reason: str) -> None:
        self.request_stop(reason)

    def _emit(self, level: str, message: str) -> None:
        self._on_event(MacroEvent(level=level, message=message))

    def _run(self, macro_type: str) -> None:
        self._guard.arm()
        try:
            if macro_type == "cobblestone":
                self._run_cobblestone()
            else:
                self._run_routes(macro_type)
        except InputError as exc:
            self.request_stop(f"input injection failed: {exc}")
            logger.error("Input failure during %s: %s", macro_type, exc)
        except Exception as exc:
            self.request_stop(f"unexpected error: {exc}")
            logger.exception("Error running macro %s", macro_type)
        finally:
            self.input.release_all()
            self._guard.disarm()
            self.stats.finished_at = time.monotonic()
            self._emit("stop", self._stop_reason or "macro finished")
            self._emit("stats", self.stats.summary())

    def _sleep(self, seconds: float) -> bool:
        """Interruptible sleep. False when a stop was requested."""
        if seconds <= 0:
            return not self._stop.is_set()
        return not self._stop.wait(seconds)

    def _jitter(self, seconds: float) -> float:
        """Vary a duration slightly, capped so long legs stay aligned.

        A percentage alone is not enough: five percent of a two minute leg is
        six seconds, which is plenty to overshoot a row.
        """
        if seconds <= 0:
            return 0.0
        spread = seconds * (self._wait_jitter_percent / 100.0)
        if self._wait_jitter_max > 0:
            spread = min(spread, self._wait_jitter_max)
        if spread <= 0:
            return seconds
        return max(0.0, seconds + random.uniform(-spread, spread))

    def _jitter_step(self, seconds: float) -> float:
        if self._step_jitter <= 0:
            return max(0.0, seconds)
        return max(0.0, seconds + random.uniform(-self._step_jitter, self._step_jitter))

    def _send_command(self, command: str) -> bool:
        """Open the chat, type a command and send it."""
        if not command:
            return True
        self.input.tap(self._chat_key)
        if not self._sleep(self._chat_open):
            return False
        self.input.type_text(command, mode=self._command_mode)
        self.input.tap("enter")
        self.stats.commands += 1
        return self._sleep(_COMMAND_SETTLE_SECONDS)

    def _run_leg(self, keys: list[str], hold_seconds: float, step_seconds: float) -> bool:
        """Hold the first key for the leg, then add the second one to turn."""
        keep_going = True
        try:
            self.input.mouse_down(self._mouse_button)
            self.input.key_down(keys[0])

            if hold_seconds > 0:
                keep_going = self._sleep(self._jitter(hold_seconds))

            if keep_going:
                self.input.key_down(keys[1])
                keep_going = self._sleep(self._jitter_step(step_seconds))
        finally:
            self.input.mouse_up(self._mouse_button)
            self.input.key_up(keys[1])
            self.input.key_up(keys[0])
        return keep_going

    def _hold(self, key: str, seconds: float) -> bool:
        try:
            self.input.mouse_down(self._mouse_button)
            self.input.key_down(key)
            return self._sleep(self._jitter(seconds))
        finally:
            self.input.mouse_up(self._mouse_button)
            self.input.key_up(key)

    def _run_routes(self, macro_type: str) -> None:
        macro = self.config.get("macros", macro_type)
        keys = [str(key) for key in macro["keys"]]
        routes_per_warp = int(macro["routes_per_warp"])
        step = float(macro["step_seconds"])
        forward = max(0.0, float(macro.get("forward_seconds", 0.0)))
        backward = max(0.0, float(macro.get("return_seconds", 0.0)))
        warp = self.config.text("commands", "warp_garden")

        self._emit(
            "info",
            f"{routes_per_warp} laps per warp ({routes_per_warp * 2} rows), "
            f"out {forward:.1f}s, back {backward:.1f}s, step {step:.2f}s",
        )
        if forward <= 0:
            self._emit("warn", "forward_seconds is 0, so the outward leg will not move you")

        while not self._stop.is_set():
            for _ in range(routes_per_warp):
                if not self._run_leg(keys[0:2], forward, step):
                    return
                if not self._run_leg(keys[2:4], backward, step):
                    return
                self.stats.routes += 2

            if not self._send_command(warp):
                return
            self.stats.warps += 1
            self.stats.cycles += 1
            self._emit("cycle", f"cycle {self.stats.cycles} complete")

    def _run_cobblestone(self) -> None:
        macro = self.config.get("macros", "cobblestone")
        key = str(macro["key"])
        mining = float(macro["mining_seconds"])
        hub_wait = float(macro.get("hub_wait_seconds", 3.0))
        warp_hub = self.config.text("commands", "warp_hub")
        warp_island = self.config.text("commands", "warp_island")

        self._emit("info", f"{mining:.0f}s of mining per cycle")

        while not self._stop.is_set():
            if not self._hold(key, mining):
                return
            if not self._send_command(warp_hub):
                return
            if not self._sleep(self._jitter(hub_wait)):
                return
            if not self._send_command(warp_island):
                return

            self.stats.warps += 2
            self.stats.cycles += 1
            self._emit("cycle", f"cycle {self.stats.cycles} complete")
