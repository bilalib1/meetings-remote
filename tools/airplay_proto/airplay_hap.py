#!/usr/bin/env python3
"""AirPlay-2 HomeKit pairing (sender side): persistent pair-setup with a PIN,
then PIN-less pair-verify on later connects. Validated against the real TV
before porting to Kotlin.

Usage:
  airplay_hap.py trigger              # M1/M2 only -> makes the TV show its code
  airplay_hap.py setup <PIN>          # full M1-M6, stores creds to creds.json
  airplay_hap.py verify               # pair-verify from creds.json (no PIN)
"""
import hashlib, json, os, socket, sys, uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey)
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization
from srptools import SRPContext, SRPClientSession, constants

HOST = os.environ.get("TV", "192.168.1.233")
PORT, UA = 7000, "AirPlay/377.40.00"
CREDS = os.path.join(os.path.dirname(__file__), "creds.json")

# TLV8 tags
METHOD, IDENT, SALT, PUBKEY, PROOF, ENC, STATE, ERROR, SIG, PERM, FLAGS = \
    0, 1, 2, 3, 4, 5, 6, 7, 0x0A, 0x0B, 0x13

def H(*c):
    h = hashlib.sha512()
    [h.update(x) for x in c]; return h.digest()

def hkdf(salt, info, ikm, n=32):
    return HKDF(algorithm=hashes.SHA512(), length=n, salt=salt, info=info).derive(ikm)

def tlv_parse(b):
    o, i = {}, 0
    while i < len(b):
        t, l = b[i], b[i+1]; o[t] = o.get(t, b"") + b[i+2:i+2+l]; i += 2+l
    return o

def tlv_build(items):
    o = b""
    for t, v in items:
        while True:
            c, v = v[:255], v[255:]; o += bytes([t, len(c)]) + c
            if not v: break
    return o

def raw(k):  # raw 32 bytes of an ed25519/x25519 key
    return k.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

