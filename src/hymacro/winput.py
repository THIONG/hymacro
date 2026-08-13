"""Backend de entrada para Windows basado en SendInput.

Sustituye a pyautogui por dos motivos:

1. Inyecta *scancodes* en lugar de virtual-keys. Minecraft usa GLFW, que lee
   el scancode del mensaje; los virtual-keys que enviaba pyautogui se pierden
   en algunas configuraciones de teclado.
2. Evita arrastrar Pillow/pyscreeze/pytweening al ejecutable final, que es
   peso muerto y superficie extra para los falsos positivos de antivirus.

El backend lleva registro de todo lo que mantiene presionado para poder
soltarlo de golpe cuando se detiene el macro. Sin esto, parar el macro a mitad
de una ruta te deja caminando contra una pared indefinidamente.
"""

from __future__ import annotations

import ctypes
import logging
import sys
import threading

logger = logging.getLogger(__name__)

_IS_WINDOWS = sys.platform == "win32"

# --- Constantes de la API de Windows -----------------------------------------

_INPUT_MOUSE = 0
_INPUT_KEYBOARD = 1

_KEYEVENTF_EXTENDEDKEY = 0x0001
_KEYEVENTF_KEYUP = 0x0002
_KEYEVENTF_UNICODE = 0x0004
_KEYEVENTF_SCANCODE = 0x0008

_MOUSE_FLAGS: dict[str, tuple[int, int]] = {
    # boton: (flag_down, flag_up)
    "left": (0x0002, 0x0004),
    "right": (0x0008, 0x0010),
    "middle": (0x0020, 0x0040),
}

# Scancodes del Set 1 (los que entiende GLFW/Minecraft directamente).
_SCANCODES: dict[str, int] = {
    "escape": 0x01,
    "1": 0x02,
    "2": 0x03,
    "3": 0x04,
    "4": 0x05,
    "5": 0x06,
    "6": 0x07,
    "7": 0x08,
    "8": 0x09,
    "9": 0x0A,
    "0": 0x0B,
    "-": 0x0C,
    "=": 0x0D,
    "backspace": 0x0E,
    "tab": 0x0F,
    "q": 0x10,
    "w": 0x11,
    "e": 0x12,
    "r": 0x13,
    "t": 0x14,
    "y": 0x15,
    "u": 0x16,
    "i": 0x17,
    "o": 0x18,
    "p": 0x19,
    "[": 0x1A,
    "]": 0x1B,
    "enter": 0x1C,
    "ctrl": 0x1D,
    "a": 0x1E,
    "s": 0x1F,
    "d": 0x20,
    "f": 0x21,
    "g": 0x22,
    "h": 0x23,
    "j": 0x24,
    "k": 0x25,
    "l": 0x26,
    ";": 0x27,
    "'": 0x28,
    "`": 0x29,
    "shift": 0x2A,
    "\\": 0x2B,
    "z": 0x2C,
    "x": 0x2D,
    "c": 0x2E,
    "v": 0x2F,
    "b": 0x30,
    "n": 0x31,
    "m": 0x32,
    ",": 0x33,
    ".": 0x34,
    "/": 0x35,
    "space": 0x39,
    "f1": 0x3B,
    "f2": 0x3C,
    "f3": 0x3D,
    "f4": 0x3E,
    "f5": 0x3F,
    "f6": 0x40,
    "f7": 0x41,
    "f8": 0x42,
    "f9": 0x43,
    "f10": 0x44,
    "f11": 0x57,
    "f12": 0x58,
}

# Teclas que necesitan el flag de "extendida" para que Windows las entregue bien.
_EXTENDED = {"rctrl", "ralt", "up", "down", "left_arrow", "right_arrow"}

# Simbolos que en un layout US se escriben con Shift.
_SHIFTED_CHARS: dict[str, str] = {
    "!": "1",
    "@": "2",
    "#": "3",
    "$": "4",
    "%": "5",
    "^": "6",
    "&": "7",
    "*": "8",
    "(": "9",
    ")": "0",
    "_": "-",
    "+": "=",
    "{": "[",
    "}": "]",
    ":": ";",
    '"': "'",
    "~": "`",
    "|": "\\",
    "<": ",",
    ">": ".",
    "?": "/",
}


class InputError(RuntimeError):
    """Error al inyectar entrada en el sistema."""


