#!/usr/bin/env python3
"""Try SRP transient proof variants against the real TV to isolate the reject."""
import hashlib, os, sys, socket

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.233"
PORT, UA = 7000, "AirPlay/377.40.00"
N = int((
"FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
"EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
"EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
"83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
"E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
"15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
"ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C"
"BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"), 16)
g, WIDTH = 5, 384
def H(*c):
    h = hashlib.sha512()
    [h.update(x) for x in c]; return h.digest()
pad = lambda x: x.to_bytes(WIDTH, "big")
def tlv_p(b):
    o, i = {}, 0
    while i < len(b):
        t, l = b[i], b[i+1]; o[t] = o.get(t, b"") + b[i+2:i+2+l]; i += 2 + l
    return o
def tlv_b(d):
    o = b""
    for t, v in d:
        while True:
            c, v = v[:255], v[255:]; o += bytes([t, len(c)]) + c
            if not v: break
    return o
def session(m1_flags, kpad):
    s = socket.create_connection((HOST, PORT), timeout=8)
    def post(body):
        s.sendall((f"POST /pair-setup HTTP/1.1\r\nHost: {HOST}\r\nUser-Agent: {UA}\r\n"
                   f"X-Apple-HKP: 4\r\nContent-Type: application/octet-stream\r\n"
                   f"Content-Length: {len(body)}\r\nConnection: keep-alive\r\n\r\n").encode()+body)
        buf = b""
        while b"\r\n\r\n" not in buf: buf += s.recv(4096)
        head, rest = buf.split(b"\r\n\r\n", 1)
        n = int(dict(l.split(b":",1) for l in head.split(b"\r\n")[1:] if b":" in l).get(b"Content-Length", b"0"))
        while len(rest) < n: rest += s.recv(4096)
        return head.split(b"\r\n")[0].decode(), tlv_p(rest)
    st, r = post(tlv_b([(0, b"\x00"), (0x13, m1_flags), (6, b"\x01")]))
    if 7 in r: return f"M2 err {r[7].hex()}"
    salt, B = r[2], int.from_bytes(r[3], "big")
    a = int.from_bytes(os.urandom(32), "big"); A = pow(g, a, N)
    k = int.from_bytes(H(pad(N), pad(g)), "big")
    x = int.from_bytes(H(salt, H(b"Pair-Setup:3939")), "big")
    u = int.from_bytes(H(pad(A), pad(B)), "big")
    S = pow((B - k*pow(g, x, N)) % N, a + u*x, N)
    K = H(pad(S)) if kpad else H(S.to_bytes((S.bit_length()+7)//8, "big"))
    M1 = H(bytes(p ^ q for p, q in zip(H(pad(N)), H(pad(g)))), H(b"Pair-Setup"), salt, pad(A), pad(B), K)
    st, r = post(tlv_b([(6, b"\x03"), (3, pad(A)), (4, M1)]))
    s.close()
    return "M4 err "+r[7].hex() if 7 in r else "*** SUCCESS (M4 proof, no error) ***"

for name, flags, kpad in [
    ("flag=0x10 1B, K=H(pad S)", b"\x10", True),
    ("flag=0x10 1B, K=H(min S)", b"\x10", False),
    ("flag=0x18 1B, K=H(pad S)", b"\x18", True),
    ("flag=0x10 4B-BE, K=H(pad S)", b"\x00\x00\x00\x10", True),
]:
    try: print(f"{name:32} -> {session(flags, kpad)}")
    except Exception as e: print(f"{name:32} -> EXC {e}")
