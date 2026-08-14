"""The banner and the shared header every screen starts with."""

from __future__ import annotations

from . import __version__
from .console import BOLD, GREY, WHITE, Banner, clear_screen, paint
from .ui import set_banner

BANNER = r"""
+==============================================================================+
|     /$$   /$$           /$$      /$$                                         |
|    | $$  | $$          | $$$    /$$$                                         |
|    | $$  | $$ /$$   /$$| $$$$  /$$$$  /$$$$$$   /$$$$$$$  /$$$$$$  /$$$$$$   |
|    | $$$$$$$$| $$  | $$| $$ $$/$$ $$ |____  $$ /$$_____/ /$$__  $$/$$__  $$  |
|    | $$__  $$| $$  | $$| $$  $$$| $$  /$$$$$$$| $$      | $$  \__/ $$  \ $$  |
|    | $$  | $$| $$  | $$| $$\  $ | $$ /$$__  $$| $$      | $$     | $$  | $$  |
|    | $$  | $$|  $$$$$$$| $$ \/  | $$|  $$$$$$$|  $$$$$$$| $$     |  $$$$$$/  |
|    |__/  |__/ \____  $$|__/     |__/ \_______/ \_______/|__/      \______/   |
|               /$$  | $$                                                      |
|              |  $$$$$$/                                                      |
|               \______/                                                       |
+==============================================================================+
"""

TAGLINE = "Hypixel Garden Automation Tool"


def header() -> str:
    """The version line, identical on every screen."""
    name = paint(f"  HyMacro v{__version__}", BOLD, WHITE)
    return f"{name} {paint('- ' + TAGLINE, GREY)}"


def new_screen(animate: bool = True) -> Banner:
    """Clear the screen, draw the banner and register it for animation."""
    clear_screen()
    banner = Banner(BANNER)
    banner.draw()
    print(header())
    set_banner(banner if animate else None)
    return banner
