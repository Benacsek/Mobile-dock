"""Auto updates from this project's GitHub releases.

The PC downloads updates for itself and for the phone. The phone gets its APK
from the PC over the encrypted link, because old Android versions often cannot
open modern HTTPS connections to GitHub.

Checks:
  every file comes over HTTPS from github.com (with a read-only token for private repositories)
  every file must match the SHA-256 listed in latest.json of the same release
  Android also refuses an APK that is not signed with the same key as the installed app
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable

from . import __version__
from .config import Config, config_dir

log = logging.getLogger("mobiledock.update")

CHECK_EVERY = 6 * 3600
FIRST_CHECK_DELAY = 20
MAX_DOWNLOAD = 200 * 1024 * 1024
CHUNK = 256 * 1024


def parse_version(text: str) -> tuple[int, ...]:
    try:
        return tuple(int(p) for p in text.strip().lstrip("v").split("."))
    except ValueError:
        return (0,)


class _DropAuthOnRedirect(urllib.request.HTTPRedirectHandler):
    """GitHub redirects asset downloads to a signed storage URL. The token must not follow."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None:
            new.remove_header("Authorization")
        return new


def _fetch(url: str, limit: int = MAX_DOWNLOAD, token: str = "", accept: str = "") -> bytes:
    if not url.startswith("https://"):
        raise ValueError("refusing non-HTTPS URL")
    req = urllib.request.Request(url, headers={"User-Agent": f"MobileDockHost/{__version__}"})
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if accept:
        req.add_header("Accept", accept)
    opener = urllib.request.build_opener(_DropAuthOnRedirect)
    with opener.open(req, timeout=60) as resp:
        if not resp.geturl().startswith("https://"):
            raise ValueError("redirected to non-HTTPS URL")
        data = resp.read(limit + 1)
    if len(data) > limit:
        raise ValueError("download too large")
    return data


