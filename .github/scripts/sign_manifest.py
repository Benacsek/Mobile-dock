"""Signs latest.json for the PC updater.

  sign_manifest.py out/latest.json

Reads the OpenSSH Ed25519 private key from the MDOCK_UPDATE_KEY environment variable, writes
out/latest.json.sig (base64), then checks the signature against UPDATE_PUBLIC_KEY in the host code,
so a release can never go out signed with a key the apps would not accept.
"""

from __future__ import annotations

import base64
import os
import re
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, load_ssh_private_key

ROOT = Path(__file__).resolve().parents[2]


def main(path: str) -> None:
    secret = os.environ.get("MDOCK_UPDATE_KEY", "")
    if not secret.strip():
        raise SystemExit("MDOCK_UPDATE_KEY is not set. Without it the PC apps would refuse the release.")
    key = load_ssh_private_key(secret.encode(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise SystemExit("MDOCK_UPDATE_KEY must be an Ed25519 key (ssh-keygen -t ed25519)")
    data = Path(path).read_bytes()
    signature = key.sign(data)
    Path(path + ".sig").write_text(base64.b64encode(signature).decode() + "\n", encoding="ascii")

    source = (ROOT / "host/mobiledock_host/updater.py").read_text(encoding="utf-8")
    match = re.search(r'^UPDATE_PUBLIC_KEY = "([^"]*)"', source, re.M)
    expected = (match.group(1) if match else "").split()[:2]
    actual = key.public_key().public_bytes(Encoding.OpenSSH, PublicFormat.OpenSSH).decode().split()[:2]
    if expected != actual:
        raise SystemExit("MDOCK_UPDATE_KEY does not match UPDATE_PUBLIC_KEY in updater.py. Not publishing.")
    print("latest.json signed and checked against the key built into the apps")


if __name__ == "__main__":
    main(sys.argv[1])
