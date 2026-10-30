"""USB support through adb port forwarding. The phone then connects to 127.0.0.1."""

from __future__ import annotations

import os
import shutil
import subprocess

from . import protocol as p


def enable_usb(port: int = p.TCP_PORT) -> str:
    adb = shutil.which("adb")
    if not adb:
        return "adb not found. Install Android platform-tools and add it to PATH."
    flags = 0x08000000 if os.name == "nt" else 0
    devices = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15, creationflags=flags)
    attached = [ln for ln in devices.stdout.splitlines()[1:] if ln.strip().endswith("device")]
    if not attached:
        return "No phone found. Plug it in and allow USB debugging."
    result = subprocess.run([adb, "reverse", f"tcp:{port}", f"tcp:{port}"],
                            capture_output=True, text=True, timeout=15, creationflags=flags)
    if result.returncode != 0:
        return "adb reverse failed: " + (result.stderr.strip() or "unknown error")
    return "USB ready. In the phone app, tap USB."
