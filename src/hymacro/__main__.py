"""Command line entry point."""

from __future__ import annotations

import argparse
import contextlib
import sys

from . import __version__, calibration, menu
from .app import run_headless, setup_logging
from .config import MACRO_LABELS, Config, ConfigError
from .console import (
    BOLD,
    GREEN,
    GREY,
    RED,
    WHITE,
    YELLOW,
    init_colors,
    paint,
    set_console_icon,
    set_console_title,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hymacro",
        description="Automation tool for the Hypixel Skyblock Garden.",
    )
    parser.add_argument("--config", metavar="PATH", help="Use a different config.json")
    parser.add_argument("--check", action="store_true", help="Validate the configuration and exit")
    parser.add_argument(
        "--calibrate",
        nargs="?",
        const="nether_wart",
        metavar="MACRO",
        help="Time your route and print the values to apply",
    )
    parser.add_argument("--no-menu", action="store_true", help="Go straight to the macro screen")
    parser.add_argument("--verbose", action="store_true", help="Show debug logs on the console")
    parser.add_argument("--version", action="version", version=f"HyMacro {__version__}")
    return parser


def enable_utf8() -> None:
    """Put the console in UTF-8 so the banner renders correctly."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            with contextlib.suppress(OSError, ValueError):
                reconfigure(encoding="utf-8", errors="replace")


def check_config(config_path: str | None) -> int:
    """Validate the configuration and summarise the hotkeys."""
    enable_utf8()
    init_colors("auto")
    try:
        config = Config(config_path, auto_create=False)
    except ConfigError as exc:
        print(f"{paint('Error:', BOLD, RED)} {exc}", file=sys.stderr)
        return 1

    print(f"{paint('Configuration is valid:', BOLD, GREEN)} {paint(str(config.path), GREY)}")
    binds = config.get("keybinds")
    for macro, label in MACRO_LABELS.items():
        key = str(binds[macro]).upper().ljust(5)
        print(f"    {paint(key, BOLD, YELLOW)}{paint('->', GREY)} {paint(label, WHITE)}")
    stop = str(binds["stop"]).upper().ljust(5)
    print(f"    {paint(stop, BOLD, RED)}{paint('->', GREY)} {paint('Stop', WHITE)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.check:
        return check_config(args.config)

    enable_utf8()
    setup_logging(verbose=args.verbose)
    set_console_title(f"HyMacro v{__version__}")
    set_console_icon()

    if sys.platform != "win32":
        print("HyMacro only runs on Windows.", file=sys.stderr)
        return 1

    if args.calibrate is not None:
        init_colors("auto")
        return calibration.run(args.config, args.calibrate)

    if args.no_menu or not (sys.stdin is not None and sys.stdin.isatty()):
        try:
            config = Config(args.config)
        except ConfigError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            return 1
        init_colors(config.text("general", "colors", default="auto"))
        return run_headless(config)

    return menu.run(args.config, verbose=args.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
