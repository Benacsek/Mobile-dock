"""Windows backend. Uses only ctypes and built-in Windows tools.

Optional extras:
  ffmpeg on PATH          screen recording
  winrt-Windows.Media.Control   now-playing title on the phone
"""

from __future__ import annotations

import asyncio
import ctypes
import json
import subprocess
import time
from ctypes import wintypes
from pathlib import Path

from .base import KEYS, MEDIA_ACTIONS, MODIFIERS, MOUSE_BUTTONS, Platform, PlatformError
from .png import write_png

CREATE_NO_WINDOW = 0x08000000

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x1, 0x2, 0x4
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x1000
BUTTON_FLAGS = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010), "middle": (0x0020, 0x0040)}

VK = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
    "delete": 0x2E, "insert": 0x2D, "up": 0x26, "down": 0x28, "left": 0x25,
    "right": 0x27, "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22,
    "ctrl": 0x11, "alt": 0x12, "shift": 0x10, "meta": 0x5B,
    **{f"f{i}": 0x6F + i for i in range(1, 13)},
    **{c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz0123456789"},
}
EXTENDED = {0x2E, 0x2D, 0x26, 0x28, 0x25, 0x27, 0x24, 0x23, 0x21, 0x22, 0x5B}
MEDIA_VK = {"play_pause": 0xB3, "next": 0xB0, "prev": 0xB1, "vol_up": 0xAF, "vol_down": 0xAE, "mute": 0xAD}


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
        ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
        ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
        ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD),
    ]


def _key_input(vk: int = 0, scan: int = 0, flags: int = 0) -> INPUT:
    if vk in EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.u.ki = KEYBDINPUT(wVk=vk, wScan=scan, dwFlags=flags)
    return inp


def _mouse_input(dx: int = 0, dy: int = 0, data: int = 0, flags: int = 0) -> INPUT:
    inp = INPUT(type=INPUT_MOUSE)
    inp.u.mi = MOUSEINPUT(dx=dx, dy=dy, mouseData=ctypes.c_uint32(data).value, dwFlags=flags)
    return inp


