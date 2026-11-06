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
    assert all(box.cget("text").isdigit() for box in app.pin_boxes)
    app.show_connection([{"id": "x", "name": "Test phone", "since": 0, "via": "USB"}])
    assert "Test phone" in app.pill_text.cget("text")
    app.show_connection([])
    for i in range(len(app.TABS)):
        app.show_tab(i)
    app.edit_pads(1)
    win = app.edit_search()
    app.root.update()
    state = win.edit_state
    state["new"]()
    state["set_icon"]({"kind": "file"}, _png())
    state["save"]()
    assert app.server.search_presets()[-1]["icon"] == {"kind": "file"}
    assert app.server.config.data["search_icons"][app.server.search_presets()[-1]["id"]]
    app.copy_diagnostics()
    assert "Mobile Dock host" in app.root.clipboard_get()
    app.whats_new()  # first run only remembers the version
    app.root.geometry("560x420")  # smallest size: pages must still build and scroll
    app.root.update()
    app.add_apps()
    app.edit_pads()
    import time

    for _ in range(50):  # let the app list thread finish and hand its result to the Tk thread
        app.poll_events()
        app.root.update()
        time.sleep(0.05)
    app.root.destroy()


def _png():
    import io

    from PIL import Image

    out = io.BytesIO()
    Image.new("RGBA", (64, 64), (0, 128, 255, 255)).save(out, "PNG")
    return out.getvalue()
