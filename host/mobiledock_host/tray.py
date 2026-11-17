"""Windows tray icon and single copy, through the Windows API. No extra packages.

The tray runs its own thread with a hidden window that receives the icon's clicks. It never touches Tk.
It only calls the callbacks it was given, and the app hands those to the Tk thread.

A second start of the app finds this window by its class name and asks it to show the main window
(or a pairing PIN), then exits. A named mutex makes the check safe when two copies start at once.
"""

from __future__ import annotations

import logging
import sys
import threading
import time
from pathlib import Path

log = logging.getLogger(__name__)

CLASS_NAME = "MobileDockTray"
MUTEX_NAME = "Local\\MobileDockHost"
WM_APP = 0x8000
MSG_ICON = WM_APP + 1   # clicks on the tray icon
MSG_SHOW = WM_APP + 2   # a second start asks to show the window
MSG_PAIR = WM_APP + 3   # a second start with --pair asks for a PIN
MSG_STOP = WM_APP + 9
MENU_OPEN, MENU_PAIR, MENU_QUIT = 1, 2, 3
ERROR_ALREADY_EXISTS = 183

if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    shell32 = ctypes.WinDLL("shell32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    LRESULT = ctypes.c_ssize_t
    WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)

    class WNDCLASSW(ctypes.Structure):
        _fields_ = [("style", wintypes.UINT), ("lpfnWndProc", WNDPROC), ("cbClsExtra", ctypes.c_int),
                    ("cbWndExtra", ctypes.c_int), ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON),
                    ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HBRUSH),
                    ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR)]

    class GUID(ctypes.Structure):
        _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD), ("Data3", wintypes.WORD),
                    ("Data4", ctypes.c_ubyte * 8)]

    class NOTIFYICONDATAW(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("hWnd", wintypes.HWND), ("uID", wintypes.UINT),
                    ("uFlags", wintypes.UINT), ("uCallbackMessage", wintypes.UINT), ("hIcon", wintypes.HICON),
                    ("szTip", wintypes.WCHAR * 128), ("dwState", wintypes.DWORD), ("dwStateMask", wintypes.DWORD),
                    ("szInfo", wintypes.WCHAR * 256), ("uVersion", wintypes.UINT),
                    ("szInfoTitle", wintypes.WCHAR * 64), ("dwInfoFlags", wintypes.DWORD), ("guidItem", GUID),
                    ("hBalloonIcon", wintypes.HICON)]

    class POINT(ctypes.Structure):
        _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

    def _sig(fn, res, *args):
        fn.restype, fn.argtypes = res, list(args)

    H, U, P = wintypes.HANDLE, wintypes.UINT, ctypes.c_void_p
    _sig(user32.DefWindowProcW, LRESULT, wintypes.HWND, U, wintypes.WPARAM, wintypes.LPARAM)
    _sig(user32.RegisterClassW, wintypes.ATOM, ctypes.POINTER(WNDCLASSW))
    _sig(user32.CreateWindowExW, wintypes.HWND, wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
         ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, P)
    _sig(user32.DestroyWindow, wintypes.BOOL, wintypes.HWND)
    _sig(user32.GetMessageW, wintypes.BOOL, ctypes.POINTER(wintypes.MSG), wintypes.HWND, U, U)
    _sig(user32.TranslateMessage, wintypes.BOOL, ctypes.POINTER(wintypes.MSG))
    _sig(user32.DispatchMessageW, LRESULT, ctypes.POINTER(wintypes.MSG))
    _sig(user32.PostMessageW, wintypes.BOOL, wintypes.HWND, U, wintypes.WPARAM, wintypes.LPARAM)
    _sig(user32.PostQuitMessage, None, ctypes.c_int)
    _sig(user32.FindWindowW, wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR)
    _sig(user32.RegisterWindowMessageW, U, wintypes.LPCWSTR)
    _sig(user32.CreatePopupMenu, wintypes.HMENU)
    _sig(user32.AppendMenuW, wintypes.BOOL, wintypes.HMENU, U, ctypes.c_size_t, wintypes.LPCWSTR)
    _sig(user32.TrackPopupMenu, wintypes.BOOL, wintypes.HMENU, U, ctypes.c_int, ctypes.c_int, ctypes.c_int,
         wintypes.HWND, P)
    _sig(user32.DestroyMenu, wintypes.BOOL, wintypes.HMENU)
    _sig(user32.GetCursorPos, wintypes.BOOL, ctypes.POINTER(POINT))
    _sig(user32.SetForegroundWindow, wintypes.BOOL, wintypes.HWND)
    _sig(user32.LoadImageW, H, wintypes.HINSTANCE, wintypes.LPCWSTR, U, ctypes.c_int, ctypes.c_int, U)
    _sig(user32.DestroyIcon, wintypes.BOOL, wintypes.HICON)
    _sig(user32.AllowSetForegroundWindow, wintypes.BOOL, wintypes.DWORD)
    _sig(shell32.Shell_NotifyIconW, wintypes.BOOL, wintypes.DWORD, ctypes.POINTER(NOTIFYICONDATAW))
    _sig(kernel32.GetModuleHandleW, wintypes.HMODULE, wintypes.LPCWSTR)
    _sig(kernel32.CreateMutexW, H, P, wintypes.BOOL, wintypes.LPCWSTR)
    _sig(kernel32.CloseHandle, wintypes.BOOL, H)

