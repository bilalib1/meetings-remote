#!/usr/bin/env python3
"""AirPlay-2 screen-mirroring SENDER prototype: pair-verify (creds.json) ->
encrypted control channel -> RTSP SETUP x2 -> type-110 H.264 stream, unencrypted
(no ekey/eiv). Proves the protocol against the real TV before the Kotlin port.

Usage: airplay_mirror.py [file.h264]     (default test.h264, annex-B)
"""
import hashlib, json, os, plistlib, socket, struct, sys, threading, time, uuid

from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey)
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from airplay_hap import (HOST, PORT, UA, CREDS, IDENT, PUBKEY, ENC, STATE,
                         ERROR, SIG, tlv_parse, tlv_build, raw, hkdf, nonce, Conn)

DEVICE_ID = "C8:D0:83:AD:52:9B"  # any stable MAC-shaped id
SESSION_UUID = str(uuid.uuid4()).upper()

def log(*a): print(*a, flush=True)

# ------------------------------------------------------------- pair-verify
def pair_verify(c):
    d = json.load(open(CREDS))
    our_id = d["our_id"].encode()
    ltsk = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(d["ltsk"]))
    acc_ltpk = Ed25519PublicKey.from_public_bytes(bytes.fromhex(d["acc_ltpk"]))
    eph = X25519PrivateKey.generate(); eph_pub = raw(eph.public_key())
    r = c.post("/pair-verify", tlv_build([(STATE, b"\x01"), (PUBKEY, eph_pub)]), hkp="4")
    if ERROR in r: sys.exit(f"PV M2 error {r[ERROR].hex()}")
    acc_pub = r[PUBKEY]
    shared = eph.exchange(X25519PublicKey.from_public_bytes(acc_pub))
    sess_key = hkdf(b"Pair-Verify-Encrypt-Salt", b"Pair-Verify-Encrypt-Info", shared)
    info = tlv_parse(ChaCha20Poly1305(sess_key).decrypt(nonce(b"PV-Msg02"), r[ENC], None))
    acc_ltpk.verify(info[SIG], acc_pub + info[IDENT] + eph_pub)
    sub = tlv_build([(IDENT, our_id), (SIG, ltsk.sign(eph_pub + our_id + acc_pub))])
    enc = ChaCha20Poly1305(sess_key).encrypt(nonce(b"PV-Msg03"), sub, None)
    r = c.post("/pair-verify", tlv_build([(STATE, b"\x03"), (ENC, enc)]), hkp="4")
    if ERROR in r: sys.exit(f"PV M4 error {r[ERROR].hex()}")
    log("pair-verify OK")
    return shared

