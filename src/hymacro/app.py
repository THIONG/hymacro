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


def _load_for_diagnostic(config_path: str | None) -> ConfigManager | None:
    enable_utf8_console()
    if sys.platform != "win32":
        print("[ERROR] Los diagnosticos solo funcionan en Windows.", file=sys.stderr)
        return None
    try:
        return ConfigManager(config_path, auto_create=False)
    except ConfigError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return None


def _countdown(seconds: int) -> None:
    print("  Pon Minecraft en primer plano AHORA.")
    for remaining in range(seconds, 0, -1):
        print(f"  {remaining}...", flush=True)
        time.sleep(1)


def test_move(config_path: str | None = None, key: str | None = None, seconds: float = 3.0) -> int:
    """Mantiene una tecla de movimiento para ver si el juego la registra.

    Separa dos fallos que se parecen: que la entrada no llegue a Minecraft, y
    que llegue pero durante demasiado poco tiempo.
    """
    config = _load_for_diagnostic(config_path)
    if config is None:
        return 1

    from .winput import InputBackend, foreground_window_title

    key = key or str(config.get("macros", "cocoa_beans", "keys")[0])
    button = config.get_str("general", "mouse_button")
    backend = InputBackend()

    print(f"Se mantendra '{key}' + click {button} durante {seconds:.1f} s seguidos.")
    _countdown(5)
    print(f"  ventana activa: {foreground_window_title()!r}")

    try:
        backend.mouse_down(button)
        backend.key_down(key)
        time.sleep(seconds)
    finally:
        backend.release_all()

    print("")
    print("Listo. Interpreta el resultado:")
    print("  - No se movio nada        -> la entrada no llega al juego")
    print("  - Se movio de forma fluida -> la entrada llega; el problema son los timings")
    return 0


def calibrate(config_path: str | None = None, macro_type: str = "nether_wart") -> int:
    """Mide cuanto se tarda en cruzar una fila y da el forward_seconds exacto.

    Es el numero que no se puede adivinar desde fuera: depende del tamano de tu
    plot, de tu velocidad y de tus buffs.
    """
    config = _load_for_diagnostic(config_path)
    if config is None:
        return 1
    if macro_type not in MACRO_TYPES or macro_type == "cobblestone":
        print(f"[ERROR] Calibra 'cocoa_beans' o 'nether_wart', no {macro_type!r}", file=sys.stderr)
        return 1

    import keyboard

    from .winput import InputBackend, foreground_window_title

    key = str(config.get("macros", macro_type, "keys")[0])
    button = config.get_str("general", "mouse_button")
    stop_key = str(config.get("keybinds", "stop"))
    backend = InputBackend()

    print(f"Calibrando macros.{macro_type}.forward_seconds")
    print(f"Se mantendra '{key}' + click {button} y se ira contando.")
    print(f"Pulsa {stop_key.upper()} JUSTO al llegar al final de la fila.")
    _countdown(5)
    print(f"  ventana activa: {foreground_window_title()!r}")
    print("  caminando... (limite de seguridad: 120 s)")

    inicio = time.perf_counter()
    try:
        backend.mouse_down(button)
        backend.key_down(key)
        while not keyboard.is_pressed(stop_key):
            time.sleep(0.02)
            if time.perf_counter() - inicio > 120:
                print("  limite alcanzado, se corta")
                break
    finally:
        backend.release_all()

    transcurrido = time.perf_counter() - inicio
    print("")
    print(f"Has tardado {transcurrido:.1f} s en cruzar la fila.")
    print("Copia esto en tu config.json, dentro de ese macro:")
    print(f'    "forward_seconds": {transcurrido:.1f}')
    return 0


def test_chat(config_path: str | None = None) -> int:
    """Abre el chat y escribe el comando de warp SIN enviarlo."""
    config = _load_for_diagnostic(config_path)
    if config is None:
        return 1

    from .winput import InputBackend, foreground_window_title

    command = config.get_str("commands", "warp_garden")
    chat_key = config.get_str("general", "chat_key")
    open_delay = config.get_float("general", "chat_open_delay_ms") / 1000.0
    mode = config.get_str("general", "command_input_mode")
    backend = InputBackend()

    print(f"Se abrira el chat con '{chat_key}' y se escribira {command!r}.")
    print("NO se pulsa enter: el comando no se ejecuta, solo se queda escrito.")
    _countdown(5)
    print(f"  ventana activa: {foreground_window_title()!r}  (modo: {mode})")

    try:
        backend.tap(chat_key)
        time.sleep(open_delay)
        backend.type_text(command, mode=mode)
    finally:
        backend.release_all()

    print("")
    print("Mira la caja del chat:")
    print(f"  - Aparece {command!r} entero -> la escritura funciona")
    print("  - Aparece cortado           -> sube general.chat_open_delay_ms")
    print("  - No aparece nada           -> prueba general.command_input_mode = 'scancode'")
    return 0
