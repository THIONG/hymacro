"""Tests del color de consola.

Lo importante aqui no es que quede bonito, sino que si la terminal no puede
pintar NO se cuelen secuencias de escape en la salida.
"""

from __future__ import annotations

import re
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
    print_rainbow("\n".join(str(n) for n in range(40)), animate=True, duracion=2.0)
    tardado = time.perf_counter() - inicio

    capsys.readouterr()
    assert tardado < 0.2, f"tardo {tardado:.2f} s: se esta durmiendo sin pintar"


def test_la_ola_varia_a_lo_ancho_no_solo_por_fila() -> None:
    """Si el tono dependiera solo de la fila seria un degradado, no una ola."""
    init_colors("always")
    pintada = console._linea_ola("#" * 60, fila=0, fase=0.0, paso_fila=0.05)

    tonos = set(re.findall(r"\x1b\[38;2;[\d;]+m", pintada))
    assert len(tonos) > 5, "no hay degradado horizontal"


def test_la_ola_se_desplaza_hacia_la_derecha() -> None:
    """Un tono fijo debe aparecer en columnas mayores segun avanza la fase."""
    init_colors("always")
    patron = re.compile(r"\x1b\[38;2;(\d+;\d+;\d+)m")

    def columna_del_primer_tono(fase: float) -> tuple[str, int]:
        pintada = console._linea_ola("#" * 60, fila=0, fase=fase, paso_fila=0.05)
        limpia = patron.sub("", pintada)
        primer = patron.search(pintada)
        assert primer is not None
        return primer.group(1), len(limpia)

    tono_inicial, _ = columna_del_primer_tono(0.0)

    despues = console._linea_ola("#" * 60, fila=0, fase=0.10, paso_fila=0.05)
    posicion = 0
    encontrada = None
    resto = despues
    while (m := patron.search(resto)) is not None:
        posicion += m.start()
        if m.group(1) == tono_inicial:
            encontrada = posicion
            break
        resto = resto[m.end() :]

    assert encontrada is not None and encontrada > 0, (
        "el tono no se ha movido a la derecha; la ola va al reves o esta quieta"
    )
