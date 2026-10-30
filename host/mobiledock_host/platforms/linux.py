"""Linux backend, built for Hyprland (works on most wlroots compositors).

Runtime tools:
  python-evdev + /dev/uinput   touchpad and keys (see linux/99-mobile-dock-uinput.rules)
  wtype                        layout-independent typing (falls back to uinput, US layout)
  playerctl                    media control and now-playing
  wpctl or pactl               volume
  grim                         screenshots
  wf-recorder or wl-screenrec  screen recording
"""

from __future__ import annotations

import configparser
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import threading
import time
from pathlib import Path

from .base import KEYS, MEDIA_ACTIONS, MODIFIERS, MOUSE_BUTTONS, Platform, PlatformError

FIELD_CODE = re.compile(r"%[fFuUdDnNickvm]")

US_SHIFTED = dict(zip('~!@#$%^&*()_+{}|:"<>?', "`1234567890-=[]\\;',./"))
US_KEYS = {
    "`": "GRAVE", "-": "MINUS", "=": "EQUAL", "[": "LEFTBRACE", "]": "RIGHTBRACE",
    "\\": "BACKSLASH", ";": "SEMICOLON", "'": "APOSTROPHE", ",": "COMMA", ".": "DOT",
    "/": "SLASH", " ": "SPACE", "\n": "ENTER", "\t": "TAB",
}
KEY_NAMES = {
    "enter": "ENTER", "backspace": "BACKSPACE", "tab": "TAB", "esc": "ESC", "space": "SPACE",
    "delete": "DELETE", "insert": "INSERT", "up": "UP", "down": "DOWN", "left": "LEFT",
    "right": "RIGHT", "home": "HOME", "end": "END", "pgup": "PAGEUP", "pgdn": "PAGEDOWN",
    "ctrl": "LEFTCTRL", "alt": "LEFTALT", "shift": "LEFTSHIFT", "meta": "LEFTMETA",
    **{f"f{i}": f"F{i}" for i in range(1, 13)},
    **{c: c.upper() for c in "abcdefghijklmnopqrstuvwxyz0123456789"},
}


def _run(cmd: list[str], timeout: float = 5) -> str:
    if not shutil.which(cmd[0]):
        raise PlatformError(f"{cmd[0]} is not installed")
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired as exc:
        raise PlatformError(f"{cmd[0]} timed out") from exc


def _data_dirs() -> list[Path]:
    home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    dirs = os.environ.get("XDG_DATA_DIRS", "/usr/local/share:/usr/share").split(":")
    extra = [Path.home() / ".local/share/flatpak/exports/share", Path("/var/lib/flatpak/exports/share")]
    return [home, *(Path(d) for d in dirs if d), *extra]


