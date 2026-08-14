"""Interactive menu shown when HyMacro is opened without arguments."""

from __future__ import annotations

from pathlib import Path

from . import calibration, settings
from .app import MacroApp, setup_logging
from .config import Config, ConfigError, ensure_config_exists, resolve_config_path
from .console import BOLD, GREY, RED, init_colors, paint
from .screen import new_screen
from .ui import BACK, Option, read_option, read_text, render_options

MAIN_OPTIONS: list[Option] = [
    ("1", "Start the macro", ""),
    ("2", "Calibrate timings", ""),
    ("3", "Settings", ""),
    (BACK, "Exit", ""),
]

MACRO_OPTIONS: list[Option] = [
    ("1", "Nether Wart", ""),
    ("2", "Cocoa Beans", ""),
    (BACK, "Back", ""),
]

_MACRO_BY_OPTION = {"1": "nether_wart", "2": "cocoa_beans"}


def _config_path(explicit: str | None) -> Path:
    path = resolve_config_path(explicit)
    ensure_config_exists(path)
    return path


def _preferences(explicit: str | None) -> tuple[str, bool]:
    """Colour mode and banner animation, defaulting when the file is unusable."""
    try:
        config = Config(explicit, auto_create=False)
    except ConfigError:
        return "auto", True
    return (
        config.text("general", "colors", default="auto"),
        config.flag("general", "banner_animation", default=True),
    )


def _choose_macro() -> str | None:
    print(render_options("Which macro?", MACRO_OPTIONS))
    print("")
    return _MACRO_BY_OPTION.get(read_option({option[0] for option in MACRO_OPTIONS}, BACK))


def run(config_path: str | None = None, verbose: bool = False) -> int:
    """Show the menu until the user exits."""
    setup_logging(verbose=verbose)
    color_mode, animate = _preferences(config_path)
    init_colors(color_mode)
    try:
        return _loop(config_path, animate)
    finally:
        from .ui import set_banner

        set_banner(None)


def _loop(config_path: str | None, animate: bool) -> int:
    options = {option[0] for option in MAIN_OPTIONS}

    while True:
        new_screen(animate)
        print(render_options("HyMacro", MAIN_OPTIONS))
        print("")

        choice = read_option(options, "1")

        if choice == BACK:
            print(paint("  Goodbye.", GREY))
            return 0

        if choice == "1":
            code = _start_macro(config_path)
            if code is not None:
                return code
        elif choice == "2":
            new_screen(animate)
            macro_type = _choose_macro()
            if macro_type is None:
                continue
            new_screen(animate)
            calibration.run(config_path, macro_type)
        elif choice == "3":

            def redraw() -> None:
                new_screen(animate)

            settings.run(_config_path(config_path), redraw)


def _start_macro(config_path: str | None) -> int | None:
    """Run the macro screen. None means the user asked to come back."""
    from .ui import set_banner

    set_banner(None)
    try:
        config = Config(config_path)
    except ConfigError as exc:
        print(f"  {paint('Error:', BOLD, RED)} {exc}")
        read_text(paint("\n  Press Enter to continue > ", GREY))
        return None

    app = MacroApp(config, allow_back=True)
    code = app.run()
    return None if app.back_to_menu else code
