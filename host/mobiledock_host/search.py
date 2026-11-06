"""Search from the phone: type once, and a preset decides where it goes.

A preset has a name, an optional keyword ("yt"), the apps it belongs to ("chrome.exe"), an action and
an icon. The action uses the same steps as shortcut tiles, with {q} for the typed text:

  open https://www.youtube.com/results?search_query={q}    a web search
  Ctrl+L | type {q} | Enter                                search in the app in front
  Win+S | wait 0.6 | type {q} | Enter                      Windows search

The phone only sends text and, if you tapped a chip, the preset's id. {q} goes into a web address
URL-encoded, so it cannot change where the address points, and "run" steps are not allowed here,
so typed text can never start a program.
"""

from __future__ import annotations

import base64
import copy
import io
import re
import urllib.parse
import urllib.request

from . import pads

BROWSERS = ("default", "chrome", "edge", "firefox")
BUILTIN_ICONS = ("search", "globe", "pin", "windows")
MAX_ICON_DOWNLOAD = 512 * 1024
SITE_RE = re.compile(r"^(?:https?://)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)(/\S*)?$", re.I)


def _preset(pid, name, keyword, apps, action, icon, default=False, browser="default"):
    return {"id": pid, "name": name, "keyword": keyword, "apps": apps, "action": action, "browser": browser,
            "icon": icon, "default": default}


DEFAULT_PRESETS = [
    _preset("google", "Google", "g", [], "open https://www.google.com/search?q={q}",
            {"kind": "site"}, default=True),
    _preset("youtube", "YouTube", "yt", [], "open https://www.youtube.com/results?search_query={q}", {"kind": "site"}),
    _preset("maps", "Maps", "map", [], "open https://www.google.com/maps/search/{q}", {"kind": "builtin", "value": "pin"}),
    _preset("wikipedia", "Wikipedia", "w", [], "open https://en.wikipedia.org/w/index.php?search={q}",
            {"kind": "site"}),
    _preset("browser", "In browser", "", ["chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe",
                                         "vivaldi.exe", "firefox", "chromium", "google-chrome", "brave-browser"],
            "Ctrl+L | type {q} | Enter", {"kind": "app", "match": "Chrome"}),
    _preset("vscode", "In VS Code", "", ["code.exe", "code", "code-oss", "codium"], "Ctrl+Shift+F | type {q} | Enter",
            {"kind": "app", "match": "Visual Studio Code"}),
    _preset("figma", "In Figma", "", ["figma.exe", "figma", "figma-linux"], "Ctrl+slash | type {q}",
            {"kind": "app", "match": "Figma"}),
    _preset("explorer", "In Explorer", "", ["explorer.exe"], "Ctrl+F | type {q} | Enter",
            {"kind": "app", "match": "File Explorer"}),
    _preset("spotify", "In Spotify", "sp", ["spotify.exe", "spotify"], "Ctrl+L | type {q} | Enter",
            {"kind": "app", "match": "Spotify"}),
    _preset("windows", "Windows", "win", [], "Win+S | wait 0.6 | type {q} | Enter",
            {"kind": "builtin", "value": "windows"}),
]


def defaults() -> list[dict]:
    return copy.deepcopy(DEFAULT_PRESETS)


def steps_for(action: str, query: str) -> list[dict]:
    """The preset's steps with {q} filled in: URL-encoded in web addresses, as typed text elsewhere."""
    out = []
    for step in pads.parse_action(action):
        if step["do"] == "run":
            raise ValueError("Search presets cannot start programs")
        if step["do"] == "open":
            url = step["url"].replace("{q}", urllib.parse.quote_plus(query))
            if not re.match(r"^https?://[^\s]+$", url, re.I):
                raise ValueError("A search must open an http or https address")
            step = {"do": "open", "url": url}
        elif step["do"] == "type":
            step = {"do": "type", "text": step["text"].replace("{q}", query)}
        out.append(step)
    return out


def home(preset: dict) -> str | None:
    """The site a web preset searches, for typing just its name or keyword."""
    match = re.match(r"^\s*open\s+(https?://[^/\s]+)", preset.get("action", ""), re.I)
    return match.group(1) + "/" if match else None


def site_url(text: str) -> str | None:
    """'youtube.com' or 'https://x.org/page' as an https address. Plain words are not sites."""
    match = SITE_RE.match(text.strip())
    if not match or " " in text.strip():
        return None
    return "https://" + match.group(1).lower() + (match.group(2) or "/")


