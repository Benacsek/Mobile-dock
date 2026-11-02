"""Shortcut pads: tiles on the phone that run actions on the PC.

A pad belongs to one or more apps. When that app is in front, the phone shows its pad.
Shortcuts are written like "Ctrl+Shift+G". Several key presses are separated by commas.

A tile can also do more than press keys. Steps are separated by " | ":
  open https://figma.com      opens a web page
  run C:\\Tools\\app.exe       starts a program, file or folder
  type Hello there            types the text
  wait 1                      pauses up to 5 seconds
For example: "open https://mail.google.com | wait 2 | Ctrl+K"

Only the PC owner writes tiles in the editor. The phone can only pick a tile by its number,
so a phone can never make the PC run something new.
"""

from __future__ import annotations

import copy
import re

from .platforms.base import KEYS, MODIFIERS

MOD_NAMES = {"ctrl": "ctrl", "control": "ctrl", "alt": "alt", "shift": "shift", "win": "meta", "super": "meta",
             "meta": "meta", "cmd": "meta"}
KEY_ALIASES = {
    "return": "enter", "escape": "esc", "del": "delete", "ins": "insert", "pageup": "pgup", "pagedown": "pgdn",
    "-": "minus", "=": "equal", "[": "bracketleft", "]": "bracketright", "\\": "backslash", ";": "semicolon",
    "'": "quote", ",": "comma", ".": "period", "/": "slash", "`": "grave", "arrowup": "up", "arrowdown": "down",
    "arrowleft": "left", "arrowright": "right", "spacebar": "space",
}


def parse(shortcut: str) -> list[dict]:
    """'Ctrl+Shift+G, Enter' -> [{'key': 'g', 'mods': ['ctrl', 'shift']}, {'key': 'enter', 'mods': []}]."""
    steps = []
    for part in [p.strip() for p in shortcut.split(",") if p.strip()]:
        tokens = [t.strip().lower() for t in re.split(r"\+(?!$)", part)]
        *mods, key = tokens
        key = KEY_ALIASES.get(key, key)
        clean = []
        for m in mods:
            if m not in MOD_NAMES:
                raise ValueError(f"Unknown modifier: {m}")
            if MOD_NAMES[m] not in clean:
                clean.append(MOD_NAMES[m])
        if key not in KEYS or any(m not in MODIFIERS for m in clean):
            raise ValueError(f"Unknown key: {key}")
        steps.append({"key": key, "mods": clean})
    if not steps or len(steps) > 8:
        raise ValueError("A tile needs 1 to 8 steps")
    return steps


ACTION_WORDS = ("open", "run", "type", "wait")


def parse_action(text: str) -> list[dict]:
    """A tile's text as a list of steps: keys, open, run, type and wait. Raises ValueError with a reason."""
    steps: list[dict] = []
    parts = [p.strip() for p in str(text).split(" | ") if p.strip()]
    if not parts:
        raise ValueError("A tile needs a shortcut or an action")
    for part in parts:
        word, _, rest = part.partition(" ")
        word, rest = word.lower(), rest.strip()
        if word == "open":
            if not re.match(r"^https?://\S+$", rest, re.I):
                raise ValueError("open needs a web address that starts with http:// or https://")
            steps.append({"do": "open", "url": rest})
        elif word == "run":
            if not rest:
                raise ValueError("run needs a program, file or folder")
            steps.append({"do": "run", "target": rest[:400]})
        elif word == "type":
            if not rest:
                raise ValueError("type needs some text")
            steps.append({"do": "type", "text": rest[:2000]})
        elif word == "wait":
            try:
                seconds = float(rest)
            except ValueError:
                raise ValueError("wait needs a number of seconds, like wait 1") from None
            steps.append({"do": "wait", "seconds": max(0.0, min(5.0, seconds))})
        else:
            steps.append({"do": "keys", "keys": parse(part)})
    if len(steps) > 10:
        raise ValueError("A tile can have at most 10 steps")
    return steps


def _pad(pid, name, apps, tiles):
    return {"id": pid, "name": name, "apps": apps, "tiles": [{"label": l, "keys": k} for l, k in tiles]}


