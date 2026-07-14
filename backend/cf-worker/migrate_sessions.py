#!/usr/bin/env python3
"""One-time D1 field-encryption migration (safe to rerun).

Prerequisites:
  DATA_ENCRYPTION_KEY=<base64url 32-byte key>
  Cloudflare wrangler auth variables described in README.md

Run only after migrations/0002_encrypt_sessions.sql and the compatible Worker
have been deployed. Plaintext never prints; wrangler output is captured.
"""
import base64
import json
import os
import secrets
import subprocess

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


ROOT = os.path.dirname(os.path.abspath(__file__))
PREFIX = "enc:v1:"
FIELDS = ("refresh_token", "zoom_user_id", "name", "pmi", "zak")


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def unb64url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


key = unb64url(os.environ["DATA_ENCRYPTION_KEY"])
if len(key) != 32:
    raise SystemExit("DATA_ENCRYPTION_KEY must be base64url for exactly 32 bytes")
aes = AESGCM(key)


def aad(sid: str, field: str) -> bytes:
    return f"meetingsremote:{sid}:{field}:v1".encode()


def open_value(sid: str, field: str, stored: str) -> str:
    if not stored.startswith(PREFIX):
        return stored
    iv, ciphertext = stored[len(PREFIX):].split(":", 1)
    return aes.decrypt(unb64url(iv), unb64url(ciphertext), aad(sid, field)).decode()


def seal(sid: str, field: str, value: str) -> str:
    iv = secrets.token_bytes(12)
    ciphertext = aes.encrypt(iv, value.encode(), aad(sid, field))
    return f"{PREFIX}{b64url(iv)}:{b64url(ciphertext)}"


def d1(sql: str):
    proc = subprocess.run(
        ["npx", "wrangler", "d1", "execute", "meetingsremote", "--remote",
         "--json", "--command", sql],
        cwd=ROOT,
        env=os.environ,
        text=True,
        capture_output=True,
        check=True,
    )
    start = proc.stdout.find("[")
    payload = json.loads(proc.stdout[start:]) if start >= 0 else []
    return payload[0].get("results", []) if payload else []


rows = d1(
    "SELECT sid,refresh_token,zoom_user_id,name,pmi,zak "
    "FROM sessions ORDER BY sid LIMIT 10000"
)
for row in rows:
    sid = row["sid"]
    plain = {field: open_value(sid, field, row[field]) for field in FIELDS}
    encrypted = {field: seal(sid, field, plain[field]) for field in FIELDS}
    # HMAC-SHA256 with the encoded secret string matches Worker userHash().
    import hashlib
    import hmac
    user_hash = hmac.new(
        os.environ["DATA_ENCRYPTION_KEY"].encode(),
        f"zoom-user-id:v1:{plain['zoom_user_id']}".encode(),
        hashlib.sha256,
    ).hexdigest()
    assignments = ",".join(f"{f}='{encrypted[f]}'" for f in FIELDS)
    d1(f"UPDATE sessions SET {assignments},zoom_user_hash='{user_hash}' "
       f"WHERE sid='{sid}'")

remaining = d1(
    "SELECT COUNT(*) AS n FROM sessions WHERE "
    "refresh_token NOT LIKE 'enc:v1:%' OR zoom_user_id NOT LIKE 'enc:v1:%' OR "
    "name NOT LIKE 'enc:v1:%' OR pmi NOT LIKE 'enc:v1:%' OR "
    "zak NOT LIKE 'enc:v1:%' OR zoom_user_hash=''"
)
count = int(remaining[0]["n"]) if remaining else -1
if count:
    raise SystemExit(f"migration incomplete: {count} plaintext/unindexed rows remain")
print(f"encrypted {len(rows)} session row(s); plaintext rows remaining: 0")
