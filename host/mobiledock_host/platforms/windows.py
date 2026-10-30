"""Windows backend. Uses only ctypes and built-in Windows tools.

Optional extras:
  ffmpeg on PATH          screen recording
  winrt-Windows.Media.Control   now playing, album art, seek, shuffle and repeat
"""

from __future__ import annotations

import asyncio
import ctypes
import hashlib
import json
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes
from pathlib import Path

from .base import KEYS, MEDIA_ACTIONS, MODIFIERS, MOUSE_BUTTONS, Platform, PlatformError
from .png import encode_png, write_png

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


class SIZE(ctypes.Structure):
    _fields_ = [("cx", wintypes.LONG), ("cy", wintypes.LONG)]


class BITMAP(ctypes.Structure):
    _fields_ = [
        ("bmType", wintypes.LONG), ("bmWidth", wintypes.LONG), ("bmHeight", wintypes.LONG),
        ("bmWidthBytes", wintypes.LONG), ("bmPlanes", wintypes.WORD), ("bmBitsPixel", wintypes.WORD),
        ("bmBits", ctypes.c_void_p),
    ]


class GUID(ctypes.Structure):
    _fields_ = [("data", ctypes.c_ubyte * 16)]

    @classmethod
    def from_uuid(cls, text: str) -> "GUID":
        return cls.from_buffer_copy(uuid.UUID(text).bytes_le)


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
        # Explicit signatures. Without them ctypes passes handles as 32-bit ints on 64-bit Windows.
        u, g, w = self.user32, self.gdi32, wintypes
        u.GetDC.argtypes, u.GetDC.restype = (w.HWND,), w.HDC
        u.ReleaseDC.argtypes = (w.HWND, w.HDC)
        g.CreateCompatibleDC.argtypes, g.CreateCompatibleDC.restype = (w.HDC,), w.HDC
        g.CreateCompatibleBitmap.argtypes = (w.HDC, ctypes.c_int, ctypes.c_int)
        g.CreateCompatibleBitmap.restype = w.HBITMAP
        g.SelectObject.argtypes, g.SelectObject.restype = (w.HDC, w.HGDIOBJ), w.HGDIOBJ
        g.BitBlt.argtypes = (w.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HDC,
                             ctypes.c_int, ctypes.c_int, w.DWORD)
        g.GetDIBits.argtypes = (w.HDC, w.HBITMAP, w.UINT, w.UINT, ctypes.c_void_p, ctypes.c_void_p, w.UINT)
        g.DeleteObject.argtypes = (w.HGDIOBJ,)
        g.DeleteDC.argtypes = (w.HDC,)
        g.GetObjectW.argtypes = (w.HANDLE, ctypes.c_int, ctypes.c_void_p)
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError):
            self.user32.SetProcessDPIAware()
        self._apps: list[dict] = []
        self._apps_time = 0.0
        self._icons: dict[str, bytes | None] = {}
        self._icon_thread = ThreadPoolExecutor(max_workers=1, thread_name_prefix="icons")

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
    # Uses the Windows media session API (the same one the volume flyout uses).
    # It works with Spotify, browsers and most players, with no Spotify account.

    def _media_run(self, fn):
        """Runs fn(session) in a fresh event loop. Returns None when the API or a session is missing."""
        try:
            from winrt.windows.media.control import (  # type: ignore
                GlobalSystemMediaTransportControlsSessionManager as Manager,
            )
        except ImportError:
            return None

        async def go():
            manager = await Manager.request_async()
            session = None
            for s in manager.get_sessions():
                if "spotify" in (s.source_app_user_model_id or "").lower():
                    session = s
                    break
            session = session or manager.get_current_session()
            return None if session is None else await fn(session)

        try:
            return asyncio.run(asyncio.wait_for(go(), 4))
        except Exception:  # noqa: BLE001 - optional feature, never fatal
            return None

    def media(self, action: str) -> None:
        if action not in MEDIA_ACTIONS:
            raise PlatformError("Unknown media action")
        if action in ("play_pause", "next", "prev"):
            async def control(s):
                method = {"play_pause": s.try_toggle_play_pause_async, "next": s.try_skip_next_async,
                          "prev": s.try_skip_previous_async}[action]
                return await method()

            if self._media_run(control):
                time.sleep(0.3)  # let the player update its state before we report it
                return
        vk = MEDIA_VK[action]
        self._send(_key_input(vk, flags=KEYEVENTF_EXTENDEDKEY),
                   _key_input(vk, flags=KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP))

    def media_status(self) -> dict:
        async def query(s):
            props = await s.try_get_media_properties_async()
            info = s.get_playback_info()
            playing = int(info.playback_status) == 4
            status = {
                "title": props.title or "", "artist": props.artist or "", "album": props.album_title or "",
                "playing": playing, "app": (s.source_app_user_model_id or "").split("!")[0],
            }
            try:
                tl = s.get_timeline_properties()
                position = tl.position.total_seconds()
                duration = (tl.end_time - tl.start_time).total_seconds()
                if playing and tl.last_updated_time:
                    from datetime import datetime, timezone

                    position += (datetime.now(timezone.utc) - tl.last_updated_time).total_seconds()
                if duration > 0:
                    status["duration"] = duration
                    status["position"] = max(0.0, min(position, duration))
            except Exception:  # noqa: BLE001
                pass
            if info.is_shuffle_active is not None:
                status["shuffle"] = bool(info.is_shuffle_active)
            if info.auto_repeat_mode is not None:
                status["repeat"] = {0: "none", 1: "track", 2: "list"}.get(int(info.auto_repeat_mode), "none")
            if props.thumbnail is not None:
                status["art_id"] = hashlib.sha1(
                    f"{status['title']}|{status['artist']}|{status['album']}".encode()).hexdigest()[:16]
            return status

        return self._media_run(query) or {}

    def media_art(self) -> bytes | None:
        async def art(s):
            from winrt.windows.storage.streams import Buffer, DataReader, InputStreamOptions  # type: ignore

            props = await s.try_get_media_properties_async()
            if props.thumbnail is None:
                return None
            stream = await props.thumbnail.open_read_async()
            size = int(stream.size)
            if not 0 < size <= 2_000_000:
                return None
            result = await stream.read_async(Buffer(size), size, InputStreamOptions.READ_AHEAD)
            try:
                return bytes(memoryview(result))
            except TypeError:
                data = bytearray(result.length)
                DataReader.from_buffer(result).read_bytes(data)
                return bytes(data)

        return self._media_run(art)

    def media_seek(self, seconds: float) -> None:
        async def seek(s):
            return await s.try_change_playback_position_async(int(seconds * 10_000_000))

        if not self._media_run(seek):
            raise PlatformError("This player does not allow seeking")

    def media_shuffle(self) -> None:
        async def shuffle(s):
            current = s.get_playback_info().is_shuffle_active
            return await s.try_change_shuffle_active_async(not bool(current))

        if not self._media_run(shuffle):
            raise PlatformError("This player does not allow shuffle")

    def media_repeat(self) -> None:
        async def repeat(s):
            from winrt.windows.media import MediaPlaybackAutoRepeatMode as Mode  # type: ignore

            current = int(s.get_playback_info().auto_repeat_mode or 0)
            nxt = {0: 2, 2: 1, 1: 0}[current]  # off, repeat all, repeat one
            return await s.try_change_auto_repeat_mode_async(Mode(nxt))

        if not self._media_run(repeat):
            raise PlatformError("This player does not allow repeat")

    # Icons ----------------------------------------------------------------

    def app_icon(self, app_id: str) -> bytes | None:
        """Renders the Start menu icon through the shell, the same way Explorer does.

        Works for desktop and Store apps. Returns a 64x64 PNG or None.
        """
        if app_id in self._icons:
            return self._icons[app_id]
        # The shell needs a single-threaded COM apartment, while the media API needs the
        # multithreaded one. Icons run on their own thread so the two never share a thread.
        png = self._icon_thread.submit(self._render_icon, app_id).result(timeout=15)
        self._icons[app_id] = png
        return png

    def _render_icon(self, app_id: str) -> bytes | None:
        png = None
        try:
            if not getattr(self, "_icon_com_ready", False):
                ctypes.WinDLL("ole32").CoInitializeEx(None, 2)  # COINIT_APARTMENTTHREADED
                self._icon_com_ready = True
            shell32 = ctypes.WinDLL("shell32")
            shell32.SHCreateItemFromParsingName.argtypes = (
                wintypes.LPCWSTR, ctypes.c_void_p, ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p))
            shell32.SHCreateItemFromParsingName.restype = ctypes.HRESULT
            item = ctypes.c_void_p()
            iid = GUID.from_uuid("bcc18b79-ba16-442f-80c4-8a59c30c463b")  # IShellItemImageFactory
            shell32.SHCreateItemFromParsingName("shell:AppsFolder\\" + app_id, None, ctypes.byref(iid),
                                                ctypes.byref(item))
            vtable = ctypes.cast(item, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
            get_image = ctypes.WINFUNCTYPE(ctypes.HRESULT, ctypes.c_void_p, SIZE, ctypes.c_int,
                                           ctypes.POINTER(wintypes.HBITMAP))(vtable[3])
            release = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtable[2])
            hbm = wintypes.HBITMAP()
            try:
                get_image(item, SIZE(64, 64), 0x1 | 0x4, ctypes.byref(hbm))  # BIGGERSIZEOK | ICONONLY
            finally:
                release(item)
            try:
                png = self._hbitmap_png(hbm)
            finally:
                self.gdi32.DeleteObject(hbm)
        except (OSError, ValueError):
            png = None
        return png

    def _hbitmap_png(self, hbm) -> bytes | None:
        bm = BITMAP()
        if not self.gdi32.GetObjectW(hbm, ctypes.sizeof(BITMAP), ctypes.byref(bm)):
            return None
        width, height = bm.bmWidth, abs(bm.bmHeight)
        if not (0 < width <= 256 and 0 < height <= 256):
            return None
        info = BITMAPINFOHEADER(biSize=ctypes.sizeof(BITMAPINFOHEADER), biWidth=width, biHeight=-height,
                                biPlanes=1, biBitCount=32)
        buf = ctypes.create_string_buffer(width * height * 4)
        dc = self.user32.GetDC(None)
        try:
            if not self.gdi32.GetDIBits(dc, hbm, 0, height, buf, ctypes.byref(info), 0):
                return None
        finally:
            self.user32.ReleaseDC(None, dc)
        raw = buf.raw
        rgba = bytearray(len(raw))
        rgba[0::4], rgba[1::4], rgba[2::4], rgba[3::4] = raw[2::4], raw[1::4], raw[0::4], raw[3::4]
        if not any(rgba[3::4]):
            rgba[3::4] = b"\xff" * (width * height)  # bitmap had no alpha channel
        return encode_png(width, height, bytes(rgba), alpha=True)

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
