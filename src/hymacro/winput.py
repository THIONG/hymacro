"""Windows input backend built on SendInput.

Scancodes are injected rather than virtual keys because Minecraft runs on GLFW,
which reads the scancode from the message. The backend also tracks everything it
is holding down, so a stop can release it all at once instead of leaving the
character walking into a wall.
"""

from __future__ import annotations

import ctypes
import logging
import sys
import threading

logger = logging.getLogger(__name__)

_IS_WINDOWS = sys.platform == "win32"


_INPUT_MOUSE = 0
_INPUT_KEYBOARD = 1

_KEYEVENTF_EXTENDEDKEY = 0x0001
_KEYEVENTF_KEYUP = 0x0002
_KEYEVENTF_UNICODE = 0x0004
_KEYEVENTF_SCANCODE = 0x0008

_MOUSE_FLAGS: dict[str, tuple[int, int]] = {
    "left": (0x0002, 0x0004),
    "right": (0x0008, 0x0010),
    "middle": (0x0020, 0x0040),
}


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


_EXTENDED = {"rctrl", "ralt", "up", "down", "left_arrow", "right_arrow"}


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
    """Raised when input could not be injected."""


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


_user32 = ctypes.WinDLL("user32", use_last_error=True) if _IS_WINDOWS else None


def _send(*inputs: _Input) -> None:
    """Send a batch of input events."""
    if _user32 is None:
        raise InputError("Input injection is only available on Windows")

    count = len(inputs)
    array = (_Input * count)(*inputs)
    sent = _user32.SendInput(count, ctypes.byref(array), ctypes.sizeof(_Input))
    if sent != count:
        err = ctypes.get_last_error()
        raise InputError(f"SendInput delivered {sent}/{count} events (error {err})")


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
    """Translate a key name into its scancode."""
    normalized = key.strip().lower()
    if normalized not in _SCANCODES:
        raise ValueError(f"unsupported key: {key!r}")
    return _SCANCODES[normalized]


_VK_CODES: dict[str, int] = {
    **{f"f{n}": 0x6F + n for n in range(1, 13)},
    **{chr(c): c for c in range(ord("A"), ord("Z") + 1)},
    **{chr(c).lower(): c for c in range(ord("A"), ord("Z") + 1)},
    **{str(n): ord(str(n)) for n in range(10)},
    "space": 0x20,
    "enter": 0x0D,
    "escape": 0x1B,
    "tab": 0x09,
    "backspace": 0x08,
    "insert": 0x2D,
    "delete": 0x2E,
    "home": 0x24,
    "end": 0x23,
    "pageup": 0x21,
    "pagedown": 0x22,
    "ctrl": 0x11,
    "shift": 0x10,
    "alt": 0x12,
}

if _user32 is not None:
    _user32.GetAsyncKeyState.restype = ctypes.c_short


def is_key_held(key: str) -> bool:
    """True when the key is held right now, according to Windows itself.

    This bypasses the `keyboard` hook, so it keeps working even if that hook
    stops delivering events.
    """
    if _user32 is None:
        return False
    vk = _VK_CODES.get(key.strip().lower())
    if vk is None:
        return False
    return bool(_user32.GetAsyncKeyState(vk) & 0x8000)


def cursor_position() -> tuple[int, int]:
    """Current cursor position, in screen pixels."""
    if _user32 is None:
        return (0, 0)
    point = _Point()
    if not _user32.GetCursorPos(ctypes.byref(point)):
        return (0, 0)
    return (point.x, point.y)


def foreground_window_title() -> str:
    """Title of the foreground window, or an empty string."""
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
    """Injects keyboard and mouse input, remembering what is held down.

    Every method is safe to call from several threads: the watchdog may call
    `release_all()` while the macro thread is mid-route.
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._held_keys: dict[str, int] = {}
        self._held_buttons: set[str] = set()

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

    def mouse_down(self, button: str = "left") -> None:
        flags = _MOUSE_FLAGS.get(button)
        if flags is None:
            raise ValueError(f"unsupported mouse button: {button!r}")
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

    def type_text(self, text: str, *, mode: str = "unicode") -> None:
        """Type text one character at a time.

        `unicode` produces WM_CHAR messages, which the Minecraft chat accepts on
        any keyboard layout. `scancode` simulates physical US layout presses.
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
                    logger.warning("No scancode for %r; sending it as unicode", char)
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

    def release_all(self) -> None:
        """Release every held key and button. Never raises."""
        with self._lock:
            for key in list(self._held_keys):
                try:
                    self.key_up(key)
                except Exception:
                    logger.exception("Could not release key %r", key)
                    self._held_keys.pop(key, None)
            for button in list(self._held_buttons):
                try:
                    self.mouse_up(button)
                except Exception:
                    logger.exception("Could not release button %r", button)
                    self._held_buttons.discard(button)

    @property
    def has_pending_input(self) -> bool:
        with self._lock:
            return bool(self._held_keys or self._held_buttons)


def _utf16_units(text: str) -> list[int]:
    """Split text into UTF-16 code units, handling surrogate pairs."""
    raw = text.encode("utf-16-le")
    return [int.from_bytes(raw[i : i + 2], "little") for i in range(0, len(raw), 2)]
