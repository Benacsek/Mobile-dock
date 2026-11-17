import os
import signal
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mobiledock_host import tray  # noqa: E402

windows = pytest.mark.skipif(sys.platform != "win32", reason="Windows only")


def test_icon_file(tmp_path):
    from PIL import Image

    path = tray.make_icon(tmp_path / "sub" / "tray.ico")
    with Image.open(path) as img:
        assert img.format == "ICO"


@windows
def test_one_copy_only():
    first = tray.acquire_single()
    try:
        assert first
        assert tray.acquire_single() is None
    finally:
        tray.release_single(first)
    again = tray.acquire_single()
    assert again
    tray.release_single(again)


@windows
def test_second_start_reaches_the_tray(tmp_path):
    """A second start finds the tray window and asks it to show the window or a PIN."""
    calls = []
    icon = tray.Tray(tmp_path / "tray.ico", lambda: calls.append("open"), lambda: calls.append("pair"),
                     lambda: calls.append("quit"))
    assert icon.start()
    try:
        assert tray.signal_running(wait=1)
        assert tray.signal_running(pair=True, wait=1)
        deadline = time.monotonic() + 3
        while len(calls) < 2 and time.monotonic() < deadline:
            time.sleep(0.05)
        assert calls == ["open", "pair"]
        icon.notify("Mobile Dock", "Test")  # must not raise, with or without a taskbar
    finally:
        icon.stop()
    assert not tray.user32.FindWindowW(tray.CLASS_NAME, None)
    assert not tray.signal_running(wait=0)


def test_no_tray_off_windows():
    if sys.platform == "win32":
        pytest.skip("Windows has a tray")
    assert tray.acquire_single() is True
    assert not tray.Tray(Path("x.ico"), None, None, None).start()
    assert not tray.signal_running(wait=0)


@pytest.mark.skipif(not hasattr(signal, "SIGUSR2"), reason="Linux only")
def test_linux_second_start_opens_the_window(tmp_path, monkeypatch):
    from mobiledock_host import __main__ as entry

    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    got = []
    old = signal.signal(signal.SIGUSR2, lambda *_: got.append("show"))
    try:
        assert not entry._signal_running_instance(show=True)  # no host running
        (tmp_path / "mobile-dock-host.pid").write_text(str(os.getpid()))
        assert not entry._signal_running_instance(show=True)  # headless or older host: it would stop on SIGUSR2
        entry._write_pid(str(tmp_path / "mobile-dock-host.pid"))
        assert entry._signal_running_instance(show=True)
        time.sleep(0.1)
        assert got == ["show"]
    finally:
        signal.signal(signal.SIGUSR2, old)


def test_updater_and_autostart_start_in_the_background():
    root = Path(tray.__file__).parent
    assert "--background" in (root / "updater.py").read_text()
    assert (root / "gui.py").read_text().count("--background") == 2
