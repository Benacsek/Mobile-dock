"""Interface every desktop backend implements."""

from __future__ import annotations

import shutil
import subprocess
import time
from pathlib import Path

MEDIA_ACTIONS = ("play_pause", "next", "prev", "vol_up", "vol_down", "mute")
MOUSE_BUTTONS = ("left", "right", "middle")
MODIFIERS = ("ctrl", "alt", "shift", "meta")
KEYS = (
    "enter", "backspace", "tab", "esc", "space", "delete", "insert",
    "up", "down", "left", "right", "home", "end", "pgup", "pgdn",
    *(f"f{i}" for i in range(1, 13)),
    *"abcdefghijklmnopqrstuvwxyz0123456789",
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

    def media_shuffle(self) -> None:
        raise PlatformError("Shuffle is not supported here")

    def media_repeat(self) -> None:
        raise PlatformError("Repeat is not supported here")

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
