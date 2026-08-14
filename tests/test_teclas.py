"""ESC tiene que volver en TODAS las pantallas.

Existe porque una sustitucion no llego a aplicarse y `leer_opcion` se quedo sin
la rama de ESC: los menus de dentro no respondian y no lo detecto nada.
"""

from __future__ import annotations

import sys
import types
from collections.abc import Iterator

import pytest

from hymacro import console, ui
from hymacro.console import init_colors


class MsvcrtFalso(types.ModuleType):
    """Simula el modulo msvcrt devolviendo una secuencia de teclas."""

    def __init__(self, teclas: str) -> None:
        super().__init__("msvcrt")
        self._teclas: Iterator[str] = iter(teclas)
        self._siguiente: str | None = None

    def kbhit(self) -> bool:
        if self._siguiente is None:
            self._siguiente = next(self._teclas, None)
        return self._siguiente is not None

    def getwch(self) -> str:
        if self._siguiente is not None:
            tecla, self._siguiente = self._siguiente, None
            return tecla
        siguiente = next(self._teclas, None)
        if siguiente is None:
            raise AssertionError("se leyeron mas teclas de las previstas")
        return siguiente


@pytest.fixture(autouse=True)
def _consola_simulada(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    previo = console.colors_enabled()
    init_colors("always")
    monkeypatch.setattr(ui, "consola_interactiva", lambda: True)
    monkeypatch.setattr(ui, "_animando", lambda: _NoAnima())
    yield
    console._enabled = previo


class _NoAnima:
    def __enter__(self) -> None:
        return None

    def __exit__(self, *exc: object) -> None:
        return None


def _con_teclas(monkeypatch: pytest.MonkeyPatch, teclas: str) -> None:
    monkeypatch.setitem(sys.modules, "msvcrt", MsvcrtFalso(teclas))


def test_esc_vuelve_en_leer_opcion(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_teclas(monkeypatch, "\x1b")
    assert ui.leer_opcion({ui.VOLVER, "1", "2"}, "1") == ui.VOLVER


def test_ctrl_c_tambien_vuelve(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_teclas(monkeypatch, "\x03")
    assert ui.leer_opcion({ui.VOLVER, "1"}, "1") == ui.VOLVER


def test_una_opcion_normal_se_devuelve_tal_cual(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_teclas(monkeypatch, "2")
    assert ui.leer_opcion({ui.VOLVER, "1", "2"}, "1") == "2"


def test_enter_devuelve_el_valor_por_defecto(monkeypatch: pytest.MonkeyPatch) -> None:
    _con_teclas(monkeypatch, "\r")
    assert ui.leer_opcion({ui.VOLVER, "1", "2"}, "1") == "1"


def test_se_ignoran_las_teclas_que_no_son_opcion(monkeypatch: pytest.MonkeyPatch) -> None:
    """Las flechas llegan como un par y no deben colarse como opcion."""
    _con_teclas(monkeypatch, "\xe0Hz\x1b")
    assert ui.leer_opcion({ui.VOLVER, "1"}, "1") == ui.VOLVER


def test_todos_los_lectores_manejan_esc() -> None:
    """Los tres puntos donde se leen teclas tienen que tratar el 0x1b."""
    import inspect

    from hymacro import app

    for funcion in (app._leer_tecla_animando, app.HyMacroApp._se_pidio_volver, ui.leer_opcion):
        fuente = inspect.getsource(funcion)
        assert "\\x1b" in fuente, f"{funcion.__name__} no mira la tecla ESC"
