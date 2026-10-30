"""Entry point: mobile-dock-host [--headless] [--pair] [--minimized]."""

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
from .config import Config
from .platforms import get_platform
from .server import DockServer


def _pid_file():
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    return os.path.join(runtime, "mobile-dock-host.pid") if runtime else None


def _signal_running_instance() -> bool:
    """Asks an already running headless host to show a PIN. Linux only."""
    path = _pid_file()
    if not path or not hasattr(signal, "SIGUSR1") or not os.path.exists(path):
        return False
    try:
        with open(path, encoding="ascii") as fh:
            pid = int(fh.read().strip())
        os.kill(pid, signal.SIGUSR1)
        return True
    except (OSError, ValueError):
        return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="mobile-dock-host", description="Turn your phone into a dock for this PC.")
    parser.add_argument("--headless", action="store_true", help="run without a window (PIN is printed)")
    parser.add_argument("--pair", action="store_true", help="show a pairing PIN at start")
    parser.add_argument("--minimized", action="store_true", help="start with the window minimized")
    parser.add_argument("--usb", action="store_true", help="run adb reverse at start")
    parser.add_argument("--port", type=int, help="TCP port (default 47800)")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args(argv)

    if args.pair and not args.headless and _signal_running_instance():
        print("The running Mobile Dock host is showing a pairing PIN as a notification.")
        return 0

    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(asctime)s %(levelname)s %(message)s")
    config = Config()
    if args.port:
        config.data["port"] = args.port
    events: "queue.Queue[tuple[str, str]]" = queue.Queue()

    def notify(kind: str, text: str) -> None:
        events.put((kind, text))
        if args.headless:
            print(f"[{kind}] {text}", flush=True)
        if kind == "paired" and sys.platform.startswith("linux") and shutil.which("notify-send"):
            subprocess.run(["notify-send", "Mobile Dock", f"Paired with {text}"], check=False)

    server = DockServer(config, get_platform(), notify)
    try:
        server.start()
    except OSError as exc:
        print(f"Cannot listen on port {server.port}: {exc}", file=sys.stderr)
        return 1

    if args.usb:
        from .usb import enable_usb

        print(enable_usb(server.port))

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
            while not stop.wait(1):
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

    app = App(server, events, minimized=args.minimized)
    if args.pair:
        app.start_pairing()
    app.run()
    server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
