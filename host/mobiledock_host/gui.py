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
    def __init__(self, server: DockServer, events: "queue.Queue[tuple[str, str]]", minimized: bool = False):
        self.server = server
        self.events = events
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

        if sys.platform == "win32":
            self.autostart = tk.BooleanVar(value=_autostart_get())
            ttk.Checkbutton(frame, text="Start with Windows", variable=self.autostart,
                            command=lambda: _autostart_set(self.autostart.get())).pack(anchor="w", **pad)

        self.refresh_devices()
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

    def poll_events(self) -> None:
        try:
            while True:
                kind, text = self.events.get_nowait()
                if kind == "paired":
                    self.pin_label.config(text=f"Paired with {text}")
                elif kind == "info":
                    messagebox.showinfo("Mobile Dock", text)
                self.refresh_devices()
        except queue.Empty:
            pass
        self.root.after(300, self.poll_events)
