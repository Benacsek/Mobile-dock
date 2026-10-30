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


BG, CARD, CARD_HI, ACCENT, TEXT, MUTED = "#0F1115", "#1A1D23", "#262A33", "#4F8CFF", "#E8EAED", "#9AA0A6"
GREEN, GREEN_BG = "#3DDC84", "#16352A"


def apply_theme(root: tk.Tk) -> None:
    """Dark theme that matches the phone app."""
    from tkinter import font as tkfont

    root.configure(background=BG)
    base = tkfont.nametofont("TkDefaultFont")
    title = base.copy()
    title.configure(size=base.cget("size") + 8, weight="bold")
    pin = base.copy()
    pin.configure(size=base.cget("size") + 20, weight="bold")
    bold = base.copy()
    bold.configure(weight="bold")
    root._fonts = (title, pin, bold)  # keep references alive

    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(".", background=BG, foreground=TEXT, fieldbackground=CARD_HI, bordercolor=BG,
                    lightcolor=BG, darkcolor=BG, troughcolor=CARD, focuscolor=ACCENT)
    style.configure("TFrame", background=BG)
    style.configure("Card.TFrame", background=CARD)
    style.configure("TLabel", background=BG, foreground=TEXT)
    style.configure("Title.TLabel", font=title)
    style.configure("Status.TLabel", foreground=MUTED)
    style.configure("Footer.TLabel", foreground=MUTED)
    style.configure("CardTitle.TLabel", background=CARD, font=bold)
    style.configure("Muted.TLabel", background=CARD, foreground=MUTED)
    style.configure("Pin.TLabel", background=CARD, foreground=ACCENT, font=pin)
    style.configure("TButton", background=CARD_HI, foreground=TEXT, padding=(14, 7), borderwidth=0)
    style.map("TButton", background=[("active", "#343A46"), ("pressed", "#343A46")])
    style.configure("Accent.TButton", background=ACCENT, foreground="#FFFFFF")
    style.map("Accent.TButton", background=[("active", "#3A6FD0"), ("pressed", "#3A6FD0")])
    style.configure("TCheckbutton", background=BG, foreground=TEXT, indicatorbackground=CARD_HI,
                    indicatorforeground=TEXT)
    style.map("TCheckbutton", background=[("active", BG)], indicatorbackground=[("selected", ACCENT)])
    style.configure("TEntry", fieldbackground=CARD_HI, foreground=TEXT, insertcolor=TEXT, padding=6)
    style.configure("TCombobox", fieldbackground=CARD_HI, foreground=TEXT, background=CARD_HI, arrowcolor=TEXT)
    style.map("TCombobox", fieldbackground=[("readonly", CARD_HI)], foreground=[("readonly", TEXT)])


def card(parent, title: str, expand: bool = False) -> ttk.Frame:
    box = ttk.Frame(parent, style="Card.TFrame", padding=14)
    box.pack(fill="both" if expand else "x", expand=expand, pady=(0, 12))
    ttk.Label(box, text=title, style="CardTitle.TLabel").pack(anchor="w", pady=(0, 8))
    return box


def themed_listbox(parent, **kw) -> tk.Listbox:
    return tk.Listbox(parent, background=CARD_HI, foreground=TEXT, selectbackground=ACCENT,
                      selectforeground="#FFFFFF", highlightthickness=0, borderwidth=0, activestyle="none", **kw)


