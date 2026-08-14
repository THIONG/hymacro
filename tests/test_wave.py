"""Tests del repintado en vivo del banner.

Lo delicado aqui es la aritmetica del cursor: si el salto hacia arriba no
coincide con lo que hay debajo, el banner se repinta encima del menu.
"""

from __future__ import annotations

import os
import re
import shutil

import pytest

from hymacro import console
from hymacro.console import BannerWave, init_colors

BANNER = "linea1\nlinea2\nlinea3"


@pytest.fixture(autouse=True)
def _restaurar_estado() -> object:
    previo = console.colors_enabled()
    yield
    console._enabled = previo


def test_alto_es_el_numero_de_filas() -> None:
    assert BannerWave(BANNER).alto == 3


def test_tick_sube_lo_justo_y_vuelve(capsys: pytest.CaptureFixture[str]) -> None:
    init_colors("always")
    ola = BannerWave(BANNER)
    ola.tick(lineas_debajo=7)

    salida = capsys.readouterr().out

    assert salida.startswith("\x1b[s"), "no guarda la posicion del cursor"
    assert salida.endswith("\x1b[u"), "no la restaura; el prompt acabaria en otro sitio"

    subida = re.search(r"\x1b\[(\d+)A", salida)
    assert subida is not None, "no sube el cursor hasta el banner"
    assert int(subida.group(1)) == 3 + 7, "el salto no cuadra con banner + lineas debajo"


def test_tick_repinta_exactamente_las_filas_del_banner(
    capsys: pytest.CaptureFixture[str],
) -> None:
    init_colors("always")
    BannerWave(BANNER).tick(lineas_debajo=2)

    salida = capsys.readouterr().out
    limpia = re.sub(r"\x1b\[[0-9;]*[a-zA-Z]", "", salida)

    assert limpia.count("\n") == 3, "escribe mas o menos filas de las que ocupa"
    for texto in ("linea1", "linea2", "linea3"):
        assert texto in limpia


def test_tick_no_escribe_nada_sin_color(capsys: pytest.CaptureFixture[str]) -> None:
    """Sin ANSI no hay forma de mover el cursor: repintar dejaria basura."""
    init_colors("never")
    BannerWave(BANNER).tick(lineas_debajo=5)

    assert capsys.readouterr().out == ""


def test_lineas_debajo_negativo_no_rompe_el_salto(
    capsys: pytest.CaptureFixture[str],
) -> None:
    init_colors("always")
    BannerWave(BANNER).tick(lineas_debajo=-4)

    subida = re.search(r"\x1b\[(\d+)A", capsys.readouterr().out)
    assert subida is not None
    assert int(subida.group(1)) == 3, "un valor negativo deberia tratarse como 0"


def test_la_fase_avanza_con_el_tiempo() -> None:
    """Si la fase no cambiara, el banner se repintaria siempre igual."""
    import time

    init_colors("always")
    ola = BannerWave(BANNER, velocidad=10.0)
    primera = ola._fase()
    time.sleep(0.05)
    assert ola._fase() > primera


def test_el_salto_del_menu_coincide_con_las_filas_escritas(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Regresion del off-by-one: el salto debe ser exactamente lo escrito.

    Se cuentan los saltos de linea que emite el menu y se comprueba que el
    repintado sube justo esa cantidad mas el alto del banner. Si no cuadra, la
    ola se dibuja encima de otra cosa.
    """

    import hymacro.app as app

    # Terminal alta: si no, salta el guardia de scroll y no se repinta nada.
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((200, 200)))
    monkeypatch.setattr(app, "_consola_interactiva", lambda: False)
    monkeypatch.setattr(app, "_preguntar", lambda opciones, defecto: "0")

    app.run_menu()  # ojo: por dentro llama a init_colors("auto")
    salida = capsys.readouterr().out

    # Filas escritas desde que termina el banner hasta donde queda el cursor.
    ola = BannerWave(app._BANNER)
    filas_totales = salida.count("\n")
    filas_menu = filas_totales - ola.alto

    # run_menu ha dejado el color apagado (no hay tty en pytest); se reactiva
    # para poder inspeccionar las secuencias que emite el repintado.
    init_colors("always")
    capsys.readouterr()
    ola.tick(lineas_debajo=filas_menu)
    subida = re.search(r"\x1b\[(\d+)A", capsys.readouterr().out)

    assert subida is not None
    assert int(subida.group(1)) == filas_totales, (
        "el salto no coincide con las filas realmente escritas: el banner se repintaria fuera de su sitio"
    )


def test_no_repinta_si_el_banner_se_ha_ido_por_scroll(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Si el banner ya no cabe en pantalla, subir el cursor caeria en medio de
    otra cosa y la repintariamos con el banner."""

    init_colors("always")
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((80, 10)))

    BannerWave(BANNER).tick(lineas_debajo=20)  # 3 + 20 no cabe en 10 filas

    assert capsys.readouterr().out == ""


def test_repinta_si_cabe_de_sobra(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:

    init_colors("always")
    monkeypatch.setattr(shutil, "get_terminal_size", lambda fallback=(80, 24): os.terminal_size((80, 50)))

    BannerWave(BANNER).tick(lineas_debajo=20)

    assert "\x1b[23A" in capsys.readouterr().out