class WindowsPlatform(Platform):
    name = "windows"

    def __init__(self):
        super().__init__()
        self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        self.gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
        self.user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        self.user32.GetDC.restype = wintypes.HDC
        self.gdi32.CreateCompatibleDC.restype = wintypes.HDC
        self.gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
        self.gdi32.SelectObject.restype = wintypes.HGDIOBJ
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError):
            self.user32.SetProcessDPIAware()
        self._apps: list[dict] = []
        self._apps_time = 0.0

    def _send(self, *inputs: INPUT) -> None:
        arr = (INPUT * len(inputs))(*inputs)
        if self.user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT)) != len(inputs):
            raise PlatformError("Windows blocked the input. Is an admin window focused?")

    # Apps -----------------------------------------------------------------

    def list_apps(self) -> list[dict]:
        if self._apps and time.time() - self._apps_time < 60:
            return self._apps
        out = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
             "Get-StartApps | Select-Object Name, AppID | ConvertTo-Json -Compress"],
            capture_output=True, text=True, encoding="utf-8", timeout=30, creationflags=CREATE_NO_WINDOW,
        ).stdout
        items = json.loads(out or "[]")
        if isinstance(items, dict):
            items = [items]
        apps = {i["AppID"]: i["Name"] for i in items if i.get("AppID") and i.get("Name")}
        self._apps = sorted(({"id": k, "name": v} for k, v in apps.items()), key=lambda a: a["name"].lower())
        self._apps_time = time.time()
        return self._apps

    def launch(self, app_id: str) -> None:
        # app_id was validated against list_apps() by the server.
        subprocess.Popen(["explorer.exe", "shell:AppsFolder\\" + app_id], creationflags=CREATE_NO_WINDOW)

    # Media ----------------------------------------------------------------

    def media(self, action: str) -> None:
        if action not in MEDIA_ACTIONS:
            raise PlatformError("Unknown media action")
        vk = MEDIA_VK[action]
        self._send(_key_input(vk, flags=KEYEVENTF_EXTENDEDKEY),
                   _key_input(vk, flags=KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP))

    def media_status(self) -> dict:
        try:
            from winrt.windows.media.control import (  # type: ignore
                GlobalSystemMediaTransportControlsSessionManager as Manager,
            )
        except ImportError:
            return {}

        async def query():
            manager = await Manager.request_async()
            session = manager.get_current_session()
            if session is None:
                return {}
            props = await session.try_get_media_properties_async()
            playing = session.get_playback_info().playback_status == 4
            return {"title": props.title or "", "artist": props.artist or "", "playing": playing}

        try:
            return asyncio.run(asyncio.wait_for(query(), 3))
        except Exception:  # noqa: BLE001 - optional feature, never fatal
            return {}

    # Capture --------------------------------------------------------------

    def screenshot(self, path: Path) -> None:
        sm = self.user32.GetSystemMetrics
        left, top, width, height = sm(76), sm(77), sm(78), sm(79)
        screen = self.user32.GetDC(None)
        mem = self.gdi32.CreateCompatibleDC(screen)
        bmp = self.gdi32.CreateCompatibleBitmap(screen, width, height)
        old = self.gdi32.SelectObject(mem, bmp)
        try:
            if not self.gdi32.BitBlt(mem, 0, 0, width, height, screen, left, top, 0x00CC0020 | 0x40000000):
                raise PlatformError("Screen capture failed")
            info = BITMAPINFOHEADER(biSize=ctypes.sizeof(BITMAPINFOHEADER), biWidth=width,
                                    biHeight=-height, biPlanes=1, biBitCount=32)
            buf = ctypes.create_string_buffer(width * height * 4)
            self.gdi32.GetDIBits(mem, bmp, 0, height, buf, ctypes.byref(info), 0)
        finally:
            self.gdi32.SelectObject(mem, old)
            self.gdi32.DeleteObject(bmp)
            self.gdi32.DeleteDC(mem)
            self.user32.ReleaseDC(None, screen)
        raw = buf.raw
        rgb = bytearray(width * height * 3)
        rgb[0::3], rgb[1::3], rgb[2::3] = raw[2::4], raw[1::4], raw[0::4]
        write_png(path, width, height, bytes(rgb))

    def recording_command(self, path: Path) -> list[str]:
        return ["ffmpeg", "-loglevel", "error", "-f", "gdigrab", "-framerate", "30", "-i", "desktop",
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", str(path)]

    def start_recording(self, path: Path) -> None:
        try:
            super().start_recording(path)
        except PlatformError as exc:
            if "not installed" in str(exc):
                raise PlatformError("Install ffmpeg to record: winget install ffmpeg") from exc
            raise

    def signal_recorder_stop(self, proc: subprocess.Popen) -> None:
        try:
            proc.communicate(b"q", timeout=15)
        except (subprocess.TimeoutExpired, OSError):
            proc.terminate()

    # Input ----------------------------------------------------------------

    def type_text(self, text: str) -> None:
        events = []
        for ch in text:
            if ch == "\n":
                events += [_key_input(VK["enter"]), _key_input(VK["enter"], flags=KEYEVENTF_KEYUP)]
                continue
            data = ch.encode("utf-16-le")
            for i in range(0, len(data), 2):
                unit = int.from_bytes(data[i : i + 2], "little")
                events += [_key_input(scan=unit, flags=KEYEVENTF_UNICODE),
                           _key_input(scan=unit, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)]
        if events:
            self._send(*events)

    def key(self, key: str, mods: list[str]) -> None:
        if key not in KEYS or any(m not in MODIFIERS for m in mods):
            raise PlatformError("Unknown key")
        down = [_key_input(VK[m]) for m in mods] + [_key_input(VK[key])]
        up = [_key_input(VK[key], flags=KEYEVENTF_KEYUP)] + [
            _key_input(VK[m], flags=KEYEVENTF_KEYUP) for m in reversed(mods)
        ]
        self._send(*down, *up)

    def mouse_move(self, dx: int, dy: int) -> None:
        self._send(_mouse_input(dx, dy, flags=MOUSEEVENTF_MOVE))

    def mouse_button(self, button: str, action: str) -> None:
        if button not in MOUSE_BUTTONS:
            raise PlatformError("Unknown button")
        down, up = BUTTON_FLAGS[button]
        if action == "down":
            self._send(_mouse_input(flags=down))
        elif action == "up":
            self._send(_mouse_input(flags=up))
        else:
            self._send(_mouse_input(flags=down), _mouse_input(flags=up))

    def scroll(self, dx: int, dy: int) -> None:
        events = []
        if dy:
            events.append(_mouse_input(data=dy * 40, flags=MOUSEEVENTF_WHEEL))
        if dx:
            events.append(_mouse_input(data=dx * 40, flags=MOUSEEVENTF_HWHEEL))
        if events:
            self._send(*events)
