"""Primitivas de menu compartidas por las distintas pantallas.

Viven aparte para que el editor y el menu principal se dibujen y se manejen
igual sin que uno tenga que importar al otro.
"""

from __future__ import annotations

import sys
import time

from .console import BOLD, CYAN, DIM, GREY, WHITE, BannerAnimator, BannerWave, colors_enabled, paint

Opcion = tuple[str, str, str]

#: Etiqueta de la opcion de volver/salir. Una sola tecla en todas las
#: pantallas: antes unas pedian 0 y otras ESC y habia que adivinar cual.
VOLVER = "ESC"

#: Banner de la pantalla actual. Lo fija quien la dibuja, y cualquier lectura
#: de teclado lo anima mientras espera, sin tener que ir pasandolo por todas
#: las funciones intermedias.
_ola_actual: BannerWave | None = None


def fijar_ola(ola: BannerWave | None) -> None:
    global _ola_actual
    _ola_actual = ola


def _animando() -> BannerAnimator:
    return BannerAnimator(_ola_actual)


def consola_interactiva() -> bool:
    """True si se pueden leer teclas sueltas de la consola."""
    if sys.platform != "win32" or not colors_enabled():
        return False
    entrada = sys.stdin
    return bool(entrada is not None and getattr(entrada, "isatty", lambda: False)())


def pintar_opciones(titulo: str, opciones: list[Opcion]) -> str:
    """Dibuja una lista de opciones con el mismo estilo en todas las pantallas."""
    ancho = max(len(nombre) for _, nombre, _ in opciones)
    # Las etiquetas no siempre miden lo mismo ("F8)" contra "F10)"), asi que el
    # relleno va fuera del color: dentro serian espacios pintados para nada.
    ancho_num = max(len(numero) for numero, _, _ in opciones) + 1
    lineas = ["", paint(f"  {titulo}", BOLD, WHITE), ""]
    for numero, nombre, ayuda in opciones:
        apagada = numero in ("0", VOLVER)
        color_num = GREY if apagada else CYAN
        color_txt = GREY if apagada else WHITE
        etiqueta = nombre.ljust(ancho) if ayuda else nombre
        relleno = " " * (ancho_num - len(numero) - 1)
        fila = f"    {paint(numero + ')', BOLD, color_num)}{relleno} {paint(etiqueta, color_txt)}"
        if ayuda:
            fila += f"  {paint(ayuda, DIM, GREY)}"
        lineas.append(fila)
    return "\n".join(lineas)


def preguntar(opciones: set[str], por_defecto: str) -> str:
    """Pide una opcion por linea, para cuando no hay consola interactiva."""
    while True:
        try:
            with _animando():
                respuesta = input("  > ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("")
            return VOLVER if VOLVER in opciones else "0"
        if not respuesta:
            return por_defecto
        if respuesta.upper() in opciones:
            return respuesta.upper()
        if respuesta in opciones:
            return respuesta
        print(f"  Opcion no valida. Elige entre: {', '.join(sorted(opciones))}")


def leer_opcion(opciones: set[str], por_defecto: str) -> str:
    """Lee una opcion: de una tecla si hay consola, y si no por linea."""
    if not consola_interactiva():
        return preguntar(opciones, por_defecto)

    import msvcrt

    sys.stdout.write("  > ")
    sys.stdout.flush()
    with _animando():
        while True:
            # kbhit + espera corta en vez de getwch a secas: getwch bloquea el
            # hilo entero y con el la ola.
            if not msvcrt.kbhit():
                time.sleep(0.02)
                continue
            tecla = msvcrt.getwch()
            if tecla in ("\x00", "\xe0"):  # teclas extendidas: llegan en pares
                msvcrt.getwch()
                continue
            if tecla in ("\r", "\n"):
                print(por_defecto)
                return por_defecto
            if tecla in ("\x1b", "\x03") and VOLVER in opciones:  # ESC o Ctrl+C
                print(VOLVER)
                return VOLVER
            if tecla == "\x03":
                print("")
                return "0"
            if tecla.lower() in opciones:
                print(tecla.lower())
                return tecla.lower()


def leer_texto(mensaje: str) -> str | None:
    """Pide un valor escrito. None si se cancela con Enter vacio o Ctrl+C."""
    try:
        with _animando():
            respuesta = input(mensaje).strip()
    except (EOFError, KeyboardInterrupt):
        print("")
        return None
    return respuesta or None
