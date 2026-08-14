"""Tests del menu interactivo.

Existe porque la forma normal de usar HyMacro es abrir el .exe con doble clic,
y ahi no hay manera de escribir --calibrate ni ningun otro argumento.
"""

from __future__ import annotations

import builtins
import json
import re
from collections.abc import Iterator
from pathlib import Path

import pytest

import hymacro.app as app
from hymacro.config import DEFAULTS

LIMPIAR = "\x1b[H\x1b[2J\x1b[3J"
ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")


def _config_con(tmp_path: Path, **general: object) -> Path:
    """Escribe un config valido con los ajustes indicados en 'general'."""
    datos = json.loads(json.dumps(DEFAULTS))
    datos["general"].update(general)
    ruta = tmp_path / "config.json"
    ruta.write_text(json.dumps(datos), encoding="utf-8")
    return ruta


def _con_respuestas(monkeypatch: pytest.MonkeyPatch, respuestas: list[str]) -> None:
    """Alimenta input() con una lista; al agotarse simula EOF (consola cerrada)."""
    it: Iterator[str] = iter(respuestas)

    def fake_input(prompt: str = "") -> str:
        try:
            return next(it)
        except StopIteration as exc:
            raise EOFError from exc

    monkeypatch.setattr(builtins, "input", fake_input)


