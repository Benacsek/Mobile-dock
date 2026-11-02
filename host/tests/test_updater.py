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


def make_key():
    """A throwaway update key for the tests: (private key, ssh public line)."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    key = Ed25519PrivateKey.generate()
    return key, key.public_key().public_bytes(Encoding.OpenSSH, PublicFormat.OpenSSH).decode()


def signed(key, data: bytes) -> bytes:
    return base64.b64encode(key.sign(data))


CURRENT = {}


@pytest.fixture(autouse=True)
def update_key(monkeypatch):
    """Every test runs with its own update key built into the 'app'."""
    key, public = make_key()
    monkeypatch.setattr(up, "UPDATE_PUBLIC_KEY", public)
    CURRENT["key"] = key
    return key


def make(tmp_path, monkeypatch, apk=APK, sha=None, key=None):
    manifest = {"version": "1.1.7", "android": {"file": "MobileDock-1.1.7.apk", "code": 7,
                                                 "sha256": sha or hashlib.sha256(apk).hexdigest()}}
    raw = json.dumps(manifest).encode()
    base = "https://github.com/Benacsek/Mobile-dock/releases"
    served = {
        f"{base}/latest/download/latest.json": raw,
        f"{base}/latest/download/latest.json.sig": signed(key or CURRENT["key"], raw),
        f"{base}/download/v1.1.7/MobileDock-1.1.7.apk": apk,
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
    release = {"assets": [{"name": "latest.json", "url": f"{api}/assets/1"}, {"name": "a.apk", "url": f"{api}/assets/2"},
                          {"name": "latest.json.sig", "url": f"{api}/assets/3"}]}
    raw = json.dumps(manifest).encode()
    served = {f"{api}/latest": json.dumps(release).encode(), f"{api}/assets/1": raw,
              f"{api}/assets/2": APK, f"{api}/assets/3": signed(CURRENT["key"], raw)}
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


def test_restart_env_drops_pyinstaller_state():
    env = up.clean_env({"PATH": "x", "_PYI_APPLICATION_HOME_DIR": "C:\\\\Temp\\\\_MEI1", "_MEIPASS2": "y"})
    assert env == {"PATH": "x", "PYINSTALLER_RESET_ENVIRONMENT": "1"}


def test_unsigned_or_foreign_release_refused(tmp_path, monkeypatch):
    """A release signed with another key, a changed latest.json or a missing key installs nothing."""
    other, _ = make_key()
    u = make(tmp_path, monkeypatch, key=other)  # someone else's key, as after a repository takeover
    assert u.check().startswith("Update refused")
    assert u.phone_offer(3) == {"available": False}

    raw = b'{"version": "9.9.9"}'
    good = signed(CURRENT["key"], raw)
    up.verify_manifest(raw, good)
    for bad_raw, bad_sig in ((raw + b" ", good), (raw, b"not base64!"), (raw, b"")):
        with pytest.raises(up.UpdateRefused):
            up.verify_manifest(bad_raw, bad_sig)
    with pytest.raises(up.UpdateRefused):
        up.verify_manifest(raw, good, public_key="")  # a build without a key never trusts anything


def test_ci_signing_script(tmp_path, monkeypatch):
    """The release step signs with the secret and refuses a key the apps would not accept."""
    import importlib.util

    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

    root = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("sign_manifest", root / ".github/scripts/sign_manifest.py")
    sign = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sign)
    fake_root = tmp_path / "repo"
    (fake_root / "host/mobiledock_host").mkdir(parents=True)
    monkeypatch.setattr(sign, "ROOT", fake_root)

    key, public = make_key()
    pem = key.private_bytes(Encoding.PEM, PrivateFormat.OpenSSH, NoEncryption()).decode()
    monkeypatch.setenv("MDOCK_UPDATE_KEY", pem)
    manifest = tmp_path / "latest.json"
    manifest.write_bytes(b'{"version": "1.4.0"}')

    (fake_root / "host/mobiledock_host/updater.py").write_text(f'UPDATE_PUBLIC_KEY = "{public}"\n')
    sign.main(str(manifest))
    up.verify_manifest(manifest.read_bytes(), (tmp_path / "latest.json.sig").read_bytes(), public_key=public)

    _, other_public = make_key()
    (fake_root / "host/mobiledock_host/updater.py").write_text(f'UPDATE_PUBLIC_KEY = "{other_public}"\n')
    with pytest.raises(SystemExit):
        sign.main(str(manifest))  # wrong key built into the apps: do not publish
    monkeypatch.setenv("MDOCK_UPDATE_KEY", "")
    with pytest.raises(SystemExit):
        sign.main(str(manifest))  # no secret: do not publish
