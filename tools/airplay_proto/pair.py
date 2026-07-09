#!/usr/bin/env python3
"""Prove AirPlay-2 transient pairing (SRP-6a, fixed PIN 3939) against the real TV.

If M4 (server proof) verifies, PINless pairing works and we can derive the
session key. This de-risks the Kotlin sender before we write any.
"""
import hashlib, os, sys, socket

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.233"
PORT = 7000
UA = "AirPlay/377.40.00"

# RFC 5054 3072-bit group (N, g=5) — the group AirPlay/HomeKit SRP uses.
N_HEX = (
"FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
"020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
"4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
"EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
"98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
"9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
"E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
"3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
"A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
"ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
"D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
"08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF")
N = int(N_HEX, 16)
g = 5
WIDTH = (N.bit_length() + 7) // 8  # 384

def H(*chunks):
    h = hashlib.sha512()
    for c in chunks:
        h.update(c)
    return h.digest()

def pad(x: int) -> bytes:
    return x.to_bytes(WIDTH, "big")

def tlv_parse(b):
    out, i = {}, 0
    while i < len(b):
        t, l = b[i], b[i+1]; i += 2
        v = b[i:i+l]; i += l
        out[t] = out.get(t, b"") + v  # concat fragmented entries
    return out

def tlv_build(d):
    out = b""
    for t, v in d:
        while True:
            chunk, v = v[:255], v[255:]
            out += bytes([t, len(chunk)]) + chunk
            if not v:
                break
    return out

_sock = None
def _connect():
    global _sock
    _sock = socket.create_connection((HOST, PORT), timeout=8)

def post(path, body):
    """POST on the ONE persistent connection (AirPlay pairing is stateful)."""
    if _sock is None:
        _connect()
    req = (f"POST {path} HTTP/1.1\r\nHost: {HOST}\r\nUser-Agent: {UA}\r\n"
           f"X-Apple-HKP: 4\r\nContent-Type: application/octet-stream\r\n"
           f"Content-Length: {len(body)}\r\nConnection: keep-alive\r\n\r\n").encode() + body
    _sock.sendall(req)
    # read headers
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += _sock.recv(4096)
    head, rest = buf.split(b"\r\n\r\n", 1)
    status = head.split(b"\r\n", 1)[0].decode()
    hdrs = {}
    for line in head.split(b"\r\n")[1:]:
        k, _, v = line.partition(b":")
        hdrs[k.strip().lower()] = v.strip()
    n = int(hdrs.get(b"content-length", b"0"))
    body_out = rest
    while len(body_out) < n:
        body_out += _sock.recv(4096)
    if "200" not in status:
        raise RuntimeError(f"{status}  body={body_out[:64].hex()}")
    return body_out

# ---- M1: start transient pair-setup ----
STATE, METHOD, SALT, PUBKEY, PROOF, ERROR, FLAGS = 6, 0, 2, 3, 4, 7, 0x13
# Flags is read big-endian by the receiver and must EQUAL 0x10 for transient —
# send a single byte, not 4-byte little-endian (that reads as 0x10000000).
m1 = tlv_build([(METHOD, b"\x00"), (FLAGS, b"\x10"), (STATE, b"\x01")])
r = tlv_parse(post("/pair-setup", m1))
if ERROR in r:
    print("M2 ERROR:", r[ERROR].hex()); sys.exit(1)
salt, B = r[SALT], int.from_bytes(r[PUBKEY], "big")
print(f"M2 ok: salt={salt.hex()} B={len(r[PUBKEY])}B")

# ---- SRP-6a client via srptools (identical to pyatv) ----
from srptools import SRPContext, SRPClientSession, constants
ctx = SRPContext("Pair-Setup", "3939",
                 prime=constants.PRIME_3072, generator=constants.PRIME_3072_GEN,
                 hash_func=hashlib.sha512)
sess = SRPClientSession(ctx)
def tob(v):
    return v if isinstance(v, bytes) else bytes.fromhex(v if len(v) % 2 == 0 else "0" + v)
A_bytes = tob(sess.public)
sess.process(B.to_bytes(WIDTH, "big").hex(), salt.hex())
M1proof = tob(sess.key_proof)

# ---- M3: send A + proof ----
m3 = tlv_build([(STATE, b"\x03"), (PUBKEY, A_bytes), (PROOF, M1proof)])
r = tlv_parse(post("/pair-setup", m3))
if ERROR in r:
    print("M4 ERROR (proof rejected):", r[ERROR].hex()); sys.exit(2)
HAMK = r.get(PROOF, b"")
print(f"M4 received, server proof = {HAMK.hex()[:32]}…")
print("\n*** TRANSIENT PAIRING SUCCEEDED — no PIN needed. ***")
# Derive the ChaCha20-Poly1305 session key from the SRP shared secret K.
K = tob(sess.key)
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
def hkdf(salt, info, ikm, n=32):
    return HKDF(algorithm=hashes.SHA512(), length=n, salt=salt, info=info).derive(ikm)
enc = hkdf(b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info", K)
print("SRP shared K   =", K.hex()[:48], "…")
print("session enc key=", enc.hex())
