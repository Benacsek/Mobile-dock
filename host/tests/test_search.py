import base64
import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mobiledock_host import search  # noqa: E402
from test_server import FakePlatform, call, pair, server  # noqa: E402,F401  (server is a fixture)

P = search.defaults()


def test_auto_rules():
    # A keyword: "yt lofi" searches YouTube for "lofi".
    preset, query, url = search.choose(P, "yt lofi beats")
    assert preset["id"] == "youtube" and query == "lofi beats" and not url
    # Just a keyword or a site's name opens the site.
    assert search.choose(P, "yt")[2] == "https://www.youtube.com/"
    assert search.choose(P, "YouTube")[2] == "https://www.youtube.com/"
    # A domain opens directly.
    assert search.choose(P, "github.com/trending")[2] == "https://github.com/trending"
    # The app in front decides when nothing else does.
    assert search.choose(P, "lofi beats", front_app="chrome.exe")[0]["id"] == "browser"
    assert search.choose(P, "lofi beats", front_app="Code.exe")[0]["id"] == "vscode"
    # Otherwise the default preset, Google.
    assert search.choose(P, "lofi beats", front_app="notepad.exe")[0]["id"] == "google"
    # A tapped chip always wins, with the whole text.
    assert search.choose(P, "yt lofi", preset_id="windows")[:2] == (P[-1], "yt lofi")
    with pytest.raises(ValueError):
        search.choose(P, "x", preset_id="gone")


def test_query_cannot_escape_the_address():
    steps = search.steps_for(P[1]["action"], "a&b c/../evil.com#x?y=1")
    url = steps[0]["url"]
    assert url.startswith("https://www.youtube.com/results?search_query=")
    assert "&" not in url.split("=", 1)[1] and "/" not in url.split("=", 1)[1] and "#" not in url
    # Typed text is typed as it is.
    steps = search.steps_for("Ctrl+L | type {q} | Enter", "hello & bye")
    assert steps[1] == {"do": "type", "text": "hello & bye"}


def test_presets_cannot_start_programs_or_leave_the_web():
    for bad in ("run notepad.exe", "open file:///C:/x?{q}", "open javascript:alert({q})", "Hyper+Q"):
        with pytest.raises(ValueError):
            search.validate([{"id": "x", "name": "X", "action": bad}])
    clean = search.validate([{"id": "a", "name": "A", "action": "open https://a.example/?q={q}", "keyword": "a"},
                             {"id": "b", "name": "B", "action": "open https://b.example/?q={q}", "keyword": "b"}])
    assert sum(p["default"] for p in clean) == 1  # always exactly one default
    with pytest.raises(ValueError):
        search.validate([{"id": "a", "name": "A", "action": "Enter", "keyword": "x"},
                         {"id": "b", "name": "B", "action": "Enter", "keyword": "x"}])  # duplicate keyword


def png_bytes(color=(255, 0, 0, 255), size=(48, 48), fmt="PNG"):
    from PIL import Image

    out = io.BytesIO()
    Image.new("RGBA", size, color).save(out, fmt)
    return out.getvalue()


def test_site_icons_are_fetched_from_the_site_over_https():
    asked = []

    def fake_get(url):
        asked.append(url)
        if url.endswith("apple-touch-icon.png"):
            raise OSError("404")
        return png_bytes(fmt="ICO")

    png = search.fetch_site_icon(P[1], get=fake_get)
    assert asked == ["https://www.youtube.com/apple-touch-icon.png", "https://www.youtube.com/favicon.ico"]
    from PIL import Image

    assert Image.open(io.BytesIO(png)).size == (64, 64)
    assert search.fetch_site_icon(P[1], get=lambda url: b"not a picture") is None
    assert search.fetch_site_icon(P[4], get=fake_get) is None  # an in-app preset has no site


def test_search_commands(server):
    opened, typed, keys = [], [], []
    pf = server.platform
    pf.open_url = lambda url, browser="default": opened.append((url, browser))
    pf.type_text = lambda t: typed.append(t)
    pf.key = lambda k, m: keys.append((k, tuple(m)))
    pf.foreground = lambda: {"app": "chrome.exe"}
    pf.app_icon = lambda app_id: png_bytes((0, 0, 255, 255))
    pf.list_apps = lambda: [{"id": "chrome-id", "name": "Google Chrome"}]
    channel, _ = pair(server, server.pairing.start())

    listed = call(channel, "search.presets", 1)["presets"]
    assert [p["id"] for p in listed][:2] == ["google", "youtube"] and all(p["icon_rev"] for p in listed)
    assert call(channel, "search.run", 2, text="yt lofi")["did"] == "YouTube: lofi"
    assert opened[-1][0] == "https://www.youtube.com/results?search_query=lofi"
    call(channel, "search.run", 3, text="lofi beats")  # Chrome is in front: search in the browser
    assert keys[0] == ("l", ("ctrl",)) and typed[-1] == "lofi beats"
    assert not call(channel, "search.run", 4, text="  ")["ok"]

    icon = call(channel, "search.icon", 5, id="browser")  # the Chrome app's own icon
    assert base64.b64decode(icon["png"]).startswith(b"\x89PNG")
    assert call(channel, "search.icon", 6, id="windows")["builtin"] == "windows"
    server.config.data["search_fetch_icons"] = False
    assert call(channel, "search.icon", 7, id="youtube")["builtin"] == "globe"  # no fetching: a line icon

    data = server.export_setup()
    assert data["search"] and "browser" in data["search_icons"]
    server.config.data["search"] = search.defaults()[:1]
    server.import_setup(data)
    assert len(server.config.data["search"]) == len(search.defaults())
