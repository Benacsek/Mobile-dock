"""Small control window: pairing PIN, paired phones, USB and autostart."""

from __future__ import annotations

import queue
import socket
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

from .server import DockServer
from .usb import enable_usb

AUTOSTART_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
AUTOSTART_NAME = "MobileDock"


def local_addresses() -> list[str]:
    addrs = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # no packet is sent, this only picks the LAN interface
            addrs.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addrs.add(info[4][0])
    except OSError:
        pass
    return sorted(a for a in addrs if not a.startswith("127."))


def _autostart_get() -> bool:
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTOSTART_KEY) as key:
            winreg.QueryValueEx(key, AUTOSTART_NAME)
            return True
    except OSError:
        return False


def _autostart_set(enabled: bool) -> None:
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTOSTART_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            if getattr(sys, "frozen", False):
                value = f'"{sys.executable}" --minimized'
            else:
                pythonw = Path(sys.executable).with_name("pythonw.exe")
                exe = pythonw if pythonw.exists() else Path(sys.executable)
                value = f'"{exe}" -m mobiledock_host --minimized'
            winreg.SetValueEx(key, AUTOSTART_NAME, 0, winreg.REG_SZ, value)
        else:
            try:
                winreg.DeleteValue(key, AUTOSTART_NAME)
            except OSError:
                pass


