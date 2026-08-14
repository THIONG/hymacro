"""Punto de entrada de HyMacro."""

from __future__ import annotations

import argparse
import logging
import sys

from . import __version__
from .app import (
    HyMacroApp,
    calibrate,
    check_config,
    enable_utf8_console,
    run_menu,
    setup_logging,
)
from .config import ConfigError

logger = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hymacro",
        description="Herramienta de automatizacion para el Garden de Hypixel Skyblock.",
    )
    parser.add_argument("--config", metavar="RUTA", help="Ruta a un config.json alternativo")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Valida la configuracion y sale, sin registrar hotkeys",
    )
    parser.add_argument(
        "--no-menu",
        action="store_true",
        help="Va directo al modo hotkeys, sin mostrar el menu interactivo",
    )
    parser.add_argument("--verbose", action="store_true", help="Muestra los logs de debug en consola")
    parser.add_argument("--version", action="version", version=f"HyMacro {__version__}")

    diag = parser.add_argument_group("diagnostico")
    diag.add_argument(
        "--calibrate",
        nargs="?",
        const="nether_wart",
        metavar="MACRO",
        help="Cronometro manual: mides tu recorrido con F12 y te da los tres tiempos",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.check:
        return check_config(args.config)

    if args.calibrate is not None:
        return calibrate(args.config, args.calibrate)

    enable_utf8_console()
    setup_logging(verbose=args.verbose)

    if sys.platform != "win32":
        print("[ERROR] HyMacro solo funciona en Windows.", file=sys.stderr)
        return 1

    # Sin argumentos y con consola interactiva se muestra el menu: abrir el
    # .exe con doble clic es la forma normal de usarlo, y ahi no hay manera de
    # escribir --calibrate.
    if not args.no_menu and sys.stdin is not None and sys.stdin.isatty():
        return run_menu(args.config, verbose=args.verbose)

    try:
        app = HyMacroApp(args.config)
    except ConfigError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        logger.error("Configuracion invalida: %s", exc)
        return 1

    try:
        return app.run()
    except Exception as exc:
        logger.exception("Error fatal")
        print(f"[ERROR] Error fatal: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
