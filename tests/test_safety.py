"""Tests de los failsafes y utilidades de entrada que no tocan la API de Windows."""

from __future__ import annotations

import threading
import time

import pytest

import hymacro.safety
from hymacro.safety import SafetyGuard, SafetyLimits, window_matches
from hymacro.winput import _utf16_units, resolve_scancode


def make_limits(**overrides: object) -> SafetyLimits:
    base = {
        "require_focus": False,
        "title_contains": "Minecraft",
        "mouse_failsafe": False,
        "mouse_threshold_px": 100,
        "max_session_seconds": 0.0,
        "interval_seconds": 0.01,
        "stop_key": "",
    }
    base.update(overrides)
    return SafetyLimits(**base)  # type: ignore[arg-type]


def test_window_matches_ignora_mayusculas() -> None:
    assert window_matches("Minecraft 1.8.9 - usuario", "minecraft")
    assert window_matches("cualquier cosa", "")
    assert not window_matches("Google Chrome", "Minecraft")
    assert not window_matches("", "Minecraft")


def test_limite_de_sesion_dispara_el_failsafe() -> None:
    disparado = threading.Event()
    motivos: list[str] = []

    def on_violation(reason: str) -> None:
        motivos.append(reason)
        disparado.set()

    guard = SafetyGuard(make_limits(max_session_seconds=0.05), on_violation)
    guard.arm()
    try:
        assert disparado.wait(timeout=2.0), "el watchdog no disparo a tiempo"
    finally:
        guard.disarm()

    assert "limite de sesion" in motivos[0]


def test_preflight_sin_exigir_foco_pasa() -> None:
    guard = SafetyGuard(make_limits(require_focus=False), lambda reason: None)
    assert guard.preflight() is None


def test_disarm_sin_arm_no_explota() -> None:
    guard = SafetyGuard(make_limits(), lambda reason: None)
    guard.disarm()


def test_scancodes_de_las_teclas_por_defecto() -> None:
    assert resolve_scancode("w") == 0x11
    assert resolve_scancode("A") == 0x1E  # se normaliza a minuscula
    assert resolve_scancode(" t ") == 0x14
    assert resolve_scancode("enter") == 0x1C
    assert resolve_scancode("f12") == 0x58


def test_utf16_units_maneja_ascii_y_emoji() -> None:
    assert _utf16_units("/is") == [ord("/"), ord("i"), ord("s")]
    # Fuera del BMP: se parte en un par subrogado, no en un solo code unit.
    assert len(_utf16_units("\U0001f600")) == 2


def test_la_tecla_de_parada_se_detecta_sin_depender_del_hook(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """El hotkey puede no entregarse; el watchdog pregunta el estado al sistema."""
    pulsada = {"f12": False}
    monkeypatch.setattr(hymacro.safety, "is_key_held", lambda k: pulsada.get(k, False))

    disparado = threading.Event()
    motivos: list[str] = []

    def on_violation(reason: str) -> None:
        motivos.append(reason)
        disparado.set()

    guard = SafetyGuard(make_limits(stop_key="f12"), on_violation)
    guard.arm()
    try:
        assert not disparado.wait(timeout=0.3), "no deberia dispararse sin pulsar nada"
        pulsada["f12"] = True
        assert disparado.wait(timeout=2.0), "no detecto la tecla de parada"
    finally:
        guard.disarm()

    assert "parada manual" in motivos[0]
    assert "F12" in motivos[0]


def test_sin_stop_key_no_se_comprueba_el_teclado(monkeypatch: pytest.MonkeyPatch) -> None:
    consultas: list[str] = []

    def espia(key: str) -> bool:
        consultas.append(key)
        return False

    monkeypatch.setattr(hymacro.safety, "is_key_held", espia)

    guard = SafetyGuard(make_limits(stop_key=""), lambda reason: None)
    guard.arm()
    time.sleep(0.2)
    guard.disarm()

    assert consultas == []
