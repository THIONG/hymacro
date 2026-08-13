"""Tests del cronometro de calibracion."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

from hymacro.app import _cronometrar_tramo, _esperar_pulsacion


class TecladoFalso:
    """Teclado simulado, para no depender de Minecraft ni de hooks reales."""

    def __init__(self) -> None:
        self.pulsado = False

    def is_pressed(self, key: str) -> bool:
        return self.pulsado

    def pulsar(self, duracion: float = 0.05) -> None:
        self.pulsado = True
        time.sleep(duracion)
        self.pulsado = False


def _en_segundo_plano(objetivo: Callable[[], None]) -> None:
    threading.Thread(target=objetivo, daemon=True).start()


def test_mide_el_tiempo_entre_dos_pulsaciones() -> None:
    kb = TecladoFalso()
    esperado = 1.0

    def guion() -> None:
        time.sleep(0.2)
        kb.pulsar()
        time.sleep(esperado)
        kb.pulsar()

    _en_segundo_plano(guion)
    medido = _cronometrar_tramo(kb, "f12", "prueba", "recorrido simulado")

    assert abs(medido - esperado) < 0.15, f"medido {medido:.3f} s"


def test_una_pulsacion_larga_no_cierra_el_tramo() -> None:
    """Sin esperar a que se suelte, el mismo apretado marcaria inicio y final."""
    kb = TecladoFalso()

    def guion() -> None:
        time.sleep(0.1)
        kb.pulsar(duracion=0.4)  # se queda apretada un buen rato
        time.sleep(0.6)
        kb.pulsar()

    _en_segundo_plano(guion)
    medido = _cronometrar_tramo(kb, "f12", "prueba", "recorrido simulado")

    assert 0.8 < medido < 1.3, f"medido {medido:.3f} s"


def test_esperar_pulsacion_vuelve_con_la_tecla_ya_suelta() -> None:
    kb = TecladoFalso()

    def guion() -> None:
        time.sleep(0.1)
        kb.pulsar(duracion=0.2)

    _en_segundo_plano(guion)
    _esperar_pulsacion(kb, "f12")

    assert not kb.pulsado, "deberia haber esperado a que se soltara"
