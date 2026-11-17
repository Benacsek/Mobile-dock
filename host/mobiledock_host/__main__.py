"""Entry point: mobile-dock-host [--headless] [--pair] [--background]."""

from __future__ import annotations

import argparse
import logging
import os
import queue
import shutil
import signal
import subprocess
import sys
import threading

from . import __version__
from .config import Config, config_dir
from .platforms import get_platform
from .server import DockServer
from .updater import Updater


def _pid_file():
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    return os.path.join(runtime, "mobile-dock-host.pid") if runtime else None


PID_SHOW_MARK = "show"  # this host opens its window on SIGUSR2. Older hosts would stop on it, so they lack the mark


def _signal_running_instance(show: bool = False) -> bool:
    """Asks an already running host to show a PIN, or with show=True to open its window. Linux only."""
    path = _pid_file()
    if not path or not hasattr(signal, "SIGUSR1") or not os.path.exists(path):
        return False
    try:
        with open(path, encoding="ascii") as fh:
            parts = fh.read().split()
        pid = int(parts[0])
        if show and PID_SHOW_MARK not in parts[1:]:
            return False
        os.kill(pid, signal.SIGUSR2 if show else signal.SIGUSR1)
        return True
    except (OSError, ValueError, IndexError):
        return False


def _write_pid(path: str) -> None:
    with open(path, "w", encoding="ascii") as fh:
        fh.write(f"{os.getpid()} {PID_SHOW_MARK}")


LOG_NAME = "mobile-dock.log"


def setup_error_log() -> None:
    """Keeps warnings and errors in a small file next to the config. It never leaves the PC.

    Copy diagnostics in the window puts its last lines on the clipboard for a bug report.
    """
    from logging.handlers import RotatingFileHandler

    try:
        folder = config_dir()
        folder.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(folder / LOG_NAME, maxBytes=256 * 1024, backupCount=1, encoding="utf-8")
    except OSError:
        return
    handler.setLevel(logging.WARNING)
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.getLogger().addHandler(handler)

    def crashed(kind, value, tb):
        logging.getLogger("crash").critical("Unhandled error", exc_info=(kind, value, tb))
        sys.__excepthook__(kind, value, tb)

    def thread_crashed(args):
        logging.getLogger("crash").critical("Unhandled error in thread %s", getattr(args.thread, "name", "?"),
                                            exc_info=(args.exc_type, args.exc_value, args.exc_traceback))

    sys.excepthook = crashed
    threading.excepthook = thread_crashed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="mobile-dock-host", description="Turn your phone into a dock for this PC.")
    parser.add_argument("--headless", action="store_true", help="run without a window (PIN is printed)")
    parser.add_argument("--pair", action="store_true", help="show a pairing PIN at start")
    parser.add_argument("--background", action="store_true", help="start without showing the window")
    parser.add_argument("--minimized", action="store_true", help=argparse.SUPPRESS)  # old name of --background
    parser.add_argument("--usb", action="store_true", help="run adb reverse at start")
    parser.add_argument("--port", type=int, help="TCP port (default 47800)")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args(argv)

    if args.pair and not args.headless and _signal_running_instance():
        print("The running Mobile Dock host is showing a pairing PIN.")
        return 0
    if not args.headless and _signal_running_instance(show=True):
        print("Mobile Dock is already running. Its window is open now.")
        return 0

    from . import tray as traylib

    lock = traylib.acquire_single()  # Windows: one copy only. A second start opens the running copy's window.
    if lock is None:
        if traylib.signal_running(pair=args.pair):
            return 0
        print("Mobile Dock is already running.", file=sys.stderr)
        return 1

    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(asctime)s %(levelname)s %(message)s")
    setup_error_log()
    config = Config()
    if args.port:
        config.data["port"] = args.port
    events: "queue.Queue[tuple[str, str]]" = queue.Queue()

    def notify(kind: str, text: str) -> None:
        events.put((kind, text))
        if args.headless:
            print(f"[{kind}] {text}", flush=True)
        if args.headless and kind == "paired" and sys.platform.startswith("linux") and shutil.which("notify-send"):
            subprocess.run(["notify-send", "Mobile Dock", f"Paired with {text}"], check=False)

    server = DockServer(config, get_platform(), notify)
    restart = threading.Event()
    updater = Updater(config, on_restart=restart.set, notify=notify)
    server.updater = updater
    try:
        server.start()
    except OSError as exc:
        print(f"Cannot listen on port {server.port}: {exc}", file=sys.stderr)
        return 1

    if args.usb:
        from .usb import enable_usb

        print(enable_usb(server.port))

    updater.start()

    def show_pin(*_):
        pin = server.pairing.start()
        message = f"Pairing PIN: {pin} (valid 3 minutes)"
        print(message, flush=True)
        if sys.platform.startswith("linux") and shutil.which("notify-send"):
            subprocess.run(["notify-send", "-t", "60000", "Mobile Dock", message], check=False)

    if args.headless:
        if args.pair:
            show_pin()
        print(f"Mobile Dock host listening on port {server.port}. Ctrl+C to stop.", flush=True)
        stop = threading.Event()
        signal.signal(signal.SIGINT, lambda *_: stop.set())
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
        pid_path = _pid_file()
        if pid_path and hasattr(signal, "SIGUSR1"):
            signal.signal(signal.SIGUSR1, show_pin)
            with open(pid_path, "w", encoding="ascii") as fh:
                fh.write(str(os.getpid()))
        try:
            while not stop.wait(1) and not restart.is_set():
                pass
        finally:
            server.stop()
            if pid_path and os.path.exists(pid_path):
                os.unlink(pid_path)
        return 0

    try:
        from .gui import App
    except ImportError:
        server.stop()
        print("Tk is not installed. Install it (python-tk or tk) or run with --headless.", file=sys.stderr)
        return 1

    tray = None
    if sys.platform == "win32":
        tray = traylib.Tray(config_dir() / "tray.ico", on_open=lambda: app.later(app.show),
                            on_pair=lambda: app.later(app.pair_and_show), on_quit=lambda: app.later(app.quit))
        if not tray.start():
            tray = None
    app = App(server, events, hidden=args.background or args.minimized, updater=updater, restart=restart, tray=tray)
    pid_path = _pid_file()
    if pid_path and hasattr(signal, "SIGUSR2"):  # Linux: a second start opens this window, --pair shows a PIN
        signal.signal(signal.SIGUSR1, lambda *_: app.later(app.pair_and_show))
        signal.signal(signal.SIGUSR2, lambda *_: app.later(app.show))
        _write_pid(pid_path)
    if args.pair:
        app.pair_and_show()
    try:
        app.run()
    finally:
        server.stop()
        if tray is not None:
            tray.stop()
        traylib.release_single(lock)
        if pid_path and hasattr(signal, "SIGUSR2") and os.path.exists(pid_path):
            os.unlink(pid_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
