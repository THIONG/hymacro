"""Route execution, timing and stopping."""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any

import pytest

from hymacro.config import DEFAULTS, Config
from hymacro.controller import MacroController, SessionStats


class Recorder:
    """A backend that records what would have been pressed."""

    def __init__(self) -> None:
        self.events: list[tuple[float, str, str]] = []
        self.held: set[str] = set()
        self.commands: list[str] = []
        self._start = time.perf_counter()

    def _record(self, kind: str, key: str) -> None:
        self.events.append((time.perf_counter() - self._start, kind, key))

    def mouse_down(self, button: str) -> None:
        self.held.add(f"mouse:{button}")

    def mouse_up(self, button: str) -> None:
        self.held.discard(f"mouse:{button}")

    def key_down(self, key: str) -> None:
        self.held.add(key)
        self._record("down", key)

    def key_up(self, key: str) -> None:
        self.held.discard(key)
        self._record("up", key)

    def tap(self, key: str) -> None:
        self._record("tap", key)

    def type_text(self, text: str, mode: str = "unicode") -> None:
        self.commands.append(text)

    def release_all(self) -> None:
        self.held.clear()

    def hold_time(self, key: str) -> float:
        total = 0.0
        opened: float | None = None
        for moment, kind, pressed in self.events:
            if pressed != key:
                continue
            if kind == "down":
                opened = moment
            elif kind == "up" and opened is not None:
                total += moment - opened
                opened = None
        return total


def make_controller(tmp_path: Path, **nether: Any) -> tuple[MacroController, Recorder]:
    data = json.loads(json.dumps(DEFAULTS))
    data["macros"]["nether_wart"].update(nether)
    data["safety"]["require_window_focus"] = False
    data["safety"]["mouse_failsafe"] = False
    path = tmp_path / "config.json"
    path.write_text(json.dumps(data), encoding="utf-8")

    controller = MacroController(Config(path, auto_create=False))
    recorder = Recorder()
    controller.input = recorder  # type: ignore[assignment]
    return controller, recorder


def run_one_cycle(controller: MacroController, macro: str = "nether_wart") -> None:
    def stopper() -> None:
        while controller.stats.cycles < 1 and not controller._stop.is_set():
            time.sleep(0.005)
        controller.request_stop("test finished")

    threading.Thread(target=stopper, daemon=True).start()
    controller._run_routes(macro)


def test_the_serpentine_alternates_sides(tmp_path: Path) -> None:
    controller, recorder = make_controller(
        tmp_path, forward_seconds=0.05, return_seconds=0.05, step_seconds=0.01, routes_per_warp=2
    )
    run_one_cycle(controller)

    long_legs = [key for _, kind, key in recorder.events if kind == "down" and key in ("d", "a")]
    assert long_legs[:4] == ["d", "a", "d", "a"]


def test_both_legs_honour_their_duration(tmp_path: Path) -> None:
    """The return leg used to be hard coded to zero, so it never moved."""
    controller, recorder = make_controller(
        tmp_path, forward_seconds=0.2, return_seconds=0.2, step_seconds=0.01, routes_per_warp=1
    )
    run_one_cycle(controller)

    assert recorder.hold_time("d") == pytest.approx(0.2, abs=0.08)
    assert recorder.hold_time("a") == pytest.approx(0.2, abs=0.08)


def test_a_warp_is_sent_after_the_laps(tmp_path: Path) -> None:
    controller, recorder = make_controller(
        tmp_path, forward_seconds=0.01, return_seconds=0.01, step_seconds=0.01, routes_per_warp=1
    )
    run_one_cycle(controller)

    assert recorder.commands == ["/warp garden"]
    assert controller.stats.warps == 1


def test_stopping_releases_everything(tmp_path: Path) -> None:
    controller, recorder = make_controller(
        tmp_path, forward_seconds=30.0, return_seconds=30.0, step_seconds=0.01
    )

    started, reason = controller.start("nether_wart")
    assert started, reason
    time.sleep(0.3)
    assert recorder.held, "the macro should be holding something down"

    controller.request_stop("stop requested")
    controller.join(timeout=5.0)

    assert not controller.is_running
    assert not recorder.held


def test_the_jitter_cannot_drift_a_long_leg(tmp_path: Path) -> None:
    """Five percent of two minutes is six seconds, enough to overshoot a row."""
    controller, _ = make_controller(tmp_path)

    samples = [controller._jitter(120.0) for _ in range(500)]

    assert max(abs(sample - 120.0) for sample in samples) <= 0.5
    assert len(set(samples)) > 1, "the jitter should still vary"


def test_the_summary_reads_cleanly() -> None:
    stats = SessionStats(macro_type="nether_wart", started_at=1.0, finished_at=3726.0)
    stats.cycles, stats.warps, stats.routes = 3, 3, 24

    assert stats.summary() == "runtime 1:02:05 | cycles 3 | warps 3 | routes 24"
