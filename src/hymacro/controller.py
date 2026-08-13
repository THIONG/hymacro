"""Controlador de macros: ejecuta las rutas en un hilo aparte y sabe pararse."""

from __future__ import annotations

import logging
import random
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .config import ConfigManager
from .safety import SafetyGuard, SafetyLimits
from .winput import InputBackend, InputError

logger = logging.getLogger(__name__)

#: Pausa despues de enviar un comando al chat, para que el servidor procese el warp.
_POST_COMMAND_SETTLE_SECONDS = 0.35


def resolve_forward_hold(macro: dict[str, Any]) -> float:
    """Segundos que se mantiene la primera tecla antes de pulsar la de giro.

    Este es el tramo que de verdad te desplaza por el plot; `timing_ms` solo
    cubre el giro. En la v2 el concepto existia escondido bajo el nombre
    `cocoa_wait_seconds` y solo se activaba con `use_cocoa_wait`, asi que
    nether_wart nunca caminaba. Se sigue leyendo el par antiguo para no romper
    los config.json de la v2.
    """
    if "forward_seconds" in macro:
        return max(0.0, float(macro["forward_seconds"]))
    if macro.get("use_cocoa_wait", False):
        return max(0.0, float(macro.get("cocoa_wait_seconds", 0)))
    return 0.0


@dataclass
class SessionStats:
    """Contadores de una sesion de macro."""

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
        elapsed = self.elapsed_seconds
        minutes, seconds = divmod(int(elapsed), 60)
        hours, minutes = divmod(minutes, 60)
        clock = f"{hours:d}:{minutes:02d}:{seconds:02d}"
        parts = [f"tiempo activo {clock}", f"ciclos {self.cycles}", f"warps {self.warps}"]
        if self.routes:
            parts.append(f"rutas {self.routes}")
        return " | ".join(parts)


@dataclass(frozen=True)
class MacroEvent:
    """Mensaje que el controlador manda a la interfaz.

    `level` es uno de: info, cycle, stop, stats.
    """

    level: str
    message: str


EventHandler = Callable[[MacroEvent], None]


