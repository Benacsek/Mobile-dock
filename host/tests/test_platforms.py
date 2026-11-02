import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mobiledock_host.platforms import PlatformError, get_platform  # noqa: E402
from mobiledock_host.platforms.png import write_png  # noqa: E402


def test_png_writer(tmp_path):
    path = tmp_path / "x.png"
    write_png(path, 2, 1, bytes([255, 0, 0, 0, 255, 0]))
    assert path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")


def test_platform_lists_apps():
    pf = get_platform()
    apps = pf.list_apps()
    assert isinstance(apps, list)
    assert all(set(a) == {"id", "name"} for a in apps)


def test_bad_input_rejected():
    pf = get_platform()
    with pytest.raises(PlatformError):
        pf.key("rm -rf", [])
    with pytest.raises(PlatformError):
        pf.media("format_c")


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="Linux only")
def test_linux_unknown_app_rejected():
    pf = get_platform()
    with pytest.raises(PlatformError):
        pf.launch("does-not-exist.desktop")


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_icons_render():
    pf = get_platform()
    icons = [pf.app_icon(a["id"]) for a in pf.list_apps()[:8]]
    assert any(i and i.startswith(b"\x89PNG") for i in icons)


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_screenshot(tmp_path):
    pf = get_platform()
    path = tmp_path / "shot.png"
    try:
        pf.screenshot(path)
    except PlatformError:
        pytest.skip("no interactive desktop on this runner")
    assert path.read_bytes().startswith(b"\x89PNG")


def test_media_status_never_raises():
    # No player runs in CI. The call must still return a dict, which exercises the optional imports.
    pf = get_platform()
    if sys.platform == "win32":
        pf.app_icon(pf.list_apps()[0]["id"])  # icons first, so a COM apartment clash would show up here
    assert isinstance(pf.media_status(), dict)


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_finds_start_menu_apps():
    pf = get_platform()
    assert len(pf._start_apps()) > 5, pf._apps_error
    links = pf._start_menu_links()
    assert links, "Start menu shortcut fallback found nothing"
    some = next(iter(links))
    assert pf.app_icon(some) is None or pf.app_icon(some).startswith(b"\x89PNG")


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_clipboard_image_and_foreground():
    import ctypes
    import io

    from PIL import Image

    pf = get_platform()
    buf = io.BytesIO()
    Image.new("RGB", (4, 3), (255, 0, 0)).save(buf, "PNG")
    pf.set_clipboard_image(buf.getvalue())
    assert ctypes.windll.user32.IsClipboardFormatAvailable(8)  # CF_DIB
    assert isinstance(pf.foreground(), dict)


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_secrets_encrypted_at_rest(tmp_path):
    import json

    from mobiledock_host import secretstore
    from mobiledock_host.config import Config

    enc = secretstore.protect("abc123")
    assert enc.startswith("dpapi:") and secretstore.reveal(enc) == "abc123"
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"devices": {"d1": {"name": "Phone", "secret": "aa" * 32}}, "github_token": "tok"}))
    cfg = Config(path)
    raw = path.read_text()
    assert "aa" * 32 not in raw and '"tok"' not in raw
    assert cfg.device_secret("d1") == bytes.fromhex("aa" * 32)
    assert cfg.get_secret("github_token") == "tok"


def test_mixer_and_clipboard_answer_or_explain():
    """On every system these either work or raise a readable PlatformError, never crash."""
    pf = get_platform()
    try:
        apps = pf.mixer()
        assert all({"id", "name", "volume", "muted"} <= set(a) for a in apps)
    except PlatformError:
        pass
    try:
        assert isinstance(pf.get_clipboard_text(), str)
    except PlatformError:
        pass


@pytest.mark.skipif(sys.platform != "win32", reason="Windows only")
def test_windows_clipboard_text_round_trip():
    pf = get_platform()
    pf.set_clipboard_text("Mobile Dock ✓ test")
    assert pf.get_clipboard_text() == "Mobile Dock ✓ test"