class Conn:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=20)
    def pin_start(self):
        # Tells the receiver to display its on-screen pairing code.
        self.s.sendall((f"POST /pair-pin-start HTTP/1.1\r\nHost: {HOST}\r\n"
                        f"User-Agent: {UA}\r\nContent-Length: 0\r\n"
                        f"Connection: keep-alive\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf: buf += self.s.recv(4096)
        status = buf.split(b"\r\n")[0].decode()
        print(f"/pair-pin-start: {status}")
    def post(self, path, body, hkp="3"):
        self.s.sendall((f"POST {path} HTTP/1.1\r\nHost: {HOST}\r\nUser-Agent: {UA}\r\n"
                        f"X-Apple-HKP: {hkp}\r\nContent-Type: application/octet-stream\r\n"
                        f"Content-Length: {len(body)}\r\nConnection: keep-alive\r\n\r\n").encode()+body)
        buf = b""
        while b"\r\n\r\n" not in buf: buf += self.s.recv(4096)
        head, rest = buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n")[0].decode()
        n = int(dict(l.split(b":", 1) for l in head.split(b"\r\n")[1:] if b":" in l)
                .get(b"Content-Length", b"0"))
        while len(rest) < n: rest += self.s.recv(4096)
        if "200" not in status: raise RuntimeError(f"{path}: {status} {rest[:60].hex()}")
        return tlv_parse(rest)

def srp_client(salt, B, pin):
    ctx = SRPContext("Pair-Setup", str(pin), prime=constants.PRIME_3072,
                     generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
    sess = SRPClientSession(ctx)
    def unhex(v):  # srptools returns hex as str OR hex-encoded bytes
        if isinstance(v, bytes): v = v.decode()
        return bytes.fromhex(v if len(v) % 2 == 0 else "0"+v)
    A = unhex(sess.public)
    sess.process(B.hex(), salt.hex())
    return A, unhex(sess.key_proof), unhex(sess.key)  # A, M1proof, K(64B)

def nonce(s):  # 8-byte ASCII label -> 12-byte HAP nonce
    return b"\x00\x00\x00\x00" + s

PIN_FILE = os.path.join(os.path.dirname(__file__), "pin.txt")

def wait_for_pin(timeout=180):
    # Session must stay open while the human reads the code off the TV,
    # so poll for pin.txt instead of stdin (runs unattended).
    import time
    if os.path.exists(PIN_FILE): os.remove(PIN_FILE)
    print(f"Waiting for code: echo NNNN > {PIN_FILE}", flush=True)
    for _ in range(timeout):
        if os.path.exists(PIN_FILE):
            pin = open(PIN_FILE).read().strip()
            if pin: return pin
        time.sleep(1)
    sys.exit("timed out waiting for pin.txt")

# ---------------------------------------------------------------- pair-setup
def do_setup(pin=None):
    c = Conn()
    c.pin_start()
    r = c.post("/pair-setup", tlv_build([(METHOD, b"\x00"), (STATE, b"\x01")]))
    if ERROR in r: sys.exit(f"M2 error {r[ERROR].hex()}")
    salt, B = r[SALT], r[PUBKEY]
    if not pin:
        pin = wait_for_pin()
    A, M1, K = srp_client(salt, B, pin)
    r = c.post("/pair-setup", tlv_build([(STATE, b"\x03"), (PUBKEY, A), (PROOF, M1)]))
    if ERROR in r: sys.exit(f"M4 error {r[ERROR].hex()} (wrong PIN?)")
    print("M4 ok — PIN accepted.")

    # our long-term identity (persisted for pair-verify)
    our_id = str(uuid.uuid4()).encode()
    ltsk = Ed25519PrivateKey.generate()
    ltpk = raw(ltsk.public_key())
    sess_key = hkdf(b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info", K)
    dev_x = hkdf(b"Pair-Setup-Controller-Sign-Salt", b"Pair-Setup-Controller-Sign-Info", K)
    sig = ltsk.sign(dev_x + our_id + ltpk)
    sub = tlv_build([(IDENT, our_id), (PUBKEY, ltpk), (SIG, sig)])
    enc = ChaCha20Poly1305(sess_key).encrypt(nonce(b"PS-Msg05"), sub, None)
    r = c.post("/pair-setup", tlv_build([(STATE, b"\x05"), (ENC, enc)]))
    if ERROR in r: sys.exit(f"M6 error {r[ERROR].hex()}")
    dec = ChaCha20Poly1305(sess_key).decrypt(nonce(b"PS-Msg06"), r[ENC], None)
    acc = tlv_parse(dec)
    acc_id, acc_ltpk = acc[IDENT], acc[PUBKEY]
    json.dump({
        "our_id": our_id.decode(),
        "ltsk": ltsk.private_bytes(serialization.Encoding.Raw,
            serialization.PrivateFormat.Raw, serialization.NoEncryption()).hex(),
        "acc_id": acc_id.decode(errors="replace"),
        "acc_ltpk": acc_ltpk.hex(),
    }, open(CREDS, "w"), indent=2)
    print(f"*** PAIRED. Stored creds for accessory {acc_id.decode(errors='replace')}. ***")
    print(f"    accessory LTPK={acc_ltpk.hex()[:32]}…")

# --------------------------------------------------------------- pair-verify
def do_verify():
    d = json.load(open(CREDS))
    our_id = d["our_id"].encode()
    ltsk = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(d["ltsk"]))
    acc_ltpk = Ed25519PublicKey.from_public_bytes(bytes.fromhex(d["acc_ltpk"]))
    c = Conn()
    eph = X25519PrivateKey.generate(); eph_pub = raw(eph.public_key())
    r = c.post("/pair-verify", tlv_build([(STATE, b"\x01"), (PUBKEY, eph_pub)]), hkp="4")
    if ERROR in r: sys.exit(f"PV M2 error {r[ERROR].hex()}")
    acc_pub = r[PUBKEY]
    shared = eph.exchange(X25519PublicKey.from_public_bytes(acc_pub))
    sess_key = hkdf(b"Pair-Verify-Encrypt-Salt", b"Pair-Verify-Encrypt-Info", shared)
    dec = ChaCha20Poly1305(sess_key).decrypt(nonce(b"PV-Msg02"), r[ENC], None)
    info = tlv_parse(dec)
    acc_ltpk.verify(info[SIG], acc_pub + info[IDENT] + eph_pub)  # raises if bad
    our_sig = ltsk.sign(eph_pub + our_id + acc_pub)
    sub = tlv_build([(IDENT, our_id), (SIG, our_sig)])
    enc = ChaCha20Poly1305(sess_key).encrypt(nonce(b"PV-Msg03"), sub, None)
    r = c.post("/pair-verify", tlv_build([(STATE, b"\x03"), (ENC, enc)]), hkp="4")
    if ERROR in r: sys.exit(f"PV M4 error {r[ERROR].hex()}")
    # control-channel keys (for the encrypted RTSP session)
    wkey = hkdf(b"Control-Salt", b"Control-Write-Encryption-Key", shared)
    rkey = hkdf(b"Control-Salt", b"Control-Read-Encryption-Key", shared)
    print("*** PAIR-VERIFY OK (no PIN). Encrypted session established. ***")
    print(f"    shared={shared.hex()[:32]}…  write={wkey.hex()[:16]}…  read={rkey.hex()[:16]}…")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "verify"
    if cmd == "trigger":
        c = Conn()
        c.pin_start()
        r = c.post("/pair-setup", tlv_build([(METHOD, b"\x00"), (STATE, b"\x01")]))
        print("M2:", "error "+r[ERROR].hex() if ERROR in r else "salt+B received — LOOK AT TV FOR CODE")
        import time; time.sleep(2)
    elif cmd == "setup":
        do_setup(sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        do_verify()