# ------------------------------------------- encrypted control channel (HAP)
class SecureChannel:
    """ChaCha20-Poly1305 framing over the pair-verified socket:
    2B LE plaintext-length (=AAD) | ciphertext | 16B tag, 64-bit LE counter
    nonce per direction, <=1024B plaintext per block."""
    def __init__(self, sock, shared):
        self.s = sock
        self.wc = ChaCha20Poly1305(hkdf(b"Control-Salt", b"Control-Write-Encryption-Key", shared))
        self.rc = ChaCha20Poly1305(hkdf(b"Control-Salt", b"Control-Read-Encryption-Key", shared))
        self.wn = self.rn = 0
        self.buf = b""

    def _nonce(self, n): return b"\x00" * 4 + struct.pack("<Q", n)

    def send(self, data):
        out = b""
        for i in range(0, len(data), 1024):
            chunk = data[i:i+1024]
            ln = struct.pack("<H", len(chunk))
            out += ln + self.wc.encrypt(self._nonce(self.wn), chunk, ln)
            self.wn += 1
        self.s.sendall(out)

    def _recv_exact(self, n):
        while len(self.buf) < n:
            d = self.s.recv(4096)
            if not d: raise ConnectionError("socket closed")
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv_some(self):
        ln = self._recv_exact(2)
        n = struct.unpack("<H", ln)[0]
        ct = self._recv_exact(n + 16)
        pt = self.rc.decrypt(self._nonce(self.rn), ct, ln)
        self.rn += 1
        return pt

    def request(self, method, path, body=b"", ctype="application/x-apple-binary-plist",
                proto="RTSP/1.0"):
        cseq = getattr(self, "cseq", 0) + 1; self.cseq = cseq
        hdr = (f"{method} {path} {proto}\r\nCSeq: {cseq}\r\nUser-Agent: {UA}\r\n"
               f"DACP-ID: 4358E1A05339E866\r\nActive-Remote: 1986535575\r\n"
               f"Client-Instance: 4358E1A05339E866\r\n")
        if body: hdr += f"Content-Type: {ctype}\r\nContent-Length: {len(body)}\r\n"
        self.send(hdr.encode() + b"\r\n" + body)
        raw_resp = b""
        while b"\r\n\r\n" not in raw_resp: raw_resp += self.recv_some()
        head, rest = raw_resp.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n")[0].decode()
        hdrs = dict(l.decode().split(":", 1) for l in head.split(b"\r\n")[1:] if b":" in l)
        n = int(hdrs.get("Content-Length", "0").strip())
        while len(rest) < n: rest += self.recv_some()
        log(f"{method} {path} -> {status} ({n}B)")
        if " 200 " not in status + " ": raise RuntimeError(f"{method} {path}: {status}")
        if rest[:6] == b"bplist": return plistlib.loads(rest)
        return rest

# ----------------------------------------------------------- H.264 source
def read_annexb(path):
    """Annex-B elementary stream -> list of NAL units (no start codes)."""
    data = open(path, "rb").read()
    nals, i = [], data.find(b"\x00\x00\x01") + 3
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            nals.append(data[i:].rstrip(b"\x00")); break
        end = j - 1 if data[j-1] == 0 else j
        nals.append(data[i:end]); i = j + 3
    return [n for n in nals if n]

def group_frames(nals):
    """Split NALs into access units; return (sps, pps, [(is_idr, [vcl+other nals])])."""
    sps = pps = None
    frames, cur = [], []
    for n in nals:
        t = n[0] & 0x1F
        if t == 7: sps = n; continue
        if t == 8: pps = n; continue
        if t in (6, 9):  # SEI/AUD ride along
            cur.append(n); continue
        cur.append(n)
        frames.append((t == 5, cur)); cur = []
    return sps, pps, frames

def avcc_record(sps, pps):
    return (bytes([1, sps[1], sps[2], sps[3], 0xFF, 0xE1]) +
            struct.pack(">H", len(sps)) + sps + b"\x01" +
            struct.pack(">H", len(pps)) + pps)

# --------------------------------------------------- mirror stream cipher
class StreamCipher:
    """AES-CTR keystream the receiver derives from the pair-verify ECDH secret
    and streamConnectionID (mirror_buffer.c). ekey omitted -> aeskey = 16 zeros.
    Video (type 0) is one contiguous CTR stream; SPS/PPS (type 1) stays plaintext."""
    def __init__(self, ecdh_secret, scid):
        eaeskey = hashlib.sha512(b"\x00" * 16 + ecdh_secret).digest()[:16]
        key = hashlib.sha512(b"AirPlayStreamKey%d" % scid + eaeskey).digest()[:16]
        iv = hashlib.sha512(b"AirPlayStreamIV%d" % scid + eaeskey).digest()[:16]
        self.enc = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()

    def encrypt(self, data):
        return self.enc.update(data)

# ------------------------------------------------------------- mirror data
def ntp_now():
    t = time.monotonic()
    return (int(t) << 32) | int((t % 1.0) * (1 << 32))

