"""TCP server, LAN discovery and command handling."""

from __future__ import annotations

import hmac
import logging
import os
import secrets
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from typing import Callable

from . import protocol as p
from .config import Config
from .platforms import Platform, PlatformError

log = logging.getLogger("mobiledock")

HANDSHAKE_TIMEOUT = 15
IDLE_TIMEOUT = 60
MAX_SESSIONS = 4
PIN_LIFETIME = 180
PIN_ATTEMPTS = 5
FAIL_WINDOW = 300
FAIL_LIMIT = 8


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
        self.channel: p.SecureChannel | None = None
        self.send_lock = threading.Lock()
        self.closed = False

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
            result = self.server.dispatch(cmd, msg)
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
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class DockServer:
    SLOW = {"apps.available", "apps.launch", "apps.pin", "screenshot", "record.start", "record.stop", "media",
            "media.status"}

    def __init__(self, config: Config, platform: Platform, notify: Callable[[str, str], None] | None = None):
        self.config = config
        self.platform = platform
        self.pairing = Pairing()
        self.pool = ThreadPoolExecutor(max_workers=4)
        self.sessions: list[Session] = []
        self.lock = threading.Lock()
        self.failures: dict[str, list[float]] = {}
        self._notify = notify or (lambda kind, text: None)
        self._stop = threading.Event()
        self._sockets: list[socket.socket] = []
        self.port = int(config.data["port"])

    def notify(self, kind: str, text: str) -> None:
        try:
            self._notify(kind, text)
        except Exception:  # noqa: BLE001
            log.exception("notify failed")

    def identity(self) -> dict:
        return {"host_id": self.config.data["host_id"], "host_name": self.config.data["host_name"],
                "os": self.platform.name}

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

    # Sessions -------------------------------------------------------------

    def session_started(self, session: Session) -> None:
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

    def dispatch(self, cmd: str, msg: dict) -> dict | None:
        pf = self.platform
        if cmd == "ping":
            return {"recording": pf.is_recording()}
        if cmd == "apps.pinned":
            return {"apps": self.config.pinned()}
        if cmd == "apps.available":
            return {"apps": pf.list_apps()}
        if cmd == "apps.pin":
            app_id = str(msg["app"])
            available = self._available()
            if app_id not in available:
                raise PlatformError("App not found")
            pinned = [a for a in self.config.pinned() if a["id"] != app_id]
            pinned.append({"id": app_id, "name": available[app_id]})
            self.config.set_pinned(pinned)
            return {"apps": pinned}
        if cmd == "apps.unpin":
            pinned = [a for a in self.config.pinned() if a["id"] != str(msg["app"])]
            self.config.set_pinned(pinned)
            return {"apps": pinned}
        if cmd == "apps.reorder":
            order = [str(i) for i in msg["ids"]]
            current = {a["id"]: a for a in self.config.pinned()}
            ordered = [current.pop(i) for i in order if i in current] + list(current.values())
            self.config.set_pinned(ordered)
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
        raise PlatformError(f"Unknown command {cmd[:32]}")

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
            if busy:
                sock.close()
                continue
            threading.Thread(target=Session(self, sock, addr).run, daemon=True, name=f"session-{addr[0]}").start()

    def _discovery_loop(self, udp: socket.socket) -> None:
        while not self._stop.is_set():
            try:
                data, addr = udp.recvfrom(64)
            except OSError:
                return
            if data.strip() == p.DISCOVERY_PROBE:
                reply = {"t": "mdock", "v": p.PROTOCOL_VERSION, "port": self.port, **self.identity()}
                try:
                    udp.sendto(p.encode_json(reply), addr)
                except OSError:
                    pass


def _clamp(value, limit: int = 2000) -> int:
    return max(-limit, min(limit, int(value)))