NIM_ADD, NIM_MODIFY, NIM_DELETE = 0, 1, 2
NIF_MESSAGE, NIF_ICON, NIF_TIP, NIF_INFO = 0x1, 0x2, 0x4, 0x10
WM_LBUTTONUP, WM_RBUTTONUP, WM_CLOSE, WM_DESTROY = 0x0202, 0x0205, 0x0010, 0x0002
TPM_RETURNCMD, TPM_RIGHTBUTTON = 0x0100, 0x0002
MF_STRING, MF_SEPARATOR = 0x0, 0x800
IMAGE_ICON, LR_LOADFROMFILE = 1, 0x10
ASFW_ANY = 0xFFFFFFFF


def acquire_single() -> object | None:
    """The named mutex if this is the only copy, else None. Keep the handle for the life of the app."""
    if sys.platform != "win32":
        return True
    handle = kernel32.CreateMutexW(None, False, MUTEX_NAME)
    if handle and ctypes.get_last_error() == ERROR_ALREADY_EXISTS:
        kernel32.CloseHandle(handle)
        return None
    return handle


def release_single(handle) -> None:
    if sys.platform == "win32" and handle and handle is not True:
        kernel32.CloseHandle(handle)


def signal_running(pair: bool = False, wait: float = 5.0) -> bool:
    """Asks the running copy to show its window, or a PIN. It may still be starting, so try for a while."""
    if sys.platform != "win32":
        return False
    deadline = time.monotonic() + wait
    while True:
        hwnd = user32.FindWindowW(CLASS_NAME, None)
        if hwnd:
            user32.AllowSetForegroundWindow(ASFW_ANY)  # lets the running copy bring its window to the front
            return bool(user32.PostMessageW(hwnd, MSG_PAIR if pair else MSG_SHOW, 0, 0))
        if time.monotonic() > deadline:
            return False
        time.sleep(0.2)


def make_icon(path: Path) -> Path:
    """The dock logo, four tiles, as an .ico file. Drawn with Pillow so no picture is shipped."""
    from PIL import Image, ImageDraw

    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for x, y, color in ((4, 4, "#4C8DF6"), (34, 4, "#4C8DF6"), (4, 34, "#4C8DF6"), (34, 34, "#F2B33D")):
        draw.rectangle((x, y, x + 25, y + 25), fill=color)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])
    return path


