"""Primitivas de menu compartidas por las distintas pantallas.

Viven aparte para que el editor y el menu principal se dibujen y se manejen
igual sin que uno tenga que importar al otro.
"""

from __future__ import annotations

import sys

from .console import BOLD, CYAN, DIM, GREY, WHITE, colors_enabled, paint

Opcion = tuple[str, str, str]


def consola_interactiva() -> bool:
    """True si se pueden leer teclas sueltas de la consola."""
    if sys.platform != "win32" or not colors_enabled():
        return False
    entrada = sys.stdin
    return bool(entrada is not None and getattr(entrada, "isatty", lambda: False)())


def pintar_opciones(titulo: str, opciones: list[Opcion]) -> str:
    """Dibuja una lista de opciones con el mismo estilo en todas las pantallas."""
    ancho = max(len(nombre) for _, nombre, _ in opciones)
    lineas = ["", paint(f"  {titulo}", BOLD, WHITE), ""]
    for numero, nombre, ayuda in opciones:
        color_num = GREY if numero == "0" else CYAN
        color_txt = GREY if numero == "0" else WHITE
        # Solo se rellena cuando hay ayuda que alinear a la derecha; si no, el
        # relleno quedaria dentro del color sin pintar nada.
        etiqueta = nombre.ljust(ancho) if ayuda else nombre
        fila = f"    {paint(numero + ')', BOLD, color_num)} {paint(etiqueta, color_txt)}"
        if ayuda:
            fila += f"  {paint(ayuda, DIM, GREY)}"
        lineas.append(fila)
    return "\n".join(lineas)


def preguntar(opciones: set[str], por_defecto: str) -> str:
    """Pide una opcion por linea, para cuando no hay consola interactiva."""
    while True:
        try:
            respuesta = input("  > ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("")
            return "0"
        if not respuesta:
            return por_defecto
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
    while True:
        tecla = msvcrt.getwch()
        if tecla in ("\x00", "\xe0"):  # teclas extendidas: llegan en pares
            msvcrt.getwch()
            continue
        if tecla in ("\r", "\n"):
            print(por_defecto)
            return por_defecto
        if tecla == "\x03":
            print("")
            return "0"
        if tecla.lower() in opciones:
            print(tecla.lower())
            return tecla.lower()


def leer_texto(mensaje: str) -> str | None:
    """Pide un valor escrito. None si se cancela con Enter vacio o Ctrl+C."""
    try:
        respuesta = input(mensaje).strip()
    except (EOFError, KeyboardInterrupt):
        print("")
        return None
    return respuesta or None
