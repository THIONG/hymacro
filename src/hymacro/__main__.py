"""Punto de entrada de HyMacro."""

from __future__ import annotations

import argparse
import logging
import sys

from . import __version__
from .app import HyMacroApp, check_config, enable_utf8_console, setup_logging
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
    parser.add_argument("--verbose", action="store_true", help="Muestra los logs de debug en consola")
    parser.add_argument("--version", action="version", version=f"HyMacro {__version__}")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.check:
        return check_config(args.config)

    enable_utf8_console()
    setup_logging(verbose=args.verbose)

    if sys.platform != "win32":
        print("[ERROR] HyMacro solo funciona en Windows.", file=sys.stderr)
        return 1

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
