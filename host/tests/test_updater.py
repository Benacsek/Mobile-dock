import base64
import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mobiledock_host import updater as up  # noqa: E402
from mobiledock_host.config import Config  # noqa: E402

APK = b"PK\x03\x04 fake apk " * 50000  # bigger than one chunk


def make(tmp_path, monkeypatch, apk=APK, sha=None):
    manifest = {"version": "1.1.7", "android": {"file": "MobileDock-1.1.7.apk", "code": 7,
                                                 "sha256": sha or hashlib.sha256(apk).hexdigest()}}
    served = {
        "https://github.com/Benacsek/Mobile-dock/releases/latest/download/latest.json": json.dumps(manifest).encode(),
        "https://github.com/Benacsek/Mobile-dock/releases/download/v1.1.7/MobileDock-1.1.7.apk": apk,
    }
    monkeypatch.setattr(up, "_fetch", lambda url, limit=0, token="", accept="": served[url])
    u = up.Updater(Config(tmp_path / "config.json"))
    u.dir = tmp_path / "updates"
    return u


def test_versions():
    assert up.parse_version("v1.1.10") > up.parse_version("1.1.9")
    assert up.parse_version("junk") == (0,)


def test_phone_update_flow(tmp_path, monkeypatch):
    u = make(tmp_path, monkeypatch)
    assert u.check().startswith("Up to date")
    assert u.phone_offer(7) == {"available": False}
    offer = u.phone_offer(3)
    assert offer["available"] and offer["size"] == len(APK)
    got, offset = b"", 0
    while offset < offer["size"]:
        chunk = u.phone_chunk(offset)["data"]
        got += chunk
        offset += len(chunk)
    assert got == APK


def test_tampered_download_rejected(tmp_path, monkeypatch):
    u = make(tmp_path, monkeypatch, sha="0" * 64)
    assert "SHA-256" in u.check()
    assert u.phone_offer(0) == {"available": False}


def test_http_refused():
    with pytest.raises(ValueError):
        up._fetch("http://example.com/x")


def test_server_serves_update(tmp_path, monkeypatch):
    from test_server import FakePlatform, call, pair
    from mobiledock_host.server import DockServer

    u = make(tmp_path, monkeypatch)
    u.check()
    config = Config(tmp_path / "server.json")
    config.data.update(port=0, discovery=False)
    server = DockServer(config, FakePlatform())
    server.updater = u
    server.start()
    try:
        channel, _ = pair(server, server.pairing.start())
        offer = call(channel, "update.check", code=1)
        assert offer["available"] and offer["code"] == 7
        first = call(channel, "update.chunk", 2, offset=0)
        assert base64.b64decode(first["data"]) == APK[: up.CHUNK]
        assert call(channel, "media.seek", 3, position=10)["ok"] is False  # fake player cannot seek
    finally:
        server.stop()


def test_private_repo_uses_token_and_api(tmp_path, monkeypatch):
    manifest = {"version": "1.1.8", "android": {"file": "a.apk", "code": 8, "sha256": hashlib.sha256(APK).hexdigest()}}
    api = "https://api.github.com/repos/Benacsek/Mobile-dock/releases"
    release = {"assets": [{"name": "latest.json", "url": f"{api}/assets/1"}, {"name": "a.apk", "url": f"{api}/assets/2"}]}
    served = {f"{api}/latest": json.dumps(release).encode(), f"{api}/assets/1": json.dumps(manifest).encode(),
              f"{api}/assets/2": APK}
    seen = []

    def fake(url, limit=0, token="", accept=""):
        seen.append(token)
        return served[url]

    monkeypatch.setattr(up, "_fetch", fake)
    config = Config(tmp_path / "config.json")
    config.data["github_token"] = "secret-token"
    u = up.Updater(config)
    u.dir = tmp_path / "updates"
    assert u.check().startswith("Up to date")
    assert u.phone_offer(1)["code"] == 8
    assert seen and all(t == "secret-token" for t in seen)


def test_redirect_drops_token():
    import urllib.request

    req = urllib.request.Request("https://api.github.com/x", headers={"Authorization": "Bearer t"})
    new = up._DropAuthOnRedirect().redirect_request(req, None, 302, "Found", {}, "https://storage.example/x")
    assert not new.has_header("Authorization")
