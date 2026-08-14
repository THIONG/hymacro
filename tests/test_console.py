"""Tests del color de consola.

Lo importante aqui no es que quede bonito, sino que si la terminal no puede
pintar NO se cuelen secuencias de escape en la salida.
"""

from __future__ import annotations

import sys

import pytest

from hymacro import console
from hymacro.console import BOLD, GREEN, RESET, hue, init_colors, paint, print_rainbow


@pytest.fixture(autouse=True)
def _restaurar_estado() -> object:
    """Deja el modulo como estaba: el estado del color es global."""
    previo = console.colors_enabled()
    yield
    console._enabled = previo


def test_sin_color_el_texto_sale_intacto() -> None:
    init_colors("never")
    assert paint("hola", BOLD, GREEN) == "hola"
    assert "\x1b" not in paint("hola", BOLD, GREEN)


def test_con_color_envuelve_y_cierra() -> None:
    init_colors("always")
    pintado = paint("hola", GREEN)

    assert pintado.startswith(GREEN)
    assert pintado.endswith(RESET), "sin reset, el color se derrama al resto de la consola"
    assert "hola" in pintado


def test_no_color_manda_sobre_always(monkeypatch: pytest.MonkeyPatch) -> None:
    """NO_COLOR es un convenio estandar; se respeta aunque se pida always."""
    monkeypatch.setenv("NO_COLOR", "1")
    assert init_colors("always") is False
    assert paint("hola", GREEN) == "hola"


def test_auto_no_pinta_si_la_salida_no_es_una_consola(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("NO_COLOR", raising=False)
    monkeypatch.setattr(sys.stdout, "isatty", lambda: False, raising=False)
    assert init_colors("auto") is False


def test_el_arcoiris_da_la_vuelta_completa() -> None:
    """Sin colores repetidos seguidos, y el ciclo cierra donde empieza."""
    codigos = [hue(i / 12) for i in range(12)]

    assert len(set(codigos)) == 12, "hay tonos repetidos en la rueda"
    assert hue(0.0) == hue(1.0), "el arcoiris deberia ser ciclico"
    for codigo in codigos:
        assert codigo.startswith("\x1b[38;2;")


def test_print_rainbow_imprime_todas_las_lineas(capsys: pytest.CaptureFixture[str]) -> None:
    init_colors("never")
    print_rainbow("uno\ndos\ntres", animate=False)

    salida = capsys.readouterr().out
    assert salida.splitlines() == ["uno", "dos", "tres"]
    assert "\x1b" not in salida


def test_print_rainbow_no_se_anima_sin_color(capsys: pytest.CaptureFixture[str]) -> None:
    """La animacion en un fichero de log solo serviria para hacerla lenta."""
    import time

    init_colors("never")
    inicio = time.perf_counter()
    print_rainbow("\n".join(str(n) for n in range(40)), animate=True, delay=0.05)
    tardado = time.perf_counter() - inicio

    capsys.readouterr()
    assert tardado < 0.2, f"tardo {tardado:.2f} s: se esta durmiendo sin pintar"
