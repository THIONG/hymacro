"""Tests del editor de configuracion.

Lo importante: que nunca llegue a escribirse un config.json que luego no cargue.
"""

from __future__ import annotations

import builtins
import json
from collections.abc import Iterator
from pathlib import Path

import pytest

from hymacro import editor
from hymacro.config import DEFAULTS, ConfigManager
from hymacro.editor import Campo, _interpretar, _mostrar, editar_configuracion


@pytest.fixture
def config(tmp_path: Path) -> Path:
    ruta = tmp_path / "config.json"
    ruta.write_text(json.dumps(DEFAULTS), encoding="utf-8")
    return ruta


def _responde(monkeypatch: pytest.MonkeyPatch, respuestas: list[str]) -> None:
    it: Iterator[str] = iter(respuestas)

    def fake_input(prompt: str = "") -> str:
        try:
            return next(it)
        except StopIteration as exc:
            raise EOFError from exc

    monkeypatch.setattr(builtins, "input", fake_input)
    monkeypatch.setattr(editor, "leer_opcion", lambda opciones, defecto: fake_input())


def test_interpretar_acepta_la_coma_decimal() -> None:
    """Escribiendo en espanol lo natural es 12,5 y no 12.5."""
    campo = Campo(("x",), "x", "segundos")
    assert _interpretar("12,5", campo) == 12.5
    assert _interpretar("12.5", campo) == 12.5


def test_interpretar_booleanos_en_espanol() -> None:
    campo = Campo(("x",), "x", "booleano")
    assert _interpretar("si", campo) is True
    assert _interpretar("N", campo) is False
    with pytest.raises(ValueError, match="responde si o no"):
        _interpretar("puede", campo)


def test_interpretar_teclas_exige_cuatro() -> None:
    campo = Campo(("x",), "x", "teclas")
    assert _interpretar("d w a w", campo) == ["d", "w", "a", "w"]
    assert _interpretar("d,w,a,w", campo) == ["d", "w", "a", "w"]
    with pytest.raises(ValueError, match="4 teclas"):
        _interpretar("d w a", campo)


def test_mostrar_formatea_para_leer() -> None:
    assert _mostrar(True, "booleano") == "si"
    assert _mostrar(["d", "w"], "teclas") == "D W"
    assert _mostrar(120.0, "segundos") == "120 s"
    assert _mostrar(1.2, "segundos") == "1.2 s"
    assert _mostrar(None, "entero") == "(sin definir)"


def test_guarda_un_valor_valido(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # 1 = Nether Wart, 2 = Ida, valor, Enter, 0 = volver, 0 = salir
    _responde(monkeypatch, ["1", "2", "95,5", "", "0", "0"])
    editar_configuracion(config)

    guardado = json.loads(config.read_text(encoding="utf-8"))
    assert guardado["macros"]["nether_wart"]["forward_seconds"] == 95.5
    ConfigManager(config, auto_create=False)  # y sigue cargando


def test_un_valor_invalido_no_toca_el_fichero(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Es la razon de ser del editor: no dejar un config que no abre."""
    antes = config.read_text(encoding="utf-8")
    # Tecla inexistente en el recorrido de Nether Wart.
    _responde(monkeypatch, ["1", "1", "d w a pepino", "", "0", "0"])
    editar_configuracion(config)

    assert config.read_text(encoding="utf-8") == antes, "escribio un config invalido"


def test_no_deja_asignar_dos_veces_la_misma_tecla(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    antes = json.loads(config.read_text(encoding="utf-8"))
    # 4 = Teclas, 4 = Detener, f8 (ya usada por cocoa beans)
    _responde(monkeypatch, ["4", "4", "f8", "", "0", "0"])
    editar_configuracion(config)

    assert json.loads(config.read_text(encoding="utf-8")) == antes


def test_enter_vacio_deja_el_valor_como_estaba(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    antes = json.loads(config.read_text(encoding="utf-8"))
    _responde(monkeypatch, ["1", "2", "", "", "0", "0"])
    editar_configuracion(config)

    assert json.loads(config.read_text(encoding="utf-8")) == antes


def test_un_config_ilegible_no_revienta(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    roto = tmp_path / "roto.json"
    roto.write_text("{ no soy json", encoding="utf-8")
    _responde(monkeypatch, [])

    editar_configuracion(roto)  # solo debe informar y volver


def test_solo_se_escribe_lo_que_cambia(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """No se vuelca el config fusionado: se respeta el fichero del usuario."""
    minimo = {"macros": {"nether_wart": {"forward_seconds": 10}}}
    config.write_text(json.dumps(minimo), encoding="utf-8")

    _responde(monkeypatch, ["1", "3", "60", "", "0", "0"])
    editar_configuracion(config)

    guardado = json.loads(config.read_text(encoding="utf-8"))
    assert guardado["macros"]["nether_wart"]["return_seconds"] == 60.0
    assert guardado["macros"]["nether_wart"]["forward_seconds"] == 10
    assert "keybinds" not in guardado, "se colaron los valores por defecto en el fichero"


def test_los_errores_de_numero_se_explican_en_castellano() -> None:
    """El mensaje crudo de Python ('could not convert string to float') no dice nada."""
    with pytest.raises(ValueError, match="no es un numero de segundos"):
        _interpretar("pepino", Campo(("x",), "x", "segundos"))
    with pytest.raises(ValueError, match="no es un numero"):
        _interpretar("pepino", Campo(("x",), "x", "entero"))
