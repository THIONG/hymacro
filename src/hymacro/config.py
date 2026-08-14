"""Configuration loading, validation and persistence."""

from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any

_MISSING = object()

MACRO_TYPES = ("cocoa_beans", "nether_wart", "cobblestone")
ROUTE_MACROS = ("cocoa_beans", "nether_wart")

MACRO_LABELS = {
    "cocoa_beans": "Cocoa Beans",
    "nether_wart": "Nether Wart",
    "cobblestone": "Cobblestone",
}

DEFAULTS: dict[str, Any] = {
    "macros": {
        "cocoa_beans": {
            "keys": ["w", "d", "s", "a"],
            "routes_per_warp": 8,
            "forward_seconds": 1.0,
            "return_seconds": 0.0,
            "step_seconds": 0.093,
        },
        "nether_wart": {
            "keys": ["d", "w", "a", "w"],
            "routes_per_warp": 4,
            "forward_seconds": 120.0,
            "return_seconds": 120.0,
            "step_seconds": 1.2,
        },
        "cobblestone": {
            "key": "w",
            "mining_seconds": 240.0,
            "hub_wait_seconds": 3.0,
        },
    },
    "commands": {
        "warp_garden": "/warp garden",
        "warp_hub": "/hub",
        "warp_island": "/is",
    },
    "keybinds": {
        "cocoa_beans": "f8",
        "nether_wart": "f9",
        "cobblestone": "f10",
        "stop": "f12",
    },
    "general": {
        "mouse_button": "left",
        "chat_key": "t",
        "chat_open_seconds": 0.12,
        "command_input_mode": "unicode",
        "step_jitter_seconds": 0.008,
        "wait_jitter_percent": 5.0,
        "wait_jitter_max_seconds": 0.5,
        "suppress_hotkeys": True,
        "colors": "auto",
        "banner_animation": True,
        "idle_poll_seconds": 0.05,
    },
    "safety": {
        "require_window_focus": True,
        "window_title_contains": "Minecraft",
        "mouse_failsafe": True,
        "mouse_failsafe_px": 100,
        "max_session_seconds": 0.0,
        "watchdog_seconds": 0.1,
    },
}


class ConfigError(RuntimeError):
    """The configuration could not be loaded or is not valid."""


