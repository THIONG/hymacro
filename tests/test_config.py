"""Tests de carga y validacion de configuracion."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import pytest

import hymacro.config
from hymacro.config import (
    DEFAULTS,
    ConfigError,
    ConfigManager,
    _deep_merge,
    ensure_config_exists,
    resolve_config_path,
)
from hymacro.controller import (
    MacroController,
    resolve_forward_hold,
    resolve_return_hold,
    resolve_step_ms,
)


def write_config(tmp_path: Path, data: dict[str, Any]) -> Path:
    path = tmp_path / "config.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_deep_merge_no_muta_los_originales() -> None:
    base = {"a": {"x": 1, "y": 2}}
    override = {"a": {"y": 99}}
    merged = _deep_merge(base, override)

    assert merged == {"a": {"x": 1, "y": 99}}
    assert base == {"a": {"x": 1, "y": 2}}


def test_config_v2_hereda_las_claves_nuevas(tmp_path: Path) -> None:
    """Un config.json de la v2 no tiene 'safety' ni el keybind de parada."""
    legacy = {
        "macros": DEFAULTS["macros"],
        "commands": DEFAULTS["commands"],
        "keybinds": {"cocoa_beans": "f8", "nether_wart": "f9", "cobblestone": "f10"},
        "general": {"loop_delay_ms": 100, "mouse_button": "left", "chat_key": "t"},
    }
    config = ConfigManager(write_config(tmp_path, legacy), auto_create=False)

    assert config.get("keybinds", "stop") == "f12"
    assert config.get("safety", "require_window_focus") is True
    assert config.get("general", "loop_delay_ms") == 100


def test_get_con_default_keyword_only(tmp_path: Path) -> None:
    """Regresion del crash de la v2: el default era posicional y se leia como clave."""
    config = ConfigManager(write_config(tmp_path, DEFAULTS), auto_create=False)

    assert config.get("general", "loop_delay_ms", default=999) == 100
    assert config.get("general", "no_existe", default=42) == 42
    assert config.get_float("general", "loop_delay_ms", default=100) / 1000.0 == 0.1


def test_get_sin_default_falla_fuerte(tmp_path: Path) -> None:
    config = ConfigManager(write_config(tmp_path, DEFAULTS), auto_create=False)

    with pytest.raises(ConfigError, match="Falta la clave"):
        config.get("general", "no_existe")


def test_keybinds_repetidos_se_rechazan(tmp_path: Path) -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["keybinds"]["stop"] = "f8"

    with pytest.raises(ConfigError, match="keybinds repetidos"):
        ConfigManager(write_config(tmp_path, data), auto_create=False)


def test_tecla_invalida_se_rechaza(tmp_path: Path) -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["macros"]["cocoa_beans"]["keys"] = ["w", "d", "s", "pepino"]

    with pytest.raises(ConfigError, match="Tecla no soportada"):
        ConfigManager(write_config(tmp_path, data), auto_create=False)


def test_json_roto_da_error_con_linea(tmp_path: Path) -> None:
    path = tmp_path / "config.json"
    path.write_text('{"macros": }', encoding="utf-8")

    with pytest.raises(ConfigError, match="JSON invalido"):
        ConfigManager(path, auto_create=False)


def test_congelado_no_cae_al_directorio_actual(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """El .exe se ancla a su carpeta; si no, cargaria la config de otra parte."""
    exe_dir = tmp_path / "app"
    exe_dir.mkdir()
    cwd_dir = tmp_path / "otra"
    cwd_dir.mkdir()
    (cwd_dir / "config.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(exe_dir / "HyMacro.exe"))
    monkeypatch.chdir(cwd_dir)
    monkeypatch.delenv("HYMACRO_CONFIG", raising=False)

    assert resolve_config_path() == exe_dir / "config.json"


def test_sin_congelar_si_usa_el_directorio_actual(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """En dev, si no hay config junto a la app, vale la del directorio actual."""
    app_sin_config = tmp_path / "app"
    app_sin_config.mkdir()
    cwd_dir = tmp_path / "otra"
    cwd_dir.mkdir()
    (cwd_dir / "config.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", False, raising=False)
    monkeypatch.setattr(hymacro.config, "app_dir", lambda: app_sin_config)
    monkeypatch.chdir(cwd_dir)
    monkeypatch.delenv("HYMACRO_CONFIG", raising=False)

    assert resolve_config_path() == cwd_dir / "config.json"


def test_la_app_gana_al_directorio_actual(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    app_con_config = tmp_path / "app"
    app_con_config.mkdir()
    (app_con_config / "config.json").write_text("{}", encoding="utf-8")
    cwd_dir = tmp_path / "otra"
    cwd_dir.mkdir()
    (cwd_dir / "config.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", False, raising=False)
    monkeypatch.setattr(hymacro.config, "app_dir", lambda: app_con_config)
    monkeypatch.chdir(cwd_dir)
    monkeypatch.delenv("HYMACRO_CONFIG", raising=False)

    assert resolve_config_path() == app_con_config / "config.json"


def test_variable_de_entorno_manda(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    externo = tmp_path / "mi-config.json"
    externo.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("HYMACRO_CONFIG", str(externo))

    assert resolve_config_path() == externo.resolve()


def test_ensure_config_exists_crea_desde_la_plantilla(tmp_path: Path) -> None:
    destino = tmp_path / "config.json"

    assert ensure_config_exists(destino) is True
    assert destino.exists()
    assert ensure_config_exists(destino) is False  # ya existe, no lo pisa

    ConfigManager(destino, auto_create=False)  # y lo que genera es valido


def test_config_por_defecto_del_repo_es_valida() -> None:
    """El config.json versionado tiene que pasar la validacion tal cual."""
    repo_config = Path(__file__).resolve().parents[1] / "config.json"
    ConfigManager(repo_config, auto_create=False)


def test_plantilla_del_paquete_coincide_con_la_del_repo() -> None:
    root = Path(__file__).resolve().parents[1]
    repo = json.loads((root / "config.json").read_text(encoding="utf-8"))
    bundled = json.loads(
        (root / "src" / "hymacro" / "data" / "config.default.json").read_text(encoding="utf-8")
    )
    assert repo == bundled


def test_forward_hold_prefiere_la_clave_nueva() -> None:
    assert resolve_forward_hold({"forward_seconds": 2.5}) == 2.5


def test_forward_hold_lee_el_par_antiguo_de_la_v2() -> None:
    """Un config.json de la v2 no tiene forward_seconds."""
    assert resolve_forward_hold({"use_cocoa_wait": True, "cocoa_wait_seconds": 1}) == 1.0
    # nether_wart de la v2: por esto nunca caminaba.
    assert resolve_forward_hold({"use_cocoa_wait": False, "cocoa_wait_seconds": 0}) == 0.0


def test_forward_hold_la_clave_nueva_gana_a_la_antigua() -> None:
    macro = {"forward_seconds": 4, "use_cocoa_wait": True, "cocoa_wait_seconds": 1}
    assert resolve_forward_hold(macro) == 4.0


def test_forward_hold_negativo_se_recorta_a_cero() -> None:
    assert resolve_forward_hold({"forward_seconds": -3}) == 0.0


def test_config_del_repo_camina_en_los_dos_macros() -> None:
    """Regresion: nether_wart salia con 0 s de avance y no se movia del sitio."""
    repo_config = Path(__file__).resolve().parents[1] / "config.json"
    config = ConfigManager(repo_config, auto_create=False)
    for name in ("cocoa_beans", "nether_wart"):
        assert resolve_forward_hold(config.get("macros", name)) > 0, name


def test_return_hold_por_defecto_es_cero() -> None:
    """Los recorridos de un solo sentido, como cocoa, no deben cambiar."""
    assert resolve_return_hold({"forward_seconds": 5}) == 0.0


def test_return_hold_se_lee_cuando_esta() -> None:
    assert resolve_return_hold({"return_seconds": 12.5}) == 12.5


def test_return_hold_negativo_se_recorta() -> None:
    assert resolve_return_hold({"return_seconds": -2}) == 0.0


def test_nether_wart_del_repo_hace_serpentina() -> None:
    """Regresion: la vuelta estaba a 0 a fuego y la fila de vuelta no duraba nada."""
    repo_config = Path(__file__).resolve().parents[1] / "config.json"
    config = ConfigManager(repo_config, auto_create=False)
    macro = config.get("macros", "nether_wart")

    assert resolve_forward_hold(macro) > 0
    assert resolve_return_hold(macro) > 0
    # Los tramos largos son laterales y el paso entre filas es hacia delante.
    assert macro["keys"][0] in ("a", "d")
    assert macro["keys"][1] == "w"
    assert macro["keys"][2] in ("a", "d")
    assert macro["keys"][3] == "w"


def test_cocoa_del_repo_sigue_siendo_de_un_solo_sentido() -> None:
    repo_config = Path(__file__).resolve().parents[1] / "config.json"
    config = ConfigManager(repo_config, auto_create=False)
    assert resolve_return_hold(config.get("macros", "cocoa_beans")) == 0.0


def test_step_en_segundos_gana_a_los_milisegundos() -> None:
    assert resolve_step_ms({"step_seconds": 0.25, "timing_ms": 119}) == 250.0


def test_step_lee_timing_ms_si_no_hay_step_seconds() -> None:
    assert resolve_step_ms({"timing_ms": 119}) == 119.0


def test_config_sin_step_valido_se_rechaza(tmp_path: Path) -> None:
    data = json.loads(json.dumps(DEFAULTS))
    data["macros"]["cocoa_beans"]["timing_ms"] = 0

    with pytest.raises(ConfigError, match="step_seconds debe ser > 0"):
        ConfigManager(write_config(tmp_path, data), auto_create=False)


def test_la_fila_de_dos_minutos_no_se_desalinea_por_el_jitter(tmp_path: Path) -> None:
    """Un 5% sobre 120 s serian 6 s de deriva; el tope absoluto lo impide."""
    config = ConfigManager(write_config(tmp_path, DEFAULTS), auto_create=False)
    ctrl = MacroController(config)

    muestras = [ctrl._jittered_seconds(120.0) for _ in range(500)]
    desviacion_max = max(abs(m - 120.0) for m in muestras)

    assert desviacion_max <= 0.5, f"deriva de {desviacion_max:.2f} s"
    assert len(set(muestras)) > 1, "deberia seguir habiendo variacion"