def test_salir_devuelve_cero(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_respuestas(monkeypatch, ["esc"])
    assert app.run_menu() == 0


def test_eof_no_revienta(monkeypatch: pytest.MonkeyPatch) -> None:
    """Cerrar la consola no debe dejar un traceback en pantalla."""
    _con_respuestas(monkeypatch, [])
    assert app.run_menu() == 0


def test_opcion_invalida_vuelve_a_preguntar(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_respuestas(monkeypatch, ["99", "pepino", "esc"])
    assert app.run_menu() == 0


def test_enter_arranca_el_macro(monkeypatch: pytest.MonkeyPatch) -> None:
    """Enter a secas es la opcion 1, que es lo que se usa el 99% de las veces."""
    llamadas: list[str] = []

    class AppFalsa:
        def __init__(self, config_path: str | None, *, permitir_volver: bool = False) -> None:
            llamadas.append("construida")
            self.volver_al_menu = False

        def run(self) -> int:
            llamadas.append("run")
            return 0

    monkeypatch.setattr(app, "HyMacroApp", AppFalsa)
    _con_respuestas(monkeypatch, [""])

    assert app.run_menu() == 0
    assert llamadas == ["construida", "run"]


def test_la_opcion_de_calibrar_pregunta_que_macro(monkeypatch: pytest.MonkeyPatch) -> None:
    elegidos: list[str] = []
    monkeypatch.setattr(
        app,
        "calibrate",
        lambda cfg, macro: elegidos.append(macro) or 0,  # type: ignore[func-returns-value]
    )
    # 2 = calibrar, 2 = cocoa beans, Enter = volver al menu, 0 = salir
    _con_respuestas(monkeypatch, ["2", "2", "", "esc"])

    assert app.run_menu() == 0
    assert elegidos == ["cocoa_beans"]


def test_volver_del_submenu_no_pide_otra_tecla(monkeypatch: pytest.MonkeyPatch) -> None:
    """'Volver' vuelve directo: pedir un Enter extra ahi era una molestia."""
    monkeypatch.setattr(app, "calibrate", lambda cfg, macro: pytest.fail("no debia calibrar"))
    # 2 = calibrar, 0 = volver, 0 = salir. Sin Enter intermedio.
    _con_respuestas(monkeypatch, ["2", "esc", "esc"])

    assert app.run_menu() == 0


def test_esc_en_el_macro_devuelve_al_menu(monkeypatch: pytest.MonkeyPatch) -> None:
    """La pantalla del macro tenia como unica salida cerrar el programa."""
    arranques: list[int] = []

    class AppQueVuelve:
        def __init__(self, config_path: str | None, *, permitir_volver: bool = False) -> None:
            arranques.append(1)
            # La primera vez simula que se pulso ESC; la segunda, salir.
            self.volver_al_menu = len(arranques) == 1

        def run(self) -> int:
            return 0

    monkeypatch.setattr(app, "HyMacroApp", AppQueVuelve)
    _con_respuestas(monkeypatch, ["1", "1"])

    assert app.run_menu() == 0
    assert len(arranques) == 2, "tras volver al menu deberia poder arrancarse otra vez"


def test_se_limpia_la_pantalla_al_volver_al_menu(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Sin limpiar, cada vuelta apilaba otro banner debajo del anterior."""
    _con_respuestas(monkeypatch, ["3", "", "esc"])
    app.run_menu(str(_config_con(tmp_path, colors="always")))

    salida = capsys.readouterr().out
    trozos = salida.split(LIMPIAR)

    assert salida.count(LIMPIAR) >= 2, "no se limpia al volver al menu"
    for trozo in trozos:
        cuantos = ANSI.sub("", trozo).count("Hypixel Garden Automation Tool")
        assert cuantos <= 1, "dos banners sin limpiar entre medias"


def test_el_menu_respeta_general_colors(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Antes el menu forzaba 'auto' y el ajuste solo valia para el macro."""
    _con_respuestas(monkeypatch, ["esc"])
    app.run_menu(str(_config_con(tmp_path, colors="never")))

    assert "\x1b" not in capsys.readouterr().out


def test_un_config_roto_no_impide_abrir_el_menu(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Si el config esta mal, se quiere el menu para poder ver el error."""
    roto = tmp_path / "roto.json"
    roto.write_text("{ esto no es json", encoding="utf-8")
    _con_respuestas(monkeypatch, ["esc"])

    assert app.run_menu(str(roto)) == 0


def test_las_etiquetas_de_distinto_ancho_quedan_alineadas() -> None:
    """En la pantalla del macro conviven "F8)" y "F10)"."""
    from hymacro.ui import pintar_opciones

    texto = ANSI.sub(
        "",
        pintar_opciones(
            "Teclas",
            [("F8", "Cocoa Beans", ""), ("F10", "Cobblestone", ""), ("ESC", "volver", "")],
        ),
    )
    columnas = {
        linea.index(nombre)
        for linea, nombre in zip(
            [line for line in texto.splitlines() if ")" in line],
            ["Cocoa Beans", "Cobblestone", "volver"],
            strict=True,
        )
    }

    assert len(columnas) == 1, f"los nombres empiezan en columnas distintas: {columnas}"


def _olas_registradas(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, animar: bool) -> list[object]:
    """Corre el menu anotando cada ola que se registra para animar.

    Se entra en Calibrar (2) porque es una de las pantallas que registran su
    banner; el menu principal lo anima por otra via.
    """
    registros: list[object] = []
    monkeypatch.setattr(app, "fijar_ola", registros.append)
    _con_respuestas(monkeypatch, ["2", "esc", "esc"])
    app.run_menu(str(_config_con(tmp_path, colors="always", banner_animation=animar)))
    return registros


def test_banner_animation_false_apaga_la_ola_en_el_menu(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Regresion: el menu creaba la ola sin mirar el ajuste, asi que ponerlo
    en `false` solo tenia efecto en la pantalla del macro."""
    registros = _olas_registradas(tmp_path, monkeypatch, animar=False)
    capsys.readouterr()

    assert registros, "no se llego a registrar nada"
    assert all(ola is None for ola in registros), "se animo pese a banner_animation=false"


def test_banner_animation_true_registra_la_ola(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    registros = _olas_registradas(tmp_path, monkeypatch, animar=True)
    capsys.readouterr()

    assert any(ola is not None for ola in registros)


def test_al_salir_del_menu_no_queda_ninguna_ola(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Si no, la siguiente espera de teclado repinta un banner que ya no esta."""
    from hymacro import ui

    _con_respuestas(monkeypatch, ["esc"])
    app.run_menu(str(_config_con(tmp_path, colors="always", banner_animation=True)))
    capsys.readouterr()

    assert ui._ola_actual is None


def test_esc_es_la_unica_tecla_para_volver() -> None:
    """Antes unos menus pedian 0 y otros ESC."""
    from hymacro.app import _OPCIONES_MACRO, _OPCIONES_MENU
    from hymacro.editor import SECCIONES
    from hymacro.ui import VOLVER

    assert _OPCIONES_MENU[-1][0] == VOLVER
    assert _OPCIONES_MACRO[-1][0] == VOLVER
    for _, campos in SECCIONES:
        assert campos, "una seccion vacia no tendria como salir"


def test_el_menu_principal_pasa_el_ajuste_a_la_animacion(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """El menu principal anima pasando la ola, no registrandola: hay que
    comprobar esa via aparte."""
    recibidas: list[object] = []

    def espia(opciones: set[str], defecto: str, ola: object, debajo: int, fps: int = 15) -> str:
        recibidas.append(ola)
        return "ESC"

    monkeypatch.setattr(app, "consola_interactiva", lambda: True)
    monkeypatch.setattr(app, "_leer_tecla_animando", espia)

    app.run_menu(str(_config_con(tmp_path, colors="always", banner_animation=False)))
    capsys.readouterr()
    assert recibidas == [None], "el menu anima pese a banner_animation=false"

    recibidas.clear()
    app.run_menu(str(_config_con(tmp_path, colors="always", banner_animation=True)))
    capsys.readouterr()
    assert recibidas and recibidas[0] is not None
