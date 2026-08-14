"""Interfaz de consola: registra los hotkeys globales y reporta el estado."""

from __future__ import annotations

import contextlib
import logging
import sys
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from . import __version__
from .config import (
    MACRO_TYPES,
    ConfigError,
    ConfigManager,
    app_dir,
    ensure_config_exists,
    resolve_config_path,
)
from .console import (
    BOLD,
    CYAN,
    GREEN,
    GREY,
    MAGENTA,
    RED,
    WHITE,
    YELLOW,
    BannerWave,
    clear_screen,
    colors_enabled,
    init_colors,
    paint,
    print_rainbow,
)
from .controller import MacroController, MacroEvent
from .editor import editar_configuracion
from .ui import (
    VOLVER,
    Opcion,
    consola_interactiva,
    fijar_ola,
    leer_opcion,
    pintar_opciones,
    preguntar,
)

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

    def __init__(self, config_path: str | None = None, *, permitir_volver: bool = False) -> None:
        self.config = ConfigManager(config_path)
        self.controller = MacroController(self.config, on_event=self._handle_event)
        self._print_lock = threading.Lock()
        self._alive = True
        self._keyboard: Any = None
        #: Lo consulta run_menu para saber si hay que volver a dibujarlo.
        self.volver_al_menu = False
        self._permitir_volver = permitir_volver and consola_interactiva()
        init_colors(self.config.get_str("general", "colors", default="auto"))
        self._animar_banner = self.config.get_bool("general", "banner_animation", default=True)
        self._ola: BannerWave | None = None
        # Filas impresas desde que acaba el banner: es lo que necesita el
        # repintado para saber cuanto subir el cursor.
        self._lineas_bajo_banner = 0

    # --- salida por consola ---

    def _say(self, message: str) -> None:
        with self._print_lock:
            print(message, flush=True)
            self._lineas_bajo_banner += message.count("\n") + 1

    def _se_pidio_volver(self) -> bool:
        """True si se ha pulsado ESC en la ventana de la consola.

        Se lee de la consola, no con un hook global, precisamente para que
        pulsar ESC dentro de Minecraft (que es abrir el menu del juego) no
        cierre el macro.
        """
        if not self._permitir_volver:
            return False
        import msvcrt

        pedido = False
        while msvcrt.kbhit():
            tecla = msvcrt.getwch()
            if tecla in ("\x00", "\xe0"):  # teclas extendidas: llegan en pares
                msvcrt.getwch()
                continue
            if tecla == "\x1b":
                pedido = True
        return pedido

    def _tick_banner(self) -> None:
        """Avanza la ola. Coge el mismo lock que _say: si otro hilo imprime a
        la vez que movemos el cursor, la pantalla se descuadra."""
        if self._ola is None:
            return
        with self._print_lock:
            self._ola.tick(self._lineas_bajo_banner)

    def _handle_event(self, event: MacroEvent) -> None:
        prefijo, color = {
            "info": ("  ->", CYAN),
            "cycle": ("  ..", GREEN),
            "stop": ("[STOP]", YELLOW),
            "stats": ("[STATS]", MAGENTA),
        }.get(event.level, ("  ", GREY))
        self._say(f"{paint(prefijo, BOLD, color)} {event.message}")

    def display_banner(self) -> None:
        clear_screen()
        if colors_enabled() and self._animar_banner:
            self._ola = BannerWave(_BANNER)
            self._ola.draw()
        else:
            print_rainbow(_BANNER, animate=False)
        self._lineas_bajo_banner = 0
        self._say(_cabecera())
        if self.config.created_default:
            self._say(paint("  (configuracion nueva con los valores por defecto)", GREY))

        binds = self.config.get("keybinds")
        teclas: list[Opcion] = [
            (str(binds[macro_type]).upper(), _LABELS[macro_type], "") for macro_type in MACRO_TYPES
        ]
        teclas.append((str(binds["stop"]).upper(), "DETENER macro", ""))
        if self._permitir_volver:
            teclas.append(("ESC", "volver al menu", ""))
        self._say(pintar_opciones("Teclas", teclas))
        self._say("")

        safety = self.config.get("safety")
        active = []
        if safety.get("require_window_focus"):
            active.append(f"foco en '{safety.get('window_title_contains')}'")
        if safety.get("mouse_failsafe"):
            active.append(f"failsafe de raton ({safety.get('mouse_failsafe_px')} px)")
        if float(safety.get("max_session_minutes") or 0) > 0:
            active.append(f"limite de sesion {safety.get('max_session_minutes')} min")
        detalle = ", ".join(active) if active else "ninguno (!)"
        color_fs = GREEN if active else RED
        self._say(f"  {paint('Failsafes:', BOLD, color_fs)} {paint(detalle, GREY)}")
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
                    etiqueta = paint("[START]", BOLD, GREEN)
                    self._say(f"{etiqueta} {paint(_LABELS[macro_type], WHITE)}")
                else:
                    etiqueta = paint("[NO]", BOLD, RED)
                    self._say(f"{etiqueta} No se arranco {_LABELS[macro_type]}: {reason}")
            except Exception:
                logger.exception("Error en el hotkey de %s", macro_type)

        return callback

    def _on_stop_pressed(self) -> None:
        try:
            if self.controller.is_running:
                self.controller.request_stop("parada manual (hotkey)")
            else:
                self._say(f"{paint('[STOP]', BOLD, YELLOW)} No hay ningun macro en marcha")
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
        if self._ola is not None:
            # A 100 ms la ola se ve a saltos; el bucle en reposo no cuesta nada.
            loop_delay = min(loop_delay, 1 / 15)

        try:
            while self._alive:
                if self._se_pidio_volver():
                    self.volver_al_menu = True
                    break
                self._tick_banner()
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
        if not self.volver_al_menu:
            self._say("Hasta luego!")