class Tray:
    """The tray icon. on_open, on_pair and on_quit run on the tray thread: they must only queue work."""

    def __init__(self, icon_path: Path, on_open, on_pair, on_quit, tip: str = "Mobile Dock"):
        self.icon_path, self.tip = icon_path, tip
        self.on_open, self.on_pair, self.on_quit = on_open, on_pair, on_quit
        self.hwnd = self.hicon = None
        self.added = False
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run, name="tray", daemon=True)

    def start(self, timeout: float = 5.0) -> bool:
        if sys.platform != "win32":
            return False
        self._thread.start()
        self._ready.wait(timeout)
        return bool(self.hwnd)

    def stop(self) -> None:
        if self.hwnd:
            user32.PostMessageW(self.hwnd, MSG_STOP, 0, 0)
            self._thread.join(3)

    def notify(self, title: str, text: str) -> None:
        """A Windows notification from the tray icon. Safe to call from any thread."""
        if not self.added:
            return
        data = self._data(NIF_INFO)
        data.szInfoTitle, data.szInfo = title[:63], text[:255]
        shell32.Shell_NotifyIconW(NIM_MODIFY, ctypes.byref(data))

    # Tray thread ---------------------------------------------------------

    def _data(self, flags: int):
        data = NOTIFYICONDATAW()
        data.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        data.hWnd, data.uID, data.uFlags = self.hwnd, 1, flags
        return data

    def _add(self) -> None:
        data = self._data(NIF_MESSAGE | NIF_ICON | NIF_TIP)
        data.uCallbackMessage, data.hIcon, data.szTip = MSG_ICON, self.hicon, self.tip[:127]
        self.added = bool(shell32.Shell_NotifyIconW(NIM_ADD, ctypes.byref(data)))
        if not self.added:
            log.warning("The tray icon could not be added")

    def _menu(self) -> None:
        menu = user32.CreatePopupMenu()
        user32.AppendMenuW(menu, MF_STRING, MENU_OPEN, "Open Mobile Dock")
        user32.AppendMenuW(menu, MF_STRING, MENU_PAIR, "Show pairing PIN")
        user32.AppendMenuW(menu, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(menu, MF_STRING, MENU_QUIT, "Quit")
        pos = POINT()
        user32.GetCursorPos(ctypes.byref(pos))
        user32.SetForegroundWindow(self.hwnd)  # without this the menu does not close when you click elsewhere
        choice = user32.TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, pos.x, pos.y, 0, self.hwnd, None)
        user32.DestroyMenu(menu)
        {MENU_OPEN: self.on_open, MENU_PAIR: self.on_pair, MENU_QUIT: self.on_quit}.get(choice, lambda: None)()

    def _proc(self, hwnd, msg, wparam, lparam):
        try:
            if msg == MSG_ICON:
                if lparam & 0xFFFF == WM_LBUTTONUP:
                    self.on_open()
                elif lparam & 0xFFFF == WM_RBUTTONUP:
                    self._menu()
                return 0
            if msg == MSG_SHOW:
                self.on_open()
                return 0
            if msg == MSG_PAIR:
                self.on_pair()
                return 0
            if msg == self.taskbar_created:  # Explorer restarted: the icon is gone, add it again
                self._add()
                return 0
            if msg == MSG_STOP:
                user32.DestroyWindow(hwnd)
                return 0
            if msg == WM_CLOSE:  # only MSG_STOP closes the tray
                return 0
            if msg == WM_DESTROY:
                if self.added:
                    shell32.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self._data(0)))
                    self.added = False
                user32.PostQuitMessage(0)
                return 0
        except Exception:  # noqa: BLE001 - an error here must not take down the message loop
            log.exception("Tray message %s failed", msg)
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def _run(self) -> None:
        try:
            instance = kernel32.GetModuleHandleW(None)
            self._wndproc = WNDPROC(self._proc)  # kept on self so it is not garbage collected
            wc = WNDCLASSW()
            wc.lpfnWndProc, wc.hInstance, wc.lpszClassName = self._wndproc, instance, CLASS_NAME
            user32.RegisterClassW(ctypes.byref(wc))
            self.taskbar_created = user32.RegisterWindowMessageW("TaskbarCreated")
            # A normal window that is never shown: it hears the TaskbarCreated broadcast and FindWindow finds it.
            self.hwnd = user32.CreateWindowExW(0, CLASS_NAME, "Mobile Dock", 0, 0, 0, 0, 0, None, None, instance, None)
            self.hicon = user32.LoadImageW(None, str(make_icon(self.icon_path)), IMAGE_ICON, 0, 0, LR_LOADFROMFILE)
            self._add()
        except Exception:  # noqa: BLE001 - without a tray the app still works, the window just stays open
            log.exception("The tray could not start")
            self.hwnd = None
            self._ready.set()
            return
        self._ready.set()
        msg = wintypes.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        if self.hicon:
            user32.DestroyIcon(self.hicon)
        self.hwnd = None
