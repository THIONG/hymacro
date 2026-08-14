"""Editor de configuracion dentro del programa.

Todo esto se podia hacer ya editando config.json a mano, pero eso obliga a
saber donde esta el fichero, no avisa de un valor invalido hasta que arrancas,
y una coma de menos deja el macro sin abrir. Aqui se valida antes de guardar y
nunca se escribe un config que no cargue.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import DEFAULTS, ConfigError, _deep_merge, validate_config
from .console import BOLD, CYAN, GREEN, GREY, RED, WHITE, paint
from .ui import VOLVER, Opcion, leer_opcion, leer_texto, pintar_opciones


@dataclass(frozen=True)
class Campo:
    """Un ajuste editable del config.json."""

    ruta: tuple[str, ...]
    etiqueta: str
    tipo: str  # segundos | entero | booleano | tecla | teclas | opcion
    ayuda: str = ""
    valores: tuple[str, ...] = ()


def _campos_macro(nombre: str) -> list[Campo]:
    return [
        Campo(("macros", nombre, "keys"), "Recorrido", "teclas", "ida, paso, vuelta, paso"),
        Campo(("macros", nombre, "forward_seconds"), "Ida", "segundos", "la fila entera"),
        Campo(("macros", nombre, "return_seconds"), "Vuelta", "segundos", "0 = un solo sentido"),
        Campo(("macros", nombre, "step_seconds"), "Paso entre filas", "segundos"),
        Campo(("macros", nombre, "routes_per_warp"), "Idas y vueltas", "entero", "antes del warp"),
    ]


SECCIONES: list[tuple[str, list[Campo]]] = [
    ("Nether Wart", _campos_macro("nether_wart")),
    ("Cocoa Beans", _campos_macro("cocoa_beans")),
    (
        "Cobblestone",
        [
            Campo(("macros", "cobblestone", "key"), "Tecla de avance", "tecla"),
            Campo(("macros", "cobblestone", "mining_duration_seconds"), "Minado por ciclo", "segundos"),
            Campo(("macros", "cobblestone", "hub_wait_seconds"), "Espera en el hub", "segundos"),
        ],
    ),
    (
        "Teclas",
        [
            Campo(("keybinds", "cocoa_beans"), "Iniciar Cocoa Beans", "tecla"),
            Campo(("keybinds", "nether_wart"), "Iniciar Nether Wart", "tecla"),
            Campo(("keybinds", "cobblestone"), "Iniciar Cobblestone", "tecla"),
            Campo(("keybinds", "stop"), "Detener el macro", "tecla"),
            Campo(("general", "chat_key"), "Abrir el chat", "tecla"),
        ],
    ),
    (
        "Seguridad",
        [
            Campo(("safety", "require_window_focus"), "Parar si pierde el foco", "booleano"),
            Campo(("safety", "window_title_contains"), "Titulo de la ventana", "texto"),
            Campo(("safety", "mouse_failsafe"), "Parar al mover el raton", "booleano"),
            Campo(("safety", "mouse_failsafe_px"), "Margen del raton (px)", "entero"),
            Campo(("safety", "max_session_minutes"), "Limite de sesion (min)", "entero", "0 = sin limite"),
        ],
    ),
    (
        "Aspecto",
        [
            Campo(("general", "colors"), "Colores", "opcion", valores=("auto", "always", "never")),
            Campo(("general", "banner_animation"), "Animar el banner", "booleano"),
        ],
    ),
]


def _leer(datos: dict[str, Any], ruta: tuple[str, ...]) -> Any:
    valor: Any = datos
    for clave in ruta:
        if not isinstance(valor, dict) or clave not in valor:
            return None
        valor = valor[clave]
    return valor


def _escribir(datos: dict[str, Any], ruta: tuple[str, ...], valor: Any) -> None:
    destino = datos
    for clave in ruta[:-1]:
        siguiente = destino.get(clave)
        if not isinstance(siguiente, dict):
            siguiente = {}
            destino[clave] = siguiente
        destino = siguiente
    destino[ruta[-1]] = valor


def _mostrar(valor: Any, tipo: str) -> str:
    if valor is None:
        return "(sin definir)"
    if tipo == "booleano":
        return "si" if valor else "no"
    if tipo == "teclas":
        return " ".join(str(k).upper() for k in valor)
    if tipo == "tecla":
        return str(valor).upper()
    if tipo == "segundos":
        return f"{float(valor):g} s"
    return str(valor)


def _interpretar(texto: str, campo: Campo) -> Any:
    """Convierte lo escrito al tipo del campo. Lanza ValueError si no encaja."""
    if campo.tipo == "segundos":
        # Se acepta la coma decimal: es lo natural escribiendo en espanol.
        try:
            return float(texto.replace(",", "."))
        except ValueError:
            raise ValueError(f"{texto!r} no es un numero de segundos") from None
    if campo.tipo == "entero":
        try:
            return int(float(texto.replace(",", ".")))
        except ValueError:
            raise ValueError(f"{texto!r} no es un numero") from None
    if campo.tipo == "booleano":
        bajo = texto.lower()
        if bajo in ("s", "si", "sí", "y", "yes", "true", "1"):
            return True
        if bajo in ("n", "no", "false", "0"):
            return False
        raise ValueError("responde si o no")
    if campo.tipo == "teclas":
        partes = texto.replace(",", " ").split()
        if len(partes) != 4:
            raise ValueError("hacen falta 4 teclas, por ejemplo: d w a w")
        return [p.lower() for p in partes]
    if campo.tipo == "tecla":
        return texto.strip().lower()
    if campo.tipo == "opcion":
        if texto.lower() not in campo.valores:
            raise ValueError(f"elige entre {', '.join(campo.valores)}")
        return texto.lower()
    return texto


def _guardar(ruta_config: Path, crudo: dict[str, Any]) -> None:
    ruta_config.write_text(json.dumps(crudo, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _editar_campo(
    ruta_config: Path,
    crudo: dict[str, Any],
    campo: Campo,
    redibujar: Callable[[], None],
) -> bool:
    """Pide un valor nuevo, valida y guarda. True si se llego a guardar."""
    redibujar()
    fusionado = _deep_merge(DEFAULTS, crudo)
    actual = _mostrar(_leer(fusionado, campo.ruta), campo.tipo)

    print("")
    print(f"  {paint(campo.etiqueta, BOLD, WHITE)}   {paint('ahora: ' + actual, GREY)}")
    if campo.ayuda:
        print(paint(f"  {campo.ayuda}", GREY))
    if campo.tipo == "opcion":
        print(paint(f"  valores: {', '.join(campo.valores)}", GREY))
    print(paint("  (Enter sin escribir nada para dejarlo como esta)", GREY))

    texto = leer_texto(paint("  nuevo valor > ", BOLD, CYAN))
    if texto is None:
        return False

    try:
        valor = _interpretar(texto, campo)
    except ValueError as exc:
        print(f"{paint('  [NO]', BOLD, RED)} {exc}")
        return False

    candidato = json.loads(json.dumps(crudo))
    _escribir(candidato, campo.ruta, valor)

    try:
        validate_config(_deep_merge(DEFAULTS, candidato))
    except ConfigError as exc:
        # Aqui esta la gracia: se rechaza antes de tocar el fichero, en vez de
        # dejar un config que no abre.
        print(f"{paint('  [NO]', BOLD, RED)} {exc}")
        return False

    _escribir(crudo, campo.ruta, valor)
    try:
        _guardar(ruta_config, crudo)
    except OSError as exc:
        print(f"{paint('  [NO]', BOLD, RED)} no se pudo escribir {ruta_config}: {exc}")
        return False

    nuevo = _mostrar(_leer(_deep_merge(DEFAULTS, crudo), campo.ruta), campo.tipo)
    print(f"{paint('  [OK]', BOLD, GREEN)} {campo.etiqueta}: {nuevo}")
    return True


def _menu_seccion(
    ruta_config: Path,
    crudo: dict[str, Any],
    titulo: str,
    campos: list[Campo],
    redibujar: Callable[[], None],
) -> None:
    while True:
        redibujar()
        fusionado = _deep_merge(DEFAULTS, crudo)
        opciones: list[Opcion] = [
            (str(indice + 1), campo.etiqueta, _mostrar(_leer(fusionado, campo.ruta), campo.tipo))
            for indice, campo in enumerate(campos)
        ]
        opciones.append((VOLVER, "Volver", ""))

        print(pintar_opciones(titulo, opciones))
        print("")
        eleccion = leer_opcion({o[0] for o in opciones}, VOLVER)
        if eleccion == VOLVER:
            return

        if _editar_campo(ruta_config, crudo, campos[int(eleccion) - 1], redibujar):
            print(paint("  Guardado. Reinicia el macro para que tenga efecto.", GREY))
        leer_texto("\n  Enter para seguir > ")


def editar_configuracion(
    ruta_config: Path,
    redibujar: Callable[[], None] = lambda: None,
) -> None:
    """Menu principal del editor.

    `redibujar` limpia la pantalla y vuelve a pintar el banner. Se recibe de
    fuera para que el editor no tenga que saber nada del banner.
    """
    try:
        crudo = json.loads(ruta_config.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"{paint('  [ERROR]', BOLD, RED)} no se pudo leer {ruta_config}: {exc}")
        print(paint("  Borra el fichero y se regenerara con los valores por defecto.", GREY))
        return
    if not isinstance(crudo, dict):
        print(f"{paint('  [ERROR]', BOLD, RED)} {ruta_config} no contiene un objeto JSON")
        return

    while True:
        redibujar()
        opciones: list[Opcion] = [
            (str(indice + 1), titulo, "") for indice, (titulo, campos) in enumerate(SECCIONES)
        ]
        opciones.append((VOLVER, "Volver", ""))

        print(pintar_opciones("Que quieres cambiar?", opciones))
        print("")
        eleccion = leer_opcion({o[0] for o in opciones}, VOLVER)
        if eleccion == VOLVER:
            return

        titulo, campos = SECCIONES[int(eleccion) - 1]
        _menu_seccion(ruta_config, crudo, titulo, campos, redibujar)
