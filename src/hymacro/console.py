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


def print_rainbow(texto: str, *, animate: bool = True, delay: float = 0.045) -> None:
    """Imprime el texto con un arcoiris que avanza fila a fila.

    Sin color se imprime de golpe y sin pausas: la animacion solo tiene sentido
    en una consola, y en un fichero de log solo serviria para hacerla lenta.
    """
    lineas = texto.splitlines()
    if not _enabled:
        print(texto, flush=True)
        return

    total = max(1, len(lineas))
    for indice, linea in enumerate(lineas):
        print(paint(linea, hue(indice / total)), flush=True)
        if animate and delay > 0:
            time.sleep(delay)
