"""TCP server, LAN discovery and command handling."""

from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import logging
import os
import secrets
import socket
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from typing import Callable

from . import pads
from . import search
from . import protocol as p
from .config import Config, config_dir
from .perf import PerfMonitor
from . import __version__
from .platforms.base import SYSTEM_ACTIONS
from .platforms import Platform, PlatformError

log = logging.getLogger("mobiledock")

HANDSHAKE_TIMEOUT = 15
IDLE_TIMEOUT = 60
MAX_SESSIONS = 4
PIN_LIFETIME = 180
PIN_ATTEMPTS = 5
FAIL_WINDOW = 300
FAIL_LIMIT = 8


MAX_UPLOAD = 200 * 1024 * 1024


def safe_name(name: str) -> str:
    """Keeps a plain file name. No folders, no device names, no control characters."""
    name = name.replace("\\", "/").split("/")[-1]
    name = "".join(ch for ch in name if ch.isprintable() and ch not in '<>:"|?*').strip(" .")
    stem = name.rsplit(".", 1)[0].upper() if name else ""
    if not name or stem in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(10)),
                            *(f"LPT{i}" for i in range(10))}:
        name = "file-from-phone"
    return name[:120]


_CGNAT = ipaddress.ip_network("100.64.0.0/10")  # also used by VPNs such as Tailscale


def is_local(ip: str) -> bool:
    """True for addresses that can only come from your own network: LAN, loopback (USB), link-local, VPN."""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if getattr(addr, "ipv4_mapped", None):
        addr = addr.ipv4_mapped
    return addr.is_private or addr.is_loopback or addr.is_link_local or (addr.version == 4 and addr in _CGNAT)


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem, suffix = path.stem, path.suffix
    for i in range(2, 1000):
        candidate = path.with_name(f"{stem} ({i}){suffix}")
        if not candidate.exists():
            return candidate
    return path.with_name(f"{stem} {secrets.token_hex(3)}{suffix}")


class Pairing:
    """One-time PIN shown on the PC. It expires and dies after a few wrong tries."""

    def __init__(self):
        self.lock = threading.Lock()
        self.handshake = threading.Lock()
        self.pin: str | None = None
        self.expires = 0.0
        self.attempts = 0

    def start(self) -> str:
        with self.lock:
            self.pin = f"{secrets.randbelow(1_000_000):06d}"
            self.expires = time.monotonic() + PIN_LIFETIME
            self.attempts = 0
            return self.pin

    def cancel(self) -> None:
        with self.lock:
            self.pin = None

    def current(self) -> str | None:
        with self.lock:
            if self.pin and time.monotonic() > self.expires:
                self.pin = None
            return self.pin

    def failed(self) -> None:
        with self.lock:
            self.attempts += 1
            if self.attempts >= PIN_ATTEMPTS:
                self.pin = None


