"""Reads the newest version and its notes from CHANGELOG.md.

CI uses it in three ways:
  release_notes.py --env           prints VERSION=1.3.0 for $GITHUB_ENV
  release_notes.py --write-apps    puts the notes into the PC app and the phone app ("What's new")
  release_notes.py --notes FILE    writes the notes for the GitHub release page

A release happens only when the top version in CHANGELOG.md has no release yet.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def newest() -> tuple[str, str]:
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    sections = re.split(r"^## ", text, flags=re.M)[1:]
    if not sections:
        raise SystemExit("CHANGELOG.md has no '## version' heading")
    head, _, body = sections[0].partition("\n")
    version = head.split()[0]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit(f"Top heading must start with a version like 1.3.0, not {head!r}")
    return version, body.strip()


def main(argv: list[str]) -> None:
    version, notes = newest()
    if "--env" in argv:
        print(f"VERSION={version}")
    if "--write-apps" in argv:
        (ROOT / "host/mobiledock_host/_notes.py").write_text(
            f'"""Written by CI from CHANGELOG.md."""\nNOTES = {json.dumps(notes)}\n', encoding="utf-8")
        assets = ROOT / "android/app/src/main/assets"
        assets.mkdir(parents=True, exist_ok=True)
        (assets / "whats_new.txt").write_text(notes + "\n", encoding="utf-8")
    if "--notes" in argv:
        Path(argv[argv.index("--notes") + 1]).write_text(notes + "\n", encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1:])
