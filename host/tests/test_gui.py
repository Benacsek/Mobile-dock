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


def _app(tmp_path, tray=None):
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
    return App(DockServer(config, FakePlatform()), queue.Queue(), tray=tray)


class FakeTray:
    added = True

    def __init__(self):
        self.said = []

    def notify(self, title, text):
        self.said.append((title, text))


def test_close_keeps_running_in_background(tmp_path):
    """The X hides the window and the server keeps running. Show brings it back. Messages go to the tray."""
    tray = FakeTray()
    app = _app(tmp_path, tray)
    app.server.start()
    try:
        app.root.update()
        app.close_window()
        app.root.update()
        assert app.hidden and app.root.state() == "withdrawn"
        assert app.server.port  # still listening
        assert tray.said and "still running" in tray.said[0][0]
        app.close_window()
        assert len(tray.said) == 1  # the notice shows once
        app.events.put(("info", "USB ready"))
        app.poll_events()
        assert tray.said[-1] == ("Mobile Dock", "USB ready")  # no popup while hidden
        ran = []
        app.later(lambda: 1 / 0)
        app.later(lambda: ran.append(1))
        app.poll_events()
        assert ran  # a failed call does not stop the loop the tray relies on
        app.show()
        app.root.update()
        assert not app.hidden and app.root.state() == "normal"
        app.pair_and_show()
        assert app.server.pairing.current()
    finally:
        app.server.stop()
        app.root.destroy()


def test_close_quits_when_background_is_off(tmp_path):
    app = _app(tmp_path)
    app.flip_keep_running()
    assert app.server.config.data["close_to_tray"] is False
    app.close_window()
    with pytest.raises(tk.TclError):
        app.root.winfo_exists()


def test_without_tray_windows_minimizes(tmp_path, monkeypatch):
    """With no tray icon there would be no way back, so the window only minimizes."""
    app = _app(tmp_path)
    monkeypatch.setattr(sys, "platform", "win32")
    app.root.update()
    app.close_window()
    assert not app.hidden
    app.root.destroy()


def test_release_notes_wait_until_the_window_opens(tmp_path, monkeypatch):
    import types

    from mobiledock_host import __version__

    monkeypatch.setitem(sys.modules, "mobiledock_host._notes", types.SimpleNamespace(NOTES="New things"))
    shown = []
    monkeypatch.setattr("mobiledock_host.gui.messagebox.showinfo", lambda *a, **k: shown.append(a))
    tray = FakeTray()
    app = _app(tmp_path, tray)
    app.server.config.data["seen_version"] = "0.0.1"
    app.hide()
    app.whats_new()
    assert not shown and app.notes_waiting and __version__ in tray.said[-1][0]
    app.show()
    assert shown and shown[0][1] == "New things"
    app.root.destroy()


def _png():
    import io

    from PIL import Image

    out = io.BytesIO()
    Image.new("RGBA", (64, 64), (0, 128, 255, 255)).save(out, "PNG")
    return out.getvalue()
