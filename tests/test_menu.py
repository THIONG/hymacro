"""Tests del menu interactivo.

Existe porque la forma normal de usar HyMacro es abrir el .exe con doble clic,
y ahi no hay manera de escribir --calibrate ni ningun otro argumento.
"""

from __future__ import annotations

import builtins
from collections.abc import Iterator

import pytest

import hymacro.app as app


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
    _con_respuestas(monkeypatch, ["0"])
    assert app.run_menu() == 0


def test_eof_no_revienta(monkeypatch: pytest.MonkeyPatch) -> None:
    """Cerrar la consola no debe dejar un traceback en pantalla."""
    _con_respuestas(monkeypatch, [])
    assert app.run_menu() == 0


def test_opcion_invalida_vuelve_a_preguntar(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_respuestas(monkeypatch, ["99", "pepino", "0"])
    assert app.run_menu() == 0


def test_enter_arranca_el_macro(monkeypatch: pytest.MonkeyPatch) -> None:
    """Enter a secas es la opcion 1, que es lo que se usa el 99% de las veces."""
    llamadas: list[str] = []

    class AppFalsa:
        def __init__(self, config_path: str | None) -> None:
            llamadas.append("construida")

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
    _con_respuestas(monkeypatch, ["2", "2", "", "0"])

    assert app.run_menu() == 0
    assert elegidos == ["cocoa_beans"]


def test_se_puede_volver_del_submenu_sin_calibrar(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(app, "calibrate", lambda cfg, macro: pytest.fail("no debia calibrar"))
    _con_respuestas(monkeypatch, ["2", "0", "", "0"])

    assert app.run_menu() == 0