def app_dir() -> Path:
    """Directory the app lives in: next to the executable, or the repository root."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[2]


def template_path() -> Path:
    """Location of the default configuration shipped inside the package."""
    return Path(__file__).resolve().parent / "data" / "config.default.json"


def resolve_config_path(explicit: str | os.PathLike[str] | None = None) -> Path:
    """Pick the configuration file to use.

    Priority: explicit path, then HYMACRO_CONFIG, then next to the app. The
    working directory is only considered when running from source; a frozen
    executable always anchors to its own folder.
    """
    if explicit is not None:
        return Path(explicit).expanduser().resolve()

    from_env = os.environ.get("HYMACRO_CONFIG")
    if from_env:
        return Path(from_env).expanduser().resolve()

    candidate = app_dir() / "config.json"
    if candidate.exists():
        return candidate

    if not getattr(sys, "frozen", False):
        cwd_candidate = Path.cwd() / "config.json"
        if cwd_candidate.exists():
            return cwd_candidate

    return candidate


def ensure_config_exists(path: Path) -> bool:
    """Create the configuration from the template when it is missing."""
    if path.exists():
        return False

    path.parent.mkdir(parents=True, exist_ok=True)
    template = template_path()
    if template.exists():
        shutil.copyfile(template, path)
    else:
        path.write_text(json.dumps(DEFAULTS, indent=2) + "\n", encoding="utf-8")
    return True


def read_raw(path: Path) -> dict[str, Any]:
    """Read the configuration file without merging the defaults in."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ConfigError(f"Configuration file not found: {path}") from exc
    except OSError as exc:
        raise ConfigError(f"Could not read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ConfigError(f"Invalid JSON in {path} (line {exc.lineno}): {exc.msg}") from exc

    if not isinstance(data, dict):
        raise ConfigError(f"{path} must contain a JSON object at the top level")
    return data


def write_raw(path: Path, data: dict[str, Any]) -> None:
    """Persist the configuration file."""
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def merge_defaults(override: dict[str, Any]) -> dict[str, Any]:
    """Combine a configuration with the built-in defaults."""
    return _deep_merge(DEFAULTS, override)


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    result = dict(base)
    for key, value in override.items():
        current = result.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            result[key] = _deep_merge(current, value)
        else:
            result[key] = value
    return result


def lookup(config: dict[str, Any], *keys: str) -> Any:
    """Read a nested key, returning None when any level is missing."""
    value: Any = config
    for key in keys:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def assign(config: dict[str, Any], keys: tuple[str, ...], value: Any) -> None:
    """Write a nested key, creating the intermediate objects it needs."""
    target = config
    for key in keys[:-1]:
        branch = target.get(key)
        if not isinstance(branch, dict):
            branch = {}
            target[key] = branch
        target = branch
    target[keys[-1]] = value


def validate(config: dict[str, Any]) -> None:
    """Check every field the macro relies on, so it cannot fail mid-route."""
    for section in ("macros", "commands", "keybinds", "general", "safety"):
        if not isinstance(config.get(section), dict):
            raise ConfigError(f"Section '{section}' is missing or is not an object")

    _validate_general(config)
    _validate_route_macros(config)
    _validate_cobblestone(config)
    _validate_keybinds(config)


def _check_key(value: Any, field: str) -> None:
    from .winput import resolve_scancode

    try:
        resolve_scancode(str(value))
    except ValueError as exc:
        raise ConfigError(f"{field}: {exc}") from exc


def _validate_general(config: dict[str, Any]) -> None:
    button = lookup(config, "general", "mouse_button")
    if button not in ("left", "right", "middle"):
        raise ConfigError(f"general.mouse_button must be left, right or middle, not {button!r}")

    mode = lookup(config, "general", "command_input_mode")
    if mode not in ("unicode", "scancode"):
        raise ConfigError(f"general.command_input_mode must be 'unicode' or 'scancode', not {mode!r}")

    colors = lookup(config, "general", "colors")
    if colors not in ("auto", "always", "never"):
        raise ConfigError(f"general.colors must be auto, always or never, not {colors!r}")

    _check_key(lookup(config, "general", "chat_key"), "general.chat_key")


def _validate_route_macros(config: dict[str, Any]) -> None:
    for name in ROUTE_MACROS:
        macro = lookup(config, "macros", name)
        if not isinstance(macro, dict):
            raise ConfigError(f"Missing configuration for macro '{name}'")

        keys = macro.get("keys")
        if not isinstance(keys, list) or len(keys) != 4:
            raise ConfigError(f"macros.{name}.keys must be a list of 4 keys, not {keys!r}")
        for key in keys:
            _check_key(key, f"macros.{name}.keys")

        if int(macro.get("routes_per_warp", 0)) < 1:
            raise ConfigError(f"macros.{name}.routes_per_warp must be 1 or more")
        if float(macro.get("step_seconds", 0)) <= 0:
            raise ConfigError(f"macros.{name}.step_seconds must be greater than 0")
        for field in ("forward_seconds", "return_seconds"):
            if float(macro.get(field, 0)) < 0:
                raise ConfigError(f"macros.{name}.{field} cannot be negative")


def _validate_cobblestone(config: dict[str, Any]) -> None:
    macro = lookup(config, "macros", "cobblestone")
    if not isinstance(macro, dict):
        raise ConfigError("Missing configuration for macro 'cobblestone'")
    _check_key(macro.get("key"), "macros.cobblestone.key")
    if float(macro.get("mining_seconds", 0)) <= 0:
        raise ConfigError("macros.cobblestone.mining_seconds must be greater than 0")


def _validate_keybinds(config: dict[str, Any]) -> None:
    binds = lookup(config, "keybinds")
    if not isinstance(binds, dict):
        raise ConfigError("Section 'keybinds' is missing or is not an object")
    for action in (*MACRO_TYPES, "stop"):
        if not binds.get(action):
            raise ConfigError(f"Missing keybind for '{action}'")

    used = [str(value).lower() for value in binds.values()]
    duplicated = sorted({key for key in used if used.count(key) > 1})
    if duplicated:
        raise ConfigError(f"Duplicated keybinds: {', '.join(duplicated)}")


class Config:
    """Validated configuration, merged with the built-in defaults."""

    def __init__(
        self,
        path: str | os.PathLike[str] | None = None,
        *,
        auto_create: bool = True,
    ) -> None:
        self.path = resolve_config_path(path)
        self.created_default = ensure_config_exists(self.path) if auto_create else False
        self.values = merge_defaults(read_raw(self.path))
        validate(self.values)

    def get(self, *keys: str, default: Any = _MISSING) -> Any:
        value = lookup(self.values, *keys)
        if value is None:
            if default is _MISSING:
                raise ConfigError(f"Missing configuration key: {'.'.join(keys)}")
            return default
        return value

    def number(self, *keys: str, default: Any = _MISSING) -> float:
        return float(self.get(*keys, default=default))

    def integer(self, *keys: str, default: Any = _MISSING) -> int:
        return int(self.get(*keys, default=default))

    def flag(self, *keys: str, default: Any = _MISSING) -> bool:
        return bool(self.get(*keys, default=default))

    def text(self, *keys: str, default: Any = _MISSING) -> str:
        return str(self.get(*keys, default=default))
