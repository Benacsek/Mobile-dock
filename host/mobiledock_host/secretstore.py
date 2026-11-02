"""Encrypts secrets at rest in the config file.

Windows: DPAPI ties the data to your Windows account. Another user, or a copy of the file on
another PC, cannot decrypt it. Linux: the config file is readable only by you (mode 600).
"""

from __future__ import annotations

import base64
import sys

PREFIX = "dpapi:"
_ENTROPY = b"Mobile Dock config v1"


def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def blob(raw: bytes) -> Blob:
        buf = ctypes.create_string_buffer(raw, len(raw))
        b = Blob(len(raw), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
        b._keep = buf  # keep the buffer alive while the call runs
        return b

    crypt32 = ctypes.WinDLL("crypt32")
    kernel32 = ctypes.WinDLL("kernel32")
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    fn.argtypes = (ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                   wintypes.DWORD, ctypes.POINTER(Blob))
    fn.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = (ctypes.c_void_p,)
    src, entropy, out = blob(data), blob(_ENTROPY), Blob()
    if not fn(ctypes.byref(src), None, ctypes.byref(entropy), None, None, 0x1, ctypes.byref(out)):  # UI_FORBIDDEN
        raise OSError("Windows could not " + ("encrypt" if protect else "decrypt") + " the secret")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def protect(value: str) -> str:
    """Encrypts on Windows. Elsewhere returns the value unchanged (the file is mode 600)."""
    if not value or sys.platform != "win32" or value.startswith(PREFIX):
        return value
    return PREFIX + base64.b64encode(_dpapi(value.encode("utf-8"), True)).decode("ascii")


def reveal(value: str) -> str:
    if not value or not value.startswith(PREFIX):
        return value
    return _dpapi(base64.b64decode(value[len(PREFIX):]), False).decode("utf-8")