def check_config(config_path: str | None = None) -> int:
    """Valida la configuracion y sale. Se usa como smoke test en CI."""
    enable_utf8_console()
    try:
        config = ConfigManager(config_path, auto_create=False)
    except ConfigError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(f"{paint('  Configuracion valida:', BOLD, GREEN)} {paint(str(config.config_path), GREY)}")
    for macro_type in MACRO_TYPES:
        bind = str(config.get("keybinds", macro_type)).upper().ljust(5)
        print(f"    {paint(bind, BOLD, YELLOW)}{paint('->', GREY)} {paint(_LABELS[macro_type], WHITE)}")
    parada = str(config.get("keybinds", "stop")).upper().ljust(5)
    print(f"    {paint(parada, BOLD, RED)}{paint('->', GREY)} {paint('DETENER', WHITE)}")
    return 0


_OPCIONES_MENU = [
    ("1", "Arrancar el macro", ""),
    ("2", "Calibrar los tiempos", ""),
    ("3", "Ajustes", ""),
    (VOLVER, "Salir", ""),
]


def _cabecera() -> str:
    """La linea de version, igual en todas las pantallas."""
    nombre = paint(f"  HyMacro v{__version__}", BOLD, WHITE)
    return f"{nombre} {paint('- Hypixel Garden Automation Tool', GREY)}"


_OPCIONES_MACRO = [
    ("1", "Nether Wart", ""),
    ("2", "Cocoa Beans", ""),
    (VOLVER, "Volver", ""),
]


def _pintar_menu() -> str:
    return pintar_opciones("Que quieres hacer?", _OPCIONES_MENU)


def _leer_tecla_animando(
    opciones: set[str],
    por_defecto: str,
    ola: BannerWave | None,
    lineas_debajo: int,
    fps: int = 15,
) -> str:
    """Espera una tecla mientras la ola del banner sigue corriendo.

    input() bloquea y se queda con el control del hilo, asi que no se puede
    animar nada mientras espera. Aqui se sondea el teclado y se repinta entre
    sondeo y sondeo.
    """
    import msvcrt

    espera = 1.0 / fps
    while True:
        while msvcrt.kbhit():
            tecla = msvcrt.getwch()
            if tecla in ("\x00", "\xe0"):  # teclas extendidas: se descarta el par
                msvcrt.getwch()
                continue
            if tecla in ("\r", "\n"):
                return por_defecto
            if tecla == "\x03":  # Ctrl+C
                raise KeyboardInterrupt
            if tecla == "\x1b" and VOLVER in opciones:
                return VOLVER
            if tecla.lower() in opciones:
                return tecla.lower()
        if ola is not None:
            ola.tick(lineas_debajo)
        time.sleep(espera)


