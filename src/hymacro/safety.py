"""Failsafes que detienen el macro cuando algo se sale de lo previsto.

El macro corre en su propio hilo y pasa mucho tiempo dormido (la ruta de
cobblestone son 4 minutos seguidos). Un watchdog aparte vigila el entorno cada
100 ms y pide la parada, asi los failsafes reaccionan igual de rapido durante
una espera larga que entre dos pulsaciones.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from .config import ConfigManager
from .winput import cursor_position, foreground_window_title, is_key_held

logger = logging.getLogger(__name__)

#: Margen tras arrancar antes de empezar a exigir el foco, para dar tiempo a que
#: Windows termine de cambiar de ventana.
_FOCUS_GRACE_SECONDS = 1.0


@dataclass(frozen=True)
class SafetyLimits:
    """Limites configurados para una sesion de macro."""

    require_focus: bool
    title_contains: str
    mouse_failsafe: bool
    mouse_threshold_px: int
    max_session_seconds: float
    interval_seconds: float
    stop_key: str = ""

    @classmethod
    def from_config(cls, config: ConfigManager) -> SafetyLimits:
        return cls(
            require_focus=config.get_bool("safety", "require_window_focus", default=True),
            title_contains=config.get_str("safety", "window_title_contains", default="Minecraft"),
            mouse_failsafe=config.get_bool("safety", "mouse_failsafe", default=True),
            mouse_threshold_px=config.get_int("safety", "mouse_failsafe_px", default=100),
            max_session_seconds=config.get_float("safety", "max_session_minutes", default=0) * 60.0,
            interval_seconds=config.get_float("safety", "watchdog_interval_ms", default=100) / 1000.0,
            stop_key=config.get_str("keybinds", "stop", default=""),
        )


def window_matches(title: str, needle: str) -> bool:
    """True si el titulo de ventana contiene `needle` (sin distinguir mayusculas)."""
    if not needle:
        return True
    return needle.casefold() in title.casefold()


class SafetyGuard:
    """Vigila foco de ventana, movimiento del raton y duracion de la sesion."""

    def __init__(self, limits: SafetyLimits, on_violation: Callable[[str], None]) -> None:
        self._limits = limits
        self._on_violation = on_violation
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._origin: tuple[int, int] = (0, 0)
        self._started_at: float = 0.0

    def arm(self) -> None:
        """Toma la referencia inicial y arranca el hilo de vigilancia."""
        self._stop.clear()
        self._origin = cursor_position()
        self._started_at = time.monotonic()
        self._thread = threading.Thread(target=self._watch, name="hymacro-watchdog", daemon=True)
        self._thread.start()

    def disarm(self) -> None:
        """Detiene el hilo de vigilancia."""
        self._stop.set()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)
        self._thread = None

    def preflight(self) -> str | None:
        """Comprueba las condiciones antes de arrancar. Devuelve el motivo del rechazo."""
        if self._limits.require_focus:
            title = foreground_window_title()
            if not window_matches(title, self._limits.title_contains):
                shown = title or "(sin titulo)"
                return f"la ventana activa es {shown!r} y no contiene {self._limits.title_contains!r}"
        return None

    def _watch(self) -> None:
        while not self._stop.wait(self._limits.interval_seconds):
            reason = self._check()
            if reason is not None:
                logger.info("Failsafe disparado: %s", reason)
                self._on_violation(reason)
                return

    def _check(self) -> str | None:
        elapsed = time.monotonic() - self._started_at

        # Segunda via para la tecla de parada, independiente del hook de
        # `keyboard`: aqui se pregunta el estado al sistema en vez de esperar a
        # que alguien nos entregue el evento. El hook puede dejar de entregar
        # (Windows desengancha los hooks lentos), y quedarse sin forma de parar
        # con una tecla mantenida 2 minutos es lo peor que puede pasar.
        if self._limits.stop_key and is_key_held(self._limits.stop_key):
            return f"parada manual ({self._limits.stop_key.upper()})"

        if self._limits.max_session_seconds > 0 and elapsed >= self._limits.max_session_seconds:
            minutes = self._limits.max_session_seconds / 60
            return f"se alcanzo el limite de sesion ({minutes:.0f} min)"

        if self._limits.require_focus and elapsed >= _FOCUS_GRACE_SECONDS:
            title = foreground_window_title()
            if not window_matches(title, self._limits.title_contains):
                shown = title or "(sin titulo)"
                return f"Minecraft perdio el foco (ventana activa: {shown!r})"

        if self._limits.mouse_failsafe:
            x, y = cursor_position()
            dx = abs(x - self._origin[0])
            dy = abs(y - self._origin[1])
            if max(dx, dy) > self._limits.mouse_threshold_px:
                return f"el raton se movio {max(dx, dy)} px (limite: {self._limits.mouse_threshold_px})"

        return None