class Updater:
    def __init__(self, config: Config, on_restart: Callable[[], None] | None = None,
                 notify: Callable[[str, str], None] | None = None):
        self.config = config
        self.on_restart = on_restart
        self.notify = notify or (lambda kind, text: None)
        self.dir = config_dir() / "updates"
        self.status = "Not checked yet"
        self.apk: dict | None = None
        self._release: dict | None = None
        self._lock = threading.Lock()
        self._load_apk_meta()

    @property
    def repo(self) -> str:
        return str(self.config.data.get("update_repo", "Benacsek/Mobile-dock"))

    @property
    def enabled(self) -> bool:
        return bool(self.config.data.get("auto_update", True))

    def start(self) -> None:
        if self.enabled:
            threading.Thread(target=self._loop, daemon=True, name="updater").start()

    def _loop(self) -> None:
        time.sleep(FIRST_CHECK_DELAY)
        while True:
            self.check()
            time.sleep(CHECK_EVERY)

    # Release data ---------------------------------------------------------

    @property
    def token(self) -> str:
        # Needed only for a private repository: a fine-grained token with read access to Contents.
        return str(os.environ.get("MDOCK_GITHUB_TOKEN") or self.config.data.get("github_token") or "")

    def _asset(self, release: dict | None, version: str, name: str, limit: int = MAX_DOWNLOAD) -> bytes:
        if "/" in name or "\\" in name:
            raise ValueError("bad file name")
        if release is None:
            return _fetch(f"https://github.com/{self.repo}/releases/download/v{version}/{name}", limit)
        for asset in release.get("assets", []):
            if asset.get("name") == name:
                return _fetch(str(asset["url"]), limit, self.token, "application/octet-stream")
        raise ValueError(f"{name} is missing from the release")

    def _latest(self) -> tuple[dict, dict | None]:
        release = None
        if self.token:
            release = json.loads(_fetch(f"https://api.github.com/repos/{self.repo}/releases/latest", 1 << 20,
                                        self.token, "application/vnd.github+json"))
            raw = self._asset(release, "", "latest.json", 65536)
        else:
            raw = _fetch(f"https://github.com/{self.repo}/releases/latest/download/latest.json", 65536)
        manifest = json.loads(raw)
        if not isinstance(manifest, dict) or "version" not in manifest:
            raise ValueError("bad latest.json")
        return manifest, release

    def _download(self, version: str, entry: dict) -> bytes:
        name = str(entry["file"])
        data = self._asset(self._release, version, name)
        if hashlib.sha256(data).hexdigest() != str(entry["sha256"]).lower():
            raise ValueError(f"{name} failed the SHA-256 check")
        return data

    def check(self) -> str:
        with self._lock:
            try:
                manifest, self._release = self._latest()
                version = str(manifest["version"])
                self._update_android(version, manifest.get("android"))
                self.status = f"Up to date ({__version__})"
                if self._should_update_self(version, manifest.get("windows")):
                    self.status = f"Installing {version}"
                    self._apply_windows(version, manifest["windows"])
            except urllib.error.HTTPError as exc:
                if exc.code in (401, 403, 404) and not self.token:
                    self.status = "No release found. A private repository needs a token, see the README."
                else:
                    self.status = f"Update check failed: HTTP {exc.code}"
                log.warning("update check failed: %s", exc)
            except Exception as exc:  # noqa: BLE001 - updates must never crash the dock
                log.warning("update check failed: %s", exc)
                self.status = f"Update check failed: {exc}"
            return self.status

    # Phone ----------------------------------------------------------------

    def _load_apk_meta(self) -> None:
        try:
            meta = json.loads((self.dir / "android.json").read_text(encoding="utf-8"))
            if (self.dir / "MobileDock.apk").is_file():
                self.apk = meta
        except (OSError, ValueError):
            self.apk = None

    def _update_android(self, version: str, entry: dict | None) -> None:
        if not entry:
            return
        code = int(entry["code"])
        if self.apk and int(self.apk["code"]) >= code:
            return
        data = self._download(version, entry)
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / "MobileDock.apk.part"
        tmp.write_bytes(data)
        os.replace(tmp, self.dir / "MobileDock.apk")
        self.apk = {"code": code, "version": version, "sha256": entry["sha256"].lower(), "size": len(data)}
        (self.dir / "android.json").write_text(json.dumps(self.apk), encoding="utf-8")
        self.notify("update", f"Phone app {version} downloaded")

    def phone_offer(self, installed_code: int) -> dict:
        apk = self.apk
        if not apk or int(apk["code"]) <= installed_code:
            return {"available": False}
        return {"available": True, **apk}

    def phone_chunk(self, offset: int) -> dict:
        apk = self.apk
        if not apk:
            raise ValueError("no update")
        with open(self.dir / "MobileDock.apk", "rb") as fh:
            fh.seek(max(0, offset))
            data = fh.read(CHUNK)
        return {"data": data, "total": apk["size"]}

    # This PC --------------------------------------------------------------

    def _should_update_self(self, version: str, entry: dict | None) -> bool:
        # Only the packaged Windows .exe replaces itself. Source installs update with pip or git.
        return bool(entry) and sys.platform == "win32" and getattr(sys, "frozen", False) \
            and parse_version(version) > parse_version(__version__)

    def _apply_windows(self, version: str, entry: dict) -> None:
        exe = Path(sys.executable)
        new = exe.with_name(exe.stem + ".new.exe")
        try:
            new.write_bytes(self._download(version, entry))
        except PermissionError as exc:
            raise ValueError(f"cannot write to {exe.parent}. Move MobileDockHost.exe to a folder you own.") from exc
        pid = os.getpid()
        script = (
            "@echo off\r\n"
            ":wait\r\n"
            f'tasklist /FI "PID eq {pid}" 2>nul | find "{pid}" >nul && (ping -n 2 127.0.0.1 >nul & goto wait)\r\n'
            f'move /y "{new}" "{exe}" >nul\r\n'
            f'start "" "{exe}" --minimized\r\n'
            'del "%~f0"\r\n'
        )
        fd, path = tempfile.mkstemp(suffix=".cmd", prefix="mobiledock-update-")
        with os.fdopen(fd, "w", encoding="ascii") as fh:
            fh.write(script)
        subprocess.Popen(["cmd.exe", "/c", path], creationflags=0x08000000, close_fds=True)
        self.notify("update", f"Updating to {version}. The dock restarts in a few seconds.")
        if self.on_restart:
            self.on_restart()
