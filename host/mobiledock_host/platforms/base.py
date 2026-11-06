"""Interface every desktop backend implements."""

from __future__ import annotations

import shutil
import subprocess
import time
from pathlib import Path

# PC controls the phone may trigger. Deliberately no shutdown or restart: too easy to hit by accident.
SYSTEM_ACTIONS = ("lock", "sleep", "display_off", "desktop", "task_manager")
MEDIA_ACTIONS = ("play_pause", "next", "prev", "vol_up", "vol_down", "mute")
MOUSE_BUTTONS = ("left", "right", "middle")
MODIFIERS = ("ctrl", "alt", "shift", "meta")
KEYS = (
    "enter", "backspace", "tab", "esc", "space", "delete", "insert",
    "up", "down", "left", "right", "home", "end", "pgup", "pgdn",
    *(f"f{i}" for i in range(1, 13)),
    *"abcdefghijklmnopqrstuvwxyz0123456789",
    # Punctuation keys by their position on a US keyboard.
    "minus", "equal", "bracketleft", "bracketright", "backslash", "semicolon", "quote", "comma", "period",
    "slash", "grave",
)


class PlatformError(Exception):
    """Shown to the user on the phone."""


class Platform:
    name = "unknown"

    def __init__(self):
        self._recorder: subprocess.Popen | None = None
        self._recording_path: Path | None = None

    # Apps
    def list_apps(self) -> list[dict]:
        raise NotImplementedError

    def launch(self, app_id: str) -> None:
        raise NotImplementedError

    # Media
    def media(self, action: str) -> None:
        raise NotImplementedError

    def media_status(self) -> dict:
        """title, artist, album, playing, position, duration (seconds), shuffle, repeat, art_id.

        Missing keys mean the player does not report them.
        """
        return {}

    def media_art(self) -> bytes | None:
        """Album art of the current track as PNG or JPEG bytes."""
        return None

    def media_seek(self, seconds: float) -> None:
        raise PlatformError("Seeking is not supported here")

    def media_jump(self, delta: float) -> None:
        """Moves the current track or video by delta seconds, for the -5 and +5 buttons."""
        raise PlatformError("Jumping is not supported here")

    def media_shuffle(self) -> None:
        raise PlatformError("Shuffle is not supported here")

    def media_repeat(self) -> None:
        raise PlatformError("Repeat is not supported here")

    def system(self, action: str) -> None:
        raise PlatformError("Not supported on this system")

    def foreground(self) -> dict:
        """The app in front: {"app": exe name or window class, "title": window title}."""
        return {}

    def set_clipboard_image(self, png: bytes) -> None:
        raise PlatformError("Clipboard images are not supported here")

    def set_clipboard_text(self, text: str) -> None:
        raise PlatformError("The clipboard is not supported here")

    def get_clipboard_text(self) -> str:
        raise PlatformError("The clipboard is not supported here")

    def open_url(self, url: str, browser: str = "default") -> None:
        """Opens a web page, in a chosen browser when it is installed, else in the default one."""
        import webbrowser

        exe = self.browser_path(browser) if browser != "default" else None
        if exe:
            try:
                subprocess.Popen([exe, url], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
                return
            except OSError:
                pass
        if not webbrowser.open(url):
            raise PlatformError("Could not open a browser")

    def browser_path(self, browser: str) -> str | None:
        names = {"chrome": ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser"),
                 "edge": ("microsoft-edge", "microsoft-edge-stable"), "firefox": ("firefox",)}
        return next((shutil.which(n) for n in names.get(browser, ()) if shutil.which(n)), None)

    def run_target(self, target: str) -> None:
        """Starts a program, file or folder the PC owner wrote into a shortcut tile."""
        import shlex

        try:
            subprocess.Popen(shlex.split(target), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
        except (OSError, ValueError) as exc:
            raise PlatformError(f"Could not start {target[:60]}") from exc

    def mixer(self) -> list[dict]:
        """Apps playing sound: [{"id", "name", "volume" 0-100, "muted"}]."""
        raise PlatformError("The volume mixer is not supported here")

    def mixer_set(self, session_id: str, volume: int | None = None, muted: bool | None = None) -> None:
        raise PlatformError("The volume mixer is not supported here")

    def mac_address(self, local_ip: str) -> str | None:
        """MAC address of the network card that has local_ip, for Wake-on-LAN."""
        import psutil

        for nic, addrs in psutil.net_if_addrs().items():
            if any(a.address == local_ip for a in addrs):
                for a in addrs:
                    mac = (a.address or "").replace("-", ":").lower()
                    if len(mac) == 17 and mac.count(":") == 5 and mac != "00:00:00:00:00:00":
                        return mac
        return None

    def app_icon(self, app_id: str) -> bytes | None:
        """PNG icon for an app from list_apps(), or None."""
        return None

    # Capture
    def screenshot(self, path: Path) -> None:
        raise NotImplementedError

    def recording_command(self, path: Path) -> list[str]:
        raise NotImplementedError

    def start_recording(self, path: Path) -> None:
        if self._recorder and self._recorder.poll() is None:
            raise PlatformError("Already recording")
        cmd = self.recording_command(path)
        if not shutil.which(cmd[0]):
            raise PlatformError(f"{cmd[0]} is not installed")
        self._recorder = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self._recording_path = path
        time.sleep(0.5)
        if self._recorder.poll() is not None:
            self._recorder = None
            raise PlatformError(f"{cmd[0]} failed to start")

    def stop_recording(self) -> Path:
        proc, self._recorder = self._recorder, None
        if not proc or proc.poll() is not None:
            raise PlatformError("Not recording")
        self.signal_recorder_stop(proc)
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        return self._recording_path

    def is_recording(self) -> bool:
        return bool(self._recorder and self._recorder.poll() is None)

    def signal_recorder_stop(self, proc: subprocess.Popen) -> None:
        proc.terminate()

    # Input
    def type_text(self, text: str) -> None:
        raise NotImplementedError

    def key(self, key: str, mods: list[str]) -> None:
        raise NotImplementedError

    def mouse_move(self, dx: int, dy: int) -> None:
        raise NotImplementedError

    def mouse_button(self, button: str, action: str) -> None:
        raise NotImplementedError

    def scroll(self, dx: int, dy: int) -> None:
        raise NotImplementedError


def get_platform() -> Platform:
    import sys

    if sys.platform == "win32":
        from .windows import WindowsPlatform

        return WindowsPlatform()
    if sys.platform.startswith("linux"):
        from .linux import LinuxPlatform

        return LinuxPlatform()
    raise PlatformError(f"Unsupported OS: {sys.platform}")
