"""Minimal PNG writer so screenshots need no imaging library."""

from __future__ import annotations

import struct
import zlib
from pathlib import Path


def _chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def encode_png(width: int, height: int, pixels: bytes, alpha: bool = False) -> bytes:
    """pixels are RGB, or RGBA when alpha is True, row by row from the top."""
    stride = width * (4 if alpha else 3)
    rows = b"".join(b"\x00" + pixels[y * stride : (y + 1) * stride] for y in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header) + _chunk(b"IDAT", zlib.compress(rows, 6)) + _chunk(b"IEND", b"")


def write_png(path: Path, width: int, height: int, rgb: bytes) -> None:
    path.write_bytes(encode_png(width, height, rgb))
