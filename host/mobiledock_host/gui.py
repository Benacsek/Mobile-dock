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


# Three colors: graphite for surfaces, blue for actions and selection, amber for live state.
BG, SURFACE, SURFACE_HI, LINE = "#16181C", "#1E2126", "#272B31", "#33383F"
TEXT, MUTED, FAINT = "#E6E8EB", "#8B919A", "#5C626B"
ACCENT, ACCENT_HOVER, ACCENT_PRESS, ON_ACCENT = "#4C8DF6", "#6A9FF8", "#3A74D4", "#FFFFFF"
SIGNAL, SIGNAL_BG = "#F2B33D", "#2E2618"
HOVER, PRESS = "#2F343B", "#383E46"
# Old names, still used by the dialogs below.
CARD, CARD_HI, GREEN = SURFACE, SURFACE_HI, SIGNAL


def apply_theme(root: tk.Tk) -> None:
    """Graphite theme with a blue accent and an amber signal color."""
    from tkinter import font as tkfont

    root.configure(background=BG)
    base = tkfont.nametofont("TkDefaultFont")
    family = base.cget("family")
    if sys.platform == "win32":
        family = "Segoe UI"
        base.configure(family=family)
    size = abs(int(base.cget("size"))) or 9
    fonts = {
        "body": tkfont.Font(family=family, size=size + 1),
        "small": tkfont.Font(family=family, size=size),
        "strong": tkfont.Font(family=family, size=size + 1, weight="bold"),
        "h2": tkfont.Font(family=family, size=size + 4, weight="bold"),
        "brand": tkfont.Font(family=family, size=size + 5, weight="bold"),
        "pin": tkfont.Font(family=family, size=size + 18, weight="bold"),
        "tab": tkfont.Font(family=family, size=size + 2),
    }
    root._fonts = fonts  # keep references alive

    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(".", background=BG, foreground=TEXT, fieldbackground=SURFACE_HI, bordercolor=LINE,
                    lightcolor=BG, darkcolor=BG, troughcolor=SURFACE, focuscolor=ACCENT, font=fonts["body"])
    style.configure("TFrame", background=BG)
    style.configure("Card.TFrame", background=SURFACE)
    style.configure("TLabel", background=BG, foreground=TEXT)
    style.configure("Footer.TLabel", foreground=MUTED)
    style.configure("TButton", background=SURFACE_HI, foreground=TEXT, padding=(14, 7), borderwidth=0)
    style.map("TButton", background=[("active", HOVER), ("pressed", PRESS)])
    style.configure("Accent.TButton", background=ACCENT, foreground=ON_ACCENT)
    style.map("Accent.TButton", background=[("active", ACCENT_HOVER), ("pressed", ACCENT_PRESS)])
    style.configure("TCheckbutton", background=BG, foreground=TEXT, indicatorbackground=SURFACE_HI,
                    indicatorforeground=TEXT)
    style.map("TCheckbutton", background=[("active", BG)], indicatorbackground=[("selected", ACCENT)])
    style.configure("TEntry", fieldbackground=SURFACE_HI, foreground=TEXT, insertcolor=TEXT, padding=6)
    style.configure("TCombobox", fieldbackground=SURFACE_HI, foreground=TEXT, background=SURFACE_HI, arrowcolor=TEXT)
    style.map("TCombobox", fieldbackground=[("readonly", SURFACE_HI)], foreground=[("readonly", TEXT)])
    style.configure("Vertical.TScrollbar", background=SURFACE_HI, troughcolor=SURFACE, arrowcolor=MUTED)