class Session:
    def __init__(self, server: "DockServer", sock: socket.socket, addr):
        self.server = server
        self.sock = sock
        self.addr = addr
        self.device_id = ""
        self.device_name = ""
        self.connected_at = 0.0
        self.channel: p.SecureChannel | None = None
        self.send_lock = threading.Lock()
        self.closed = False
        self.upload: dict | None = None

    # Handshake ------------------------------------------------------------

    def run(self) -> None:
        try:
            self.sock.settimeout(HANDSHAKE_TIMEOUT)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            hello = p.decode_json(p.recv_frame(self.sock))
            if hello.get("v") != p.PROTOCOL_VERSION:
                return self._plain_error("version", "Update the app on your phone and PC")
            if self.server.throttled(self.addr[0]):
                return self._plain_error("throttled", "Too many failed attempts. Wait a few minutes.")
            if hello.get("t") == "pair":
                ok = self._pair(hello)
            elif hello.get("t") == "hello":
                ok = self._auth(hello)
            else:
                ok = False
            if not ok:
                return
            self.server.session_started(self)
            self.sock.settimeout(IDLE_TIMEOUT)
            self._loop()
        except (OSError, ConnectionError, p.ProtocolError, ValueError, KeyError, TypeError) as exc:
            log.info("connection %s ended: %s", self.addr[0], exc)
        finally:
            self.close()
            self.server.session_ended(self)

    def _plain_error(self, code: str, message: str) -> None:
        p.send_frame(self.sock, p.encode_json({"t": "error", "code": code, "message": message}))

    def _pair(self, hello: dict) -> bool:
        pairing = self.server.pairing
        if not pairing.handshake.acquire(blocking=False):
            self._plain_error("busy", "Another phone is pairing")
            return False
        try:
            pin = pairing.current()
            if not pin:
                self._plain_error("not_pairing", "Click Pair new phone on the PC first")
                return False
            x_hex = str(hello["X"])
            if len(x_hex) > 2 * p.ELEMENT_LEN:
                raise p.ProtocolError("element too long")
            spake = p.Spake2(pin, "server")
            keys = spake.finish(int(x_hex, 16))
            p.send_frame(self.sock, p.encode_json({"t": "pair_r", "Y": format(spake.public, "x")}))
            confirm = p.decode_json(p.recv_frame(self.sock))
            got = bytes.fromhex(str(confirm.get("c", "")))
            if confirm.get("t") != "pair_c" or not hmac.compare_digest(got, keys.client_confirm):
                pairing.failed()
                self.server.record_failure(self.addr[0])
                self._plain_error("bad_pin", "Wrong PIN")
                return False
            pairing.cancel()
            self.device_name = str(hello.get("name", "Android phone"))[:64]
            self.device_id, secret = self.server.config.add_device(self.device_name)
            p.send_frame(self.sock, p.encode_json({"t": "pair_ok", "c": keys.server_confirm.hex()}))
            self.channel = p.SecureChannel(self.sock, keys.session, "server")
            self.channel.send({
                "t": "paired",
                "device_id": self.device_id,
                "secret": secret.hex(),
                **self.server.identity(),
            })
            self.server.notify("paired", self.device_name)
            return True
        finally:
            pairing.handshake.release()

    def _auth(self, hello: dict) -> bool:
        nc = bytes.fromhex(str(hello.get("nc", "")))
        if len(nc) != 32:
            raise p.ProtocolError("bad nonce")
        ns = os.urandom(32)
        # The challenge names this PC so a phone paired with several PCs can pick the right key.
        p.send_frame(self.sock, p.encode_json({"t": "challenge", "ns": ns.hex(), **self.server.identity()}))
        reply = p.decode_json(p.recv_frame(self.sock))
        device_id = str(reply.get("device_id", ""))[:32]
        secret = self.server.config.device_secret(device_id)
        # Compute with a dummy secret for unknown ids so timing does not leak which ids exist.
        client_mac, server_mac, session = p.reconnect_keys(secret or b"\x00" * 32, device_id, nc, ns)
        got = bytes.fromhex(str(reply.get("mac", "")))
        if secret is None or reply.get("t") != "auth" or not hmac.compare_digest(got, client_mac):
            self.server.record_failure(self.addr[0])
            self._plain_error("auth_failed", "This phone is not paired. Pair again.")
            return False
        p.send_frame(self.sock, p.encode_json({"t": "auth_ok", "mac": server_mac.hex()}))
        self.channel = p.SecureChannel(self.sock, session, "server")
        self.device_id = device_id
        self.device_name = self.server.config.devices().get(device_id, "phone")
        return True

    # Commands -------------------------------------------------------------

    def send(self, obj: dict) -> None:
        with self.send_lock:
            if not self.closed:
                self.channel.send(obj)

    def _loop(self) -> None:
        while not self.closed:
            msg = self.channel.recv()
            req_id = msg.get("id")
            cmd = str(msg.get("t", ""))
            if cmd in self.server.SLOW:
                self.server.pool.submit(self._handle, req_id, cmd, msg)
            else:
                self._handle(req_id, cmd, msg)

    def _handle(self, req_id, cmd: str, msg: dict) -> None:
        try:
            result = self.server.dispatch(cmd, msg, self)
            response = {"ok": True, **(result or {})}
        except PlatformError as exc:
            response = {"ok": False, "error": str(exc)}
        except Exception:  # noqa: BLE001 - never kill the session on a bad command
            log.exception("command %s failed", cmd)
            response = {"ok": False, "error": "Command failed on the PC"}
        if req_id is not None:
            try:
                self.send({"re": req_id, **response})
            except OSError:
                self.close()

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        if self.upload:  # drop a half-received file
            self.upload["fh"].close()
            self.upload["tmp"].unlink(missing_ok=True)
            self.upload = None
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class DockServer:
    SLOW = {"search.run", "search.icon", "pad.run", "mixer.list", "mixer.set", "clipboard.get_text", "clipboard.set_text", "host.info",
            "update.refresh", "system", "apps.available", "apps.launch", "apps.pin", "apps.icon", "screenshot", "record.start", "record.stop",
            "media", "media.status", "media.art", "media.seek", "media.jump", "media.shuffle", "media.repeat", "update.chunk"}

    def __init__(self, config: Config, platform: Platform, notify: Callable[[str, str], None] | None = None):
        self.config = config
        self.platform = platform
        self.pairing = Pairing()
        self.pool = ThreadPoolExecutor(max_workers=4)
        self.sessions: list[Session] = []
        self.lock = threading.Lock()
        self.failures: dict[str, list[float]] = {}
        self._replies: dict[str, list[float]] = {}
        self._notify = notify or (lambda kind, text: None)
        self._stop = threading.Event()
        self._sockets: list[socket.socket] = []
        self.port = int(config.data["port"])
        self.updater = None  # set by the entry point when auto updates are on
        self.boot = secrets.token_hex(6)
        self.perf = PerfMonitor()
        if not isinstance(config.data.get("pads"), list) or not config.data["pads"]:
            config.data["pads"] = pads.defaults()
        if not isinstance(config.data.get("search"), list) or not config.data["search"]:
            config.data["search"] = search.defaults()
        self.context = {"app": "", "title": "", "pad": pads.match(config.data["pads"], "")}

    def notify(self, kind: str, text: str) -> None:
        try:
            self._notify(kind, text)
        except Exception:  # noqa: BLE001
            log.exception("notify failed")

    def identity(self) -> dict:
        # "boot" changes every time the host starts, so a phone can tell the PC app was restarted.
        return {"host_id": self.config.data["host_id"], "host_name": self.config.data["host_name"],
                "os": self.platform.name, "boot": self.boot, "version": __version__}

    # Abuse limits ---------------------------------------------------------

    def record_failure(self, ip: str) -> None:
        with self.lock:
            self.failures.setdefault(ip, []).append(time.monotonic())

    def throttled(self, ip: str) -> bool:
        now = time.monotonic()
        with self.lock:
            recent = [t for t in self.failures.get(ip, []) if now - t < FAIL_WINDOW]
            self.failures[ip] = recent
            return len(recent) >= FAIL_LIMIT

    def _allowed(self, ip: str) -> bool:
        """Refuses the internet. Only your own network may connect, unless you turn that off."""
        return bool(self.config.data.get("allow_public_networks", False)) or is_local(ip)

    def _reply_ok(self, ip: str) -> bool:
        """At most 5 discovery replies per second per address, so the PC cannot be used to flood others."""
        now = time.monotonic()
        with self.lock:
            stamps = [t for t in self._replies.get(ip, []) if now - t < 1]
            if len(stamps) >= 5:
                self._replies[ip] = stamps
                return False
            stamps.append(now)
            self._replies[ip] = stamps
            if len(self._replies) > 256:
                self._replies = {ip: stamps}
            return True

    # Sessions -------------------------------------------------------------

    def session_started(self, session: Session) -> None:
        session.connected_at = time.time()
        with self.lock:
            self.sessions.append(session)
        self.notify("connected", session.device_name)

    def session_ended(self, session: Session) -> None:
        with self.lock:
            was_active = session in self.sessions
            if was_active:
                self.sessions.remove(session)
        if was_active:
            self.notify("disconnected", session.device_name)

    def revoke(self, device_id: str) -> None:
        self.config.remove_device(device_id)
        with self.lock:
            victims = [s for s in self.sessions if s.device_id == device_id]
        for s in victims:
            s.close()

    def broadcast(self, obj: dict) -> None:
        """Pushes an event to every connected phone, for example after pins change on the PC."""
        with self.lock:
            sessions = list(self.sessions)
        for s in sessions:
            try:
                s.send(obj)
            except OSError:
                s.close()

    def pin_app(self, app_id: str) -> list[dict]:
        available = self._available()
        if app_id not in available:
            raise PlatformError("App not found")
        pinned = [a for a in self.config.pinned() if a["id"] != app_id]
        pinned.append({"id": app_id, "name": available[app_id]})
        self.config.set_pinned(pinned)
        return pinned

    def set_pinned(self, pinned: list[dict], origin: "Session | None" = None) -> None:
        self.config.set_pinned(pinned)
        with self.lock:
            sessions = [s for s in self.sessions if s is not origin]
        for s in sessions:
            try:
                s.send({"t": "event", "e": "apps"})
            except OSError:
                s.close()

    # Setup backup ----------------------------------------------------------

    def export_setup(self) -> dict:
        """Pinned apps and shortcut pads. Never pairing keys, tokens or anything secret."""
        return {"mobile_dock_setup": 1, "pinned": self.config.pinned(), "pads": self.config.data["pads"],
                "search": self.config.data["search"], "search_icons": self.config.data.get("search_icons", {})}

    def import_setup(self, data: dict) -> str:
        """Checks a backup from export_setup and applies it. Raises ValueError with a readable reason."""
        if not isinstance(data, dict) or data.get("mobile_dock_setup") != 1:
            raise ValueError("This is not a Mobile Dock setup file")
        pinned = []
        for app in data.get("pinned", [])[:200]:
            if isinstance(app, dict) and isinstance(app.get("id"), str) and isinstance(app.get("name"), str):
                pinned.append({"id": app["id"][:400], "name": app["name"][:100]})
        presets = search.validate(data["search"]) if data.get("search") else None
        icons = {str(k)[:32]: str(v) for k, v in (data.get("search_icons") or {}).items()
                 if isinstance(v, str) and len(v) < 300_000}
        self.set_pads(data.get("pads", []))
        if presets:
            self.set_search(presets, icons)
        self.set_pinned(pinned)
        return f"Imported {len(pinned)} pinned apps and {len(self.config.data['pads'])} shortcut pads"

    def connected_sessions(self) -> list[dict]:
        """Name, how it connects, and since when, for every phone connected right now."""
        with self.lock:
            sessions = list(self.sessions)
        return [{"id": s.device_id, "name": s.device_name, "since": s.connected_at,
                 "via": "USB" if s.addr[0].startswith("127.") else f"Wi-Fi {s.addr[0]}"} for s in sessions]

    def connected_names(self) -> list[str]:
        with self.lock:
            return [s.device_name for s in self.sessions]

    # Dispatch -------------------------------------------------------------

    def _available(self) -> dict[str, str]:
        return {a["id"]: a["name"] for a in self.platform.list_apps()}

    def _output_path(self, key: str, folder: str, ext: str) -> Path:
        configured = self.config.data.get(key)
        base = Path(configured) if configured else Path.home() / folder / "Mobile Dock"
        base.mkdir(parents=True, exist_ok=True)
        return base / f"{datetime.now():%Y-%m-%d %H-%M-%S}.{ext}"

    def _run_tile(self, text: str) -> None:
        """Runs a shortcut tile: key presses, web pages, programs, typed text and pauses, in order."""
        self._run_steps(pads.parse_action(text))

    def _run_steps(self, steps: list[dict], browser: str = "default") -> None:
        pf = self.platform
        for n, step in enumerate(steps):
            if n:
                time.sleep(0.05)
            if step["do"] == "keys":
                for k, press in enumerate(step["keys"]):
                    if k:
                        time.sleep(0.05)
                    pf.key(press["key"], press["mods"])
            elif step["do"] == "open":
                if browser == "default":
                    pf.open_url(step["url"])
                else:
                    pf.open_url(step["url"], browser)
            elif step["do"] == "run":
                pf.run_target(step["target"])
            elif step["do"] == "type":
                pf.type_text(step["text"])
            elif step["do"] == "wait":
                time.sleep(step["seconds"])

    # Search -------------------------------------------------------------------

    def search_presets(self) -> list[dict]:
        return self.config.data["search"]

    def set_search(self, presets: list[dict], icons: dict | None = None) -> None:
        """Saves presets from the editor. Icons of presets whose icon changed are fetched again."""
        old = {p["id"]: p.get("icon") for p in self.config.data["search"]}
        new = search.validate(presets)
        stored = dict(self.config.data.get("search_icons", {}))
        stored.update(icons or {})
        keep = {p["id"] for p in new if old.get(p["id"]) == p["icon"] or p["id"] in (icons or {})}
        self.config.data["search"] = new
        self.config.data["search_icons"] = {k: v for k, v in stored.items() if k in keep}
        self.config.save()
        self.broadcast({"t": "event", "e": "search"})

    def _icon_rev(self, preset: dict) -> str:
        stored = self.config.data.get("search_icons", {}).get(preset["id"], "")
        return hashlib.sha1((repr(preset.get("icon")) + stored[:64]).encode()).hexdigest()[:10]

    def search_icon(self, preset_id: str) -> dict:
        """{"png": base64} when there is a picture, else {"builtin": name} for a line icon drawn on the phone."""
        preset = next((p for p in self.config.data["search"] if p["id"] == preset_id), None)
        if preset is None:
            raise PlatformError("That search preset no longer exists")
        icons = self.config.data.setdefault("search_icons", {})
        if icons.get(preset_id):
            return {"png": icons[preset_id]}
        icon = preset.get("icon", {})
        png = None
        if icon.get("kind") == "builtin":
            return {"builtin": icon.get("value", "search")}
        if icon.get("kind") == "app" and icon.get("match"):
            match = icon["match"].lower()
            app = next((a for a in self.platform.list_apps() if match in a["name"].lower()), None)
            png = self.platform.app_icon(app["id"]) if app else None
        elif icon.get("kind") == "site" and self.config.data.get("search_fetch_icons", True):
            png = search.fetch_site_icon(preset)
        if not png:
            return {"builtin": "globe" if search.home(preset) else "search"}
        icons[preset_id] = search.encode(png)
        self.config.save()
        return {"png": icons[preset_id]}

    def run_search(self, text: str, preset_id: str = "") -> str:
        text = str(text)[:500]
        if not text.strip() and not preset_id:
            raise PlatformError("Type something to search for")
        try:
            front = self.platform.foreground().get("app", "")
        except Exception:  # noqa: BLE001
            front = ""
        try:
            preset, query, url = search.choose(self.config.data["search"], text, preset_id, front)
        except ValueError as exc:
            raise PlatformError(str(exc)) from exc
        browser = (preset or {}).get("browser", "default")
        if url:
            self._run_steps([{"do": "open", "url": url}], browser)
            return f"Opened {urllib.parse.urlsplit(url).netloc}"
        if preset is None:
            raise PlatformError("No search preset fits. Add one in the PC window.")
        if not query.strip():
            raise PlatformError("Type something to search for")
        try:
            steps = search.steps_for(preset["action"], query)
        except ValueError as exc:
            raise PlatformError(str(exc)) from exc
        self._run_steps(steps, browser)
        return f"{preset['name']}: {query}"

    def _lan_ip(self, origin: "Session | None") -> str:
        ip = ""
        if origin is not None:
            try:
                ip = origin.sock.getsockname()[0]
            except OSError:
                ip = ""
        if not ip or ip.startswith("127.") or ip == "::1":
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.connect(("192.0.2.1", 9))  # no packet is sent, this only picks the LAN card
                    ip = s.getsockname()[0]
            except OSError:
                pass
        return ip.removeprefix("::ffff:")

    def dispatch(self, cmd: str, msg: dict, origin: "Session | None" = None) -> dict | None:
        pf = self.platform
        if cmd == "ping":
            return {"recording": pf.is_recording()}
        if cmd == "apps.pinned":
            return {"apps": self.config.pinned()}
        if cmd == "apps.available":
            return {"apps": pf.list_apps()}
        if cmd == "apps.pin":
            pinned = self.pin_app(str(msg["app"]))
            self.set_pinned(pinned, origin)
            return {"apps": pinned}
        if cmd == "apps.icon":
            app_id = str(msg["app"])
            if app_id not in self._available():
                raise PlatformError("App not found")
            png = pf.app_icon(app_id)
            return {"png": base64.b64encode(png).decode() if png else ""}
        if cmd == "apps.unpin":
            pinned = [a for a in self.config.pinned() if a["id"] != str(msg["app"])]
            self.set_pinned(pinned, origin)
            return {"apps": pinned}
        if cmd == "apps.reorder":
            order = [str(i) for i in msg["ids"]]
            current = {a["id"]: a for a in self.config.pinned()}
            ordered = [current.pop(i) for i in order if i in current] + list(current.values())
            self.set_pinned(ordered, origin)
            return {"apps": ordered}
        if cmd == "apps.launch":
            app_id = str(msg["app"])
            # Only ids the PC itself discovered can run. The phone never sends a command line.
            if app_id not in self._available():
                raise PlatformError("App not found on this PC")
            pf.launch(app_id)
            return None
        if cmd == "media":
            pf.media(str(msg["action"]))
            return pf.media_status()
        if cmd == "media.status":
            return pf.media_status()
        if cmd == "media.art":
            art = pf.media_art()
            return {"art": base64.b64encode(art).decode() if art else ""}
        if cmd == "media.jump":
            pf.media_jump(max(-60.0, min(60.0, float(msg.get("seconds", 0)))))
            return pf.media_status()
        if cmd == "media.seek":
            pf.media_seek(max(0.0, float(msg["position"])))
            return pf.media_status()
        if cmd == "media.shuffle":
            pf.media_shuffle()
            return pf.media_status()
        if cmd == "media.repeat":
            pf.media_repeat()
            return pf.media_status()
        if cmd == "system":
            action = str(msg.get("action", ""))
            if action not in SYSTEM_ACTIONS:
                raise PlatformError("Unknown control")
            pf.system(action)
            return None
        if cmd == "search.presets":
            return {"presets": [{"id": p["id"], "name": p["name"], "keyword": p["keyword"],
                                 "icon_rev": self._icon_rev(p)} for p in self.config.data["search"]]}
        if cmd == "search.icon":
            return self.search_icon(str(msg.get("id", "")))
        if cmd == "search.run":
            return {"did": self.run_search(str(msg.get("text", "")), str(msg.get("preset", "")))}
        if cmd == "host.info":
            # Sent only inside the encrypted session, so only paired phones learn the MAC address.
            return {"mac": pf.mac_address(self._lan_ip(origin)) or ""}
        if cmd == "mixer.list":
            return {"apps": pf.mixer()}
        if cmd == "mixer.set":
            volume = msg.get("volume")
            muted = msg.get("muted")
            pf.mixer_set(str(msg.get("id", ""))[:200],
                         None if volume is None else max(0, min(100, int(volume))),
                         None if muted is None else bool(muted))
            return None
        if cmd == "clipboard.set_text":
            text = str(msg.get("text", ""))
            if len(text) > 100_000:
                raise PlatformError("That text is too long for the clipboard")
            pf.set_clipboard_text(text)
            return None
        if cmd == "clipboard.get_text":
            return {"text": pf.get_clipboard_text()[:100_000]}
        if cmd == "update.status":
            up = self.updater
            return {"pc_version": __version__, "status": up.status if up else "Updates are off on this PC",
                    "phone_ready": (up.apk or {}).get("version", "") if up else ""}
        if cmd == "update.refresh":
            if not self.updater:
                raise PlatformError("Updates are off on this PC")
            status = self.updater.check()
            return {"pc_version": __version__, "status": status,
                    "phone_ready": (self.updater.apk or {}).get("version", "")}
        if cmd == "update.check":
            if not self.updater:
                return {"available": False}
            return self.updater.phone_offer(int(msg.get("code", 0)))
        if cmd == "update.chunk":
            if not self.updater:
                raise PlatformError("Updates are off on the PC")
            chunk = self.updater.phone_chunk(int(msg.get("offset", 0)))
            return {"data": base64.b64encode(chunk["data"]).decode(), "total": chunk["total"]}
        if cmd == "screenshot":
            path = self._output_path("screenshots_dir", "Pictures", "png")
            pf.screenshot(path)
            self.notify("screenshot", str(path))
            return {"path": str(path)}
        if cmd == "record.start":
            pf.start_recording(self._output_path("recordings_dir", "Videos", "mp4"))
            return {"recording": True}
        if cmd == "record.stop":
            path = pf.stop_recording()
            self.notify("recording", str(path))
            return {"recording": False, "path": str(path)}
        if cmd == "input.text":
            pf.type_text(str(msg["text"])[:2000])
            return None
        if cmd == "input.key":
            pf.key(str(msg["key"]), [str(m) for m in msg.get("mods", [])][:4])
            return None
        if cmd == "mouse.move":
            pf.mouse_move(_clamp(msg["dx"]), _clamp(msg["dy"]))
            return None
        if cmd == "mouse.button":
            pf.mouse_button(str(msg["button"]), str(msg.get("action", "click")))
            return None
        if cmd == "mouse.scroll":
            pf.scroll(_clamp(msg.get("dx", 0), 200), _clamp(msg.get("dy", 0), 200))
            return None
        if cmd == "perf":
            return self.perf.snapshot()
        if cmd == "context":
            return dict(self.context)
        if cmd == "pads.list":
            return {"pads": [{"id": d["id"], "name": d["name"], "tiles": [{"label": t["label"]} for t in d["tiles"]]}
                             for d in self.config.data["pads"]], "context": dict(self.context)}
        if cmd == "pad.run":
            pad = next((d for d in self.config.data["pads"] if d["id"] == str(msg["pad"])), None)
            index = int(msg["index"])
            if pad is None or not 0 <= index < len(pad["tiles"]):
                raise PlatformError("That shortcut no longer exists")
            self._run_tile(pad["tiles"][index]["keys"])
            return None
        if cmd.startswith("file."):
            if origin is None:
                raise PlatformError("No session")
            return self._file(cmd, msg, origin)
        raise PlatformError(f"Unknown command {cmd[:32]}")

    # Pads and the app in front --------------------------------------------

    def set_pads(self, new: list[dict]) -> None:
        self.config.data["pads"] = pads.validate(new)
        self.config.save()
        self.context["pad"] = pads.match(self.config.data["pads"], self.context["app"])
        self.broadcast({"t": "event", "e": "pads"})

    def _watch_foreground(self) -> None:
        """Tells phones which app is in front, so their Shortcuts page can follow it."""
        while not self._stop.wait(0.7):
            with self.lock:
                if not self.sessions:
                    continue
            try:
                fg = self.platform.foreground()
            except Exception:  # noqa: BLE001
                continue
            app = fg.get("app", "")
            if not app or app == self.context["app"]:
                continue
            if app.lower() in ("mobiledockhost.exe", "explorer.exe") and self.context["app"]:
                continue  # clicking our own window or the taskbar should not hide the pad you use
            self.context = {"app": app, "title": fg.get("title", "")[:80],
                            "pad": pads.match(self.config.data["pads"], app)}
            self.broadcast({"t": "event", "e": "context", **self.context})

    # Files from the phone -------------------------------------------------

    def _file(self, cmd: str, msg: dict, session: "Session") -> dict:
        if cmd == "file.begin":
            size = int(msg["size"])
            if not 0 < size <= MAX_UPLOAD:
                raise PlatformError("Files up to 200 MB can be sent")
            mode = str(msg.get("mode", "downloads"))
            if mode not in ("clipboard", "downloads"):
                raise PlatformError("Unknown destination")
            if mode == "clipboard" and size > 40 * 1024 * 1024:
                raise PlatformError("Pictures for the clipboard can be up to 40 MB")
            incoming = config_dir() / "incoming"
            incoming.mkdir(parents=True, exist_ok=True)
            if session.upload:
                session.upload["fh"].close()
            tmp = incoming / f"{secrets.token_hex(8)}.part"
            session.upload = {"fh": open(tmp, "wb"), "tmp": tmp, "size": size, "got": 0, "mode": mode,
                              "name": safe_name(str(msg.get("name", "file"))), "sha": hashlib.sha256()}
            return {"ok": True}
        up = session.upload
        if not up:
            raise PlatformError("No file transfer in progress")
        if cmd == "file.chunk":
            data = base64.b64decode(str(msg["data"]))
            if up["got"] + len(data) > up["size"]:
                self._abort(session)
                raise PlatformError("The file is bigger than announced")
            up["fh"].write(data)
            up["sha"].update(data)
            up["got"] += len(data)
            return {"got": up["got"]}
        if cmd == "file.end":
            up["fh"].close()
            session.upload = None
            if up["got"] != up["size"] or up["sha"].hexdigest() != str(msg.get("sha256", "")).lower():
                up["tmp"].unlink(missing_ok=True)
                raise PlatformError("The file arrived damaged. Try again.")
            if up["mode"] == "clipboard":
                try:
                    self.platform.set_clipboard_image(up["tmp"].read_bytes())
                finally:
                    up["tmp"].unlink(missing_ok=True)
                self.notify("file", "Picture from your phone is on the clipboard")
                return {"where": "clipboard"}
            folder = Path(self.config.data.get("downloads_dir") or Path.home() / "Downloads" / "Mobile Dock")
            folder.mkdir(parents=True, exist_ok=True)
            target = unique_path(folder / up["name"])
            os.replace(up["tmp"], target)
            self.notify("file", f"Saved {target}")
            return {"where": str(target)}
        raise PlatformError("Unknown file command")

    def _abort(self, session: "Session") -> None:
        up, session.upload = session.upload, None
        if up:
            up["fh"].close()
            up["tmp"].unlink(missing_ok=True)

    # Network --------------------------------------------------------------

    def start(self) -> None:
        bind = self.config.data.get("bind", "0.0.0.0")
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if os.name != "nt":
            tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind((bind, self.port))
        self.port = tcp.getsockname()[1]
        tcp.listen(8)
        self._sockets.append(tcp)
        threading.Thread(target=self._accept_loop, args=(tcp,), daemon=True, name="accept").start()
        threading.Thread(target=self._watch_foreground, daemon=True, name="foreground").start()
        if self.config.data.get("discovery", True):
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                udp.bind((bind, p.UDP_PORT))
            except OSError as exc:
                log.warning("discovery disabled: %s", exc)
                udp.close()
            else:
                self._sockets.append(udp)
                threading.Thread(target=self._discovery_loop, args=(udp,), daemon=True, name="discovery").start()
        log.info("listening on %s:%d", bind, self.port)

    def stop(self) -> None:
        self._stop.set()
        for s in self._sockets:
            s.close()
        with self.lock:
            sessions = list(self.sessions)
        for s in sessions:
            s.close()

    def _accept_loop(self, tcp: socket.socket) -> None:
        while not self._stop.is_set():
            try:
                sock, addr = tcp.accept()
            except OSError:
                return
            with self.lock:
                busy = len(self.sessions) >= MAX_SESSIONS
            if busy or not self._allowed(addr[0]):
                sock.close()
                continue
            threading.Thread(target=Session(self, sock, addr).run, daemon=True, name=f"session-{addr[0]}").start()

    def _discovery_loop(self, udp: socket.socket) -> None:
        while not self._stop.is_set():
            try:
                data, addr = udp.recvfrom(64)
            except OSError:
                return
            if data.strip() == p.DISCOVERY_PROBE and self._allowed(addr[0]) and self._reply_ok(addr[0]):
                reply = {"t": "mdock", "v": p.PROTOCOL_VERSION, "port": self.port, **self.identity()}
                try:
                    udp.sendto(p.encode_json(reply), addr)
                except OSError:
                    pass


def _clamp(value, limit: int = 2000) -> int:
    return max(-limit, min(limit, int(value)))
