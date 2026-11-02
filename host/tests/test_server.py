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


def test_pads_parse_and_run(server):
    from mobiledock_host import pads

    assert pads.parse("Ctrl+Shift+G") == [{"key": "g", "mods": ["ctrl", "shift"]}]
    assert pads.parse("Win+Left, Enter")[1] == {"key": "enter", "mods": []}
    assert pads.parse("Ctrl+grave")[0]["key"] == "grave"
    for bad in ("Hyper+X", "Ctrl+Nope", ""):
        with pytest.raises(ValueError):
            pads.parse(bad)
    assert pads.match(pads.defaults(), "Figma.exe") == "figma"
    assert pads.match(pads.defaults(), "notepad.exe") == "windows"

    keys = []
    server.platform.key = lambda k, m: keys.append((k, tuple(m)))
    channel, _ = pair(server, server.pairing.start())
    listed = call(channel, "pads.list")
    figma = next(p for p in listed["pads"] if p["id"] == "figma")
    index = [t["label"] for t in figma["tiles"]].index("Group")
    assert call(channel, "pad.run", 2, pad="figma", index=index)["ok"]
    assert keys == [("g", ("ctrl",))]
    assert not call(channel, "pad.run", 3, pad="figma", index=999)["ok"]


def test_file_upload_to_downloads(server, tmp_path):
    import base64
    import hashlib

    server.config.data["downloads_dir"] = str(tmp_path / "dl")
    channel, _ = pair(server, server.pairing.start())
    data = b"hello from the phone" * 1000
    assert call(channel, "file.begin", 1, name="../../evil/notes.txt", size=len(data), mode="downloads")["ok"]
    assert call(channel, "file.chunk", 2, data=base64.b64encode(data[:7000]).decode())["got"] == 7000
    call(channel, "file.chunk", 3, data=base64.b64encode(data[7000:]).decode())
    done = call(channel, "file.end", 4, sha256=hashlib.sha256(data).hexdigest())
    assert done["ok"] and Path(done["where"]).name == "notes.txt"
    assert Path(done["where"]).parent == tmp_path / "dl" and Path(done["where"]).read_bytes() == data

    assert call(channel, "file.begin", 5, name="x.bin", size=10, mode="downloads")["ok"]
    assert not call(channel, "file.chunk", 6, data=base64.b64encode(b"0" * 11).decode())["ok"]  # oversize
    assert not call(channel, "file.begin", 7, name="big", size=10**10, mode="downloads")["ok"]


def test_safe_names():
    from mobiledock_host.server import safe_name

    assert safe_name("C:\\Windows\\system32\\x.dll") == "x.dll"
    assert safe_name("CON.txt") == "file-from-phone"
    assert safe_name("..") == "file-from-phone"
    assert safe_name('a<b>:"c".png') == "abc.png"


def test_perf_and_boot_id(server):
    channel, paired = pair(server, server.pairing.start())
    assert paired["boot"] == server.boot
    first = call(channel, "perf")
    assert first["ok"]


def test_only_local_networks():
    from mobiledock_host.server import is_local

    for ip in ("192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "100.100.1.1", "::1",
               "fe80::1", "::ffff:192.168.1.9"):
        assert is_local(ip), ip
    for ip in ("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "nonsense"):
        assert not is_local(ip), ip


def test_discovery_rate_limit(server):
    assert all(server._reply_ok("192.168.1.50") for _ in range(5))
    assert not server._reply_ok("192.168.1.50")


def test_system_controls_and_update_status(server):
    called = []
    server.platform.system = lambda action: called.append(action)
    channel, paired = pair(server, server.pairing.start())
    assert "version" in paired
    assert call(channel, "system", 1, action="lock")["ok"]
    assert not call(channel, "system", 2, action="shutdown")["ok"]
    assert called == ["lock"]
    status = call(channel, "update.status", 3)
    assert status["ok"] and "pc_version" in status