class FlatButton(tk.Label):
    """Flat button with hover, press and a keyboard focus ring. Space or Enter clicks it."""

    KINDS = {
        "primary": (ACCENT, ACCENT_HOVER, ACCENT_PRESS, ON_ACCENT),
        "secondary": (SURFACE_HI, HOVER, PRESS, TEXT),
        "ghost": (None, SURFACE_HI, HOVER, MUTED),
    }

    def __init__(self, parent, text: str, command, kind: str = "secondary", bg: str = SURFACE):
        base, hover, press, fg = self.KINDS[kind]
        base = base or bg
        super().__init__(parent, text=text, background=base, foreground=fg, padx=16, pady=7, cursor="hand2",
                         takefocus=1, highlightthickness=1, highlightbackground=base, highlightcolor=ACCENT,
                         font=parent.winfo_toplevel()._fonts["strong" if kind == "primary" else "body"])
        self._colors, self._command, self._inside = (base, hover, press), command, False
        self.bind("<Enter>", lambda e: self._paint(1, True))
        self.bind("<Leave>", lambda e: self._paint(0, False))
        self.bind("<ButtonPress-1>", lambda e: self._paint(2, True))
        self.bind("<ButtonRelease-1>", self._release)
        self.bind("<Return>", lambda e: self._command())
        self.bind("<space>", lambda e: self._command())

    def _paint(self, state: int, inside: bool) -> None:
        self._inside = inside
        self.configure(background=self._colors[state])

    def _release(self, _event) -> None:
        self._paint(1 if self._inside else 0, self._inside)
        if self._inside:
            self._command()


def section(parent, title: str, hint: str = "") -> tk.Frame:
    """A surface block with a title, optional hint, and a body frame to fill."""
    fonts = parent.winfo_toplevel()._fonts
    box = tk.Frame(parent, background=SURFACE, padx=20, pady=18)
    tk.Label(box, text=title, background=SURFACE, foreground=TEXT, font=fonts["h2"], anchor="w").pack(fill="x")
    if hint:
        tk.Label(box, text=hint, background=SURFACE, foreground=MUTED, font=fonts["small"], anchor="w",
                 justify="left").pack(fill="x", pady=(2, 0))
    body = tk.Frame(box, background=SURFACE)
    body.pack(fill="both", expand=True, pady=(14, 0))
    box.body = body
    return box


def themed_listbox(parent, **kw) -> tk.Listbox:
    return tk.Listbox(parent, background=SURFACE_HI, foreground=TEXT, selectbackground=ACCENT,
                      selectforeground=ON_ACCENT, highlightthickness=0, borderwidth=0, activestyle="none",
                      **{"font": parent.winfo_toplevel()._fonts["body"], **kw})


def dot(parent, color: str, bg: str, size: int = 10) -> tk.Canvas:
    c = tk.Canvas(parent, width=size, height=size, background=bg, highlightthickness=0)
    c.create_oval(1, 1, size - 1, size - 1, fill=color, outline="")
    return c