def _elegir_macro() -> str | None:
    """Pregunta que macro calibrar. Devuelve None si se elige volver."""
    print(pintar_opciones("Que macro?", _OPCIONES_MACRO))
    print("")
    eleccion = leer_opcion({VOLVER, "1", "2"}, VOLVER)
    return {"1": "nether_wart", "2": "cocoa_beans"}.get(eleccion)


def _nueva_pantalla(animar: bool = True) -> BannerWave:
    """Limpia, redibuja el banner y lo deja registrado para que se anime."""
    clear_screen()
    ola = BannerWave(_BANNER)
    ola.draw()
    print(_cabecera())
    # Solo se registra si toca animar: el registro es lo que hace que las
    # esperas de teclado lo repinten.
    fijar_ola(ola if animar else None)
    return ola


def _ruta_config(config_path: str | None) -> Path:
    """Ruta del config.json que se va a editar, creandolo si aun no existe."""
    ruta = resolve_config_path(config_path)
    ensure_config_exists(ruta)
    return ruta


def _animacion_activa(config_path: str | None) -> bool:
    """Lee general.banner_animation.

    El menu creaba la ola sin consultarlo, asi que ponerlo en `false` solo
    tenia efecto en la pantalla del macro.
    """
    try:
        return ConfigManager(config_path, auto_create=False).get_bool(
            "general", "banner_animation", default=True
        )
    except ConfigError:
        return True


def _modo_color(config_path: str | None) -> str:
    """Lee general.colors del config para que el menu lo respete.

    El menu se dibuja antes de construir HyMacroApp, asi que si no se mira aqui
    el ajuste del usuario solo tendria efecto a partir de la pantalla del macro.
    Un config roto no debe impedir que salga el menu: se cae a 'auto'.
    """
    try:
        return ConfigManager(config_path, auto_create=False).get_str("general", "colors", default="auto")
    except ConfigError:
        return "auto"


def run_menu(config_path: str | None = None, verbose: bool = False) -> int:
    """Menu interactivo, para cuando se abre el .exe con doble clic.

    Sin esto los diagnosticos solo existian como argumentos de linea de
    comandos, que es justo lo que no tienes al abrir el programa haciendo clic.
    """
    enable_utf8_console()
    setup_logging(verbose=verbose)

    init_colors(_modo_color(config_path))
    animar = _animacion_activa(config_path)
    opciones = {numero for numero, _, _ in _OPCIONES_MENU}
    try:
        return _bucle_menu(config_path, animar, opciones)
    finally:
        # Sin esto queda una ola apuntando a un banner que ya no esta en
        # pantalla, y la siguiente espera de teclado la repintaria encima.
        fijar_ola(None)


def _bucle_menu(config_path: str | None, animar: bool, opciones: set[str]) -> int:
    while True:
        clear_screen()
        ola = BannerWave(_BANNER)
        ola.draw()
        cabecera = _cabecera()
        # Se escribe de una pieza y se cuentan los saltos de linea reales: es
        # el numero de filas que baja el cursor, y contarlas a mano es como se
        # cuelan los off-by-one que dejan el banner repintado una fila arriba.
        bloque = f"{cabecera}\n{_pintar_menu()}\n"
        sys.stdout.write(bloque)
        debajo = bloque.count("\n")

        if consola_interactiva():
            sys.stdout.write("  > ")
            sys.stdout.flush()
            try:
                eleccion = _leer_tecla_animando(opciones, "1", ola if animar else None, debajo)
            except KeyboardInterrupt:
                print("")
                eleccion = VOLVER
            print(eleccion)
        else:
            eleccion = preguntar(opciones, "1")

        if eleccion == VOLVER:
            print(paint("  Hasta luego!", GREY))
            return 0
        if eleccion == "1":
            fijar_ola(None)  # la pantalla del macro anima por su cuenta
            try:
                app = HyMacroApp(config_path, permitir_volver=True)
                codigo = app.run()
            except ConfigError as exc:
                print(f"{paint('  [ERROR]', BOLD, RED)} {exc}")
                return 1
            if app.volver_al_menu:
                continue
            return codigo
        if eleccion == "2":
            _nueva_pantalla(animar)
            macro_type = _elegir_macro()
            if macro_type is None:
                continue  # "Volver" vuelve directo, sin pedir otra tecla
            _nueva_pantalla(animar)
            calibrate(config_path, macro_type)
        elif eleccion == "3":

            def redibujar() -> None:
                _nueva_pantalla(animar)

            editar_configuracion(_ruta_config(config_path), redibujar)
            continue  # el editor ya tiene su propio 'Volver'

        try:
            input("\n  Pulsa Enter para volver al menu...")
        except (EOFError, KeyboardInterrupt):
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