DEFAULT_PADS = [
    _pad("figma", "Figma", ["figma.exe", "figma", "figma-linux"], [
        ("Move", "V"), ("Frame", "F"), ("Rectangle", "R"), ("Ellipse", "O"), ("Line", "L"), ("Pen", "P"),
        ("Text", "T"), ("Hand", "H"), ("Comment", "C"),
        ("Zoom to fit", "Shift+1"), ("Zoom to selection", "Shift+2"), ("Zoom 100%", "Shift+0"),
        ("Group", "Ctrl+G"), ("Ungroup", "Ctrl+Shift+G"), ("Frame selection", "Ctrl+Alt+G"),
        ("Auto layout", "Shift+A"), ("Component", "Ctrl+Alt+K"), ("Duplicate", "Ctrl+D"),
        ("Align left", "Alt+A"), ("Align center", "Alt+H"), ("Align right", "Alt+D"),
        ("Align top", "Alt+W"), ("Align middle", "Alt+V"), ("Align bottom", "Alt+S"),
        ("Copy props", "Ctrl+Alt+C"), ("Paste props", "Ctrl+Alt+V"), ("Rulers", "Shift+R"),
        ("Layers", "Alt+1"), ("Assets", "Alt+2"), ("Export", "Ctrl+Shift+E"),
        ("Undo", "Ctrl+Z"), ("Redo", "Ctrl+Shift+Z"),
    ]),
    _pad("vscode", "VS Code", ["code.exe", "code", "code-oss", "codium"], [
        ("Command palette", "Ctrl+Shift+P"), ("Quick open", "Ctrl+P"), ("Find in files", "Ctrl+Shift+F"),
        ("Explorer", "Ctrl+Shift+E"), ("Terminal", "Ctrl+grave"), ("Sidebar", "Ctrl+B"),
        ("Go to line", "Ctrl+G"), ("Go to definition", "F12"), ("Rename", "F2"), ("Format", "Shift+Alt+F"),
        ("Comment", "Ctrl+slash"), ("Duplicate line", "Shift+Alt+Down"), ("Move line up", "Alt+Up"),
        ("Move line down", "Alt+Down"), ("Run", "F5"), ("Stop", "Shift+F5"), ("Save", "Ctrl+S"),
        ("Close tab", "Ctrl+W"), ("Split editor", "Ctrl+backslash"), ("Undo", "Ctrl+Z"), ("Redo", "Ctrl+Y"),
    ]),
    _pad("browser", "Browser", ["chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe",
                                "firefox", "chromium", "google-chrome", "brave-browser", "zen"], [
        ("New tab", "Ctrl+T"), ("Close tab", "Ctrl+W"), ("Reopen tab", "Ctrl+Shift+T"),
        ("Next tab", "Ctrl+Tab"), ("Previous tab", "Ctrl+Shift+Tab"), ("Back", "Alt+Left"),
        ("Forward", "Alt+Right"), ("Reload", "F5"), ("Address bar", "Ctrl+L"), ("Find", "Ctrl+F"),
        ("Zoom in", "Ctrl+equal"), ("Zoom out", "Ctrl+minus"), ("Reset zoom", "Ctrl+0"),
        ("Full screen", "F11"), ("Downloads", "Ctrl+J"), ("History", "Ctrl+H"), ("Private window", "Ctrl+Shift+N"),
        ("Dev tools", "F12"),
    ]),
    _pad("windows", "Windows", [], [
        ("Copy", "Ctrl+C"), ("Paste", "Ctrl+V"), ("Cut", "Ctrl+X"), ("Undo", "Ctrl+Z"), ("Redo", "Ctrl+Y"),
        ("Select all", "Ctrl+A"), ("Find", "Ctrl+F"), ("Save", "Ctrl+S"), ("Switch app", "Alt+Tab"),
        ("Task view", "Win+Tab"), ("Desktop", "Win+D"), ("Snap left", "Win+Left"), ("Snap right", "Win+Right"),
        ("Maximize", "Win+Up"), ("Minimize", "Win+Down"), ("Snip", "Win+Shift+S"), ("Emoji", "Win+period"),
        ("Files", "Win+E"), ("Settings", "Win+I"), ("Close window", "Alt+F4"),
    ]),
]
FALLBACK = "windows"


def defaults() -> list[dict]:
    return copy.deepcopy(DEFAULT_PADS)


def match(pads: list[dict], app: str) -> str:
    """Id of the pad for this app (exe name or window class), or the fallback pad."""
    app = (app or "").lower()
    for pad in pads:
        if app and app in [a.lower() for a in pad.get("apps", [])]:
            return pad["id"]
    return FALLBACK if any(p["id"] == FALLBACK for p in pads) else (pads[0]["id"] if pads else "")


def validate(pads: list[dict]) -> list[dict]:
    """Checks pads coming from the editor. Raises ValueError with a readable reason."""
    clean, ids = [], set()
    for pad in pads:
        pid = re.sub(r"[^a-z0-9_-]", "", str(pad.get("id", "")).lower())[:32]
        if not pid or pid in ids:
            raise ValueError("Every pad needs a unique name")
        ids.add(pid)
        tiles = []
        for tile in pad.get("tiles", [])[:60]:
            label = str(tile.get("label", "")).strip()[:24]
            keys = str(tile.get("keys", "")).strip()[:1000]
            if not label:
                raise ValueError("Every tile needs a label")
            parse_action(keys)
            tiles.append({"label": label, "keys": keys})
        apps = [str(a).strip()[:64] for a in pad.get("apps", []) if str(a).strip()][:20]
        clean.append({"id": pid, "name": str(pad.get("name", pid))[:24], "apps": apps, "tiles": tiles})
    return clean
