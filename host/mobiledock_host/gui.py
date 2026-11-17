"""Small control window: pairing PIN, paired phones, USB and autostart."""

from __future__ import annotations

import logging
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
                value = f'"{sys.executable}" --background'
            else:
                pythonw = Path(sys.executable).with_name("pythonw.exe")
                exe = pythonw if pythonw.exists() else Path(sys.executable)
                value = f'"{exe}" -m mobiledock_host --background'
            winreg.SetValueEx(key, AUTOSTART_NAME, 0, winreg.REG_SZ, value)
        else:
            try:
                winreg.DeleteValue(key, AUTOSTART_NAME)
            except OSError:
                pass


# Apple's semantic colors, as on the phone. BG is the window, SURFACE a card on it, SURFACE_HI a fill
# for buttons and fields, LINE a hairline. use_palette sets them before any widget is built.
PALETTES = {
    "dark": dict(BG="#1C1C1E", SURFACE="#2C2C2E", SURFACE_HI="#3A3A3C", LINE="#48484A", TEXT="#FFFFFF",
                 MUTED="#98989F", FAINT="#636366", ACCENT="#0091FF", ACCENT_HOVER="#33A7FF", ACCENT_PRESS="#0074CC",
                 ON_ACCENT="#FFFFFF", SIGNAL="#FF9230", SIGNAL_BG="#3A2A1A", HOVER="#444446", PRESS="#505052",
                 GREEN="#30D158", SIDEBAR="#252527", SELECTED="#3A3A3C", DANGER="#FF4245"),
    "light": dict(BG="#F2F2F7", SURFACE="#FFFFFF", SURFACE_HI="#E9E9EE", LINE="#D1D1D6", TEXT="#000000",
                  MUTED="#6E6E73", FAINT="#AEAEB2", ACCENT="#0088FF", ACCENT_HOVER="#2E9BFF", ACCENT_PRESS="#006ECC",
                  ON_ACCENT="#FFFFFF", SIGNAL="#FF8D28", SIGNAL_BG="#FFF1E3", HOVER="#DEDEE3", PRESS="#D1D1D6",
                  GREEN="#34C759", SIDEBAR="#E8E8ED", SELECTED="#D9D9DE", DANGER="#FF383C"),
}
THEME_NAMES = ("Automatic", "Light", "Dark")
BG = SURFACE = SURFACE_HI = LINE = TEXT = MUTED = FAINT = ACCENT = ACCENT_HOVER = ACCENT_PRESS = ""
ON_ACCENT = SIGNAL = SIGNAL_BG = HOVER = PRESS = GREEN = SIDEBAR = SELECTED = DANGER = ""
DARK = True


def windows_dark() -> bool:
    """True when Windows apps use dark mode. Linux and errors count as dark."""
    if sys.platform != "win32":
        return True
    try:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize") as key:
            return winreg.QueryValueEx(key, "AppsUseLightTheme")[0] == 0
    except OSError:
        return True


def use_palette(mode: int = 0) -> None:
    """0 follows Windows, 1 is light, 2 is dark. Sets the module colors read by every widget."""
    global DARK
    DARK = mode == 2 or (mode == 0 and windows_dark())
    globals().update(PALETTES["dark" if DARK else "light"])


use_palette(0)

FONT_DIR = Path(__file__).with_name("fonts")
_fonts_loaded = False


def load_inter() -> bool:
    """Makes Inter available to this process only. Nothing is installed on the PC."""
    global _fonts_loaded
    if _fonts_loaded:
        return True
    if sys.platform == "win32":
        import ctypes

        private = 0x10  # FR_PRIVATE
        added = 0
        for name in ("Inter-Regular.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf"):
            path = FONT_DIR / name
            if path.exists():
                added += ctypes.windll.gdi32.AddFontResourceExW(str(path), private, 0)
        _fonts_loaded = added > 0
    return _fonts_loaded


