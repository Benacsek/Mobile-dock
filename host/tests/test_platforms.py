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