def mirror_header(size, ptype, ntp=0, w=1280.0, h=720.0):
    hd = bytearray(128)
    struct.pack_into("<IHH", hd, 0, size, ptype, 0)
    struct.pack_into("<Q", hd, 8, ntp)
    if ptype == 1:
        struct.pack_into("<f", hd, 40, w); struct.pack_into("<f", hd, 44, h)
        struct.pack_into("<f", hd, 56, w); struct.pack_into("<f", hd, 60, h)
    return bytes(hd)

def stream_video(data_sock, path, cipher, fps=30):
    nals = read_annexb(path)
    sps, pps, frames = group_frames(nals)
    log(f"h264: {len(frames)} frames, sps={len(sps)}B pps={len(pps)}B")
    rec = avcc_record(sps, pps)  # SPS/PPS: type 1, plaintext
    data_sock.sendall(mirror_header(len(rec), 1) + rec)
    t0, n = time.monotonic(), 0
    for idr, fnals in frames:
        payload = b"".join(struct.pack(">I", len(x)) + x for x in fnals)
        payload = cipher.encrypt(payload)  # type 0: AES-CTR
        data_sock.sendall(mirror_header(len(payload), 0, ntp_now()) + payload)
        n += 1
        time.sleep(max(0, t0 + n / fps - time.monotonic()))
        if n % 30 == 0: log(f"  sent {n} frames")
    log("stream done")

# ------------------------------------------------------------------- main
def main():
    h264 = sys.argv[1] if len(sys.argv) > 1 else "test.h264"
    c = Conn()
    shared = pair_verify(c)
    ch = SecureChannel(c.s, shared)

    info = ch.request("GET", "/info")
    log(f"/info: model={info.get('model')} name={info.get('name')} "
        f"features=0x{info.get('features', 0):X}")

    local_ip = c.s.getsockname()[0]
    setup1 = {
        "deviceID": DEVICE_ID,
        "sessionUUID": SESSION_UUID,
        "name": "ZoomRoom",
        "model": "AppleTV3,2",
        "osName": "iPhone OS", "osVersion": "17.4", "sourceVersion": "770.8.1",
        "timingProtocol": "None",
        "isScreenMirroringSession": True,
        "osBuildVersion": "21E219",
        "macAddress": DEVICE_ID,
    }
    r1 = ch.request("SETUP", f"rtsp://{local_ip}/{SESSION_UUID}",
                    plistlib.dumps(setup1, fmt=plistlib.FMT_BINARY))
    log(f"SETUP1 resp: {r1}")

    event_port = r1.get("eventPort")
    ev = None
    if event_port:
        ev = socket.create_connection((HOST, event_port), timeout=5)
        log(f"event channel connected :{event_port}")

    scid = 0x1122334455667788
    setup2 = {"streams": [{
        "type": 110,
        "streamConnectionID": scid,
        "latencyMs": 90,
    }]}
    r2 = ch.request("SETUP", f"rtsp://{local_ip}/{SESSION_UUID}",
                    plistlib.dumps(setup2, fmt=plistlib.FMT_BINARY))
    log(f"SETUP2 resp: {r2}")
    data_port = r2["streams"][0]["dataPort"]

    try:
        ch.request("RECORD", f"rtsp://{local_ip}/{SESSION_UUID}")
    except Exception as e:
        log(f"RECORD skipped: {e}")

    # feedback heartbeat keeps the session alive
    stop = threading.Event()
    def heartbeat():
        while not stop.is_set():
            try: ch.request("POST", "/feedback")
            except Exception as e: log(f"feedback: {e}"); return
            stop.wait(2.0)
    threading.Thread(target=heartbeat, daemon=True).start()

    data_sock = socket.create_connection((HOST, data_port), timeout=30)
    log(f"data channel connected :{data_port} — LOOK AT THE TV")
    cipher = StreamCipher(shared, scid)
    try:
        stream_video(data_sock, h264, cipher)
    finally:
        stop.set()
        try: ch.request("TEARDOWN", f"rtsp://{local_ip}/{SESSION_UUID}")
        except Exception: pass
        data_sock.close()
        if ev: ev.close()

if __name__ == "__main__":
    main()
