"""Carga, validacion y resolucion de rutas de la configuracion."""

from __future__ import annotations

import json
import logging
import os
import shutil
import sys
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_SENTINEL = object()

#: Valores por defecto. Se fusionan con el config.json del usuario, asi que un
#: config viejo (v2) sigue funcionando y hereda las claves nuevas.
DEFAULTS: dict[str, Any] = {
    "macros": {
        "cocoa_beans": {
            "keys": ["w", "d", "s", "a"],
            "routes_per_warp": 8,
            "use_cocoa_wait": True,
            "timing_ms": 93,
            "cocoa_wait_seconds": 1,
        },
        "nether_wart": {
            "keys": ["w", "d", "w", "a"],
            "routes_per_warp": 4,
            "use_cocoa_wait": False,
            "timing_ms": 119,
            "cocoa_wait_seconds": 0,
        },
        "cobblestone": {
            "key": "w",
            "mining_duration_seconds": 240,
            "hub_wait_seconds": 3,
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
        "loop_delay_ms": 100,
        "mouse_button": "left",
        "chat_key": "t",
        "chat_open_delay_ms": 120,
        "command_input_mode": "unicode",
        "timing_jitter_ms": 8,
        "wait_jitter_percent": 5,
        "suppress_hotkeys": True,
    },
    "safety": {
        "require_window_focus": True,
        "window_title_contains": "Minecraft",
        "mouse_failsafe": True,
        "mouse_failsafe_px": 100,
        "max_session_minutes": 0,
        "watchdog_interval_ms": 100,
    },
}

#: Tipos de macro que el controlador sabe ejecutar.
MACRO_TYPES = ("cocoa_beans", "nether_wart", "cobblestone")


class ConfigError(RuntimeError):
    """La configuracion no se pudo cargar o no es valida."""


def app_dir() -> Path:
    """Directorio donde vive la app: junto al .exe, o la raiz del repo en dev."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[2]


def bundled_default_path() -> Path:
    """Ruta de la plantilla de configuracion que viaja dentro del paquete."""
    return Path(__file__).resolve().parent / "data" / "config.default.json"


def resolve_config_path(explicit: str | os.PathLike[str] | None = None) -> Path:
    """Decide que config.json usar.

    Prioridad: argumento `--config` > variable HYMACRO_CONFIG > junto a la app >
    directorio actual (esto ultimo solo cuando NO esta congelado).
    """
    if explicit is not None:
        return Path(explicit).expanduser().resolve()

    from_env = os.environ.get("HYMACRO_CONFIG")
    if from_env:
        return Path(from_env).expanduser().resolve()

    candidate = app_dir() / "config.json"
    if candidate.exists():
        return candidate

    # Congelado, el .exe manda: nunca se mira el directorio actual. Si no,
    # arrancar el .exe desde otra carpeta cargaria una config ajena en silencio
    # y el macro correria con timings que no son los tuyos.
    if not getattr(sys, "frozen", False):
        cwd_candidate = Path.cwd() / "config.json"
        if cwd_candidate.exists():
            return cwd_candidate

    return candidate


def ensure_config_exists(path: Path) -> bool:
    """Crea el config.json desde la plantilla si falta. Devuelve True si lo creo."""
    if path.exists():
        return False

    template = bundled_default_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    if template.exists():
        shutil.copyfile(template, path)
    else:  # pragma: no cover - solo si el paquete se instalo mal
        path.write_text(json.dumps(DEFAULTS, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    logger.info("Se creo una configuracion nueva en %s", path)
    return True


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    """Fusiona `override` sobre `base` sin mutar ninguno de los dos."""
    result = dict(base)
    for key, value in override.items():
        current = result.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            result[key] = _deep_merge(current, value)
        else:
            result[key] = value
    return result


class ConfigManager:
    """Gestor de configuracion para cargar y validar settings del macro."""

    def __init__(
        self, config_path: str | os.PathLike[str] | None = None, *, auto_create: bool = True
    ) -> None:
        self.config_path = resolve_config_path(config_path)
        self.created_default = False
        self.config: dict[str, Any] = {}
        if auto_create:
            self.created_default = ensure_config_exists(self.config_path)
        self.load_config()

    def load_config(self) -> None:
        """Carga la configuracion desde el archivo JSON y la fusiona con los defaults."""
        try:
            raw = self.config_path.read_text(encoding="utf-8")
        except FileNotFoundError as exc:
            raise ConfigError(f"Archivo de configuracion no encontrado: {self.config_path}") from exc
        except OSError as exc:
            raise ConfigError(f"No se pudo leer {self.config_path}: {exc}") from exc

        try:
            user_config = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ConfigError(f"JSON invalido en {self.config_path} (linea {exc.lineno}): {exc.msg}") from exc

        if not isinstance(user_config, dict):
            raise ConfigError(f"{self.config_path} debe contener un objeto JSON en la raiz")

        self.config = _deep_merge(DEFAULTS, user_config)
        self._validate_config()
        logger.info("Configuracion cargada desde %s", self.config_path)

    def _validate_config(self) -> None:
        """Valida los campos de los que depende el macro para no fallar a mitad de ruta."""
        from .winput import resolve_scancode  # import local: winput toca la API de Windows

        for section in ("macros", "commands", "keybinds", "general", "safety"):
            if not isinstance(self.config.get(section), dict):
                raise ConfigError(f"La seccion '{section}' falta o no es un objeto")

        button = self.get("general", "mouse_button")
        if button not in ("left", "right", "middle"):
            raise ConfigError(f"general.mouse_button debe ser left/right/middle, no {button!r}")

        mode = self.get("general", "command_input_mode")
        if mode not in ("unicode", "scancode"):
            raise ConfigError(f"general.command_input_mode debe ser 'unicode' o 'scancode', no {mode!r}")

        try:
            resolve_scancode(str(self.get("general", "chat_key")))
        except ValueError as exc:
            raise ConfigError(f"general.chat_key invalida: {exc}") from exc

        for name in ("cocoa_beans", "nether_wart"):
            macro = self.get("macros", name)
            if not isinstance(macro, dict):
                raise ConfigError(f"Falta la configuracion del macro '{name}'")
            keys = macro.get("keys")
            if not isinstance(keys, list) or len(keys) != 4:
                raise ConfigError(f"macros.{name}.keys debe ser una lista de 4 teclas, no {keys!r}")
            for key in keys:
                try:
                    resolve_scancode(str(key))
                except ValueError as exc:
                    raise ConfigError(f"macros.{name}.keys: {exc}") from exc
            if int(macro.get("routes_per_warp", 0)) < 1:
                raise ConfigError(f"macros.{name}.routes_per_warp debe ser >= 1")
            if float(macro.get("timing_ms", 0)) <= 0:
                raise ConfigError(f"macros.{name}.timing_ms debe ser > 0")

        cobble = self.get("macros", "cobblestone")
        if not isinstance(cobble, dict):
            raise ConfigError("Falta la configuracion del macro 'cobblestone'")
        try:
            resolve_scancode(str(cobble.get("key")))
        except ValueError as exc:
            raise ConfigError(f"macros.cobblestone.key invalida: {exc}") from exc
        if float(cobble.get("mining_duration_seconds", 0)) <= 0:
            raise ConfigError("macros.cobblestone.mining_duration_seconds debe ser > 0")

        binds = self.get("keybinds")
        assert isinstance(binds, dict)
        for action in (*MACRO_TYPES, "stop"):
            if not binds.get(action):
                raise ConfigError(f"Falta el keybind para '{action}'")
        used = [str(v).lower() for v in binds.values()]
        duplicated = {k for k in used if used.count(k) > 1}
        if duplicated:
            raise ConfigError(f"Hay keybinds repetidos: {', '.join(sorted(duplicated))}")

    def get(self, *keys: str, default: Any = _SENTINEL) -> Any:
        """Obtiene un valor anidado de la configuracion.

        `default` es keyword-only a proposito: en la v2 era posicional y se
        interpretaba como una clave mas, lo que devolvia None y tiraba la app.
        """
        value: Any = self.config
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                if default is _SENTINEL:
                    raise ConfigError(f"Falta la clave de configuracion: {'.'.join(keys)}")
                return default
        return value

    def get_float(self, *keys: str, default: Any = _SENTINEL) -> float:
        return float(self.get(*keys, default=default))

    def get_int(self, *keys: str, default: Any = _SENTINEL) -> int:
        return int(self.get(*keys, default=default))

    def get_bool(self, *keys: str, default: Any = _SENTINEL) -> bool:
        return bool(self.get(*keys, default=default))

    def get_str(self, *keys: str, default: Any = _SENTINEL) -> str:
        return str(self.get(*keys, default=default))