class App:
    TABS = ("Phone", "Apps", "Shortcuts", "Settings")

    def __init__(self, server: DockServer, events: "queue.Queue[tuple[str, str]]", minimized: bool = False,
                 updater=None, restart: threading.Event | None = None):
        from . import __version__

        self.server = server
        self.events = events
        self.updater = updater
        self.restart = restart or threading.Event()
        self.calls: "queue.Queue" = queue.Queue()  # work handed from threads to the Tk thread
        self.root = tk.Tk()
        self.root.title("Mobile Dock")
        self.pin_job = None
        self.update_label = None

        apply_theme(self.root)
        f = self.root._fonts
        self.root.minsize(760, 660)
        self.root.geometry("860x700")

        # Top bar: brand on the left, live connection on the right.
        top = tk.Frame(self.root, background=BG, padx=24, pady=18)
        top.pack(fill="x")
        mark = tk.Canvas(top, width=28, height=28, background=BG, highlightthickness=0)
        for x, y, color in ((2, 2, ACCENT), (15, 2, ACCENT), (2, 15, ACCENT), (15, 15, SIGNAL)):
            mark.create_rectangle(x, y, x + 11, y + 11, fill=color, outline="")  # four dock tiles
        mark.pack(side="left")
        tk.Label(top, text="Mobile Dock", background=BG, foreground=TEXT, font=f["brand"]).pack(side="left", padx=(10, 0))
        self.pill = tk.Frame(top, background=SURFACE, padx=14, pady=7)
        self.pill.pack(side="right")
        self.pill_dot = tk.Canvas(self.pill, width=10, height=10, background=SURFACE, highlightthickness=0)
        self.pill_dot.pack(side="left", padx=(0, 8))
        self.pill_text = tk.Label(self.pill, text="", background=SURFACE, foreground=MUTED, font=f["body"])
        self.pill_text.pack(side="left")

        # Tabs with a blue underline on the active one.
        tabbar = tk.Frame(self.root, background=BG, padx=24)
        tabbar.pack(fill="x")
        self.tab_labels, self.tab_lines, self.pages = [], [], []
        for i, name in enumerate(self.TABS):
            cell = tk.Frame(tabbar, background=BG)
            cell.pack(side="left", padx=(0, 22))
            lbl = tk.Label(cell, text=name, background=BG, foreground=MUTED, font=f["tab"], cursor="hand2",
                           takefocus=1, highlightthickness=0)
            lbl.pack(pady=(0, 6))
            line = tk.Frame(cell, background=BG, height=2)
            line.pack(fill="x")
            lbl.bind("<Button-1>", lambda e, k=i: self.show_tab(k))
            lbl.bind("<Return>", lambda e, k=i: self.show_tab(k))
            self.tab_labels.append(lbl)
            self.tab_lines.append(line)
        tk.Frame(self.root, background=LINE, height=1).pack(fill="x", padx=24)

        # Status line at the bottom.
        foot = tk.Frame(self.root, background=SURFACE, padx=24, pady=8)
        foot.pack(fill="x", side="bottom")
        self.status = tk.Label(foot, text="", background=SURFACE, foreground=MUTED, font=f["small"], anchor="w")
        self.status.pack(side="left", fill="x", expand=True)
        tk.Label(foot, text=f"v{__version__}", background=SURFACE, foreground=FAINT, font=f["small"]).pack(side="right")

        body = tk.Frame(self.root, background=BG, padx=24, pady=20)
        body.pack(fill="both", expand=True)
        for build in (self._phone_page, self._apps_page, self._pads_page, self._settings_page):
            page = tk.Frame(body, background=BG)
            build(page)
            self.pages.append(page)

        self.show_tab(0)
        self.refresh_devices()
        self.refresh_pinned()
        self.refresh_pads()
        if minimized:
            self.root.iconify()
        self.root.after(200, self.poll_events)

    # Pages -------------------------------------------------------------

    def _phone_page(self, page: tk.Frame) -> None:
        f = self.root._fonts
        addrs = ", ".join(local_addresses()) or "no network"
        pair = section(page, "Pair a phone", f"Open Mobile Dock on the phone and type this PIN.   "
                       f"{self.server.config.data['host_name']}  ·  {addrs}  ·  port {self.server.port}")
        pair.pack(fill="x", pady=(0, 16))
        digits = tk.Frame(pair.body, background=SURFACE)
        digits.pack(side="left")
        self.pin_boxes = []
        for i in range(6):
            box = tk.Label(digits, text="·", width=2, background=SURFACE_HI, foreground=FAINT, font=f["pin"])
            box.pack(side="left", padx=(0, 14 if i == 2 else 6))
            self.pin_boxes.append(box)
        side = tk.Frame(pair.body, background=SURFACE)
        side.pack(side="left", fill="both", expand=True, padx=(18, 0))
        self.pin_hint = tk.Label(side, text="No active PIN", background=SURFACE, foreground=MUTED,
                                 font=f["body"], anchor="w")
        self.pin_hint.pack(fill="x", pady=(2, 10))
        FlatButton(side, "Pair new phone", self.start_pairing, "primary").pack(anchor="w")
        FlatButton(side, "Use USB", self.usb, "ghost").pack(anchor="w", pady=(6, 0))

        phones = section(page, "Paired phones", "A connected phone shows an amber dot.")
        phones.pack(fill="both", expand=True)
        self.device_rows = phones.body

    def _apps_page(self, page: tk.Frame) -> None:
        apps = section(page, "Pinned apps", "The phone shows these tiles in this order.")
        apps.pack(fill="both", expand=True)
        wrap = tk.Frame(apps.body, background=SURFACE)
        wrap.pack(fill="both", expand=True)
        self.pinned = themed_listbox(wrap, height=10, font=self.root._fonts["tab"])
        self.pinned.pack(side="left", fill="both", expand=True)
        side = tk.Frame(wrap, background=SURFACE)
        side.pack(side="left", fill="y", padx=(14, 0))
        FlatButton(side, "Add apps", self.add_apps, "primary").pack(fill="x")
        FlatButton(side, "Move up", lambda: self.move_pinned(-1)).pack(fill="x", pady=(14, 0))
        FlatButton(side, "Move down", lambda: self.move_pinned(1)).pack(fill="x", pady=(6, 0))
        FlatButton(side, "Unpin", self.unpin, "ghost").pack(fill="x", pady=(14, 0))
        self.pinned_count = tk.Label(apps.body, text="", background=SURFACE, foreground=FAINT,
                                     font=self.root._fonts["small"], anchor="w")
        self.pinned_count.pack(fill="x", pady=(10, 0))

    def _pads_page(self, page: tk.Frame) -> None:
        pads = section(page, "Shortcut pads", "Buttons the phone shows for the app in front. "
                       "Write shortcuts like Ctrl+Shift+G.")
        pads.pack(fill="both", expand=True)
        head = tk.Frame(pads.body, background=SURFACE)
        head.pack(fill="x")
        FlatButton(head, "Edit shortcut pads", self.edit_pads, "primary").pack(side="left")
        self.pad_rows = tk.Frame(pads.body, background=SURFACE)
        self.pad_rows.pack(fill="both", expand=True, pady=(14, 0))

    def _settings_page(self, page: tk.Frame) -> None:
        f = self.root._fonts
        if sys.platform == "win32":
            start = section(page, "Start with Windows", "Keeps the dock ready after every login.")
            start.pack(fill="x", pady=(0, 16))
            self.autostart = tk.BooleanVar(value=_autostart_get())
            self.toggle = tk.Label(start.body, background=SURFACE, font=f["strong"], padx=16, pady=6,
                                   cursor="hand2", takefocus=1)
            self.toggle.pack(anchor="w")
            self.toggle.bind("<Button-1>", lambda e: self.flip_autostart())
            self.toggle.bind("<space>", lambda e: self.flip_autostart())
            self._paint_toggle()
        if self.updater is not None:
            up = section(page, "Updates", "Checks this project's GitHub releases at start and every 6 hours.")
            up.pack(fill="x")
            self.update_label = tk.Label(up.body, text=self.updater.status, background=SURFACE, foreground=TEXT,
                                         font=f["body"], anchor="w")
            self.update_label.pack(fill="x", pady=(0, 14))
            row = tk.Frame(up.body, background=SURFACE)
            row.pack(fill="x")
            FlatButton(row, "Check for updates", self.check_updates, "primary").pack(side="left")
            FlatButton(row, "Private repository token", self.set_token, "ghost").pack(side="left", padx=8)

    def show_tab(self, index: int) -> None:
        for i, page in enumerate(self.pages):
            on = i == index
            if on:
                page.pack(fill="both", expand=True)
            else:
                page.pack_forget()
            self.tab_labels[i].configure(foreground=TEXT if on else MUTED)
            self.tab_lines[i].configure(background=ACCENT if on else BG)

    def flip_autostart(self) -> None:
        self.autostart.set(not self.autostart.get())
        _autostart_set(self.autostart.get())
        self._paint_toggle()

    def _paint_toggle(self) -> None:
        on = self.autostart.get()
        self.toggle.configure(text="On" if on else "Off", background=ACCENT if on else SURFACE_HI,
                              foreground=ON_ACCENT if on else MUTED)

    # Phone page -----------------------------------------------------------

    def run(self) -> None:
        self.root.mainloop()

    def _show_pin(self, pin: str | None) -> None:
        for i, box in enumerate(self.pin_boxes):
            box.configure(text=pin[i] if pin else "·", foreground=TEXT if pin else FAINT)

    def start_pairing(self) -> None:
        self._show_pin(self.server.pairing.start())
        self.show_tab(0)
        if self.pin_job:
            self.root.after_cancel(self.pin_job)
        self.check_pin()

    def check_pin(self) -> None:
        import time as _time

        if self.server.pairing.current():
            left = max(0, int(self.server.pairing.expires - _time.monotonic()))
            self.pin_hint.configure(text=f"Expires in {left // 60}:{left % 60:02d}", foreground=SIGNAL)
            self.pin_job = self.root.after(1000, self.check_pin)
        else:
            self._show_pin(None)
            self.pin_hint.configure(text="No active PIN", foreground=MUTED)
            self.pin_job = None
            self.refresh_devices()

    def usb(self) -> None:
        def work():
            self.events.put(("info", enable_usb(self.server.port)))

        threading.Thread(target=work, daemon=True).start()

    def refresh_devices(self) -> None:
        import time as _time

        f = self.root._fonts
        self.device_ids = list(self.server.config.devices().items())
        live = {s["id"]: s for s in self.server.connected_sessions()}
        for child in self.device_rows.winfo_children():
            child.destroy()
        if not self.device_ids:
            tk.Label(self.device_rows, text="No phones yet. Click Pair new phone to add one.", background=SURFACE,
                     foreground=MUTED, font=f["body"], anchor="w").pack(fill="x")
        for device_id, name in self.device_ids:
            s = live.get(device_id)
            row = tk.Frame(self.device_rows, background=SURFACE_HI, padx=14, pady=10)
            row.pack(fill="x", pady=(0, 6))
            FlatButton(row, "Remove", lambda d=device_id, n=name: self.remove_device(d, n), "ghost",
                       bg=SURFACE_HI).pack(side="right")
            dot(row, SIGNAL if s else FAINT, SURFACE_HI).pack(side="left", padx=(0, 12))
            texts = tk.Frame(row, background=SURFACE_HI)
            texts.pack(side="left", fill="x", expand=True)
            tk.Label(texts, text=name, background=SURFACE_HI, foreground=TEXT, font=f["strong"], anchor="w").pack(fill="x")
            sub = (f"Connected  ·  {s['via']}  ·  since {_time.strftime('%H:%M', _time.localtime(s['since']))}"
                   if s else "Offline")
            tk.Label(texts, text=sub, background=SURFACE_HI, foreground=SIGNAL if s else MUTED, font=f["small"],
                     anchor="w", justify="left").pack(fill="x")
        self.show_connection(list(live.values()))

    def show_connection(self, live: list[dict]) -> None:
        if live:
            names = ", ".join(s["name"] for s in live)
            text, color, bg = f"Connected to {names}", SIGNAL, SIGNAL_BG
            self.root.title(f"Mobile Dock  ●  {names}")
        else:
            text, color, bg = "No phone connected", FAINT, SURFACE
            self.root.title("Mobile Dock")
        for w in (self.pill, self.pill_dot, self.pill_text):
            w.configure(background=bg)
        self.pill_text.configure(text=text, foreground=color if live else MUTED)
        self.pill_dot.delete("all")
        self.pill_dot.create_oval(1, 1, 9, 9, fill=color, outline="")

    def remove_device(self, device_id: str, name: str) -> None:
        if messagebox.askyesno("Mobile Dock", f"Remove {name}? It will need a new PIN to connect."):
            self.server.revoke(device_id)
            self.refresh_devices()

    def refresh_pads(self) -> None:
        f = self.root._fonts
        for child in self.pad_rows.winfo_children():
            child.destroy()
        for pad in self.server.config.data.get("pads", []):
            row = tk.Frame(self.pad_rows, background=SURFACE_HI, padx=14, pady=9)
            row.pack(fill="x", pady=(0, 6))
            tk.Label(row, text=pad["name"], background=SURFACE_HI, foreground=TEXT, font=f["strong"]).pack(side="left")
            apps = ", ".join(pad.get("apps", [])) or "when no other pad matches"
            tk.Label(row, text=f"  {apps}", background=SURFACE_HI, foreground=MUTED, font=f["small"]).pack(side="left")
            tk.Label(row, text=f"{len(pad.get('tiles', []))} shortcuts", background=SURFACE_HI, foreground=FAINT,
                     font=f["small"]).pack(side="right")

    # Pinned apps ----------------------------------------------------------

    def refresh_pinned(self) -> None:
        self.pinned_items = self.server.config.pinned()
        self.pinned.delete(0, "end")
        for app in self.pinned_items:
            self.pinned.insert("end", f"  {app['name']}")
        n = len(self.pinned_items)
        self.pinned_count.configure(text=f"{n} app{'s' if n != 1 else ''} pinned. Select one to move or unpin it.")

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
            self.refresh_pads()
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
        self.server.config.set_secret("github_token", token.strip())
        self.check_updates()

    def check_updates(self) -> None:
        self.update_label.config(text="Checking for updates")

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
                    self._show_pin(None)
                    self.pin_hint.configure(text=f"Paired with {text}", foreground=SIGNAL)
                    self.status.config(text=f"Paired with {text}")
                elif kind == "info":
                    messagebox.showinfo("Mobile Dock", text)
                elif kind == "file":
                    self.status.config(text=text)
                elif kind in ("update", "update_status") and self.update_label is not None:
                    self.update_label.config(text=text)
                self.refresh_devices()
        except queue.Empty:
            pass
        self.root.after(300, self.poll_events)