class LinuxPlatform(Platform):
    name = "linux"

    def __init__(self):
        super().__init__()
        self._apps: dict[str, dict] = {}
        self._apps_time = 0.0
        self._ui = None
        self._ui_error = ""
        self._ui_lock = threading.Lock()
        self._scroll = [0.0, 0.0]

    # Apps -----------------------------------------------------------------

    def _scan_apps(self) -> dict[str, dict]:
        apps: dict[str, dict] = {}
        for base in _data_dirs():
            root = base / "applications"
            if not root.is_dir():
                continue
            for path in sorted(root.rglob("*.desktop")):
                app_id = str(path.relative_to(root)).replace("/", "-")
                if app_id in apps:
                    continue  # earlier dirs take priority, per the XDG spec
                parser = configparser.ConfigParser(interpolation=None, strict=False)
                try:
                    parser.read(path, encoding="utf-8")
                    entry = parser["Desktop Entry"]
                except (configparser.Error, KeyError, UnicodeDecodeError):
                    continue
                apps[app_id] = {
                    "name": entry.get("Name", app_id),
                    "exec": entry.get("Exec", ""),
                    "terminal": entry.get("Terminal", "false").lower() == "true",
                    "hidden": entry.get("Type") != "Application"
                    or entry.get("NoDisplay", "false").lower() == "true"
                    or entry.get("Hidden", "false").lower() == "true",
                }
        return apps

    def list_apps(self) -> list[dict]:
        if not self._apps or time.time() - self._apps_time > 60:
            self._apps = self._scan_apps()
            self._apps_time = time.time()
        visible = [{"id": k, "name": v["name"]} for k, v in self._apps.items() if not v["hidden"] and v["exec"]]
        return sorted(visible, key=lambda a: a["name"].lower())

    def launch(self, app_id: str) -> None:
        app = self._apps.get(app_id)
        if not app:
            raise PlatformError("App not found")
        command = FIELD_CODE.sub("", app["exec"]).replace("%%", "%").strip()
        if app["terminal"]:
            if shutil.which("xdg-terminal-exec"):
                command = f"xdg-terminal-exec {command}"
            else:
                term = os.environ.get("TERMINAL") or "kitty"
                command = f"{shlex.quote(term)} -e {command}"
        if os.environ.get("HYPRLAND_INSTANCE_SIGNATURE") and shutil.which("hyprctl"):
            # Spawning through Hyprland applies the user's window and workspace rules.
            _run(["hyprctl", "dispatch", "exec", command])
            return
        subprocess.Popen(shlex.split(command), start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Media ----------------------------------------------------------------

    def media(self, action: str) -> None:
        if action not in MEDIA_ACTIONS:
            raise PlatformError("Unknown media action")
        if action in ("play_pause", "next", "prev"):
            _run(["playerctl", {"play_pause": "play-pause", "next": "next", "prev": "previous"}[action]])
            return
        if shutil.which("wpctl"):
            sink = "@DEFAULT_AUDIO_SINK@"
            cmd = {"vol_up": ["set-volume", "-l", "1.0", sink, "5%+"], "vol_down": ["set-volume", sink, "5%-"],
                   "mute": ["set-mute", sink, "toggle"]}[action]
            _run(["wpctl", *cmd])
        else:
            sink = "@DEFAULT_SINK@"
            cmd = {"vol_up": ["set-sink-volume", sink, "+5%"], "vol_down": ["set-sink-volume", sink, "-5%"],
                   "mute": ["set-sink-mute", sink, "toggle"]}[action]
            _run(["pactl", *cmd])

    def media_status(self) -> dict:
        if not shutil.which("playerctl"):
            return {}
        out = _run(["playerctl", "metadata", "--format", "{{status}}\t{{artist}}\t{{title}}"]).strip()
        parts = out.split("\t")
        if len(parts) != 3:
            return {}
        return {"playing": parts[0] == "Playing", "artist": parts[1], "title": parts[2]}

    # Capture --------------------------------------------------------------

    def screenshot(self, path: Path) -> None:
        _run(["grim", str(path)], timeout=15)
        if not path.exists():
            raise PlatformError("grim did not save a screenshot")

    def _focused_output(self) -> str | None:
        if not shutil.which("hyprctl"):
            return None
        try:
            monitors = json.loads(_run(["hyprctl", "monitors", "-j"]))
        except (ValueError, PlatformError):
            return None
        return next((m["name"] for m in monitors if m.get("focused")), None)

    def recording_command(self, path: Path) -> list[str]:
        output = self._focused_output()
        if shutil.which("wf-recorder"):
            return ["wf-recorder", "-y", "-f", str(path), *(["-o", output] if output else [])]
        if shutil.which("wl-screenrec"):
            return ["wl-screenrec", "-f", str(path), *(["-o", output] if output else [])]
        raise PlatformError("Install wf-recorder or wl-screenrec to record")

    def signal_recorder_stop(self, proc: subprocess.Popen) -> None:
        proc.send_signal(signal.SIGINT)

    # Input ----------------------------------------------------------------

    def _uinput(self):
        with self._ui_lock:
            if self._ui is not None:
                return self._ui
            try:
                from evdev import UInput, ecodes as e
            except ImportError as exc:
                raise PlatformError("Install python-evdev for touchpad and keys") from exc
            keys = [getattr(e, f"KEY_{n}") for n in set(KEY_NAMES.values()) | set(US_KEYS.values())]
            caps = {
                e.EV_KEY: keys + [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE],
                e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL, e.REL_HWHEEL],
            }
            try:
                self._ui = UInput(caps, name="Mobile Dock virtual input")
            except OSError as exc:
                raise PlatformError("No access to /dev/uinput. See the Linux setup in the README.") from exc
            self._ecodes = e
            return self._ui

    def _tap(self, codes: list[int]) -> None:
        ui, e = self._uinput(), self._ecodes
        for code in codes:
            ui.write(e.EV_KEY, code, 1)
        for code in reversed(codes):
            ui.write(e.EV_KEY, code, 0)
        ui.syn()

    def type_text(self, text: str) -> None:
        if shutil.which("wtype") and os.environ.get("WAYLAND_DISPLAY"):
            subprocess.run(["wtype", "--", text], timeout=10, check=False)
            return
        self._uinput()
        e = self._ecodes
        for ch in text:
            shift = ch.isupper() or ch in US_SHIFTED
            base = US_SHIFTED.get(ch, ch.lower())
            name = US_KEYS.get(base) or (base.upper() if base.isascii() and base.isalnum() else None)
            if not name:
                continue  # non-ASCII needs wtype
            codes = ([e.KEY_LEFTSHIFT] if shift else []) + [getattr(e, f"KEY_{name}")]
            self._tap(codes)

    def key(self, key: str, mods: list[str]) -> None:
        if key not in KEYS or any(m not in MODIFIERS for m in mods):
            raise PlatformError("Unknown key")
        self._uinput()
        e = self._ecodes
        self._tap([getattr(e, f"KEY_{KEY_NAMES[n]}") for n in [*mods, key]])

    def mouse_move(self, dx: int, dy: int) -> None:
        ui = self._uinput()
        e = self._ecodes
        ui.write(e.EV_REL, e.REL_X, dx)
        ui.write(e.EV_REL, e.REL_Y, dy)
        ui.syn()

    def mouse_button(self, button: str, action: str) -> None:
        if button not in MOUSE_BUTTONS:
            raise PlatformError("Unknown button")
        ui = self._uinput()
        e = self._ecodes
        code = {"left": e.BTN_LEFT, "right": e.BTN_RIGHT, "middle": e.BTN_MIDDLE}[button]
        if action in ("down", "click"):
            ui.write(e.EV_KEY, code, 1)
            ui.syn()
        if action in ("up", "click"):
            ui.write(e.EV_KEY, code, 0)
            ui.syn()

    def scroll(self, dx: int, dy: int) -> None:
        # Phone units are a third of a wheel notch, matching Windows (40 of 120).
        ui = self._uinput()
        e = self._ecodes
        self._scroll[0] += dx / 3
        self._scroll[1] += dy / 3
        notch_x, notch_y = int(self._scroll[0]), int(self._scroll[1])
        self._scroll[0] -= notch_x
        self._scroll[1] -= notch_y
        if notch_y:
            ui.write(e.EV_REL, e.REL_WHEEL, notch_y)
        if notch_x:
            ui.write(e.EV_REL, e.REL_HWHEEL, notch_x)
        if notch_x or notch_y:
            ui.syn()
