#!/usr/bin/env python3
"""Production token backend for Meetings Remote (plan: 2026-07-08-playstore-and-oauth.md).

Runs on the Hetzner 16GB box behind Caddy (TLS); Postgres lives on the 32GB box
over the private network (10.0.0.2). Keeps every Zoom secret off the tablet.

  GET  /health                     -> ok
  GET  /sdk-jwt                    -> Meeting SDK JWT (join needs only this)
  GET  /oauth/start?sid=           -> 302 to Zoom consent (PKCE, state=sid)
  GET  /oauth/callback?code&state  -> exchange code, store refresh token, bounce to app
  GET  /session?sid=               -> {ready, name, zak, pmi}
  GET  /refresh?sid=               -> fresh ZAK via stored (rotating) refresh token
  GET  /signout?sid=               -> revoke at Zoom + delete row
  GET  /end-stuck-meeting?sid=     -> force-end the signed-in user's stranded PMI
  POST /deauthorize                -> Zoom uninstall webhook (delete data + confirm)
  GET  /delete                     -> public account-deletion page (Play requirement)
  GET  /return?sid=                -> App Link landing (fallback HTML if app absent)
  GET  /.well-known/assetlinks.json

Auth model: the tablet holds only `sid` (opaque 128-bit). Refresh tokens never
leave this server. PKCE verifier is generated and kept server-side, keyed by sid.

Env (backend/.env on the box):
  ZOOM_SDK_CLIENT_ID / ZOOM_SDK_CLIENT_SECRET
  ZOOM_OAUTH_CLIENT_ID [/ ZOOM_OAUTH_CLIENT_SECRET if not a public client]
  ZOOM_WEBHOOK_SECRET_TOKEN          # from the Marketplace app's Feature page
  PUBLIC_BASE       e.g. https://api.meetingsremote.app
  APP_LINK_BASE     e.g. https://meetingsremote.app   (serves /return to the app)
  ASSETLINKS_JSON   path to assetlinks.json (optional until the app link ships)
  DATABASE_URL      e.g. postgresql://meetingsremote:pw@10.0.0.2/meetingsremote
  PORT              default 8791 (loopback; Caddy fronts it)
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import psycopg2
import psycopg2.pool

MAX_BODY = 64 * 1024          # webhooks only; nothing bigger is legitimate
PENDING_TTL = 600             # sid waiting for the OAuth round-trip
ZAK_CACHE_TTL = 3600          # ZAK lives ~2h; cache half that


# ---------------------------------------------------------------- rate limit
class RateLimiter:
    """Token bucket per key. This backend serves a handful of tablets doing
    auth — anything high-volume is abuse, so limits are deliberately tight."""

    def __init__(self, rate_per_min: float, burst: int):
        self.rate = rate_per_min / 60.0
        self.burst = burst
        self.buckets = {}  # key -> [tokens, last_ts]
        self.lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self.lock:
            if len(self.buckets) > 10000:  # abuse guard: drop state, not service
                self.buckets.clear()
            tokens, last = self.buckets.get(key, (self.burst, now))
            tokens = min(self.burst, tokens + (now - last) * self.rate)
            if tokens < 1:
                self.buckets[key] = (tokens, now)
                return False
            self.buckets[key] = (tokens - 1, now)
            return True


LIMIT_GENERAL = RateLimiter(rate_per_min=60, burst=20)   # per IP, whole API
LIMIT_AUTH = RateLimiter(rate_per_min=10, burst=5)       # per IP, /oauth/* + /signout
LIMIT_GLOBAL = RateLimiter(rate_per_min=600, burst=200)  # whole server ceiling


# ---------------------------------------------------------------- database
POOL = None


def db():
    global POOL
    if POOL is None:
        POOL = psycopg2.pool.ThreadedConnectionPool(
            1, 8, os.environ["DATABASE_URL"])
    return POOL


def q(sql, args=(), fetch=False):
    pool = db()
    conn = pool.getconn()
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchall() if fetch else None
    finally:
        pool.putconn(conn)


def init_schema():
    q("""CREATE TABLE IF NOT EXISTS sessions (
           sid TEXT PRIMARY KEY,
           refresh_token TEXT NOT NULL,
           zoom_user_id TEXT NOT NULL DEFAULT '',
           name TEXT NOT NULL DEFAULT '',
           pmi TEXT NOT NULL DEFAULT '',
           zak TEXT NOT NULL DEFAULT '',
           zak_ts DOUBLE PRECISION NOT NULL DEFAULT 0,
           created_at DOUBLE PRECISION NOT NULL,
           last_used_at DOUBLE PRECISION NOT NULL)""")
    q("""CREATE TABLE IF NOT EXISTS oauth_pending (
           sid TEXT PRIMARY KEY,
           verifier TEXT NOT NULL,
           created_at DOUBLE PRECISION NOT NULL)""")


# ---------------------------------------------------------------- helpers
def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def sign_sdk_jwt(ttl=48 * 3600) -> str:
    key = os.environ["ZOOM_SDK_CLIENT_ID"]
    secret = os.environ["ZOOM_SDK_CLIENT_SECRET"]
    iat = int(time.time()) - 30
    exp = iat + ttl
    header = _b64(json.dumps({"alg": "HS256", "typ": "JWT"},
                             separators=(",", ":")).encode())
    payload = _b64(json.dumps(
        {"appKey": key, "sdkKey": key, "role": 0, "iat": iat, "exp": exp,
         "tokenExp": exp}, separators=(",", ":")).encode())
    sig = _b64(hmac.new(secret.encode(), f"{header}.{payload}".encode(),
                        hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


def _http_json(url, data=None, headers=None, method=None):
    body = urllib.parse.urlencode(data).encode() if isinstance(data, dict) else data
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def _token_request(form: dict):
    """POST to zoom.us/oauth/token. Public client (PKCE, no secret) unless a
    client secret is configured, then confidential w/ Basic auth."""
    cid = os.environ["ZOOM_OAUTH_CLIENT_ID"]
    csec = os.environ.get("ZOOM_OAUTH_CLIENT_SECRET", "")
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if csec:
        headers["Authorization"] = "Basic " + base64.b64encode(
            f"{cid}:{csec}".encode()).decode()
    else:
        form["client_id"] = cid
    return _http_json("https://zoom.us/oauth/token", form, headers, method="POST")


def _me_and_zak(access_token: str):
    auth = {"Authorization": f"Bearer {access_token}"}
    me = _http_json("https://api.zoom.us/v2/users/me", headers=auth)
    zak = _http_json("https://api.zoom.us/v2/users/me/token?type=zak",
                     headers=auth)["token"]
    name = (me.get("first_name", "") + " " + me.get("last_name", "")).strip() or "Host"
    return me.get("id", ""), name, str(me.get("pmi", "") or ""), zak


def _refresh_session(sid: str):
    """Rotate the refresh token, fetch a fresh ZAK, update the row."""
    rows = q("SELECT refresh_token FROM sessions WHERE sid=%s", (sid,), fetch=True)
    if not rows:
        return None
    tok = _token_request({"grant_type": "refresh_token",
                          "refresh_token": rows[0][0]})
    uid, name, pmi, zak = _me_and_zak(tok["access_token"])
    now = time.time()
    q("""UPDATE sessions SET refresh_token=%s, zoom_user_id=%s, name=%s, pmi=%s,
         zak=%s, zak_ts=%s, last_used_at=%s WHERE sid=%s""",
      (tok["refresh_token"], uid, name, pmi, zak, now, now, sid))
    return {"name": name, "pmi": pmi, "zak": zak}


def _delete_user(zoom_user_id: str):
    q("DELETE FROM sessions WHERE zoom_user_id=%s", (zoom_user_id,))


PAGE = ("<html><body style='font-family:sans-serif;text-align:center;"
        "margin-top:30vh'><h2>{title}</h2><p>{body}</p></body></html>")


# ---------------------------------------------------------------- handler
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, obj, ctype="application/json"):
        body = obj.encode() if isinstance(obj, str) else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, url):
        self.send_response(302)
        self.send_header("Location", url)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _client_ip(self):
        # Caddy fronts us on loopback and sets X-Forwarded-For.
        fwd = self.headers.get("X-Forwarded-For", "")
        return fwd.split(",")[0].strip() or self.client_address[0]

    def _limited(self, u):
        ip = self._client_ip()
        if not LIMIT_GLOBAL.allow("*") or not LIMIT_GENERAL.allow(ip):
            return True
        if u.path.startswith(("/oauth/", "/signout")) and not LIMIT_AUTH.allow(ip):
            return True
        return False

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(u.query)
        sid = qs.get("sid", [""])[0]
        if self._limited(u):
            self._send(429, {"error": "rate limited"})
            return
        try:
            if u.path == "/health":
                self._send(200, {"ok": True})

            elif u.path == "/sdk-jwt":
                self._send(200, {"token": sign_sdk_jwt()})

            elif u.path == "/oauth/start":
                if not (sid and len(sid) >= 16):
                    self._send(400, {"error": "bad sid"})
                    return
                verifier = _b64(secrets.token_bytes(32))
                challenge = _b64(hashlib.sha256(verifier.encode()).digest())
                q("DELETE FROM oauth_pending WHERE created_at < %s",
                  (time.time() - PENDING_TTL,))
                q("""INSERT INTO oauth_pending VALUES (%s,%s,%s)
                     ON CONFLICT (sid) DO UPDATE SET verifier=EXCLUDED.verifier,
                     created_at=EXCLUDED.created_at""",
                  (sid, verifier, time.time()))
                self._redirect("https://zoom.us/oauth/authorize?" +
                               urllib.parse.urlencode({
                                   "response_type": "code",
                                   "client_id": os.environ["ZOOM_OAUTH_CLIENT_ID"],
                                   "redirect_uri": os.environ["PUBLIC_BASE"] + "/oauth/callback",
                                   "state": sid,
                                   "code_challenge": challenge,
                                   "code_challenge_method": "S256"}))

            elif u.path == "/oauth/callback":
                code = qs.get("code", [""])[0]
                sid = qs.get("state", [""])[0]
                rows = q("""DELETE FROM oauth_pending WHERE sid=%s AND created_at>%s
                            RETURNING verifier""",
                         (sid, time.time() - PENDING_TTL), fetch=True)
                if not (code and rows):
                    self._send(400, PAGE.format(
                        title="Sign-in expired", body="Go back to the app and try again."),
                        "text/html")
                    return
                tok = _token_request({
                    "grant_type": "authorization_code", "code": code,
                    "redirect_uri": os.environ["PUBLIC_BASE"] + "/oauth/callback",
                    "code_verifier": rows[0][0]})
                uid, name, pmi, zak = _me_and_zak(tok["access_token"])
                now = time.time()
                q("""INSERT INTO sessions VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                     ON CONFLICT (sid) DO UPDATE SET refresh_token=EXCLUDED.refresh_token,
                     zoom_user_id=EXCLUDED.zoom_user_id, name=EXCLUDED.name,
                     pmi=EXCLUDED.pmi, zak=EXCLUDED.zak, zak_ts=EXCLUDED.zak_ts,
                     last_used_at=EXCLUDED.last_used_at""",
                  (sid, tok["refresh_token"], uid, name, pmi, zak, now, now, now))
                self._redirect(os.environ.get("APP_LINK_BASE",
                                              os.environ["PUBLIC_BASE"]) +
                               "/return?sid=" + urllib.parse.quote(sid))

            elif u.path == "/return":
                # App Link target: Android intercepts this URL and opens the app.
                # This page only renders if the app is missing.
                self._send(200, PAGE.format(
                    title="Signed in ✓",
                    body="Return to the Meetings Remote app."), "text/html")

            elif u.path == "/session":
                rows = q("SELECT name, pmi, zak, zak_ts FROM sessions WHERE sid=%s",
                         (sid,), fetch=True)
                if not rows:
                    self._send(200, {"ready": False})
                    return
                name, pmi, zak, zak_ts = rows[0]
                if time.time() - zak_ts > ZAK_CACHE_TTL:
                    s = _refresh_session(sid)
                    self._send(200, {"ready": True, **s})
                else:
                    q("UPDATE sessions SET last_used_at=%s WHERE sid=%s",
                      (time.time(), sid))
                    self._send(200, {"ready": True, "name": name, "pmi": pmi,
                                     "zak": zak})

            elif u.path == "/refresh":
                s = _refresh_session(sid)
                self._send(200, {"ready": bool(s), **(s or {})})

            elif u.path == "/signout":
                rows = q("DELETE FROM sessions WHERE sid=%s RETURNING refresh_token",
                         (sid,), fetch=True)
                if rows:
                    try:
                        _token_request({"grant_type": "revoke",  # best-effort
                                        "token": rows[0][0]})
                    except Exception:
                        pass
                self._send(200, {"ok": True})

            elif u.path == "/end-stuck-meeting":
                rows = q("SELECT refresh_token FROM sessions WHERE sid=%s",
                         (sid,), fetch=True)
                if not rows:
                    self._send(403, {"ok": False, "error": "not signed in"})
                    return
                tok = _token_request({"grant_type": "refresh_token",
                                      "refresh_token": rows[0][0]})
                q("UPDATE sessions SET refresh_token=%s WHERE sid=%s",
                  (tok["refresh_token"], sid))
                auth = {"Authorization": f"Bearer {tok['access_token']}"}
                pmi = str(_http_json("https://api.zoom.us/v2/users/me",
                                     headers=auth).get("pmi", ""))
                if not pmi:
                    self._send(200, {"ok": False, "error": "account has no PMI"})
                    return
                req = urllib.request.Request(
                    f"https://api.zoom.us/v2/meetings/{pmi}/status",
                    data=json.dumps({"action": "end"}).encode(),
                    headers={**auth, "Content-Type": "application/json"},
                    method="PUT")
                try:
                    with urllib.request.urlopen(req, timeout=15) as r:
                        self._send(200, {"ok": r.status in (200, 204)})
                except urllib.error.HTTPError as e:
                    detail = e.read().decode(errors="replace")[:200]
                    try:
                        zoom_code = json.loads(detail).get("code")
                    except ValueError:
                        zoom_code = None
                    # 3001 = not started; nothing to end = success for us.
                    self._send(200, {"ok": zoom_code == 3001, "detail": detail})

            elif u.path == "/delete":
                self._send(200, PAGE.format(
                    title="Delete your Meetings Remote data",
                    body="In the app: Settings → Sign out &amp; delete my data.<br>"
                         "Or email <a href='mailto:ibbilal0@gmail.com'>"
                         "ibbilal0@gmail.com</a> from your Zoom account's email and "
                         "we delete your session and refresh token within 10 days. "
                         "We store nothing else."), "text/html")

            elif u.path == "/.well-known/assetlinks.json":
                path = os.environ.get("ASSETLINKS_JSON", "")
                if path and os.path.exists(path):
                    with open(path) as f:
                        self._send(200, f.read(), "application/json")
                else:
                    self._send(404, {"error": "not configured"})

            else:
                self._send(404, {"error": "not found"})
        except Exception as e:  # noqa: BLE001
            print(f"ERROR {u.path}: {e}", flush=True)
            self._send(500, {"error": "internal"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        if self._limited(u):
            self._send(429, {"error": "rate limited"})
            return
        length = min(int(self.headers.get("Content-Length", "0") or 0), MAX_BODY)
        raw = self.rfile.read(length)
        try:
            if u.path == "/deauthorize":
                self._deauthorize(raw)
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:  # noqa: BLE001
            print(f"ERROR {u.path}: {e}", flush=True)
            self._send(500, {"error": "internal"})

    def _deauthorize(self, raw: bytes):
        """Zoom webhook: URL validation challenge + app_deauthorized event."""
        secret = os.environ["ZOOM_WEBHOOK_SECRET_TOKEN"]
        body = json.loads(raw or b"{}")
        if body.get("event") == "endpoint.url_validation":
            plain = body["payload"]["plainToken"]
            enc = hmac.new(secret.encode(), plain.encode(),
                           hashlib.sha256).hexdigest()
            self._send(200, {"plainToken": plain, "encryptedToken": enc})
            return
        # verify x-zm-signature: v0=HMAC(secret, "v0:{ts}:{body}")
        ts = self.headers.get("x-zm-request-timestamp", "")
        sig = self.headers.get("x-zm-signature", "")
        expect = "v0=" + hmac.new(secret.encode(),
                                  f"v0:{ts}:{raw.decode()}".encode(),
                                  hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, expect):
            self._send(401, {"error": "bad signature"})
            return
        if body.get("event") == "app_deauthorized":
            p = body["payload"]
            _delete_user(p.get("user_id", ""))
            # Confirm deletion to Zoom (mandatory when retention is denied).
            try:
                cid = os.environ["ZOOM_OAUTH_CLIENT_ID"]
                csec = os.environ.get("ZOOM_OAUTH_CLIENT_SECRET", "")
                headers = {"Content-Type": "application/json"}
                if csec:
                    headers["Authorization"] = "Basic " + base64.b64encode(
                        f"{cid}:{csec}".encode()).decode()
                _http_json("https://api.zoom.us/oauth/data/compliance",
                           json.dumps({
                               "client_id": cid,
                               "user_id": p.get("user_id", ""),
                               "account_id": p.get("account_id", ""),
                               "deauthorization_event_received": p,
                               "compliance_completed": True}).encode(),
                           headers, method="POST")
            except Exception as e:  # noqa: BLE001
                print(f"WARN data-compliance: {e}", flush=True)
        self._send(200, {"ok": True})

    def log_message(self, *a):
        pass


def main():
    init_schema()
    port = int(os.environ.get("PORT", "8791"))
    print(f"meetingsremote backend on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
