"""Local storage for paired phones and pinned apps. No cloud, no accounts."""

from __future__ import annotations

import json
import os
import secrets
import socket
import sys
import tempfile
import threading
from pathlib import Path

from . import secretstore


def config_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        return base / "MobileDock"
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "mobile-dock"


class Config:
    def __init__(self, path: Path | None = None):
        self.path = path or config_dir() / "config.json"
        self._lock = threading.RLock()
        self.data = {
            "host_id": secrets.token_hex(8),
            "host_name": socket.gethostname(),
            "bind": "0.0.0.0",
            "port": 47800,
            "discovery": True,
            "devices": {},
            "pinned": [],
            "screenshots_dir": "",
            "recordings_dir": "",
            "auto_update": True,
            "update_repo": "MPLearry/Mobile-dock",
        }
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            with open(self.path, "r", encoding="utf-8") as fh:
                stored = json.load(fh)
            if isinstance(stored, dict):
                self.data.update(stored)
            if self.data.get("update_repo") == "Benacsek/Mobile-dock":
                self.data["update_repo"] = "MPLearry/Mobile-dock"  # the GitHub account was renamed
                self.save()
            self._encrypt_old_secrets()
        else:
            self.save()

    def save(self) -> None:
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            if sys.platform != "win32":
                os.chmod(self.path.parent, 0o700)
            fd, tmp = tempfile.mkstemp(dir=self.path.parent, prefix=".config-")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    json.dump(self.data, fh, indent=2)
                if sys.platform != "win32":
                    os.chmod(tmp, 0o600)
                os.replace(tmp, self.path)
            except BaseException:
                if os.path.exists(tmp):
                    os.unlink(tmp)
                raise

    # Secrets --------------------------------------------------------------

    def _encrypt_old_secrets(self) -> None:
        """Older versions stored keys in plain text. Encrypt them once."""
        changed = False
        for entry in self.data.get("devices", {}).values():
            enc = secretstore.protect(entry.get("secret", ""))
            if enc != entry.get("secret"):
                entry["secret"], changed = enc, True
        token = self.data.get("github_token", "")
        if token and secretstore.protect(token) != token:
            self.data["github_token"], changed = secretstore.protect(token), True
        if changed:
            self.save()

    def get_secret(self, key: str) -> str:
        try:
            return secretstore.reveal(str(self.data.get(key, "") or ""))
        except (OSError, ValueError):
            return ""

    def set_secret(self, key: str, value: str) -> None:
        with self._lock:
            self.data[key] = secretstore.protect(value)
            self.save()

    # Paired devices -------------------------------------------------------

    def add_device(self, name: str) -> tuple[str, bytes]:
        device_id = secrets.token_hex(8)
        secret = secrets.token_bytes(32)
        with self._lock:
            self.data["devices"][device_id] = {"name": name[:64], "secret": secretstore.protect(secret.hex())}
            self.save()
        return device_id, secret

    def device_secret(self, device_id: str) -> bytes | None:
        with self._lock:
            entry = self.data["devices"].get(device_id)
            if not entry:
                return None
            try:
                return bytes.fromhex(secretstore.reveal(entry["secret"]))
            except (OSError, ValueError):
                return None  # cannot decrypt, for example a config copied from another PC

    def remove_device(self, device_id: str) -> None:
        with self._lock:
            self.data["devices"].pop(device_id, None)
            self.save()

    def devices(self) -> dict:
        with self._lock:
            return {k: v["name"] for k, v in self.data["devices"].items()}

    # Pinned apps ----------------------------------------------------------

    def pinned(self) -> list[dict]:
        with self._lock:
            return [dict(p) for p in self.data["pinned"]]

    def set_pinned(self, items: list[dict]) -> None:
        with self._lock:
            self.data["pinned"] = items
            self.save()
