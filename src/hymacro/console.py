"""Colores ANSI para la consola, con degradado suave si la terminal no puede.

Windows no interpreta secuencias ANSI por defecto en la consola clasica: hay
que pedirselo con SetConsoleMode. Si eso falla, o la salida esta redirigida a un
fichero, se imprime en texto plano en vez de llenarlo todo de basura tipo
`\\x1b[38;2;255;0;0m`.
"""

from __future__ import annotations

import ctypes
import os
import sys
import time

_STD_OUTPUT_HANDLE = -11
_ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004

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
    """Pide a Windows que interprete las secuencias ANSI. True si se pudo."""
    if sys.platform != "win32":
        return True
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        handle = kernel32.GetStdHandle(_STD_OUTPUT_HANDLE)
        modo = ctypes.c_ulong()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(modo)):
            return False
        nuevo = modo.value | _ENABLE_VIRTUAL_TERMINAL_PROCESSING
        return bool(kernel32.SetConsoleMode(handle, nuevo))
    except (OSError, AttributeError):  # pragma: no cover - consolas raras
        return False


def init_colors(mode: str = "auto") -> bool:
    """Decide si se pinta. `auto` solo pinta en una consola de verdad."""
    global _enabled
    if mode == "never" or os.environ.get("NO_COLOR"):
        _enabled = False
    elif mode == "always":
        # 'always' es 'always': se intenta activar el modo ANSI, pero si la
        # salida esta redirigida se pinta igual. Es lo que pide quien lo pone.
        enable_ansi()
        _enabled = True
    else:
        interactiva = bool(getattr(sys.stdout, "isatty", lambda: False)())
        _enabled = interactiva and enable_ansi()
    return _enabled


def colors_enabled() -> bool:
    return _enabled


def paint(text: str, *codes: str) -> str:
    """Envuelve el texto en codigos ANSI, o lo devuelve tal cual si no hay color."""
    if not _enabled or not codes:
        return text
    return f"{''.join(codes)}{text}{RESET}"


def _hue_rgb(fraccion: float) -> tuple[int, int, int]:
    """Punto del arcoiris, con saturacion y brillo al maximo."""
    h = fraccion % 1.0
    sector = int(h * 6)
    resto = h * 6 - sector
    subida = int(255 * resto)
    bajada = 255 - subida
    return [
        (255, subida, 0),
        (bajada, 255, 0),
        (0, 255, subida),
        (0, bajada, 255),
        (subida, 0, 255),
        (255, 0, bajada),
    ][sector % 6]


def hue(fraccion: float) -> str:
    """Codigo de color RGB de 24 bits para un punto del arcoiris."""
    r, g, b = _hue_rgb(fraccion)
    return f"\x1b[38;2;{r};{g};{b}m"


#: Pasos en los que se cuantiza el tono. Sin esto cada caracter llevaria su
#: propio codigo de color y cada fotograma pesaria el triple para nada: el ojo
#: no distingue saltos tan finos.
_PASOS_TONO = 48

#: Vueltas de arcoiris que caben a lo ancho del banner. Menos de una para que
#: se lea como una ola y no como una tira de confeti.
_CICLOS_POR_LINEA = 0.75


def _linea_ola(linea: str, fila: int, fase: float, paso_fila: float) -> str:
    """Colorea una linea con el tono variando por columna."""
    ancho = max(1, len(linea))
    partes: list[str] = []
    ultimo = -1
    for columna, caracter in enumerate(linea):
        # Restar la fase hace que el patron se desplace hacia la derecha:
        # un tono fijo aparece en columnas cada vez mayores segun avanza t.
        tono = (columna / ancho) * _CICLOS_POR_LINEA - fase + fila * paso_fila
        cuantizado = int(tono % 1.0 * _PASOS_TONO)
        if cuantizado != ultimo:
            partes.append(hue(cuantizado / _PASOS_TONO))
            ultimo = cuantizado
        partes.append(caracter)
    partes.append(RESET)
    return "".join(partes)


class BannerWave:
    """Repinta el banner en su sitio mientras el resto de la pantalla sigue.

    Se guarda la posicion del cursor, se sube hasta el banner, se repintan sus
    filas y se vuelve. Asi la ola sigue corriendo aunque debajo haya un menu y
    el usuario este a punto de pulsar una tecla.
    """

    def __init__(
        self,
        texto: str,
        *,
        velocidad: float = 0.7,
        paso_fila: float = 0.05,
    ) -> None:
        self._lineas = texto.splitlines()
        self._velocidad = velocidad
        self._paso_fila = paso_fila
        self._inicio = time.monotonic()

    @property
    def alto(self) -> int:
        return len(self._lineas)

    def _fase(self) -> float:
        return (time.monotonic() - self._inicio) * self._velocidad

    def draw(self) -> None:
        """Pinta el banner por primera vez, dejando el cursor debajo."""
        if not _enabled:
            print("\n".join(self._lineas), flush=True)
            return
        fase = self._fase()
        for fila, linea in enumerate(self._lineas):
            sys.stdout.write(f"{_linea_ola(linea, fila, fase, self._paso_fila)}\x1b[K\n")
        sys.stdout.flush()

    def tick(self, lineas_debajo: int) -> None:
        """Repinta el banner sin mover el cursor de donde estaba.

        `lineas_debajo` es cuantas filas hay entre el final del banner y el
        cursor; sin ese dato repintariamos encima del menu.
        """
        if not _enabled:
            return
        fase = self._fase()
        salto = self.alto + max(0, lineas_debajo)
        partes = [f"\x1b[s\x1b[{salto}A"]
        for fila, linea in enumerate(self._lineas):
            partes.append(f"\r{_linea_ola(linea, fila, fase, self._paso_fila)}\x1b[K\n")
        partes.append("\x1b[u")
        sys.stdout.write("".join(partes))
        sys.stdout.flush()


def print_rainbow(
    texto: str,
    *,
    animate: bool = True,
    duracion: float = 2.2,
    fps: int = 18,
    paso_fila: float = 0.05,
    velocidad: float = 0.7,
) -> None:
    """Dibuja el texto con una ola de arcoiris que se desplaza hacia la derecha.

    Sin color se imprime de golpe y sin pausas: la animacion solo tiene sentido
    en una consola, y en un fichero de log solo serviria para hacerla lenta.
    """
    lineas = texto.splitlines()
    if not _enabled:
        print(texto, flush=True)
        return

    if not animate or duracion <= 0 or fps <= 0:
        for fila, linea in enumerate(lineas):
            print(_linea_ola(linea, fila, 0.0, paso_fila), flush=True)
        return

    espera = 1.0 / fps
    for fotograma in range(max(1, int(duracion * fps))):
        if fotograma:
            # Subir el cursor para repintar el banner en el mismo sitio.
            sys.stdout.write(f"\x1b[{len(lineas)}A")
        fase = fotograma * espera * velocidad  # vueltas de arcoiris por segundo
        for fila, linea in enumerate(lineas):
            # \x1b[K borra hasta el final por si una linea encoge.
            sys.stdout.write(f"\r{_linea_ola(linea, fila, fase, paso_fila)}\x1b[K\n")
        sys.stdout.flush()
        time.sleep(espera)
