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
    style.configure("Vertical.TScrollbar", background=SURFACE_HI, troughcolor=BG, arrowcolor=MUTED,
                    bordercolor=BG, lightcolor=SURFACE_HI, darkcolor=SURFACE_HI, gripcount=0, arrowsize=12)
    style.map("Vertical.TScrollbar", background=[("active", HOVER)])


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
                         font=parent._root()._fonts["strong" if kind == "primary" else "body"])
        self._colors, self._command, self._inside, self._fg, self.enabled = (base, hover, press), command, False, fg, True
        self.bind("<Enter>", lambda e: self._paint(1, True))
        self.bind("<Leave>", lambda e: self._paint(0, False))
        self.bind("<ButtonPress-1>", lambda e: self._paint(2, True))
        self.bind("<ButtonRelease-1>", self._release)
        self.bind("<Return>", lambda e: self._run())
        self.bind("<space>", lambda e: self._run())

    def set_enabled(self, on: bool) -> None:
        """A disabled button stays visible but greyed out, so you can see what becomes possible."""
        self.enabled = on
        self.configure(foreground=self._fg if on else FAINT, cursor="hand2" if on else "arrow")
        self._paint(0, False)

    def _run(self) -> None:
        if self.enabled:
            self._command()

    def _paint(self, state: int, inside: bool) -> None:
        self._inside = inside
        self.configure(background=self._colors[state if self.enabled else 0])

    def _release(self, _event) -> None:
        self._paint(1 if self._inside else 0, self._inside)
        if self._inside:
            self._run()


class ScrollPage(tk.Frame):
    """A page that scrolls when the window is too small for it. The scrollbar only shows when needed."""

    def __init__(self, parent):
        super().__init__(parent, background=BG)
        self.canvas = tk.Canvas(self, background=BG, highlightthickness=0, borderwidth=0)
        self.bar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.bar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.inner = tk.Frame(self.canvas, background=BG)
        self.window = self.canvas.create_window(0, 0, window=self.inner, anchor="nw")
        self.inner.bind("<Configure>", lambda e: self._fit())
        self.canvas.bind("<Configure>", lambda e: self._fit())
        self.canvas.bind("<Enter>", lambda e: self.canvas.bind_all("<MouseWheel>", self._wheel))
        self.canvas.bind("<Leave>", lambda e: self.canvas.unbind_all("<MouseWheel>"))

    def _fit(self) -> None:
        width, height = self.canvas.winfo_width(), self.canvas.winfo_height()
        need = self.inner.winfo_reqheight()
        # Fill the window when there is room, so lists can stretch. Scroll when there is not.
        self.canvas.itemconfigure(self.window, width=width, height=max(height, need))
        self.canvas.configure(scrollregion=(0, 0, width, max(height, need)))
        if need > height + 1:
            if not self.bar.winfo_ismapped():
                self.bar.pack(side="right", fill="y", before=self.canvas)
        elif self.bar.winfo_ismapped():
            self.bar.pack_forget()
            self.canvas.yview_moveto(0)

    def _wheel(self, event) -> None:
        # A list scrolls itself. Scrolling the page too would move both at once.
        if isinstance(event.widget, tk.Listbox):
            return
        if self.bar.winfo_ismapped():
            self.canvas.yview_scroll(int(-event.delta / 120) or (-1 if event.delta > 0 else 1), "units")