# --- Estructuras de ctypes ---------------------------------------------------
# Se definen con tipos genericos de ctypes en vez de ctypes.wintypes porque ese
# modulo no importa en Linux, y el linter/CI corre en ubuntu.

_LONG = ctypes.c_long
_DWORD = ctypes.c_ulong
_WORD = ctypes.c_ushort
_ULONG_PTR = ctypes.c_size_t


class _MouseInput(ctypes.Structure):
    _fields_ = (
        ("dx", _LONG),
        ("dy", _LONG),
        ("mouseData", _DWORD),
        ("dwFlags", _DWORD),
        ("time", _DWORD),
        ("dwExtraInfo", _ULONG_PTR),
    )


class _KeyboardInput(ctypes.Structure):
    _fields_ = (
        ("wVk", _WORD),
        ("wScan", _WORD),
        ("dwFlags", _DWORD),
        ("time", _DWORD),
        ("dwExtraInfo", _ULONG_PTR),
    )


class _HardwareInput(ctypes.Structure):
    _fields_ = (
        ("uMsg", _DWORD),
        ("wParamL", _WORD),
        ("wParamH", _WORD),
    )


class _InputUnion(ctypes.Union):
    _fields_ = (("mi", _MouseInput), ("ki", _KeyboardInput), ("hi", _HardwareInput))


