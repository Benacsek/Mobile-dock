"""Wire protocol and cryptography shared with the Android app.

Everything here must stay byte-for-byte compatible with
android/app/src/main/java/dev/mobiledock/proto/. See docs/PROTOCOL.md.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import socket
import struct

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PROTOCOL_VERSION = 1
TCP_PORT = 47800
UDP_PORT = 47801
DISCOVERY_PROBE = b"MDOCK1?"
MAX_FRAME = 1 << 20  # 1 MiB

# RFC 3526 group 14 (2048-bit MODP). p is a safe prime, g = 2 generates the
# subgroup of prime order q = (p - 1) / 2.
P = int(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
    "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
    "15728E5A8AACAA68FFFFFFFFFFFFFFFF",
    16,
)
Q = (P - 1) // 2
G = 2
ELEMENT_LEN = 256


def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for part in parts:
        h.update(part)
    return h.digest()


def hmac256(key: bytes, *parts: bytes) -> bytes:
    m = hmac.new(key, digestmod=hashlib.sha256)
    for part in parts:
        m.update(part)
    return m.digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF with SHA-256."""
    prk = hmac256(salt or b"\x00" * 32, ikm)
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac256(prk, block, info, bytes([counter]))
        out += block
        counter += 1
    return out[:length]


def lp(data: bytes) -> bytes:
    """Length-prefixed field for transcripts."""
    return struct.pack(">I", len(data)) + data


def i2b(value: int) -> bytes:
    return value.to_bytes(ELEMENT_LEN, "big")


def _hash_to_group(label: bytes) -> int:
    stream = b"".join(sha256(label, bytes([i])) for i in range(9))
    return pow(int.from_bytes(stream, "big") % P, 2, P)


SPAKE_M = _hash_to_group(b"MobileDock SPAKE2 M")
SPAKE_N = _hash_to_group(b"MobileDock SPAKE2 N")


def pin_scalar(pin: str) -> int:
    return int.from_bytes(sha256(b"MobileDock PIN v1", pin.encode("utf-8")), "big") % Q


def random_scalar() -> int:
    while True:
        x = int.from_bytes(os.urandom(ELEMENT_LEN), "big") % Q
        if x > 1:
            return x


def valid_element(v: int) -> bool:
    return 1 < v < P - 1 and pow(v, Q, P) == 1


class Spake2:
    """SPAKE2 over the RFC 3526 MODP group. Role "client" is the phone."""

    def __init__(self, pin: str, role: str):
        if role not in ("client", "server"):
            raise ValueError(role)
        self.role = role
        self.w = pin_scalar(pin)
        self.secret = random_scalar()
        mine, self._theirs = (SPAKE_M, SPAKE_N) if role == "client" else (SPAKE_N, SPAKE_M)
        self.public = (pow(G, self.secret, P) * pow(mine, self.w, P)) % P

    def finish(self, peer_public: int) -> "PairKeys":
        if not valid_element(peer_public):
            raise ProtocolError("invalid group element")
        blind = pow(pow(self._theirs, self.w, P), -1, P)
        z = pow((peer_public * blind) % P, self.secret, P)
        x, y = (self.public, peer_public) if self.role == "client" else (peer_public, self.public)
        transcript = b"".join(
            [lp(b"MobileDock-SPAKE2-v1"), lp(i2b(x)), lp(i2b(y)), lp(i2b(z)), lp(i2b(self.w))]
        )
        return PairKeys(sha256(transcript))


class PairKeys:
    def __init__(self, k: bytes):
        material = hkdf(k, b"", b"mdock pair confirm v1", 64)
        self.client_confirm = hmac256(material[:32], b"client")
        self.server_confirm = hmac256(material[32:], b"server")
        self.session = hkdf(k, b"", b"mdock session v1", 128)


def reconnect_keys(secret: bytes, device_id: str, nc: bytes, ns: bytes):
    """Returns (client_mac, server_mac, session_material) for a paired device."""
    salt = nc + ns
    auth = hkdf(secret, salt, b"mdock auth v1|" + device_id.encode(), 64)
    session = hkdf(secret, salt, b"mdock session v1|" + device_id.encode(), 128)
    return hmac256(auth[:32], b"client"), hmac256(auth[32:], b"server"), session


class ProtocolError(Exception):
    pass


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed")
        buf.extend(chunk)
    return bytes(buf)


def recv_frame(sock: socket.socket) -> bytes:
    (length,) = struct.unpack(">I", _recv_exact(sock, 4))
    if length == 0 or length > MAX_FRAME:
        raise ProtocolError("bad frame length")
    return _recv_exact(sock, length)


def send_frame(sock: socket.socket, payload: bytes) -> None:
    if len(payload) > MAX_FRAME:
        raise ProtocolError("frame too large")
    sock.sendall(struct.pack(">I", len(payload)) + payload)


def encode_json(obj) -> bytes:
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


def decode_json(data: bytes) -> dict:
    try:
        obj = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("bad json") from exc
    if not isinstance(obj, dict):
        raise ProtocolError("expected object")
    return obj


class SecureChannel:
    """AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC) with implicit sequence numbers.

    Chosen because every primitive exists on every Android version without
    extra libraries. Each direction has its own keys and counter, so frames
    cannot be replayed, reordered or reflected.
    """

    def __init__(self, sock: socket.socket, material: bytes, role: str):
        c2s_enc, c2s_mac, s2c_enc, s2c_mac = (material[i : i + 32] for i in range(0, 128, 32))
        if role == "server":
            self._tx, self._rx = (s2c_enc, s2c_mac), (c2s_enc, c2s_mac)
        else:
            self._tx, self._rx = (c2s_enc, c2s_mac), (s2c_enc, s2c_mac)
        self.sock = sock
        self._tx_seq = 0
        self._rx_seq = 0

    def send(self, obj) -> None:
        enc_key, mac_key = self._tx
        iv = os.urandom(16)
        padder = padding.PKCS7(128).padder()
        plain = padder.update(encode_json(obj)) + padder.finalize()
        enc = Cipher(algorithms.AES(enc_key), modes.CBC(iv)).encryptor()
        ct = enc.update(plain) + enc.finalize()
        tag = hmac256(mac_key, struct.pack(">Q", self._tx_seq), iv, ct)
        self._tx_seq += 1
        send_frame(self.sock, iv + ct + tag)

    def recv(self) -> dict:
        frame = recv_frame(self.sock)
        if len(frame) < 16 + 16 + 32 or (len(frame) - 48) % 16:
            raise ProtocolError("bad encrypted frame")
        enc_key, mac_key = self._rx
        iv, ct, tag = frame[:16], frame[16:-32], frame[-32:]
        expected = hmac256(mac_key, struct.pack(">Q", self._rx_seq), iv, ct)
        if not hmac.compare_digest(tag, expected):
            raise ProtocolError("bad mac")
        self._rx_seq += 1
        dec = Cipher(algorithms.AES(enc_key), modes.CBC(iv)).decryptor()
        unpadder = padding.PKCS7(128).unpadder()
        plain = unpadder.update(dec.update(ct) + dec.finalize()) + unpadder.finalize()
        return decode_json(plain)