class App:
    def __init__(self, server: DockServer, events: "queue.Queue[tuple[str, str]]", minimized: bool = False,
                 updater=None, restart: threading.Event | None = None):
        self.server = server
        self.events = events
        self.updater = updater
        self.restart = restart or threading.Event()
        self.calls: "queue.Queue" = queue.Queue()  # work handed from threads to the Tk thread
        self.root = tk.Tk()
        self.root.title("Mobile Dock")
        self.pin_job = None

        apply_theme(self.root)
        self.root.minsize(720, 460)

        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)

        header = ttk.Frame(outer)
        header.pack(fill="x", pady=(0, 12))
        ttk.Label(header, text="Mobile Dock", style="Title.TLabel").pack(side="left")
        self.status = ttk.Label(header, text="", style="Status.TLabel")
        self.status.pack(side="right")

        # Big connection banner: green with the phone name when connected, grey when not.
        self.banner = tk.Frame(outer, background=CARD, padx=16, pady=12)
        self.banner.pack(fill="x", pady=(0, 12))
        self.banner_dot = tk.Canvas(self.banner, width=18, height=18, background=CARD, highlightthickness=0)
        self.banner_dot.pack(side="left", padx=(0, 12))
        texts = tk.Frame(self.banner, background=CARD)
        texts.pack(side="left", fill="x", expand=True)
        self.banner_title = tk.Label(texts, text="", background=CARD, foreground=TEXT, anchor="w",
                                     font=self.root._fonts[2])
        self.banner_title.pack(fill="x")
        self.banner_sub = tk.Label(texts, text="", background=CARD, foreground=MUTED, anchor="w")
        self.banner_sub.pack(fill="x")
        self._banner_widgets = (self.banner, self.banner_dot, texts, self.banner_title, self.banner_sub)

        columns = ttk.Frame(outer)
        columns.pack(fill="both", expand=True)
        left = ttk.Frame(columns)
        left.pack(side="left", fill="both", expand=True, padx=(0, 8))
        right = ttk.Frame(columns)
        right.pack(side="left", fill="both", expand=True, padx=(8, 0))

        conn = card(left, "Connect a phone")
        addrs = ", ".join(local_addresses()) or "no network"
        ttk.Label(conn, text=f"{server.config.data['host_name']}   {addrs}   port {server.port}",
                  style="Muted.TLabel").pack(anchor="w")
        self.pin_label = ttk.Label(conn, text="No active PIN", style="Pin.TLabel")
        self.pin_label.pack(anchor="w", pady=(10, 10))
        row = ttk.Frame(conn, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Button(row, text="Pair new phone", style="Accent.TButton", command=self.start_pairing).pack(side="left")
        ttk.Button(row, text="Enable USB", command=self.usb).pack(side="left", padx=8)

        dev = card(left, "Paired phones", expand=True)
        self.devices = themed_listbox(dev, height=4)
        self.devices.pack(fill="both", expand=True)
        ttk.Button(dev, text="Remove selected", command=self.remove_device).pack(anchor="e", pady=(8, 0))

        apps = card(right, "Pinned apps on the phone", expand=True)
        self.pinned = themed_listbox(apps, height=10)
        self.pinned.pack(fill="both", expand=True)
        buttons = ttk.Frame(apps, style="Card.TFrame")
        buttons.pack(fill="x", pady=(8, 0))
        ttk.Button(buttons, text="Add apps", style="Accent.TButton", command=self.add_apps).pack(side="left")
        ttk.Button(buttons, text="Up", command=lambda: self.move_pinned(-1)).pack(side="left", padx=(8, 0))
        ttk.Button(buttons, text="Down", command=lambda: self.move_pinned(1)).pack(side="left", padx=(8, 0))
        ttk.Button(buttons, text="Unpin", command=self.unpin).pack(side="right")

        pads_card = card(right, "Shortcut pads")
        ttk.Label(pads_card, text="Buttons the phone shows for the app in front.", style="Muted.TLabel").pack(anchor="w")
        ttk.Button(pads_card, text="Edit shortcut pads", command=self.edit_pads).pack(anchor="w", pady=(8, 0))

        footer = ttk.Frame(outer)
        footer.pack(fill="x", pady=(12, 0))
        if sys.platform == "win32":
            self.autostart = tk.BooleanVar(value=_autostart_get())
            ttk.Checkbutton(footer, text="Start with Windows", variable=self.autostart,
                            command=lambda: _autostart_set(self.autostart.get())).pack(side="left")
        if updater is not None:
            ttk.Button(footer, text="Check for updates", command=self.check_updates).pack(side="right")
            ttk.Button(footer, text="GitHub token", command=self.set_token).pack(side="right", padx=8)
            self.update_label = ttk.Label(footer, text=updater.status, style="Footer.TLabel")
            self.update_label.pack(side="right", padx=8)

        self.refresh_devices()
        self.refresh_pinned()
        if minimized:
            self.root.iconify()
        self.root.after(200, self.poll_events)

    def run(self) -> None:
        self.root.mainloop()

    def start_pairing(self) -> None:
        pin = self.server.pairing.start()
        self.pin_label.config(text=f"{pin[:3]} {pin[3:]}")
        self.status.config(text="Type this PIN on your phone. It works for 3 minutes.")
        if self.pin_job:
            self.root.after_cancel(self.pin_job)
        self.pin_job = self.root.after(1000, self.check_pin)

    def check_pin(self) -> None:
        if self.server.pairing.current():
            self.pin_job = self.root.after(1000, self.check_pin)
        else:
            self.pin_label.config(text="No active PIN")
            self.pin_job = None
            self.status.config(text="")
            self.refresh_devices()

    def usb(self) -> None:
        def work():
            self.events.put(("info", enable_usb(self.server.port)))

        threading.Thread(target=work, daemon=True).start()

    def refresh_devices(self) -> None:
        self.device_ids = list(self.server.config.devices().items())
        self.devices.delete(0, "end")
        live = self.server.connected_sessions()
        live_ids = {s["id"] for s in live}
        for device_id, name in self.device_ids:
            self.devices.insert("end", f"{name}    ●  connected" if device_id in live_ids else name)
            if device_id in live_ids:
                self.devices.itemconfig("end", foreground=GREEN)
        self.show_connection(live)

    def show_connection(self, live: list[dict]) -> None:
        import time as _time

        if live:
            bg, fg = GREEN_BG, GREEN
            names = ", ".join(s["name"] for s in live)
            title = f"Connected to {names}"
            first = live[0]
            sub = f"{first['via']}   since {_time.strftime('%H:%M', _time.localtime(first['since']))}"
            self.root.title(f"Mobile Dock  ●  {names}")
        else:
            bg, fg = CARD, MUTED
            title = "No phone connected"
            sub = "Open Mobile Dock on your phone. It connects on its own once paired."
            self.root.title("Mobile Dock")
        for w in self._banner_widgets:
            w.configure(background=bg)
        self.banner_title.configure(text=title, foreground=fg if live else TEXT)
        self.banner_sub.configure(text=sub, foreground=fg if live else MUTED)
        self.banner_dot.delete("all")
        self.banner_dot.create_oval(2, 2, 16, 16, fill=fg, outline="")

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
        win.minsize(420, 480)
        win.configure(background=BG)
        ttk.Label(win, text="Search your apps. Select one or more, then click Pin selected.").pack(
            anchor="w", padx=16, pady=(16, 6))
        query = tk.StringVar()
        entry = ttk.Entry(win, textvariable=query)
        entry.pack(fill="x", padx=16)
        listbox = themed_listbox(win, selectmode="extended")
        listbox.pack(fill="both", expand=True, padx=16, pady=10)
        shown: list[dict] = []
        apps: list[dict] = []

        def fill(*_):
            shown.clear()
            listbox.delete(0, "end")
            q = query.get().lower().strip()
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
        ttk.Button(win, text="Pin selected", style="Accent.TButton", command=add).pack(anchor="e", padx=16, pady=(0, 16))
        listbox.insert("end", "Loading apps...")
        entry.focus_set()

        def failed(message):
            listbox.delete(0, "end")
            listbox.insert("end", "Could not list apps:")
            listbox.insert("end", message)

        def work():
            try:
                result = self.server.platform.list_apps()
            except Exception as exc:  # noqa: BLE001 - show the reason instead of an empty list
                message = str(exc) or type(exc).__name__
                self.later(lambda: failed(message))
                return
            self.later(lambda: loaded(result))

        threading.Thread(target=work, daemon=True).start()

    # Shortcut pads --------------------------------------------------------

    def edit_pads(self) -> None:
        from . import pads as padlib
        import copy

        data = copy.deepcopy(self.server.config.data["pads"])
        win = tk.Toplevel(self.root)
        win.title("Shortcut pads")
        win.minsize(760, 480)
        win.configure(background=BG)
        body = ttk.Frame(win, padding=16)
        body.pack(fill="both", expand=True)

        left = ttk.Frame(body)
        left.pack(side="left", fill="y")
        ttk.Label(left, text="Pads").pack(anchor="w")
        pad_list = themed_listbox(left, width=18, exportselection=False)
        pad_list.pack(fill="y", expand=True, pady=6)
        lbtn = ttk.Frame(left)
        lbtn.pack(fill="x")

        mid = ttk.Frame(body)
        mid.pack(side="left", fill="both", expand=True, padx=(16, 0))
        name_var, apps_var = tk.StringVar(), tk.StringVar()
        ttk.Label(mid, text="Name").pack(anchor="w")
        ttk.Entry(mid, textvariable=name_var).pack(fill="x")
        ttk.Label(mid, text="Apps (program names, comma separated, for example Figma.exe)").pack(anchor="w", pady=(8, 0))
        apps_row = ttk.Frame(mid)
        apps_row.pack(fill="x")
        ttk.Entry(apps_row, textvariable=apps_var).pack(side="left", fill="x", expand=True)
        running = ttk.Combobox(apps_row, width=22, state="readonly")
        running.pack(side="left", padx=(8, 0))
        ttk.Label(mid, text="Tiles").pack(anchor="w", pady=(8, 0))
        tile_list = themed_listbox(mid, height=10, exportselection=False)
        tile_list.pack(fill="both", expand=True)
        label_var, keys_var = tk.StringVar(), tk.StringVar()
        edit_row = ttk.Frame(mid)
        edit_row.pack(fill="x", pady=(8, 0))
        ttk.Label(edit_row, text="Label").pack(side="left")
        ttk.Entry(edit_row, textvariable=label_var, width=16).pack(side="left", padx=(6, 12))
        ttk.Label(edit_row, text="Shortcut").pack(side="left")
        ttk.Entry(edit_row, textvariable=keys_var, width=22).pack(side="left", padx=(6, 0))
        ttk.Label(mid, text="Write shortcuts like Ctrl+Shift+G. Separate steps with commas: Win+Left, Enter",
                  style="Footer.TLabel").pack(anchor="w", pady=(4, 0))
        tbtn = ttk.Frame(mid)
        tbtn.pack(fill="x", pady=(8, 0))
        state = {"pad": 0}

        def current():
            return data[state["pad"]] if data else None

        def show_pads(select=0):
            pad_list.delete(0, "end")
            for d in data:
                pad_list.insert("end", d["name"])
            if data:
                state["pad"] = max(0, min(select, len(data) - 1))
                pad_list.selection_clear(0, "end")
                pad_list.selection_set(state["pad"])
                show_pad()

        def show_pad():
            d = current()
            name_var.set(d["name"] if d else "")
            apps_var.set(", ".join(d["apps"]) if d else "")
            tile_list.delete(0, "end")
            for t in (d["tiles"] if d else []):
                tile_list.insert("end", f"{t['label']}    {t['keys']}")

        def keep_fields():
            d = current()
            if d:
                d["name"] = name_var.get().strip() or d["name"]
                d["apps"] = [a.strip() for a in apps_var.get().split(",") if a.strip()]

        def pick_pad(_):
            sel = pad_list.curselection()
            if sel and sel[0] != state["pad"]:
                keep_fields()
                state["pad"] = sel[0]
                show_pad()

        def pick_tile(_):
            sel = tile_list.curselection()
            if sel:
                t = current()["tiles"][sel[0]]
                label_var.set(t["label"])
                keys_var.set(t["keys"])

        def checked_tile():
            try:
                padlib.parse(keys_var.get())
            except ValueError as exc:
                messagebox.showerror("Shortcut pads", str(exc), parent=win)
                return None
            if not label_var.get().strip():
                messagebox.showerror("Shortcut pads", "Give the tile a label", parent=win)
                return None
            return {"label": label_var.get().strip()[:24], "keys": keys_var.get().strip()}

        def add_tile():
            t = checked_tile()
            if t and current() is not None:
                current()["tiles"].append(t)
                show_pad()
                tile_list.selection_set("end")

        def update_tile():
            sel, t = tile_list.curselection(), checked_tile()
            if sel and t:
                current()["tiles"][sel[0]] = t
                show_pad()
                tile_list.selection_set(sel[0])

        def remove_tile():
            sel = tile_list.curselection()
            if sel:
                del current()["tiles"][sel[0]]
                show_pad()

        def move_tile(step):
            sel = tile_list.curselection()
            if not sel:
                return
            i, tiles = sel[0], current()["tiles"]
            j = i + step
            if 0 <= j < len(tiles):
                tiles[i], tiles[j] = tiles[j], tiles[i]
                show_pad()
                tile_list.selection_set(j)

        def new_pad():
            keep_fields()
            n = 1
            while any(d["id"] == f"custom{n}" for d in data):
                n += 1
            data.append({"id": f"custom{n}", "name": f"My pad {n}", "apps": [], "tiles": []})
            show_pads(len(data) - 1)

        def delete_pad():
            d = current()
            if d and d["id"] != padlib.FALLBACK and messagebox.askyesno("Shortcut pads", f"Delete {d['name']}?",
                                                                         parent=win):
                del data[state["pad"]]
                show_pads(0)

        def reset():
            if messagebox.askyesno("Shortcut pads", "Replace all pads with the built-in ones?", parent=win):
                data[:] = padlib.defaults()
                show_pads(0)

        def save():
            keep_fields()
            try:
                self.server.set_pads(data)
            except ValueError as exc:
                messagebox.showerror("Shortcut pads", str(exc), parent=win)
                return
            win.destroy()

        def add_running(_):
            name = running.get()
            if name:
                apps = [a.strip() for a in apps_var.get().split(",") if a.strip()]
                if name not in apps:
                    apps.append(name)
                apps_var.set(", ".join(apps))

        def load_running():
            try:
                import psutil

                names = sorted({p.info["name"] for p in psutil.process_iter(["name"]) if p.info["name"]},
                               key=str.lower)
            except Exception:  # noqa: BLE001
                names = []
            self.later(lambda: running.configure(values=names))

        ttk.Button(lbtn, text="New", command=new_pad).pack(side="left")
        ttk.Button(lbtn, text="Delete", command=delete_pad).pack(side="left", padx=6)
        ttk.Button(left, text="Reset to built-in", command=reset).pack(fill="x", pady=(6, 0))
        ttk.Button(tbtn, text="Add", style="Accent.TButton", command=add_tile).pack(side="left")
        ttk.Button(tbtn, text="Update", command=update_tile).pack(side="left", padx=6)
        ttk.Button(tbtn, text="Remove", command=remove_tile).pack(side="left")
        ttk.Button(tbtn, text="Up", command=lambda: move_tile(-1)).pack(side="left", padx=(12, 0))
        ttk.Button(tbtn, text="Down", command=lambda: move_tile(1)).pack(side="left", padx=6)
        ttk.Button(tbtn, text="Save", style="Accent.TButton", command=save).pack(side="right")
        pad_list.bind("<<ListboxSelect>>", pick_pad)
        tile_list.bind("<<ListboxSelect>>", pick_tile)
        running.bind("<<ComboboxSelected>>", add_running)
        running.set("Add a running app")
        show_pads(0)
        threading.Thread(target=load_running, daemon=True).start()

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

    def later(self, fn) -> None:
        """Runs fn on the Tk thread. Tk must never be touched from other threads."""
        self.calls.put(fn)

    def poll_events(self) -> None:
        if self.restart.is_set():
            self.root.destroy()
            return
        while True:
            try:
                self.calls.get_nowait()()
            except queue.Empty:
                break
        try:
            while True:
                kind, text = self.events.get_nowait()
                if kind == "paired":
                    self.pin_label.config(text=f"Paired with {text}")
                    self.status.config(text="")
                elif kind == "info":
                    messagebox.showinfo("Mobile Dock", text)
                elif kind == "file":
                    self.status.config(text=text)
                elif kind in ("update", "update_status") and self.updater is not None:
                    self.update_label.config(text=text)
                self.refresh_devices()
        except queue.Empty:
            pass
        self.root.after(300, self.poll_events)
