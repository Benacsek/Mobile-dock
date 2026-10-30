import queue
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

tk = pytest.importorskip("tkinter")


def test_window_builds(tmp_path):
    """Builds the real window. Catches theme or layout errors that only Tk reports."""
    from test_server import FakePlatform
    from mobiledock_host.config import Config
    from mobiledock_host.server import DockServer

    try:
        root = tk.Tk()
        root.destroy()
    except tk.TclError:
        pytest.skip("no display")
    from mobiledock_host.gui import App

    config = Config(tmp_path / "config.json")
    config.data.update(port=0, discovery=False)
    server = DockServer(config, FakePlatform())

    class FakeUpdater:
        status = "Up to date"

    app = App(server, queue.Queue(), updater=FakeUpdater())
    app.start_pairing()
    app.show_connection([{"id": "x", "name": "Test phone", "since": 0, "via": "USB"}])
    assert "Test phone" in app.banner_title.cget("text")
    app.show_connection([])
    app.add_apps()
    app.edit_pads()
    import time

    for _ in range(50):  # let the app list thread finish and hand its result to the Tk thread
        app.poll_events()
        app.root.update()
        time.sleep(0.05)
    app.root.destroy()
