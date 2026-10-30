import os
import socket
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mobiledock_host import protocol as p  # noqa: E402
from mobiledock_host.config import Config  # noqa: E402
from mobiledock_host.platforms.base import Platform  # noqa: E402
from mobiledock_host.server import DockServer  # noqa: E402


class FakePlatform(Platform):
    name = "fake"

    def __init__(self):
        super().__init__()
        self.calls = []

    def list_apps(self):
        return [{"id": "firefox.desktop", "name": "Firefox"}, {"id": "code.desktop", "name": "Code"}]

    def launch(self, app_id):
        self.calls.append(("launch", app_id))

    def media(self, action):
        self.calls.append(("media", action))

    def mouse_move(self, dx, dy):
        self.calls.append(("move", dx, dy))

    def type_text(self, text):
        self.calls.append(("text", text))


@pytest.fixture
def server(tmp_path):
    config = Config(tmp_path / "config.json")
    config.data["port"] = 0
    config.data["discovery"] = False
    srv = DockServer(config, FakePlatform())
    srv.start()
    yield srv
    srv.stop()


def connect(server):
    sock = socket.create_connection(("127.0.0.1", server.port), timeout=5)
    return sock


def pair(server, pin):
    sock = connect(server)
    spake = p.Spake2(pin, "client")
    p.send_frame(sock, p.encode_json({"v": 1, "t": "pair", "name": "Test phone", "X": format(spake.public, "x")}))
    reply = p.decode_json(p.recv_frame(sock))
    if reply["t"] != "pair_r":
        return sock, reply
    keys = spake.finish(int(reply["Y"], 16))
    p.send_frame(sock, p.encode_json({"t": "pair_c", "c": keys.client_confirm.hex()}))
    reply = p.decode_json(p.recv_frame(sock))
    if reply["t"] != "pair_ok":
        return sock, reply
    assert bytes.fromhex(reply["c"]) == keys.server_confirm
    channel = p.SecureChannel(sock, keys.session, "client")
    return channel, channel.recv()


def reconnect(server, device_id, secret):
    sock = connect(server)
    nc = os.urandom(32)
    p.send_frame(sock, p.encode_json({"v": 1, "t": "hello", "nc": nc.hex()}))
    challenge = p.decode_json(p.recv_frame(sock))
    client_mac, server_mac, session = p.reconnect_keys(secret, device_id, nc, bytes.fromhex(challenge["ns"]))
    p.send_frame(sock, p.encode_json({"t": "auth", "device_id": device_id, "mac": client_mac.hex()}))
    reply = p.decode_json(p.recv_frame(sock))
    if reply["t"] != "auth_ok":
        return None, reply
    assert bytes.fromhex(reply["mac"]) == server_mac
    return p.SecureChannel(sock, session, "client"), reply


def call(channel, cmd, req_id=1, **kw):
    channel.send({"id": req_id, "t": cmd, **kw})
    return channel.recv()


def test_spake2_agrees_and_rejects_wrong_pin():
    a, b = p.Spake2("123456", "client"), p.Spake2("123456", "server")
    ka, kb = a.finish(b.public), b.finish(a.public)
    assert ka.session == kb.session and ka.client_confirm == kb.client_confirm
    c = p.Spake2("654321", "server")
    assert a.finish(c.public).client_confirm != c.finish(a.public).client_confirm


def test_invalid_elements_rejected():
    s = p.Spake2("1", "server")
    for bad in (0, 1, p.P - 1, p.P, p.P + 5):
        with pytest.raises(p.ProtocolError):
            s.finish(bad)


def test_pairing_requires_active_pin(server):
    _, reply = pair(server, "000000")
    assert reply["code"] == "not_pairing"


def test_wrong_pin_then_pin_dies(server):
    pin = server.pairing.start()
    wrong = f"{(int(pin) + 1) % 1_000_000:06d}"
    for _ in range(5):
        _, reply = pair(server, wrong)
        assert reply["code"] in ("bad_pin", "not_pairing")
    assert server.pairing.current() is None


def test_full_flow(server):
    pin = server.pairing.start()
    channel, paired = pair(server, pin)
    assert paired["t"] == "paired" and len(bytes.fromhex(paired["secret"])) == 32
    assert server.pairing.current() is None  # PIN is single use

    resp = call(channel, "apps.pin", app="firefox.desktop")
    assert resp["ok"] and resp["apps"][0]["name"] == "Firefox"
    assert not call(channel, "apps.pin", 2, app="rm -rf /")["ok"]
    assert call(channel, "apps.launch", 3, app="firefox.desktop")["ok"]
    assert not call(channel, "apps.launch", 4, app="/bin/sh")["ok"]
    channel.send({"t": "mouse.move", "dx": 99999, "dy": -3})
    assert call(channel, "ping", 5)["ok"]
    assert ("move", 2000, -3) in server.platform.calls
    channel.sock.close()

    channel2, _ = reconnect(server, paired["device_id"], bytes.fromhex(paired["secret"]))
    assert call(channel2, "apps.pinned")["apps"][0]["id"] == "firefox.desktop"
    channel2.sock.close()

    _, reply = reconnect(server, paired["device_id"], os.urandom(32))
    assert reply["code"] == "auth_failed"

    server.revoke(paired["device_id"])
    _, reply = reconnect(server, paired["device_id"], bytes.fromhex(paired["secret"]))
    assert reply["code"] == "auth_failed"


def test_tampered_frame_rejected(server):
    pin = server.pairing.start()
    channel, _ = pair(server, pin)
    # A skipped or replayed sequence number must kill the session.
    channel._tx_seq = 5
    channel.send({"id": 1, "t": "ping"})
    channel.sock.settimeout(3)
    with pytest.raises((ConnectionError, OSError)):
        channel.recv()


def test_pin_change_notifies_other_phones(server):
    a, _ = pair(server, server.pairing.start())
    b, _ = pair(server, server.pairing.start())
    assert call(a, "apps.pin", app="code.desktop")["ok"]
    b.sock.settimeout(3)
    assert b.recv() == {"t": "event", "e": "apps"}