def apply_theme(root: tk.Tk) -> None:
    """Apple style: Inter, the system colors, and flat controls."""
    from tkinter import font as tkfont

    root.configure(background=BG)
    base = tkfont.nametofont("TkDefaultFont")
    load_inter()
    families = set(tkfont.families(root))
    family = "Inter" if "Inter" in families else ("Segoe UI" if sys.platform == "win32" else base.cget("family"))
    semi = "Inter SemiBold" if "Inter SemiBold" in families else family
    semi_weight = "normal" if semi != family else "bold"
    base.configure(family=family)
    size = abs(int(base.cget("size"))) or 9
    fonts = {
        "body": tkfont.Font(family=family, size=size + 2),
        "small": tkfont.Font(family=family, size=size + 1),
        "strong": tkfont.Font(family=semi, size=size + 2, weight=semi_weight),
        "h2": tkfont.Font(family=semi, size=size + 4, weight=semi_weight),
        "brand": tkfont.Font(family=family, size=size + 6, weight="bold"),
        "large": tkfont.Font(family=family, size=size + 13, weight="bold"),
        "pin": tkfont.Font(family=family, size=size + 18, weight="bold"),
        "tab": tkfont.Font(family=family, size=size + 3),
        "tab_on": tkfont.Font(family=semi, size=size + 3, weight=semi_weight),
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
    style.configure("TButton", background=SURFACE_HI, foreground=ACCENT, padding=(14, 7), borderwidth=0)
    style.map("TButton", background=[("active", HOVER), ("pressed", PRESS)])
    style.configure("Accent.TButton", background=ACCENT, foreground=ON_ACCENT)
    style.map("Accent.TButton", background=[("active", ACCENT_HOVER), ("pressed", ACCENT_PRESS)])
    style.configure("TCheckbutton", background=BG, foreground=TEXT, indicatorbackground=SURFACE_HI,
                    indicatorforeground=TEXT)
    style.map("TCheckbutton", background=[("active", BG)], indicatorbackground=[("selected", ACCENT)])
    style.configure("TEntry", fieldbackground=SURFACE_HI, foreground=TEXT, insertcolor=TEXT, padding=6)
    style.configure("TCombobox", fieldbackground=SURFACE_HI, foreground=TEXT, background=SURFACE_HI, arrowcolor=TEXT)
    style.map("TCombobox", fieldbackground=[("readonly", SURFACE_HI)], foreground=[("readonly", TEXT)])
    style.configure("Treeview", background=SURFACE, fieldbackground=SURFACE, foreground=TEXT, bordercolor=SURFACE)
    style.map("Treeview", background=[("selected", ACCENT)], foreground=[("selected", ON_ACCENT)])
    style.configure("Treeview.Heading", background=SURFACE_HI, foreground=MUTED, relief="flat")
    style.configure("Vertical.TScrollbar", background=SURFACE_HI, troughcolor=BG, arrowcolor=MUTED,
                    bordercolor=BG, lightcolor=SURFACE_HI, darkcolor=SURFACE_HI, gripcount=0, arrowsize=12)
    style.map("Vertical.TScrollbar", background=[("active", HOVER)])


def rounded(canvas: tk.Canvas, x0, y0, x1, y1, r, **kw):
    """A rounded rectangle on a Tk canvas, drawn as a smoothed polygon."""
    r = max(0, min(r, (x1 - x0) / 2, (y1 - y0) / 2))
    pts = [x0 + r, y0, x1 - r, y0, x1, y0, x1, y0 + r, x1, y1 - r, x1, y1, x1 - r, y1, x0 + r, y1, x0, y1,
           x0, y1 - r, x0, y0 + r, x0, y0]
    return canvas.create_polygon(pts, smooth=True, splinesteps=24, **kw)


class FlatButton(tk.Canvas):
    """A capsule button like iOS 26: accent fill for the main action, gray fill or none for the rest.

    Drawn on a canvas because Tk has no rounded widgets. Hover, press, a focus ring, and Space or Enter.
    """

    def __init__(self, parent, text: str, command, kind: str = "secondary", bg: str | None = None):
        kinds = {
            "primary": (ACCENT, ACCENT_HOVER, ACCENT_PRESS, ON_ACCENT),
            "secondary": (SURFACE_HI, HOVER, PRESS, ACCENT),
            "ghost": (None, SURFACE_HI, HOVER, ACCENT),
            "danger": (None, HOVER, PRESS, DANGER),  # red text for actions that remove something
        }
        base, hover, press, fg = kinds[kind]
        self._bg = bg or parent.cget("background")
        font = parent._root()._fonts["strong"]
        width = font.measure(text) + 36
        height = font.metrics("linespace") + 16
        super().__init__(parent, width=width, height=height, background=self._bg, highlightthickness=0,
                         borderwidth=0, cursor="hand2", takefocus=1)
        self._colors = (base or self._bg, hover, press)
        self._fg, self._command, self._inside, self.enabled = fg, command, False, True
        self._shape = rounded(self, 1, 1, width - 1, height - 1, height / 2, fill=self._colors[0], outline="")
        self._label = self.create_text(width / 2, height / 2, text=text, fill=fg, font=font)
        self.bind("<Enter>", lambda e: self._paint(1, True))
        self.bind("<Leave>", lambda e: self._paint(0, False))
        self.bind("<ButtonPress-1>", lambda e: self._paint(2, True))
        self.bind("<ButtonRelease-1>", self._release)
        self.bind("<Return>", lambda e: self._run())
        self.bind("<space>", lambda e: self._run())
        self.bind("<FocusIn>", lambda e: self.itemconfigure(self._shape, outline=ACCENT, width=2))
        self.bind("<FocusOut>", lambda e: self.itemconfigure(self._shape, outline="", width=1))

    def set_enabled(self, on: bool) -> None:
        """A disabled button stays visible but greyed out, so you can see what becomes possible."""
        self.enabled = on
        self.itemconfigure(self._label, fill=self._fg if on else FAINT)
        self.configure(cursor="hand2" if on else "arrow")
        self._paint(0, False)

    def _run(self) -> None:
        if self.enabled:
            self._command()

    def _paint(self, state: int, inside: bool) -> None:
        self._inside = inside
        self.itemconfigure(self._shape, fill=self._colors[state if self.enabled else 0])

    def _release(self, _event) -> None:
        self._paint(1 if self._inside else 0, self._inside)
        if self._inside:
            self._run()


class Switch(tk.Canvas):
    """Apple's switch: green when on, a white knob. Click, Space or Enter flips it."""

    def __init__(self, parent, on: bool, command, bg: str | None = None):
        bg = bg or parent.cget("background")
        super().__init__(parent, width=51, height=31, background=bg, highlightthickness=0, borderwidth=0,
                         cursor="hand2", takefocus=1)
        self.on, self._command = on, command
        self._track = rounded(self, 0, 0, 51, 31, 15.5, fill=GREEN if on else SURFACE_HI, outline="")
        self._knob = self.create_oval(0, 0, 27, 27, fill="#FFFFFF", outline="")
        self._place()
        for seq in ("<Button-1>", "<space>", "<Return>"):
            self.bind(seq, lambda e: self.flip())

    def _place(self) -> None:
        x = 22 if self.on else 2
        self.coords(self._knob, x, 2, x + 27, 29)
        self.itemconfigure(self._track, fill=GREEN if self.on else SURFACE_HI)

    def set(self, on: bool) -> None:
        self.on = on
        self._place()

    def flip(self) -> None:
        self.set(not self.on)
        self._command(self.on)


class Card(tk.Canvas):
    """A rounded card, like an iOS grouped list. Put content in .body; the card grows to fit it."""

    def __init__(self, parent, padx: int = 20, pady: int = 18, radius: int = 14):
        bg = parent.cget("background")
        super().__init__(parent, background=bg, highlightthickness=0, borderwidth=0, height=40)
        self._pad, self._r = (padx, pady), radius
        self._shape = rounded(self, 0, 0, 10, 10, radius, fill=SURFACE, outline="")
        self.body = tk.Frame(self, background=SURFACE)
        self._win = self.create_window(padx, pady, window=self.body, anchor="nw")
        self.body.bind("<Configure>", lambda e: self._fit(), add="+")
        self.bind("<Configure>", lambda e: self._fit())

    def _fit(self) -> None:
        w = self.winfo_width()
        h = self.body.winfo_reqheight() + 2 * self._pad[1]
        if int(float(self.cget("height"))) != h:
            self.configure(height=h)
        self.itemconfigure(self._win, width=max(1, w - 2 * self._pad[0]))
        self.coords(self._shape, *self._points(w, h))

    def _points(self, w, h):
        r, x1, y1 = self._r, w - 1, h - 1
        return [r, 0, x1 - r, 0, x1, 0, x1, r, x1, y1 - r, x1, y1, x1 - r, y1, r, y1, 0, y1, 0, y1 - r, 0, r, 0, 0]


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
        self.canvas.bind("<Leave>", self._leave)
        self._seen = None
        self._watch()

    def _leave(self, event) -> None:
        # Tk sends Leave when the pointer moves onto a card or label inside the page too.
        # Keep the wheel while the pointer is still anywhere on this page.
        over = self.winfo_containing(event.x_root, event.y_root)
        if over is None or not str(over).startswith(str(self)):
            self.canvas.unbind_all("<MouseWheel>")

    def _watch(self) -> None:
        # Wrapped text, display scaling and new rows make the content taller without a resize event.
        # So the page checks its height a few times a second and turns the scrollbar on when needed.
        if self.winfo_ismapped():
            now = (self.inner.winfo_reqheight(), self.canvas.winfo_height(), self.canvas.winfo_width())
            if now != self._seen:
                self._seen = now
                self._fit()
        self.after(250, self._watch)

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


def wrapping(label: tk.Label) -> tk.Label:
    """Lets a gray hint wrap to the width it gets, instead of being cut off at the edge."""
    label.bind("<Configure>", lambda e: label.configure(wraplength=max(80, e.width - 4)), add="+")
    return label


def section(parent, title: str, hint: str = "") -> tk.Frame:
    """A heading in sentence case above a rounded card, like an iOS grouped list. Fill .body."""
    fonts = parent._root()._fonts
    outer = tk.Frame(parent, background=parent.cget("background"))
    tk.Label(outer, text=title, background=outer.cget("background"), foreground=TEXT, font=fonts["h2"],
             anchor="w").pack(fill="x", padx=4)
    if hint:
        wrapping(tk.Label(outer, text=hint, background=outer.cget("background"), foreground=MUTED,
                          font=fonts["small"], anchor="w", justify="left")).pack(fill="x", padx=4, pady=(2, 0))
    card = Card(outer)
    card.pack(fill="both", expand=True, pady=(8, 0))
    outer.body = card.body
    return outer


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

    def __init__(self, server: DockServer, events: "queue.Queue[tuple[str, str]]", hidden: bool = False,
                 updater=None, restart: threading.Event | None = None, tray=None):
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
        self.tray = tray  # Windows tray icon, or None
        self.hidden = False
        self.notes_waiting = False
        self.root.protocol("WM_DELETE_WINDOW", self.close_window)

        self.root.minsize(600, 440)
        screen_w, screen_h = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        self.root.geometry(f"{min(900, screen_w - 60)}x{min(700, screen_h - 100)}")
        self.say_job = None
        self.tab = 0
        self._build()
        if hidden:
            self.hide()
        self.root.after(200, self.poll_events)
        self.root.after(900, self.whats_new)

    def _build(self) -> None:
        """Builds the whole window. Called again when you change Appearance, so it applies at once."""
        from . import __version__

        use_palette(int(self.server.config.data.get("theme", 0)))
        for child in self.root.winfo_children():
            child.destroy()
        apply_theme(self.root)
        f = self.root._fonts

        # Sidebar on the left, like macOS System Settings: the brand, the phone connection, then the pages.
        side = tk.Frame(self.root, background=SIDEBAR, width=210, padx=12, pady=16)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        brand = tk.Frame(side, background=SIDEBAR)
        brand.pack(fill="x", padx=6)
        mark = tk.Canvas(brand, width=26, height=26, background=SIDEBAR, highlightthickness=0)
        for x, y, color in ((1, 1, ACCENT), (14, 1, ACCENT), (1, 14, ACCENT), (14, 14, SIGNAL)):
            rounded(mark, x, y, x + 11, y + 11, 3, fill=color, outline="")  # four dock tiles
        mark.pack(side="left")
        tk.Label(brand, text="Mobile Dock", background=SIDEBAR, foreground=TEXT, font=f["strong"]).pack(
            side="left", padx=(10, 0))
        self.pill = tk.Frame(side, background=SIDEBAR)
        self.pill.pack(fill="x", padx=6, pady=(10, 14))
        self.pill_dot = tk.Canvas(self.pill, width=10, height=10, background=SIDEBAR, highlightthickness=0)
        self.pill_dot.pack(side="left", padx=(2, 8))
        self.pill_text = tk.Label(self.pill, text="", background=SIDEBAR, foreground=MUTED, font=f["small"],
                                  anchor="w")
        self.pill_text.pack(side="left", fill="x", expand=True)
        self.tab_rows = []
        for i, name in enumerate(self.TABS):
            row = tk.Canvas(side, height=36, background=SIDEBAR, highlightthickness=0, cursor="hand2", takefocus=1)
            row.pack(fill="x", pady=1)
            row.bind("<Configure>", lambda e, k=i: self._paint_row(k))
            row.bind("<Button-1>", lambda e, k=i: self.show_tab(k))
            row.bind("<Return>", lambda e, k=i: self.show_tab(k))
            self.root.bind(f"<Control-Key-{i + 1}>", lambda e, k=i: self.show_tab(k))
            row.name = name
            self.tab_rows.append(row)
        tk.Label(side, text=f"Version {__version__}", background=SIDEBAR, foreground=FAINT, font=f["small"],
                 anchor="w").pack(side="bottom", fill="x", padx=6)
        tk.Frame(self.root, background=LINE, width=1).pack(side="left", fill="y")

        # Status line at the bottom of the content.
        main = tk.Frame(self.root, background=BG)
        main.pack(side="left", fill="both", expand=True)
        foot = tk.Frame(main, background=BG, padx=28, pady=8)
        foot.pack(fill="x", side="bottom")
        self.status = tk.Label(foot, text="", background=BG, foreground=MUTED, font=f["small"], anchor="w")
        self.status.pack(side="left", fill="x", expand=True)

        body = tk.Frame(main, background=BG, padx=28, pady=18)
        body.pack(fill="both", expand=True)
        self.pages = []
        for name, build in zip(self.TABS, (self._phone_page, self._apps_page, self._pads_page,
                                            self._settings_page)):
            page = ScrollPage(body)
            tk.Label(page.inner, text=name, background=BG, foreground=TEXT, font=f["large"], anchor="w").pack(
                fill="x", pady=(0, 10))
            build(page.inner)
            self.pages.append(page)

        self.show_tab(self.tab)
        self.refresh_devices()
        self.refresh_pinned()
        self.refresh_pads()

    def _paint_row(self, index: int) -> None:
        """A sidebar row: the selected page gets a rounded gray highlight, like macOS."""
        row = self.tab_rows[index]
        on = index == self.tab
        w = row.winfo_width()
        row.delete("all")
        if on:
            rounded(row, 0, 0, w, 36, 9, fill=SELECTED, outline="")
        f = self.root._fonts
        row.create_text(14, 18, text=row.name, anchor="w", fill=TEXT, font=f["tab_on" if on else "tab"])

    # Pages -------------------------------------------------------------

    def _phone_page(self, page: tk.Frame) -> None:
        f = self.root._fonts
        self.addresses = local_addresses()
        addrs = ", ".join(self.addresses) or "no network"
        pair = section(page, "Pair a phone", f"This PC: {self.server.config.data['host_name']}  ·  {addrs}  ·  "
                       f"port {self.server.port}")
        pair.pack(fill="x", pady=(0, 16))
        # The instruction sits above the PIN, where you look, not in the status line at the bottom.
        wrapping(tk.Label(pair.body, text="Scan the QR code with the phone's camera, or tap this PC on the phone "
                          "and type the PIN.", background=SURFACE, foreground=TEXT, font=f["body"], anchor="w",
                          justify="left")).pack(side="top", fill="x", pady=(0, 12))
        digits = self.pin_digits = tk.Frame(pair.body, background=SURFACE)
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
            # The QR code needs about 540 px next to the PIN. Below that it moves under the buttons.
            qr_under = event.width < 540
            if qr_under != getattr(self, "pair_narrow", None):
                self.pair_narrow = qr_under
                if self.qr.winfo_manager():
                    self.qr.pack_forget()
                    self._pack_qr()
            if narrow != getattr(side, "narrow", None):
                side.narrow = narrow
                qr_shown = bool(self.qr.winfo_manager())
                self.qr.pack_forget()
                side.pack_forget()
                if narrow:
                    side.pack(side="top", fill="x", pady=(14, 0))
                    digits.pack_configure(side="top", anchor="w")
                else:
                    digits.pack_configure(side="left")
                    side.pack(side="left", fill="both", expand=True, padx=(18, 0))
                if qr_shown:
                    self._pack_qr()

        pair.body.bind("<Configure>", reflow, add="+")

        phones = section(page, "Paired phones", "A connected phone shows a green dot.")
        phones.pack(fill="both", expand=True)
        self.device_rows = phones.body

    def _apps_page(self, page: tk.Frame) -> None:
        apps = section(page, "Pinned apps", "The phone shows these tiles in this order. Use the arrows to move one.")
        apps.pack(fill="both", expand=True)
        top = tk.Frame(apps.body, background=SURFACE)
        top.pack(fill="x", pady=(0, 10))
        FlatButton(top, "Add apps", self.add_apps, "primary").pack(side="left")
        self.pinned_count = tk.Label(top, text="", background=SURFACE, foreground=MUTED,
                                     font=self.root._fonts["small"], anchor="w")
        self.pinned_count.pack(side="left", padx=(14, 0))
        # One row per app, like the pad rows, with the arrows and Unpin on the row itself.
        self.pinned_rows = tk.Frame(apps.body, background=SURFACE)
        self.pinned_rows.pack(fill="both", expand=True)

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
        look = section(page, "Appearance", "Automatic follows the Windows light or dark setting.")
        look.pack(fill="x", pady=(0, 16))
        choice = tk.Frame(look.body, background=SURFACE)
        choice.pack(anchor="w")
        current = int(self.server.config.data.get("theme", 0))
        for i, name in enumerate(THEME_NAMES):
            FlatButton(choice, name, lambda k=i: self.set_theme(k), "primary" if i == current else "secondary").pack(
                side="left", padx=(0, 8))

        general = section(page, "General")
        general.pack(fill="x", pady=(0, 16))
        if sys.platform == "win32":
            self.autostart = tk.BooleanVar(value=_autostart_get())
            self.toggle = self._switch_row(general.body, "Start with Windows",
                                           "Keeps the dock ready after every login, in the tray.",
                                           self.autostart.get(), lambda on: self.flip_autostart())
            tk.Frame(general.body, background=LINE, height=1).pack(fill="x", pady=10)
        where = "the tray icon next to the clock" if self.tray else "Mobile Dock in the Start menu or app list"
        self.keep_toggle = self._switch_row(general.body, "Keep running in the background",
                                            f"Your phone stays connected. Open the window from {where}.",
                                            self.server.config.data.get("close_to_tray", True),
                                            lambda on: self.flip_keep_running())
        quit_row = tk.Frame(general.body, background=SURFACE)
        quit_row.pack(fill="x", pady=(12, 0))
        FlatButton(quit_row, "Quit Mobile Dock", self.quit).pack(side="left")
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
        self.tab = index
        for i, page in enumerate(self.pages):
            if i == index:
                page.pack(fill="both", expand=True)
            else:
                page.pack_forget()
        for i in range(len(self.tab_rows)):
            self._paint_row(i)

    def _switch_row(self, parent, title: str, hint: str, on: bool, command) -> Switch:
        """A setting with a label, a gray hint under it, and an iOS switch on the right."""
        f = self.root._fonts
        row = tk.Frame(parent, background=SURFACE)
        row.pack(fill="x")
        sw = Switch(row, on, command)
        sw.pack(side="right", padx=(12, 0))
        text = tk.Frame(row, background=SURFACE)
        text.pack(side="left", fill="x", expand=True)
        tk.Label(text, text=title, background=SURFACE, foreground=TEXT, font=f["body"], anchor="w").pack(fill="x")
        wrapping(tk.Label(text, text=hint, background=SURFACE, foreground=MUTED, font=f["small"], anchor="w",
                          justify="left")).pack(fill="x")
        return sw

    def set_theme(self, mode: int) -> None:
        """Saves Automatic, Light or Dark and rebuilds the window in it at once."""
        self.server.config.data["theme"] = mode
        self.server.config.save()
        self._build()
        self.show_tab(len(self.TABS) - 1)

    def flip_autostart(self) -> None:
        self.autostart.set(not self.autostart.get())
        _autostart_set(self.autostart.get())
        self.toggle.set(self.autostart.get())

    def flip_keep_running(self) -> None:
        data = self.server.config.data
        data["close_to_tray"] = not data.get("close_to_tray", True)
        self.server.config.save()
        self.keep_toggle.set(data["close_to_tray"])

    # Background -----------------------------------------------------------

    def close_window(self) -> None:
        """The X button: hide and keep the server running, unless the setting says quit."""
        if not self.server.config.data.get("close_to_tray", True):
            self.quit()
            return
        self.hide()
        data = self.server.config.data
        if not data.get("close_notice_shown"):
            data["close_notice_shown"] = True
            self.server.config.save()
            where = "Click the tray icon" if self.tray else "Start Mobile Dock again"
            self.tell("Mobile Dock is still running", f"Your phone stays connected. {where} to open the window.")

    def hide(self) -> None:
        if self.tray is None and sys.platform == "win32":  # no tray icon to come back from: minimize instead
            self.root.iconify()
            return
        self.hidden = True
        self.root.withdraw()

    def show(self) -> None:
        """Brings the window back from the background, in front of other windows."""
        self.hidden = False
        self.root.deiconify()
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(200, lambda: self.root.attributes("-topmost", False))
        self.root.focus_force()
        if self.notes_waiting:
            self.notes_waiting = False
            self.whats_new(force=True)

    def pair_and_show(self) -> None:
        self.show()
        self.start_pairing()

    def quit(self) -> None:
        self.root.destroy()

    def tell(self, title: str, text: str) -> None:
        """A message for when the window may be hidden: a tray notification, else a desktop notification."""
        if self.tray is not None and self.tray.added:
            self.tray.notify(title, text)
        elif sys.platform.startswith("linux"):
            import shutil
            import subprocess

            if shutil.which("notify-send"):
                subprocess.run(["notify-send", title, text], check=False)

    def run(self) -> None:
        self.root.mainloop()

    def whats_new(self, force: bool = False) -> None:
        """Shows the release notes once after an update. A first install only remembers the version.

        After an update the app starts in the background, so the notes wait until you open the window.
        """
        from . import __version__

        seen = self.server.config.data.get("seen_version")
        if seen == __version__ and not force:
            return
        self.server.config.data["seen_version"] = __version__
        self.server.config.save()
        try:
            from ._notes import NOTES
        except ImportError:  # source installs have no notes file
            return
        if not (seen or force) or not NOTES:
            return
        if self.hidden:
            self.notes_waiting = True
            self.tell(f"Mobile Dock {__version__}", "Updated. Open the window to see what is new.")
        else:
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
            self._pack_qr()

    def _pack_qr(self) -> None:
        # Beside the PIN on a wide window, under the buttons on a narrow one, so it never covers them.
        if getattr(self, "pair_narrow", False):
            self.qr.pack(side="top", anchor="w", pady=(14, 0))
        else:
            self.qr.pack(side="right", anchor="n", padx=(12, 0), before=self.pin_digits)

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
            FlatButton(row, "Remove", lambda d=device_id, n=name: self.remove_device(d, n), "danger",
                       bg=SURFACE_HI).pack(side="right")
            dot(row, GREEN if s else FAINT, SURFACE_HI).pack(side="left", padx=(0, 12))
            texts = tk.Frame(row, background=SURFACE_HI)
            texts.pack(side="left", fill="x", expand=True)
            tk.Label(texts, text=name, background=SURFACE_HI, foreground=TEXT, font=f["strong"], anchor="w").pack(fill="x")
            sub = (f"Connected  ·  {s['via']}  ·  since {_time.strftime('%H:%M', _time.localtime(s['since']))}"
                   if s else "Offline")
            tk.Label(texts, text=sub, background=SURFACE_HI, foreground=MUTED, font=f["small"],
                     anchor="w", justify="left").pack(fill="x")
        self.show_connection(list(live.values()))

    def show_connection(self, live: list[dict]) -> None:
        if live:
            names = ", ".join(s["name"] for s in live)
            short = names if len(names) <= 22 else names[:21] + "…"
            text, color = short, GREEN
            self.root.title(f"Mobile Dock  ●  {names}")
        else:
            text, color = "No phone connected", FAINT
            self.root.title("Mobile Dock")
        self.pill_text.configure(text=text, foreground=TEXT if live else MUTED)
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
        f = self.root._fonts
        self.pinned_items = self.server.config.pinned()
        for child in self.pinned_rows.winfo_children():
            child.destroy()
        n = len(self.pinned_items)
        self.pinned_count.configure(text=f"{n} app{'s' if n != 1 else ''} pinned" if n else
                                    "Nothing pinned yet. Add apps to choose what the phone shows.")
        for i, app in enumerate(self.pinned_items):
            row = tk.Frame(self.pinned_rows, background=SURFACE_HI, padx=14, pady=6)
            row.pack(fill="x", pady=(0, 6))
            FlatButton(row, "Unpin", lambda k=i: self.unpin(k), "danger", bg=SURFACE_HI).pack(side="right")
            down = FlatButton(row, "↓", lambda k=i: self.move_pinned(k, 1), bg=SURFACE_HI)
            down.pack(side="right", padx=(0, 6))
            up = FlatButton(row, "↑", lambda k=i: self.move_pinned(k, -1), bg=SURFACE_HI)
            up.pack(side="right")
            up.set_enabled(i > 0)
            down.set_enabled(i < n - 1)
            tk.Label(row, text=app["name"], background=SURFACE_HI, foreground=TEXT, font=f["strong"],
                     anchor="w").pack(side="left", fill="x", expand=True)

    def move_pinned(self, i: int, step: int) -> None:
        items, j = list(self.pinned_items), i + step
        if not 0 <= j < len(items):
            return
        items[i], items[j] = items[j], items[i]
        self.server.set_pinned(items)
        self.refresh_pinned()

    def unpin(self, i: int) -> None:
        name = self.pinned_items[i]["name"]
        self.server.set_pinned([a for k, a in enumerate(self.pinned_items) if k != i])
        self.refresh_pinned()
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
                if not apps_box.winfo_exists():  # the editor was closed before the list arrived
                    return
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
                fn = self.calls.get_nowait()
            except queue.Empty:
                break
            try:
                fn()
            except Exception:  # noqa: BLE001 - one failed call must not stop this loop, the tray depends on it
                logging.getLogger(__name__).exception("A queued window update failed")
        try:
            while True:
                kind, text = self.events.get_nowait()
                if self.hidden and kind in ("paired", "info", "update"):
                    self.tell("Mobile Dock", text if kind != "paired" else f"Paired with {text}")
                    if kind == "info":
                        continue
                if kind == "paired":
                    self._show_pin(None)
                    self.pin_hint.configure(text=f"Paired with {text}", foreground=GREEN)
                    self.status.config(text=f"Paired with {text}")
                elif kind == "info":  # only shown while the window is open
                    messagebox.showinfo("Mobile Dock", text)
                elif kind == "file":
                    self.status.config(text=text)
                elif kind in ("update", "update_status") and self.update_label is not None:
                    self.update_label.config(text=text)
                self.refresh_devices()
        except queue.Empty:
            pass
        self.root.after(300, self.poll_events)
