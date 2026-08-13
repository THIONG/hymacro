"""Interfaz de consola: registra los hotkeys globales y reporta el estado."""

from __future__ import annotations

import contextlib
import logging
import sys
import threading
import time
from collections.abc import Callable
from typing import Any

from . import __version__
from .config import MACRO_TYPES, ConfigError, ConfigManager, app_dir
from .controller import MacroController, MacroEvent

logger = logging.getLogger(__name__)

_BANNER = r"""
+==============================================================================+
|     /$$   /$$           /$$      /$$                                         |
|    | $$  | $$          | $$$    /$$$                                         |
|    | $$  | $$ /$$   /$$| $$$$  /$$$$  /$$$$$$   /$$$$$$$  /$$$$$$  /$$$$$$   |
|    | $$$$$$$$| $$  | $$| $$ $$/$$ $$ |____  $$ /$$_____/ /$$__  $$/$$__  $$  |
|    | $$__  $$| $$  | $$| $$  $$$| $$  /$$$$$$$| $$      | $$  \__/ $$  \ $$  |
|    | $$  | $$| $$  | $$| $$\  $ | $$ /$$__  $$| $$      | $$     | $$  | $$  |
|    | $$  | $$|  $$$$$$$| $$ \/  | $$|  $$$$$$$|  $$$$$$$| $$     |  $$$$$$/  |
|    |__/  |__/ \____  $$|__/     |__/ \_______/ \_______/|__/      \______/   |
|               /$$  | $$                                                      |
|              |  $$$$$$/                                                      |
|               \______/                                                       |
+==============================================================================+
"""

_LABELS = {
    "cocoa_beans": "Cocoa Beans",
    "nether_wart": "Nether Wart",
    "cobblestone": "Cobblestone",
}