class App:
    def __init__(self, server: DockServer, events: "queue.Queue[tuple[str, str]]", minimized: bool = False,
                 updater=None, restart: threading.Event | None = None):
        self.server = server
        self.events = events
        self.updater = updater
        self.restart = restart or threading.Event()
        self.root = tk.Tk()
        self.root.title("Mobile Dock")
        self.root.minsize(420, 360)
        self.pin_job = None

        pad = {"padx": 12, "pady": 6}
        frame = ttk.Frame(self.root, padding=12)
        frame.pack(fill="both", expand=True)

        addrs = ", ".join(local_addresses()) or "no network"
        ttk.Label(frame, text=f"PC name: {server.config.data['host_name']}").pack(anchor="w")
        ttk.Label(frame, text=f"Address: {addrs}  port {server.port}").pack(anchor="w")
        self.status = ttk.Label(frame, text="Waiting for your phone")
        self.status.pack(anchor="w", pady=(0, 8))

        pin_box = ttk.LabelFrame(frame, text="Pairing", padding=8)
        pin_box.pack(fill="x", pady=6)
        self.pin_label = ttk.Label(pin_box, text="No active PIN")
        self.pin_label.pack(anchor="w")
        row = ttk.Frame(pin_box)
        row.pack(fill="x", pady=(6, 0))
        ttk.Button(row, text="Pair new phone", command=self.start_pairing).pack(side="left")
        ttk.Button(row, text="Enable USB", command=self.usb).pack(side="left", padx=6)

        dev_box = ttk.LabelFrame(frame, text="Paired phones", padding=8)
        dev_box.pack(fill="both", expand=True, pady=6)
        self.devices = tk.Listbox(dev_box, height=5)
        self.devices.pack(fill="both", expand=True)
        ttk.Button(dev_box, text="Remove selected", command=self.remove_device).pack(anchor="e", pady=(6, 0))

        apps_box = ttk.LabelFrame(frame, text="Pinned apps (shown on the phone)", padding=8)
        apps_box.pack(fill="both", expand=True, pady=6)
        self.pinned = tk.Listbox(apps_box, height=6)
        self.pinned.pack(side="left", fill="both", expand=True)
        buttons = ttk.Frame(apps_box)
        buttons.pack(side="left", fill="y", padx=(8, 0))
        ttk.Button(buttons, text="Add apps", command=self.add_apps).pack(fill="x")
        ttk.Button(buttons, text="Move up", command=lambda: self.move_pinned(-1)).pack(fill="x", pady=(6, 0))
        ttk.Button(buttons, text="Move down", command=lambda: self.move_pinned(1)).pack(fill="x", pady=(6, 0))
        ttk.Button(buttons, text="Unpin", command=self.unpin).pack(fill="x", pady=(6, 0))

        if updater is not None:
            upd = ttk.Frame(frame)
            upd.pack(fill="x", pady=6)
            self.update_label = ttk.Label(upd, text=updater.status)
            self.update_label.pack(side="left")
            ttk.Button(upd, text="Check for updates", command=self.check_updates).pack(side="right")
            ttk.Button(upd, text="GitHub token", command=self.set_token).pack(side="right", padx=6)

        if sys.platform == "win32":
            self.autostart = tk.BooleanVar(value=_autostart_get())
            ttk.Checkbutton(frame, text="Start with Windows", variable=self.autostart,
                            command=lambda: _autostart_set(self.autostart.get())).pack(anchor="w", **pad)

        self.refresh_devices()
        self.refresh_pinned()
        if minimized:
            self.root.iconify()
        self.root.after(200, self.poll_events)

    def run(self) -> None:
        self.root.mainloop()

    def start_pairing(self) -> None:
        pin = self.server.pairing.start()
        self.pin_label.config(text=f"PIN  {pin[:3]} {pin[3:]}   (valid 3 minutes)")
        if self.pin_job:
            self.root.after_cancel(self.pin_job)
        self.pin_job = self.root.after(1000, self.check_pin)

    def check_pin(self) -> None:
        if self.server.pairing.current():
            self.pin_job = self.root.after(1000, self.check_pin)
        else:
            self.pin_label.config(text="No active PIN")
            self.pin_job = None

    def usb(self) -> None:
        def work():
            self.events.put(("info", enable_usb(self.server.port)))

        threading.Thread(target=work, daemon=True).start()

    def refresh_devices(self) -> None:
        self.device_ids = list(self.server.config.devices().items())
        self.devices.delete(0, "end")
        for _, name in self.device_ids:
            self.devices.insert("end", name)
        connected = self.server.connected_names()
        self.status.config(text="Connected: " + ", ".join(connected) if connected else "Waiting for your phone")

    def remove_device(self) -> None:
        sel = self.devices.curselection()
        if not sel:
            return
        device_id, name = self.device_ids[sel[0]]
        if messagebox.askyesno("Mobile Dock", f"Remove {name}? It will need a new PIN to connect."):
            self.server.revoke(device_id)
            self.refresh_devices()

    # Pinned apps ----------------------------------------------------------

    def refresh_pinned(self) -> None:
        self.pinned_items = self.server.config.pinned()
        self.pinned.delete(0, "end")
        for app in self.pinned_items:
            self.pinned.insert("end", app["name"])

    def move_pinned(self, step: int) -> None:
        sel = self.pinned.curselection()
        if not sel:
            return
        i, items = sel[0], list(self.pinned_items)
        j = i + step
        if not 0 <= j < len(items):
            return
        items[i], items[j] = items[j], items[i]
        self.server.set_pinned(items)
        self.refresh_pinned()
        self.pinned.selection_set(j)

    def unpin(self) -> None:
        sel = self.pinned.curselection()
        if sel:
            self.server.set_pinned([a for k, a in enumerate(self.pinned_items) if k != sel[0]])
            self.refresh_pinned()

    def add_apps(self) -> None:
        win = tk.Toplevel(self.root)
        win.title("Add apps")
        win.minsize(360, 420)
        ttk.Label(win, text="Search").pack(anchor="w", padx=12, pady=(12, 0))
        query = tk.StringVar()
        entry = ttk.Entry(win, textvariable=query)
        entry.pack(fill="x", padx=12)
        listbox = tk.Listbox(win, selectmode="extended")
        listbox.pack(fill="both", expand=True, padx=12, pady=8)
        shown: list[dict] = []
        apps: list[dict] = []

        def fill(*_):
            shown.clear()
            listbox.delete(0, "end")
            q = query.get().lower()
            pinned_ids = {a["id"] for a in self.server.config.pinned()}
            for app in apps:
                if q in app["name"].lower() and app["id"] not in pinned_ids:
                    shown.append(app)
                    listbox.insert("end", app["name"])

        def add():
            pinned = self.server.config.pinned()
            have = {a["id"] for a in pinned}
            for i in listbox.curselection():
                if shown[i]["id"] not in have:
                    pinned.append(dict(shown[i]))
            self.server.set_pinned(pinned)
            self.refresh_pinned()
            fill()

        def loaded(result):
            apps.extend(result)
            fill()

        query.trace_add("write", fill)
        ttk.Button(win, text="Pin selected", command=add).pack(anchor="e", padx=12, pady=(0, 12))
        listbox.insert("end", "Loading apps...")
        entry.focus_set()

        def work():
            result = self.server.platform.list_apps()
            self.root.after(0, lambda: loaded(result))

        threading.Thread(target=work, daemon=True).start()

    # Updates --------------------------------------------------------------

    def set_token(self) -> None:
        from tkinter import simpledialog

        token = simpledialog.askstring(
            "Mobile Dock",
            "Only needed when the GitHub repository is private.\n"
            "Paste a fine-grained token with read-only access to Contents of this one repository.\n"
            "Leave empty to remove it.",
            show="*", parent=self.root)
        if token is None:
            return
        self.server.config.data["github_token"] = token.strip()
        self.server.config.save()
        self.check_updates()

    def check_updates(self) -> None:
        self.update_label.config(text="Checking for updates...")

        def work():
            self.events.put(("update_status", self.updater.check()))

        threading.Thread(target=work, daemon=True).start()

    def poll_events(self) -> None:
        if self.restart.is_set():
            self.root.destroy()
            return
        try:
            while True:
                kind, text = self.events.get_nowait()
                if kind == "paired":
                    self.pin_label.config(text=f"Paired with {text}")
                elif kind == "info":
                    messagebox.showinfo("Mobile Dock", text)
                elif kind in ("update", "update_status") and self.updater is not None:
                    self.update_label.config(text=text)
                self.refresh_devices()
        except queue.Empty:
            pass
        self.root.after(300, self.poll_events)