def section(parent, title: str, hint: str = "") -> tk.Frame:
    """A surface block with a title, optional hint, and a body frame to fill."""
    fonts = parent._root()._fonts
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
                      **{"font": parent._root()._fonts["body"], **kw})


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
        self.root.minsize(560, 420)
        screen_w, screen_h = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        self.root.geometry(f"{min(860, screen_w - 60)}x{min(700, screen_h - 100)}")
        self.say_job = None

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
            self.root.bind(f"<Control-Key-{i + 1}>", lambda e, k=i: self.show_tab(k))
            self.tab_labels.append(lbl)
            self.tab_lines.append(line)
        tk.Frame(self.root, background=LINE, height=1).pack(fill="x", padx=24)

        # Status line at the bottom.
        foot = tk.Frame(self.root, background=SURFACE, padx=24, pady=8)
        foot.pack(fill="x", side="bottom")
        self.status = tk.Label(foot, text="", background=SURFACE, foreground=MUTED, font=f["small"], anchor="w")
        self.status.pack(side="left", fill="x", expand=True)
        tk.Label(foot, text=f"v{__version__}", background=SURFACE, foreground=FAINT, font=f["small"]).pack(side="right")

        body = tk.Frame(self.root, background=BG, padx=24, pady=16)
        body.pack(fill="both", expand=True)
        for build in (self._phone_page, self._apps_page, self._pads_page, self._settings_page):
            page = ScrollPage(body)
            build(page.inner)
            self.pages.append(page)

        self.show_tab(0)
        self.refresh_devices()
        self.refresh_pinned()
        self.refresh_pads()
        if minimized:
            self.root.iconify()
        self.root.after(200, self.poll_events)
        self.root.after(900, self.whats_new)

    # Pages -------------------------------------------------------------

    def _phone_page(self, page: tk.Frame) -> None:
        f = self.root._fonts
        self.addresses = local_addresses()
        addrs = ", ".join(self.addresses) or "no network"
        pair = section(page, "Pair a phone", f"This PC: {self.server.config.data['host_name']}  ·  {addrs}  ·  "
                       f"port {self.server.port}")
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
        self.pin_hint = tk.Label(side, text="Click Pair new phone to get a PIN.", background=SURFACE,
                                 foreground=MUTED, font=f["body"], anchor="w", justify="left")
        self.pin_hint.pack(fill="x", pady=(2, 10))
        FlatButton(side, "Pair new phone", self.start_pairing, "primary").pack(anchor="w")
        more = tk.Frame(side, background=SURFACE)
        more.pack(anchor="w", pady=(6, 0))
        FlatButton(more, "Use USB", self.usb, "ghost").pack(side="left")
        if self.addresses:
            FlatButton(more, "Copy address", self.copy_address, "ghost").pack(side="left")
        # While a PIN is active, a QR code holds the address and PIN. The phone's camera opens the app with it.
        self.qr = tk.Canvas(pair.body, width=148, height=148, background=SURFACE, highlightthickness=0)

        def reflow(event, digits=digits, side=side):
            # On a narrow window the buttons move under the PIN instead of getting cut off.
            narrow = event.width < 600
            if narrow != getattr(side, "narrow", None):
                side.narrow = narrow
                side.pack_forget()
                if narrow:
                    side.pack(side="top", fill="x", pady=(14, 0))
                    digits.pack_configure(side="top", anchor="w")
                else:
                    digits.pack_configure(side="left")
                    side.pack(side="left", fill="both", expand=True, padx=(18, 0))

        pair.body.bind("<Configure>", reflow)

        phones = section(page, "Paired phones", "A connected phone shows an amber dot.")
        phones.pack(fill="both", expand=True)
        self.device_rows = phones.body

    def _apps_page(self, page: tk.Frame) -> None:
        apps = section(page, "Pinned apps", "The phone shows these tiles in this order.")
        apps.pack(fill="both", expand=True)
        wrap = tk.Frame(apps.body, background=SURFACE)
        wrap.pack(fill="both", expand=True)
        side = tk.Frame(wrap, background=SURFACE)
        side.pack(side="right", fill="y", padx=(14, 0))
        self.pinned = themed_listbox(wrap, height=8, font=self.root._fonts["tab"], exportselection=False)
        self.pinned.pack(side="left", fill="both", expand=True)
        FlatButton(side, "Add apps", self.add_apps, "primary").pack(fill="x")
        self.app_buttons = [
            FlatButton(side, "Move up", lambda: self.move_pinned(-1)),
            FlatButton(side, "Move down", lambda: self.move_pinned(1)),
            FlatButton(side, "Unpin", self.unpin, "ghost"),
        ]
        self.app_buttons[0].pack(fill="x", pady=(14, 0))
        self.app_buttons[1].pack(fill="x", pady=(6, 0))
        self.app_buttons[2].pack(fill="x", pady=(14, 0))
        self.pinned.bind("<<ListboxSelect>>", lambda e: self._app_selection())
        self.pinned.bind("<Delete>", lambda e: self.unpin())
        self.pinned.bind("<Alt-Up>", lambda e: self.move_pinned(-1))
        self.pinned.bind("<Alt-Down>", lambda e: self.move_pinned(1))
        self.pinned_count = tk.Label(apps.body, text="", background=SURFACE, foreground=FAINT,
                                     font=self.root._fonts["small"], anchor="w")
        self.pinned_count.pack(fill="x", pady=(10, 0))

    def _app_selection(self) -> None:
        sel = self.pinned.curselection()
        n = len(getattr(self, "pinned_items", []))
        self.app_buttons[0].set_enabled(bool(sel) and sel[0] > 0)
        self.app_buttons[1].set_enabled(bool(sel) and sel[0] < n - 1)
        self.app_buttons[2].set_enabled(bool(sel))

    def _pads_page(self, page: tk.Frame) -> None:
        pads = section(page, "Shortcut pads", "Buttons the phone shows for the app in front. "
                       "Click a pad to edit it.")
        pads.pack(fill="both", expand=True)
        head = tk.Frame(pads.body, background=SURFACE)
        head.pack(fill="x")
        FlatButton(head, "Edit shortcut pads", self.edit_pads, "primary").pack(side="left")
        FlatButton(head, "Search presets", self.edit_search).pack(side="left", padx=8)
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
        backup = section(page, "Backup and help", "Your pinned apps and shortcut pads, in one file. "
                         "Pairing keys are never included. Diagnostics stay on this PC until you paste them.")
        backup.pack(fill="x", pady=(16, 0))
        brow = tk.Frame(backup.body, background=SURFACE)
        brow.pack(fill="x")
        FlatButton(brow, "Export setup", self.export_setup).pack(side="left")
        FlatButton(brow, "Import setup", self.import_setup).pack(side="left", padx=8)
        FlatButton(brow, "Copy diagnostics", self.copy_diagnostics, "ghost").pack(side="left")

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

    def whats_new(self) -> None:
        """Shows the release notes once after an update. A first install only remembers the version."""
        from . import __version__

        seen = self.server.config.data.get("seen_version")
        if seen == __version__:
            return
        self.server.config.data["seen_version"] = __version__
        self.server.config.save()
        try:
            from ._notes import NOTES
        except ImportError:  # source installs have no notes file
            return
        if seen and NOTES:
            messagebox.showinfo(f"What's new in Mobile Dock {__version__}", NOTES, parent=self.root)

    def _show_pin(self, pin: str | None) -> None:
        for i, box in enumerate(self.pin_boxes):
            box.configure(text=pin[i] if pin else "·", foreground=TEXT if pin else FAINT)
        self._show_qr(pin)

    def _show_qr(self, pin: str | None) -> None:
        from urllib.parse import urlencode

        self.qr.delete("all")
        if not pin or not self.addresses:
            self.qr.pack_forget()
            return
        try:
            import segno
        except ImportError:
            return
        query = urlencode({"h": self.addresses[0], "p": self.server.port, "pin": pin,
                           "n": self.server.config.data["host_name"][:40]})
        code = segno.make(f"mobiledock://pair?{query}", error="m")
        rows = list(code.matrix)
        quiet, size = 2, 148
        cell = size / (len(rows) + quiet * 2)
        self.qr.create_rectangle(0, 0, size, size, fill="#FFFFFF", outline="")
        for y, row in enumerate(rows):
            for x, dark in enumerate(row):
                if dark:
                    x0, y0 = (x + quiet) * cell, (y + quiet) * cell
                    self.qr.create_rectangle(x0, y0, x0 + cell, y0 + cell, fill="#000000", outline="")
        if not self.qr.winfo_ismapped():
            self.qr.pack(side="right", anchor="n", padx=(12, 0))

    def start_pairing(self) -> None:
        self._show_pin(self.server.pairing.start())
        self.show_tab(0)
        self.say("On the phone, scan the QR code with the camera, or tap this PC and type the PIN.")
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
            self.pin_hint.configure(text="Click Pair new phone to get a PIN.", foreground=MUTED)
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
            steps = ("No phones yet. To add one:\n"
                     "1. Install Mobile Dock on the phone and open it.\n"
                     "2. Click Pair new phone here.\n"
                     "3. Tap this PC on the phone and type the PIN.")
            tk.Label(self.device_rows, text=steps, background=SURFACE, foreground=MUTED, font=f["body"],
                     anchor="w", justify="left").pack(fill="x")
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
            short = names if len(names) <= 28 else names[:27] + "…"
            text, color, bg = f"Connected to {short}", SIGNAL, SIGNAL_BG
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
            self.say(f"Removed {name}")

    def refresh_pads(self) -> None:
        f = self.root._fonts
        for child in self.pad_rows.winfo_children():
            child.destroy()
        for index, pad in enumerate(self.server.config.data.get("pads", [])):
            row = tk.Frame(self.pad_rows, background=SURFACE_HI, padx=14, pady=9, cursor="hand2")
            row.pack(fill="x", pady=(0, 6))
            top = tk.Frame(row, background=SURFACE_HI)
            top.pack(fill="x")
            name = tk.Label(top, text=pad["name"], background=SURFACE_HI, foreground=TEXT, font=f["strong"])
            name.pack(side="left")
            count = tk.Label(top, text=f"{len(pad.get('tiles', []))} shortcuts", background=SURFACE_HI,
                             foreground=FAINT, font=f["small"])
            count.pack(side="right")
            apps = pad.get("apps", [])
            # Long app lists get shortened, so the row never runs off a small screen.
            text = ", ".join(apps[:3]) + (f" and {len(apps) - 3} more" if len(apps) > 3 else "") if apps \
                else "Used when no other pad matches"
            sub = tk.Label(row, text=text, background=SURFACE_HI, foreground=MUTED, font=f["small"], anchor="w",
                           justify="left")
            sub.pack(fill="x", pady=(2, 0))
            sub.bind("<Configure>", lambda e, w=sub: w.configure(wraplength=max(120, e.width)))
            for w in (row, top, name, count, sub):
                w.bind("<Button-1>", lambda e, i=index: self.edit_pads(i))
                w.bind("<Enter>", lambda e, r=row: self._hover_row(r, True))
                w.bind("<Leave>", lambda e, r=row: self._hover_row(r, False))

    @staticmethod
    def _hover_row(row: tk.Frame, on: bool) -> None:
        color = HOVER if on else SURFACE_HI
        stack = [row]
        while stack:
            w = stack.pop()
            w.configure(background=color)
            stack.extend(w.winfo_children())

    def say(self, text: str) -> None:
        """Short feedback in the status line. It fades after a few seconds."""
        self.status.configure(text=text)
        if self.say_job:
            self.root.after_cancel(self.say_job)
        self.say_job = self.root.after(6000, lambda: self.status.configure(text=""))

    def export_setup(self) -> None:
        import json
        from tkinter import filedialog

        path = filedialog.asksaveasfilename(parent=self.root, title="Export setup", defaultextension=".json",
                                            initialfile="mobile-dock-setup.json",
                                            filetypes=[("Mobile Dock setup", "*.json")])
        if not path:
            return
        try:
            Path(path).write_text(json.dumps(self.server.export_setup(), indent=2), encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Mobile Dock", f"Could not save the file: {exc}", parent=self.root)
            return
        self.say(f"Saved your setup to {Path(path).name}")

    def import_setup(self) -> None:
        import json
        from tkinter import filedialog

        path = filedialog.askopenfilename(parent=self.root, title="Import setup",
                                          filetypes=[("Mobile Dock setup", "*.json")])
        if not path:
            return
        if not messagebox.askyesno("Mobile Dock", "Replace your pinned apps and shortcut pads with the ones in "
                                   "this file?", parent=self.root):
            return
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
            result = self.server.import_setup(data)
        except (OSError, ValueError) as exc:
            messagebox.showerror("Mobile Dock", f"Could not import: {exc}", parent=self.root)
            return
        self.refresh_pinned()
        self.refresh_pads()
        self.say(result)

    def copy_diagnostics(self) -> None:
        import platform as _platform

        from . import __version__
        from .config import config_dir

        lines = [f"Mobile Dock host {__version__}", f"{_platform.platform()}  Python {_platform.python_version()}",
                 f"Phones connected: {len(self.server.connected_sessions())}"]
        if self.updater is not None:
            lines.append(f"Updates: {self.updater.status}")
        try:
            log = (config_dir() / "mobile-dock.log").read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            log = []
        lines += ["", "Errors:"] + (log[-120:] or ["none"])
        self.root.clipboard_clear()
        self.root.clipboard_append("\n".join(lines))
        self.say("Diagnostics copied. Nothing was sent.")

    def copy_address(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(self.addresses[0])
        self.say(f"Copied {self.addresses[0]}. On the phone, use Connect by address.")

    # Pinned apps ----------------------------------------------------------

    def refresh_pinned(self) -> None:
        self.pinned_items = self.server.config.pinned()
        self.pinned.delete(0, "end")
        for app in self.pinned_items:
            self.pinned.insert("end", f"  {app['name']}")
        n = len(self.pinned_items)
        if n:
            self.pinned_count.configure(text=f"{n} app{'s' if n != 1 else ''} pinned. Select one to move or unpin "
                                             "it. Alt+Up and Alt+Down move it, Delete unpins it.")
        else:
            self.pinned_count.configure(text="Nothing pinned yet. Click Add apps to choose what the phone shows.")
        self._app_selection()

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
        self.pinned.see(j)
        self._app_selection()

    def unpin(self) -> None:
        sel = self.pinned.curselection()
        if sel:
            name = self.pinned_items[sel[0]]["name"]
            self.server.set_pinned([a for k, a in enumerate(self.pinned_items) if k != sel[0]])
            self.refresh_pinned()
            if self.pinned_items:
                self.pinned.selection_set(min(sel[0], len(self.pinned_items) - 1))
                self._app_selection()
            self.say(f"Unpinned {name}")

    def add_apps(self) -> None:
        win = tk.Toplevel(self.root)
        win.title("Add apps")
        win.minsize(380, 360)
        win.configure(background=BG)
        ttk.Label(win, text="Search your apps. Double-click one to pin it, or select several and click "
                            "Pin selected.", wraplength=380, justify="left").pack(anchor="w", padx=16, pady=(16, 6))
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

        def add(*_):
            pinned = self.server.config.pinned()
            have = {a["id"] for a in pinned}
            names = []
            for i in listbox.curselection():
                if i < len(shown) and shown[i]["id"] not in have:
                    pinned.append(dict(shown[i]))
                    names.append(shown[i]["name"])
            if not names:
                return
            self.server.set_pinned(pinned)
            self.refresh_pinned()
            fill()
            done.configure(text=f"Pinned {', '.join(names)}")
            self.say(f"Pinned {', '.join(names)}")

        def loaded(result):
            apps.extend(result)
            fill()

        query.trace_add("write", fill)
        listbox.bind("<Double-Button-1>", add)
        listbox.bind("<Return>", add)
        bottom = ttk.Frame(win)
        bottom.pack(fill="x", padx=16, pady=(0, 16))
        done = ttk.Label(bottom, text="", style="Footer.TLabel")
        done.pack(side="left")
        ttk.Button(bottom, text="Pin selected", style="Accent.TButton", command=add).pack(side="right")
        ttk.Button(bottom, text="Done", command=win.destroy).pack(side="right", padx=8)
        win.bind("<Escape>", lambda e: win.destroy())
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

    def edit_search(self) -> "tk.Toplevel":
        """Search presets: what the phone's Search page does with the text you type."""
        import base64
        import copy

        from . import search as searchlib

        data = copy.deepcopy(self.server.search_presets())
        stored = dict(self.server.config.data.get("search_icons", {}))
        new_icons: dict[str, str] = {}
        images: dict[str, tk.PhotoImage] = {}
        small: dict[str, tk.PhotoImage] = {}
        win = tk.Toplevel(self.root)
        win.title("Search presets")
        win.minsize(720, 600)
        win.geometry("860x620")
        win.configure(background=BG)
        body = ttk.Frame(win, padding=16)
        body.pack(fill="both", expand=True)

        left = ttk.Frame(body)
        left.pack(side="left", fill="y")
        style = ttk.Style(win)
        style.configure("Search.Treeview", background=SURFACE_HI, fieldbackground=SURFACE_HI, foreground=TEXT,
                        rowheight=30, borderwidth=0)
        style.map("Search.Treeview", background=[("selected", ACCENT)], foreground=[("selected", ON_ACCENT)])
        tree = ttk.Treeview(left, style="Search.Treeview", columns=("keyword",), show="tree headings", height=10,
                            selectmode="browse")
        tree.heading("#0", text="Preset")
        tree.heading("keyword", text="Keyword")
        tree.column("#0", width=200)
        tree.column("keyword", width=64)
        tree.pack(fill="y", expand=True)
        lbtn = ttk.Frame(left)
        lbtn.pack(fill="x", pady=(6, 0))
        lbtn2 = ttk.Frame(left)
        lbtn2.pack(fill="x", pady=(4, 0))

        mid = ttk.Frame(body)
        mid.pack(side="left", fill="both", expand=True, padx=(16, 0))
        name_var, key_var, apps_var, action_var = tk.StringVar(), tk.StringVar(), tk.StringVar(), tk.StringVar()
        browser_var, default_var = tk.StringVar(), tk.BooleanVar()
        for label, var in (("Name", name_var), ("Keyword, for example yt (optional)", key_var),
                           ("Apps it belongs to, comma separated (optional)", apps_var)):
            ttk.Label(mid, text=label).pack(anchor="w", pady=(6, 0))
            ttk.Entry(mid, textvariable=var).pack(fill="x")
        ttk.Label(mid, text="Action. {q} is the text you type").pack(anchor="w", pady=(6, 0))
        ttk.Entry(mid, textvariable=action_var).pack(fill="x")
        ttk.Label(mid, text="Web: open https://www.youtube.com/results?search_query={q}\nIn the app in front: "
                            "Ctrl+L | type {q} | Enter", style="Footer.TLabel", justify="left").pack(anchor="w")
        row = ttk.Frame(mid)
        row.pack(fill="x", pady=(8, 0))
        ttk.Label(row, text="Browser").pack(side="left")
        ttk.Combobox(row, textvariable=browser_var, values=searchlib.BROWSERS, state="readonly",
                     width=10).pack(side="left", padx=(6, 16))
        ttk.Checkbutton(row, text="Use when nothing else matches", variable=default_var).pack(side="left")

        ttk.Label(mid, text="Icon").pack(anchor="w", pady=(10, 0))
        icon_row = ttk.Frame(mid)
        icon_row.pack(fill="x")
        preview = tk.Label(icon_row, background=SURFACE_HI, width=48, height=48)
        preview.pack(side="left")
        icon_note = ttk.Label(icon_row, text="", style="Footer.TLabel")
        icon_note.pack(side="left", padx=8)
        icon_btns = ttk.Frame(mid)
        icon_btns.pack(fill="x", pady=(6, 0))
        icon_btns2 = ttk.Frame(mid)
        icon_btns2.pack(fill="x", pady=(6, 0))
        apps_box = ttk.Combobox(icon_btns2, width=24, state="readonly")
        builtin_box = ttk.Combobox(icon_btns2, width=12, state="readonly", values=searchlib.BUILTIN_ICONS)
        state = {"i": 0}

        def current():
            return data[state["i"]] if data else None

        def image_for(pid):
            b64 = new_icons.get(pid) or stored.get(pid)
            if not b64:
                return None
            try:
                images[pid] = tk.PhotoImage(data=b64)
            except tk.TclError:
                return None
            return images[pid]

        def small_image_for(pid):
            img = image_for(pid)
            if img is None:
                return None
            small[pid] = img.subsample(max(1, img.width() // 24))  # about 24 px, to fit a list row
            return small[pid]

        def show_list(select=0):
            tree.delete(*tree.get_children())
            for i, p in enumerate(data):
                img = small_image_for(p["id"])
                tree.insert("", "end", iid=str(i), text=("  " + p["name"]) + ("   (default)" if p.get("default") else ""),
                            values=(p.get("keyword", ""),), **({"image": img} if img else {}))
            if data:
                state["i"] = max(0, min(select, len(data) - 1))
                tree.selection_set(str(state["i"]))
                show_one()

        def show_one():
            p = current()
            if not p:
                return
            name_var.set(p["name"])
            key_var.set(p.get("keyword", ""))
            apps_var.set(", ".join(p.get("apps", [])))
            action_var.set(p.get("action", ""))
            browser_var.set(p.get("browser", "default"))
            default_var.set(bool(p.get("default")))
            img = image_for(p["id"])
            if img:
                preview.configure(image=img, text="", width=48, height=48)
            else:
                preview.configure(image="", text="line", foreground=MUTED, width=6, height=2)
            icon = p.get("icon", {})
            icon_note.configure(text={"site": "From the site", "app": f"App icon: {icon.get('match', '')}",
                                      "file": "Your picture", "builtin": f"Line icon: {icon.get('value', '')}"}
                                .get(icon.get("kind"), ""))

        def keep():
            p = current()
            if p:
                p.update(name=name_var.get().strip() or p["name"], keyword=key_var.get().strip().lower(),
                         apps=[a.strip() for a in apps_var.get().split(",") if a.strip()],
                         action=action_var.get().strip(), browser=browser_var.get() or "default",
                         default=default_var.get())
                if p["default"]:
                    for other in data:
                        if other is not p:
                            other["default"] = False

        def pick(_):
            sel = tree.selection()
            if sel and int(sel[0]) != state["i"]:
                keep()
                state["i"] = int(sel[0])
                show_one()

        def set_icon(spec, png=None):
            p = current()
            p["icon"] = spec
            new_icons.pop(p["id"], None)
            stored.pop(p["id"], None)
            if png:
                new_icons[p["id"]] = base64.b64encode(png).decode()
            show_one()
            show_list(state["i"])

        def fetch_site():
            p = current()
            keep()
            if not searchlib.home(p):
                messagebox.showinfo("Search presets", "This preset does not open a web page, so it has no site icon.",
                                    parent=win)
                return
            icon_note.configure(text="Fetching from the site...")

            def work(preset=dict(p)):
                png = searchlib.fetch_site_icon(preset)
                self.later(lambda: set_icon({"kind": "site"}, png) if png else
                           icon_note.configure(text="The site had no icon. Pick another."))

            threading.Thread(target=work, daemon=True).start()

        def use_app(_):
            name = apps_box.get()
            app = next((a for a in getattr(apps_box, "apps", []) if a["name"] == name), None)
            if not app:
                return

            def work():
                png = self.server.platform.app_icon(app["id"])
                self.later(lambda: set_icon({"kind": "app", "match": name}, png))

            threading.Thread(target=work, daemon=True).start()

        def choose_image():
            from tkinter import filedialog

            path = filedialog.askopenfilename(parent=win, title="Choose an icon",
                                              filetypes=[("Pictures", "*.png *.ico *.jpg *.jpeg *.gif *.bmp")])
            if not path:
                return
            try:
                png = searchlib.to_png(Path(path).read_bytes())
            except Exception as exc:  # noqa: BLE001
                messagebox.showerror("Search presets", f"That picture could not be read: {exc}", parent=win)
                return
            set_icon({"kind": "file"}, png)

        def use_builtin(_):
            set_icon({"kind": "builtin", "value": builtin_box.get()})

        def new():
            keep()
            n = 1
            while any(p["id"] == f"custom{n}" for p in data):
                n += 1
            data.append({"id": f"custom{n}", "name": f"My search {n}", "keyword": "", "apps": [],
                         "action": "open https://www.google.com/search?q={q}", "browser": "default",
                         "icon": {"kind": "builtin", "value": "search"}, "default": False})
            show_list(len(data) - 1)

        def delete():
            p = current()
            if p and len(data) > 1 and messagebox.askyesno("Search presets", f"Delete {p['name']}?", parent=win):
                del data[state["i"]]
                show_list(0)

        def move(step):
            keep()
            i, j = state["i"], state["i"] + step
            if 0 <= j < len(data):
                data[i], data[j] = data[j], data[i]
                show_list(j)

        def reset():
            if messagebox.askyesno("Search presets", "Replace all presets with the built-in ones?", parent=win):
                data[:] = searchlib.defaults()
                new_icons.clear()
                stored.clear()
                show_list(0)

        def save():
            keep()
            try:
                self.server.set_search(data, new_icons)
            except ValueError as exc:
                messagebox.showerror("Search presets", str(exc), parent=win)
                return
            self.say("Search presets saved")
            win.destroy()

        def load_apps():
            try:
                apps = self.server.platform.list_apps()
            except Exception:  # noqa: BLE001
                apps = []

            def fill():
                apps_box.apps = apps
                apps_box.configure(values=[a["name"] for a in apps])
                apps_box.set("Use an app's icon")

            self.later(fill)

        ttk.Button(icon_btns, text="Fetch from site", command=fetch_site).pack(side="left")
        ttk.Button(icon_btns, text="Choose image", command=choose_image).pack(side="left", padx=6)
        apps_box.pack(side="left")
        builtin_box.pack(side="left", padx=6)
        builtin_box.set("Line icon")
        apps_box.bind("<<ComboboxSelected>>", use_app)
        builtin_box.bind("<<ComboboxSelected>>", use_builtin)
        ttk.Button(lbtn, text="New", command=new).pack(side="left")
        ttk.Button(lbtn, text="Delete", command=delete).pack(side="left", padx=4)
        ttk.Button(lbtn2, text="Up", command=lambda: move(-1)).pack(side="left")
        ttk.Button(lbtn2, text="Down", command=lambda: move(1)).pack(side="left", padx=4)
        ttk.Button(left, text="Reset to built-in", command=reset).pack(fill="x", pady=(6, 0))
        bottom = ttk.Frame(mid)
        bottom.pack(fill="x", side="bottom", pady=(12, 0))
        ttk.Label(bottom, text="Site icons come once from the site itself.\nThen they stay on this PC.",
                  style="Footer.TLabel", justify="left").pack(side="left")
        ttk.Button(bottom, text="Save", style="Accent.TButton", command=save).pack(side="right")
        tree.bind("<<TreeviewSelect>>", pick)
        win.bind("<Escape>", lambda e: win.destroy())
        show_list(0)
        threading.Thread(target=load_apps, daemon=True).start()
        win.edit_state = {"data": data, "keep": keep, "save": save, "new": new, "set_icon": set_icon}  # for tests
        return win

    def edit_pads(self, select: int = 0) -> None:
        from . import pads as padlib
        import copy

        data = copy.deepcopy(self.server.config.data["pads"])
        win = tk.Toplevel(self.root)
        win.title("Shortcut pads")
        win.minsize(600, 420)
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
        ttk.Label(edit_row, text="Action").pack(side="left")
        ttk.Entry(edit_row, textvariable=keys_var, width=34).pack(side="left", padx=(6, 0), fill="x", expand=True)
        ttk.Label(mid, text="Shortcuts: Ctrl+Shift+G, or Win+Left, Enter. More steps with | between them:\n"
                            "open https://figma.com  |  run C:\\Tools\\app.exe  |  type Hello  |  wait 1",
                  style="Footer.TLabel", justify="left").pack(anchor="w", pady=(4, 0))
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
                padlib.parse_action(keys_var.get())
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
        show_pads(select)
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