def _esperar_pulsacion(keyboard: Any, key: str) -> float:
    """Espera una pulsacion completa y devuelve el instante en que se apreto.

    Se espera tambien a que se suelte antes de volver: si no, la misma
    pulsacion marcaria el inicio y el final del tramo.
    """
    while not keyboard.is_pressed(key):
        time.sleep(0.01)
    instante = time.perf_counter()
    while keyboard.is_pressed(key):
        time.sleep(0.01)
    return instante


def _cronometrar_tramo(keyboard: Any, key: str, titulo: str, instruccion: str) -> float:
    """Cronometra un tramo entre dos pulsaciones, sin tocar la entrada del juego."""
    marca = key.upper()
    print(f"\n{titulo}")
    print(f"  1. Ponte en posicion y pulsa {marca} para arrancar el cronometro.")
    print(f"  2. {instruccion}")
    print(f"  3. Vuelve a pulsar {marca} al terminar.")
    print(f"  esperando el primer {marca}...")

    inicio = _esperar_pulsacion(keyboard, key)
    print("  cronometro EN MARCHA, haz el recorrido...")
    fin = _esperar_pulsacion(keyboard, key)

    transcurrido = fin - inicio
    print(f"  -> {transcurrido:.2f} s")
    return transcurrido


def calibrate(config_path: str | None = None, macro_type: str = "nether_wart") -> int:
    """Cronometro manual: tu haces el recorrido y el programa solo mide.

    No inyecta ninguna tecla, asi que no hay riesgo de que el personaje se vaya
    del plot. Los tiempos dependen del tamano de la parcela, de tu velocidad y
    de tus buffs, asi que la unica medida fiable es la tuya.
    """
    config = _load_for_diagnostic(config_path)
    if config is None:
        return 1
    if macro_type not in MACRO_TYPES or macro_type == "cobblestone":
        print(f"[ERROR] Calibra 'cocoa_beans' o 'nether_wart', no {macro_type!r}", file=sys.stderr)
        return 1

    import keyboard

    keys = [str(k) for k in config.get("macros", macro_type, "keys")]
    stop_key = str(config.get("keybinds", "stop")).lower()

    print(f"Cronometro para macros.{macro_type}")
    print("El programa NO se mueve solo: conduces tu y el solo mide.")
    print(f"Se marca cada tramo con {stop_key.upper()}, dos veces: inicio y final.")

    fila = _cronometrar_tramo(
        keyboard,
        stop_key,
        f"TRAMO 1/2 - la fila entera (en el macro la hace '{keys[0]}')",
        "Recorre la fila de punta a punta como lo harias tu.",
    )
    paso = _cronometrar_tramo(
        keyboard,
        stop_key,
        f"TRAMO 2/2 - el paso a la fila siguiente (en el macro lo hace '{keys[1]}')",
        "Pasa a la fila de al lado y quedate encarado a ella.",
    )

    print("\n" + "=" * 58)
    print("Copia esto dentro de tu macro en config.json:\n")
    print(f'    "forward_seconds": {fila:.1f},')
    print(f'    "return_seconds": {fila:.1f},')
    print(f'    "step_seconds": {paso:.2f}')
    print("=" * 58)
    print("\nSi la fila de vuelta te mide distinto, cronometrala aparte y cambia")
    print("solo return_seconds.")
    return 0