class _Input(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = (("type", _DWORD), ("u", _InputUnion))


class _Point(ctypes.Structure):
    _fields_ = (("x", _LONG), ("y", _LONG))


# Fuera de Windows queda en None para que el modulo se pueda importar (linter y
# tests corren en Linux); cualquier uso real lanza InputError.
_user32 = ctypes.WinDLL("user32", use_last_error=True) if _IS_WINDOWS else None


def _send(*inputs: _Input) -> None:
    """Envia una tanda de eventos al sistema."""
    if _user32 is None:
        raise InputError("HyMacro solo funciona en Windows")

    count = len(inputs)
    array = (_Input * count)(*inputs)
    sent = _user32.SendInput(count, ctypes.byref(array), ctypes.sizeof(_Input))
    if sent != count:
        err = ctypes.get_last_error()
        raise InputError(f"SendInput entrego {sent}/{count} eventos (error {err})")


def _key_event(scancode: int, *, up: bool, extended: bool = False) -> _Input:
    flags = _KEYEVENTF_SCANCODE
    if up:
        flags |= _KEYEVENTF_KEYUP
    if extended:
        flags |= _KEYEVENTF_EXTENDEDKEY
    return _Input(
        type=_INPUT_KEYBOARD,
        u=_InputUnion(ki=_KeyboardInput(wVk=0, wScan=scancode, dwFlags=flags, time=0, dwExtraInfo=0)),
    )


def _unicode_event(code_unit: int, *, up: bool) -> _Input:
    flags = _KEYEVENTF_UNICODE
    if up:
        flags |= _KEYEVENTF_KEYUP
    return _Input(
        type=_INPUT_KEYBOARD,
        u=_InputUnion(ki=_KeyboardInput(wVk=0, wScan=code_unit, dwFlags=flags, time=0, dwExtraInfo=0)),
    )


def _mouse_event(flag: int) -> _Input:
    return _Input(
        type=_INPUT_MOUSE,
        u=_InputUnion(mi=_MouseInput(dx=0, dy=0, mouseData=0, dwFlags=flag, time=0, dwExtraInfo=0)),
    )


def resolve_scancode(key: str) -> int:
    """Traduce un nombre de tecla a su scancode. Lanza ValueError si no existe."""
    normalized = key.strip().lower()
    if normalized not in _SCANCODES:
        raise ValueError(f"Tecla no soportada: {key!r}")
    return _SCANCODES[normalized]


def cursor_position() -> tuple[int, int]:
    """Devuelve la posicion actual del cursor en pixeles de pantalla."""
    if _user32 is None:
        return (0, 0)
    point = _Point()
    if not _user32.GetCursorPos(ctypes.byref(point)):
        return (0, 0)
    return (point.x, point.y)


def foreground_window_title() -> str:
    """Devuelve el titulo de la ventana en primer plano ('' si no se puede leer)."""
    if _user32 is None:
        return ""
    handle = _user32.GetForegroundWindow()
    if not handle:
        return ""
    length = _user32.GetWindowTextLengthW(handle)
    if length <= 0:
        return ""
    buffer = ctypes.create_unicode_buffer(length + 1)
    _user32.GetWindowTextW(handle, buffer, length + 1)
    return buffer.value


class InputBackend:
    """Inyecta teclado y raton, recordando que sigue presionado.

    Todos los metodos son seguros de llamar desde varios hilos: el watchdog
    puede pedir `release_all()` mientras el hilo del macro esta a mitad de una
    ruta.
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._held_keys: dict[str, int] = {}
        self._held_buttons: set[str] = set()

    # --- teclado ---

    def key_down(self, key: str) -> None:
        key = key.strip().lower()
        scancode = resolve_scancode(key)
        with self._lock:
            if key in self._held_keys:
                return
            _send(_key_event(scancode, up=False, extended=key in _EXTENDED))
            self._held_keys[key] = scancode

    def key_up(self, key: str) -> None:
        key = key.strip().lower()
        with self._lock:
            scancode = self._held_keys.pop(key, None)
            if scancode is None:
                return
            _send(_key_event(scancode, up=True, extended=key in _EXTENDED))

    def tap(self, key: str) -> None:
        key = key.strip().lower()
        scancode = resolve_scancode(key)
        extended = key in _EXTENDED
        with self._lock:
            _send(
                _key_event(scancode, up=False, extended=extended),
                _key_event(scancode, up=True, extended=extended),
            )

    # --- raton ---

    def mouse_down(self, button: str = "left") -> None:
        flags = _MOUSE_FLAGS.get(button)
        if flags is None:
            raise ValueError(f"Boton de raton no soportado: {button!r}")
        with self._lock:
            if button in self._held_buttons:
                return
            _send(_mouse_event(flags[0]))
            self._held_buttons.add(button)

    def mouse_up(self, button: str = "left") -> None:
        flags = _MOUSE_FLAGS.get(button)
        if flags is None:
            return
        with self._lock:
            if button not in self._held_buttons:
                return
            _send(_mouse_event(flags[1]))
            self._held_buttons.discard(button)

    # --- texto ---

    def type_text(self, text: str, *, mode: str = "unicode") -> None:
        """Escribe texto caracter a caracter.

        `unicode` genera mensajes WM_CHAR (funciona en el chat de Minecraft y
        con cualquier layout de teclado). `scancode` simula pulsaciones fisicas
        de un layout US, util si algun mod bloquea la entrada unicode.
        """
        if mode == "scancode":
            self._type_text_scancode(text)
            return
        events: list[_Input] = []
        for code_unit in _utf16_units(text):
            events.append(_unicode_event(code_unit, up=False))
            events.append(_unicode_event(code_unit, up=True))
        if events:
            with self._lock:
                _send(*events)

    def _type_text_scancode(self, text: str) -> None:
        with self._lock:
            for char in text:
                needs_shift = char.isupper() or char in _SHIFTED_CHARS
                base = _SHIFTED_CHARS.get(char, char.lower())
                key = "space" if base == " " else base
                try:
                    scancode = resolve_scancode(key)
                except ValueError:
                    logger.warning("Caracter %r sin scancode; se envia como unicode", char)
                    unit = _utf16_units(char)
                    _send(
                        *[e for u in unit for e in (_unicode_event(u, up=False), _unicode_event(u, up=True))]
                    )
                    continue
                shift = _SCANCODES["shift"]
                events: list[_Input] = []
                if needs_shift:
                    events.append(_key_event(shift, up=False))
                events.append(_key_event(scancode, up=False))
                events.append(_key_event(scancode, up=True))
                if needs_shift:
                    events.append(_key_event(shift, up=True))
                _send(*events)

    # --- limpieza ---

    def release_all(self) -> None:
        """Suelta todas las teclas y botones pendientes. Nunca lanza excepcion."""
        with self._lock:
            for key in list(self._held_keys):
                try:
                    self.key_up(key)
                except Exception:
                    logger.exception("No se pudo soltar la tecla %r", key)
                    self._held_keys.pop(key, None)
            for button in list(self._held_buttons):
                try:
                    self.mouse_up(button)
                except Exception:
                    logger.exception("No se pudo soltar el boton %r", button)
                    self._held_buttons.discard(button)

    @property
    def has_pending_input(self) -> bool:
        with self._lock:
            return bool(self._held_keys or self._held_buttons)


def _utf16_units(text: str) -> list[int]:
    """Descompone el texto en unidades UTF-16 (maneja pares subrogados)."""
    raw = text.encode("utf-16-le")
    return [int.from_bytes(raw[i : i + 2], "little") for i in range(0, len(raw), 2)]