def choose(presets: list[dict], text: str, preset_id: str = "", front_app: str = "") -> tuple[dict | None, str, str]:
    """Decides what a search does. Returns (preset, query, url): url is set when a site opens directly.

    In order: a chip the user tapped, a keyword ("yt lofi"), a site name ("youtube" or "youtube.com"),
    a preset for the app in front, then the default preset.
    """
    text = " ".join(text.split())
    if preset_id:
        preset = next((p for p in presets if p["id"] == preset_id), None)
        if preset is None:
            raise ValueError("That search preset no longer exists")
        return preset, text, ""
    first, _, rest = text.partition(" ")
    for preset in presets:
        if preset.get("keyword") and first.lower() == preset["keyword"].lower():
            if not rest and home(preset):
                return preset, "", home(preset)
            return preset, rest, ""
    lowered = text.lower()
    for preset in presets:
        if home(preset) and lowered == preset["name"].lower():
            return preset, "", home(preset)
    url = site_url(text)
    if url:
        return None, "", url
    app = (front_app or "").lower()
    if app:
        for preset in presets:
            if app in [a.lower() for a in preset.get("apps", [])]:
                return preset, text, ""
    preset = next((p for p in presets if p.get("default")), presets[0] if presets else None)
    return preset, text, ""


def validate(presets: list[dict]) -> list[dict]:
    """Checks presets from the editor or a setup file. Raises ValueError with a readable reason."""
    clean, ids, keywords = [], set(), set()
    for p in presets[:40]:
        pid = re.sub(r"[^a-z0-9_-]", "", str(p.get("id", "")).lower())[:32]
        name = str(p.get("name", "")).strip()[:24]
        if not pid or pid in ids:
            raise ValueError("Every search preset needs a unique id")
        if not name:
            raise ValueError("Every search preset needs a name")
        keyword = str(p.get("keyword", "")).strip().lower()[:12]
        if keyword and (not re.fullmatch(r"[a-z0-9]+", keyword) or keyword in keywords):
            raise ValueError(f"The keyword {keyword!r} must be letters or digits and used only once")
        action = str(p.get("action", "")).strip()[:1000]
        steps_for(action, "test")  # raises for run steps, bad keys and non-web addresses
        browser = str(p.get("browser", "default"))
        icon = p.get("icon") if isinstance(p.get("icon"), dict) else {"kind": "builtin", "value": "search"}
        kind = icon.get("kind")
        if kind == "builtin" and icon.get("value") not in BUILTIN_ICONS:
            icon = {"kind": "builtin", "value": "search"}
        elif kind == "app":
            icon = {"kind": "app", "match": str(icon.get("match", ""))[:80]}
        elif kind not in ("site", "file", "builtin", "app"):
            icon = {"kind": "builtin", "value": "search"}
        else:
            icon = {k: v for k, v in icon.items() if k in ("kind", "value")}
        ids.add(pid)
        if keyword:
            keywords.add(keyword)
        clean.append({"id": pid, "name": name, "keyword": keyword,
                      "apps": [str(a).strip()[:64] for a in p.get("apps", []) if str(a).strip()][:20],
                      "action": action, "browser": browser if browser in BROWSERS else "default",
                      "icon": icon, "default": bool(p.get("default"))})
    if clean and not any(p["default"] for p in clean):
        clean[0]["default"] = True
    if sum(p["default"] for p in clean) > 1:
        first = next(p for p in clean if p["default"])
        for p in clean:
            p["default"] = p is first
    return clean


# Icons ---------------------------------------------------------------------------------------------


def to_png(data: bytes, size: int = 64) -> bytes:
    """Any picture (ICO, PNG, JPEG...) as a square PNG. The largest frame of an ICO is used."""
    from PIL import Image

    img = Image.open(io.BytesIO(data))
    if getattr(img, "n_frames", 1) > 1 or img.format == "ICO":
        sizes = sorted(getattr(img, "info", {}).get("sizes", []) or [img.size])
        try:
            img.size = sizes[-1]
        except Exception:  # noqa: BLE001 - not every ICO reader allows choosing a size
            pass
    img.load()
    img = img.convert("RGBA")
    img.thumbnail((size, size))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(img, ((size - img.width) // 2, (size - img.height) // 2), img)
    out = io.BytesIO()
    canvas.save(out, "PNG")
    return out.getvalue()


def _get(url: str) -> bytes:
    if not url.startswith("https://"):
        raise ValueError("icons are fetched over https only")
    req = urllib.request.Request(url, headers={"User-Agent": "MobileDock"})
    with urllib.request.urlopen(req, timeout=8) as resp:  # noqa: S310 - https checked above
        if not resp.geturl().startswith("https://"):
            raise ValueError("redirected away from https")
        data = resp.read(MAX_ICON_DOWNLOAD + 1)
    if len(data) > MAX_ICON_DOWNLOAD:
        raise ValueError("icon too large")
    return data


def fetch_site_icon(preset: dict, get=_get) -> bytes | None:
    """The site's own icon, from the site itself: a large touch icon if it has one, else favicon.ico."""
    site = home(preset)
    if not site or not site.startswith("https://"):
        return None
    for path in ("apple-touch-icon.png", "favicon.ico"):
        try:
            return to_png(get(site + path))
        except Exception:  # noqa: BLE001 - try the next file, then give up quietly
            continue
    return None


def encode(png: bytes | None) -> str:
    return base64.b64encode(png).decode() if png else ""
