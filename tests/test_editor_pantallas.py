"""El editor tiene que limpiar la pantalla en cada vista.

Sin esto los menus se iban apilando: se veian dos banners y la lista de
secciones repetida al bajar.
"""

from __future__ import annotations

import builtins
import json
from collections.abc import Iterator
from pathlib import Path

import pytest

from hymacro import editor
from hymacro.config import DEFAULTS
from hymacro.editor import editar_configuracion


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


def test_se_redibuja_en_cada_pantalla(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    redibujados: list[int] = []
    # 1 = Nether Wart, 2 = Ida, valor, Enter, 0 = volver, 0 = salir
    _responde(monkeypatch, ["1", "2", "95", "", "ESC", "ESC"])

    editar_configuracion(config, lambda: redibujados.append(1))

    # Secciones, campos de la seccion, la edicion, y otra vez cada menu al volver.
    assert len(redibujados) >= 5, f"solo se redibujo {len(redibujados)} veces"


def test_sin_callback_sigue_funcionando(config: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """El valor por defecto no hace nada: el editor no depende de la pantalla."""
    _responde(monkeypatch, ["1", "2", "95", "", "ESC", "ESC"])

    editar_configuracion(config)

    guardado = json.loads(config.read_text(encoding="utf-8"))
    assert guardado["macros"]["nether_wart"]["forward_seconds"] == 95.0
