"""Background input: messages posted straight to the game window.

The normal backend uses SendInput, which feeds the global input queue and
therefore always reaches whatever window is in the foreground. Posting messages
to a specific window instead lets the macro run while the computer is used for
something else.

Minecraft runs on GLFW, which does read WM_KEYDOWN and WM_CHAR from its message
queue, so keys and chat usually arrive. The mouse is less reliable: the client
reads movement through raw input and ignores some events while unfocused. Treat
this mode as something to try rather than something guaranteed.
"""

from __future__ import annotations

import ctypes
import logging
import threading

from .winput import _VK_CODES, InputError, resolve_scancode

logger = logging.getLogger(__name__)

_WM_KEYDOWN = 0x0100
_WM_KEYUP = 0x0101
_WM_CHAR = 0x0102

_MOUSE_MESSAGES = {
    "left": (0x0201, 0x0202, 0x0001),
    "right": (0x0204, 0x0205, 0x0002),
    "middle": (0x0207, 0x0208, 0x0010),
}

_EXTENDED = {"rctrl", "ralt", "up", "down", "left_arrow", "right_arrow"}

_user32 = ctypes.WinDLL("user32", use_last_error=True) if hasattr(ctypes, "WinDLL") else None

if _user32 is not None:
    _WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    _user32.EnumWindows.argtypes = [_WNDENUMPROC, ctypes.c_void_p]
    _user32.IsWindowVisible.argtypes = [ctypes.c_void_p]
    _user32.GetWindowTextLengthW.argtypes = [ctypes.c_void_p]
    _user32.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
    _user32.IsWindow.argtypes = [ctypes.c_void_p]
    _user32.PostMessageW.argtypes = [
        ctypes.c_void_p,
        ctypes.c_uint,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]


class _Rect(ctypes.Structure):
    _fields_ = (
        ("left", ctypes.c_long),
        ("top", ctypes.c_long),
        ("right", ctypes.c_long),
        ("bottom", ctypes.c_long),
    )


def find_window(title_contains: str) -> int | None:
    """Handle of the first visible window whose title contains the text."""
    if _user32 is None or not title_contains:
        return None

    needle = title_contains.casefold()
    found: list[int] = []

    def visit(handle: int, _lparam: int) -> bool:
        if not _user32.IsWindowVisible(handle):
            return True
        length = _user32.GetWindowTextLengthW(handle)
        if length <= 0:
            return True
        buffer = ctypes.create_unicode_buffer(length + 1)
        _user32.GetWindowTextW(handle, buffer, length + 1)
        if needle in buffer.value.casefold():
            found.append(handle)
            return False
        return True

    _user32.EnumWindows(_WNDENUMPROC(visit), None)
    return found[0] if found else None


def window_title(handle: int) -> str:
    if _user32 is None or not handle:
        return ""
    length = _user32.GetWindowTextLengthW(handle)
    if length <= 0:
        return ""
    buffer = ctypes.create_unicode_buffer(length + 1)
    _user32.GetWindowTextW(handle, buffer, length + 1)
    return buffer.value


def _key_lparam(scancode: int, *, up: bool, extended: bool) -> int:
    """Build the lParam Windows expects for a keyboard message."""
    value = 1
    value |= (scancode & 0xFF) << 16
    if extended:
        value |= 1 << 24
    if up:
        value |= 1 << 30
        value |= 1 << 31
    return value


class BackgroundBackend:
    """Same interface as InputBackend, but aimed at one window."""

    def __init__(self, title_contains: str) -> None:
        self._title = title_contains
        self._lock = threading.RLock()
        self._held_keys: dict[str, int] = {}
        self._held_buttons: set[str] = set()
        self._handle: int | None = None

    @property
    def target_title(self) -> str:
        handle = self._handle
        return window_title(handle) if handle else ""

    def ensure_target(self) -> str:
        """Resolve the target window now, so failures surface before a route."""
        self._window()
        return self.target_title

    def _window(self) -> int:
        if _user32 is None:
            raise InputError("Background input is only available on Windows")
        if self._handle and _user32.IsWindow(self._handle):
            return self._handle
        handle = find_window(self._title)
        if handle is None:
            raise InputError(f"No visible window matching {self._title!r}")
        self._handle = handle
        return handle

    def _post(self, message: int, wparam: int, lparam: int) -> None:
        handle = self._window()
        assert _user32 is not None
        if not _user32.PostMessageW(
            ctypes.c_void_p(handle),
            message,
            ctypes.c_void_p(wparam),
            ctypes.c_void_p(lparam),
        ):
            raise InputError(f"PostMessage failed (error {ctypes.get_last_error()})")

    def _centre(self) -> int:
        handle = self._window()
        assert _user32 is not None
        rect = _Rect()
        if not _user32.GetClientRect(ctypes.c_void_p(handle), ctypes.byref(rect)):
            return 0
        x = int(rect.right - rect.left) // 2
        y = int(rect.bottom - rect.top) // 2
        return (y << 16) | (x & 0xFFFF)

    def key_down(self, key: str) -> None:
        key = key.strip().lower()
        scancode = resolve_scancode(key)
        vk = _VK_CODES.get(key)
        if vk is None:
            raise ValueError(f"unsupported key: {key!r}")
        with self._lock:
            if key in self._held_keys:
                return
            self._post(_WM_KEYDOWN, vk, _key_lparam(scancode, up=False, extended=key in _EXTENDED))
            self._held_keys[key] = scancode

    def key_up(self, key: str) -> None:
        key = key.strip().lower()
        with self._lock:
            scancode = self._held_keys.pop(key, None)
            if scancode is None:
                return
            vk = _VK_CODES.get(key)
            if vk is None:
                return
            self._post(_WM_KEYUP, vk, _key_lparam(scancode, up=True, extended=key in _EXTENDED))

    def tap(self, key: str) -> None:
        self.key_down(key)
        self.key_up(key)

    def mouse_down(self, button: str = "left") -> None:
        messages = _MOUSE_MESSAGES.get(button)
        if messages is None:
            raise ValueError(f"unsupported mouse button: {button!r}")
        with self._lock:
            if button in self._held_buttons:
                return
            self._post(messages[0], messages[2], self._centre())
            self._held_buttons.add(button)

    def mouse_up(self, button: str = "left") -> None:
        messages = _MOUSE_MESSAGES.get(button)
        if messages is None:
            return
        with self._lock:
            if button not in self._held_buttons:
                return
            self._post(messages[1], 0, self._centre())
            self._held_buttons.discard(button)

    def type_text(self, text: str, *, mode: str = "unicode") -> None:
        raw = text.encode("utf-16-le")
        units = [int.from_bytes(raw[i : i + 2], "little") for i in range(0, len(raw), 2)]
        with self._lock:
            for unit in units:
                self._post(_WM_CHAR, unit, 1)

    def release_all(self) -> None:
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