def enable_utf8_console() -> None:
    """Intenta poner la consola en UTF-8 para que el banner no se rompa."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            # Consolas exoticas pueden rechazar el cambio; no es motivo para no arrancar.
            with contextlib.suppress(OSError, ValueError):
                reconfigure(encoding="utf-8", errors="replace")


def setup_logging(verbose: bool = False) -> None:
    """Log detallado al fichero, solo avisos por consola.

    El flujo normal ya se imprime bonito, asi que duplicarlo en el log de
    consola solo ensuciaba la pantalla en la v2.
    """
    log_path = app_dir() / "hymacro.log"
    handlers: list[logging.Handler] = []
    try:
        file_handler = logging.FileHandler(log_path, encoding="utf-8")
        file_handler.setLevel(logging.DEBUG)
        handlers.append(file_handler)
    except OSError:  # pragma: no cover - carpeta de solo lectura
        pass

    console = logging.StreamHandler()
    console.setLevel(logging.DEBUG if verbose else logging.WARNING)
    handlers.append(console)

    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
        handlers=handlers,
        force=True,
    )


class HyMacroApp:
    """Aplicacion principal que maneja la interfaz y los controles."""

    def __init__(self, config_path: str | None = None) -> None:
        self.config = ConfigManager(config_path)
        self.controller = MacroController(self.config, on_event=self._handle_event)
        self._print_lock = threading.Lock()
        self._alive = True
        self._keyboard: Any = None

    # --- salida por consola ---

    def _say(self, message: str) -> None:
        with self._print_lock:
            print(message, flush=True)

    def _handle_event(self, event: MacroEvent) -> None:
        prefix = {
            "info": "  ->",
            "cycle": "  ..",
            "stop": "[STOP]",
            "stats": "[STATS]",
        }.get(event.level, "  ")
        self._say(f"{prefix} {event.message}")

    def display_banner(self) -> None:
        self._say(_BANNER)
        self._say(f"  HyMacro v{__version__} - Hypixel Garden Automation Tool")
        self._say(f"  Config: {self.config.config_path}")
        if self.config.created_default:
            self._say("  (se genero una configuracion nueva con los valores por defecto)")
        self._say("")

        binds = self.config.get("keybinds")
        for macro_type in MACRO_TYPES:
            key = str(binds[macro_type]).upper()
            self._say(f"    {key:<5} -> {_LABELS[macro_type]}")
        self._say(f"    {str(binds['stop']).upper():<5} -> DETENER macro")
        self._say("    CTRL+C -> salir de HyMacro")
        self._say("")

        safety = self.config.get("safety")
        active = []
        if safety.get("require_window_focus"):
            active.append(f"foco en '{safety.get('window_title_contains')}'")
        if safety.get("mouse_failsafe"):
            active.append(f"failsafe de raton ({safety.get('mouse_failsafe_px')} px)")
        if float(safety.get("max_session_minutes") or 0) > 0:
            active.append(f"limite de sesion {safety.get('max_session_minutes')} min")
        self._say(f"  Failsafes: {', '.join(active) if active else 'ninguno (!)'}")
        self._say("")

    # --- hotkeys ---

    def _register_hotkeys(self) -> None:
        import keyboard  # import diferido: instala un hook global al importarse

        self._keyboard = keyboard
        binds = self.config.get("keybinds")
        suppress = self.config.get_bool("general", "suppress_hotkeys", default=True)

        try:
            for macro_type in MACRO_TYPES:
                keyboard.add_hotkey(
                    str(binds[macro_type]),
                    self._make_start_callback(macro_type),
                    suppress=suppress,
                )
            keyboard.add_hotkey(str(binds["stop"]), self._on_stop_pressed, suppress=suppress)
        except (OSError, ValueError) as exc:
            raise RuntimeError(
                f"No se pudieron registrar los hotkeys ({exc}). "
                "En Windows suele hacer falta ejecutar HyMacro como administrador."
            ) from exc

    def _make_start_callback(self, macro_type: str) -> Callable[[], None]:
        def callback() -> None:
            # Este callback corre en el hilo del hook de teclado: tiene que
            # volver de inmediato o se atasca la entrada de todo el sistema.
            try:
                started, reason = self.controller.start(macro_type)
                if started:
                    self._say(f"[START] {_LABELS[macro_type]}")
                else:
                    self._say(f"[NO] No se arranco {_LABELS[macro_type]}: {reason}")
            except Exception:
                logger.exception("Error en el hotkey de %s", macro_type)

        return callback

    def _on_stop_pressed(self) -> None:
        try:
            if self.controller.is_running:
                self.controller.request_stop("parada manual (hotkey)")
            else:
                self._say("[STOP] No hay ningun macro en marcha")
        except Exception:
            logger.exception("Error en el hotkey de parada")

    # --- bucle principal ---

    def run(self) -> int:
        self.display_banner()

        try:
            self._register_hotkeys()
        except RuntimeError as exc:
            self._say(f"[ERROR] {exc}")
            return 1

        loop_delay = self.config.get_float("general", "loop_delay_ms", default=100) / 1000.0
        loop_delay = max(0.01, loop_delay)

        try:
            while self._alive:
                time.sleep(loop_delay)
        except KeyboardInterrupt:
            self._say("")
            self._say("Cerrando HyMacro...")
        finally:
            self._shutdown()
        return 0

    def _shutdown(self) -> None:
        self._alive = False
        self.controller.request_stop("cierre de la aplicacion")
        self.controller.join(timeout=5.0)
        # Cinturon y tirantes: si el hilo del macro no llego a limpiar, se hace aqui.
        self.controller.input.release_all()
        if self._keyboard is not None:
            try:
                self._keyboard.unhook_all()
            except Exception:
                logger.exception("Error liberando los hooks de teclado")
        self._say("Hasta luego!")


def check_config(config_path: str | None = None) -> int:
    """Valida la configuracion y sale. Se usa como smoke test en CI."""
    enable_utf8_console()
    try:
        config = ConfigManager(config_path, auto_create=False)
    except ConfigError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(f"Configuracion valida: {config.config_path}")
    for macro_type in MACRO_TYPES:
        bind = str(config.get("keybinds", macro_type)).upper()
        print(f"  {bind:<5} -> {macro_type}")
    print(f"  {str(config.get('keybinds', 'stop')).upper():<5} -> stop")
    return 0
