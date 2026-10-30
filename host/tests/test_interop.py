"""Runs the Android app's Java protocol code against the Python host.

Needs a JDK and the org.json jar. Set MDOCK_JSON_JAR to its path. Skipped otherwise.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from test_server import FakePlatform  # noqa: F401  (also sets sys.path)
from mobiledock_host.config import Config
from mobiledock_host.server import DockServer

ROOT = Path(__file__).resolve().parents[2]
PROTO = ROOT / "android/app/src/main/java/dev/mobiledock/proto"
JAR = os.environ.get("MDOCK_JSON_JAR", "")


@pytest.mark.skipif(not (shutil.which("javac") and JAR and Path(JAR).exists()), reason="needs javac and MDOCK_JSON_JAR")
def test_java_client_against_python_host(tmp_path):
    out = tmp_path / "classes"
    sources = [str(f) for f in PROTO.glob("*.java")] + [str(Path(__file__).parent / "interop/InteropClient.java")]
    subprocess.run(["javac", "-d", str(out), "-cp", JAR, *sources], check=True)

    config = Config(tmp_path / "config.json")
    config.data.update(port=0, discovery=False)
    server = DockServer(config, FakePlatform())
    server.start()
    try:
        pin = server.pairing.start()
        result = subprocess.run(
            ["java", "-cp", os.pathsep.join([str(out), JAR]), "InteropClient", str(server.port), pin],
            capture_output=True, text=True, timeout=60,
        )
    finally:
        server.stop()
    assert result.returncode == 0, result.stderr
    lines = result.stdout.split()
    assert "pin:true" in lines and "ping:true" in lines and "pinned:code.desktop" in lines