class MacroController:
    """Arranca, ejecuta y detiene los distintos tipos de macro.

    A diferencia de la v2, `start()` no bloquea: lanza un hilo worker y vuelve
    enseguida, de forma que el hotkey de parada sigue respondiendo mientras el
    macro corre.
    """

    def __init__(self, config: ConfigManager, on_event: EventHandler | None = None) -> None:
        self.config = config
        self.input = InputBackend()
        self.stats = SessionStats()

        self._on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._stop_reason: str | None = None
        self._guard = SafetyGuard(SafetyLimits.from_config(config), self._on_guard_violation)

        self._mouse_button = config.get_str("general", "mouse_button", default="left")
        self._chat_key = config.get_str("general", "chat_key", default="t")
        self._chat_open_delay = config.get_float("general", "chat_open_delay_ms", default=120) / 1000.0
        self._command_mode = config.get_str("general", "command_input_mode", default="unicode")
        self._timing_jitter_ms = config.get_float("general", "timing_jitter_ms", default=8)
        self._wait_jitter_percent = config.get_float("general", "wait_jitter_percent", default=5)

    # --- ciclo de vida ---

    @property
    def is_running(self) -> bool:
        thread = self._thread
        return thread is not None and thread.is_alive()

    def start(self, macro_type: str) -> tuple[bool, str]:
        """Arranca un macro. Devuelve (arrancado, motivo si no arranco)."""
        with self._lock:
            if self.is_running:
                return False, "ya hay un macro en marcha"

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

    def request_stop(self, reason: str = "parada manual") -> None:
        """Pide la parada del macro. Se puede llamar desde cualquier hilo."""
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
                self._loop_cobblestone()
            else:
                self._loop_routes(macro_type)
        except InputError as exc:
            self.request_stop(f"fallo al inyectar entrada: {exc}")
            logger.error("Fallo de entrada durante %s: %s", macro_type, exc)
        except Exception as exc:
            self.request_stop(f"error inesperado: {exc}")
            logger.exception("Error ejecutando el macro %s", macro_type)
        finally:
            # Orden importante: primero soltar teclas, luego apagar el watchdog.
            self.input.release_all()
            self._guard.disarm()
            self.stats.finished_at = time.monotonic()
            self._emit("stop", self._stop_reason or "macro finalizado")
            self._emit("stats", self.stats.summary())

    # --- temporizacion ---

    def _sleep(self, seconds: float) -> bool:
        """Duerme de forma interrumpible. Devuelve False si se pidio parar."""
        if seconds <= 0:
            return not self._stop.is_set()
        return not self._stop.wait(seconds)

    def _jittered_ms(self, milliseconds: float) -> float:
        """Convierte ms a segundos aplicando una variacion aleatoria."""
        if self._timing_jitter_ms <= 0:
            return milliseconds / 1000.0
        delta = random.uniform(-self._timing_jitter_ms, self._timing_jitter_ms)
        return max(0.0, milliseconds + delta) / 1000.0

    def _jittered_seconds(self, seconds: float) -> float:
        """Aplica una variacion porcentual a una espera larga."""
        if seconds <= 0 or self._wait_jitter_percent <= 0:
            return max(0.0, seconds)
        spread = seconds * (self._wait_jitter_percent / 100.0)
        return max(0.0, seconds + random.uniform(-spread, spread))

    # --- primitivas ---

    def _send_command(self, command: str) -> bool:
        """Abre el chat, escribe el comando y lo envia."""
        if not command:
            return True
        self.input.tap(self._chat_key)
        # La v2 escribia inmediatamente y el chat se comia los primeros
        # caracteres si el juego iba con lag.
        if not self._sleep(self._chat_open_delay):
            return False
        self.input.type_text(command, mode=self._command_mode)
        self.input.tap("enter")
        self.stats.commands += 1
        return self._sleep(_POST_COMMAND_SETTLE_SECONDS)

    def _execute_route(self, keys: list[str], lead_wait_seconds: float, timing_ms: float) -> bool:
        """Ejecuta un tramo: mantiene el click y encadena dos teclas de movimiento."""
        keep_going = True
        try:
            self.input.mouse_down(self._mouse_button)
            self.input.key_down(keys[0])

            if lead_wait_seconds > 0:
                keep_going = self._sleep(self._jittered_seconds(lead_wait_seconds))

            if keep_going:
                self.input.key_down(keys[1])
                keep_going = self._sleep(self._jittered_ms(timing_ms))
        finally:
            self.input.mouse_up(self._mouse_button)
            self.input.key_up(keys[1])
            self.input.key_up(keys[0])
        return keep_going

    def _hold(self, key: str, seconds: float) -> bool:
        """Mantiene click + tecla durante el tiempo indicado."""
        try:
            self.input.mouse_down(self._mouse_button)
            self.input.key_down(key)
            return self._sleep(self._jittered_seconds(seconds))
        finally:
            self.input.mouse_up(self._mouse_button)
            self.input.key_up(key)

    # --- bucles de macro ---

    def _loop_routes(self, macro_type: str) -> None:
        macro = self.config.get("macros", macro_type)
        keys = [str(key) for key in macro["keys"]]
        routes_per_warp = int(macro["routes_per_warp"])
        timing_ms = float(macro["timing_ms"])
        forward = resolve_forward_hold(macro)
        warp_command = self.config.get_str("commands", "warp_garden")

        self._emit(
            "info",
            f"{macro_type}: {routes_per_warp} recorridos por warp, "
            f"avance {forward:.2f} s, giro {timing_ms:.0f} ms",
        )
        if forward <= 0:
            self._emit(
                "info",
                f"AVISO: macros.{macro_type}.forward_seconds es 0, "
                "asi que no se camina antes de girar y no avanzaras.",
            )

        while not self._stop.is_set():
            for _ in range(routes_per_warp):
                if not self._execute_route(keys[0:2], forward, timing_ms):
                    return
                if not self._execute_route(keys[2:4], 0.0, timing_ms):
                    return
                self.stats.routes += 2

            if not self._send_command(warp_command):
                return
            self.stats.warps += 1
            self.stats.cycles += 1
            self._emit("cycle", f"ciclo {self.stats.cycles} completado")

    def _loop_cobblestone(self) -> None:
        macro = self.config.get("macros", "cobblestone")
        key = str(macro["key"])
        mining_seconds = float(macro["mining_duration_seconds"])
        hub_wait = float(macro.get("hub_wait_seconds", 3))
        warp_hub = self.config.get_str("commands", "warp_hub")
        warp_island = self.config.get_str("commands", "warp_island")

        self._emit("info", f"cobblestone: {mining_seconds:.0f} s de minado por ciclo")

        while not self._stop.is_set():
            if not self._hold(key, mining_seconds):
                return

            if not self._send_command(warp_hub):
                return
            if not self._sleep(self._jittered_seconds(hub_wait)):
                return
            if not self._send_command(warp_island):
                return

            self.stats.warps += 2
            self.stats.cycles += 1
            self._emit("cycle", f"ciclo {self.stats.cycles} completado")
