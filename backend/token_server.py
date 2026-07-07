#!/usr/bin/env python3
"""Token backend for the Zoom Room appliance.

Keeps all Zoom developer secrets OFF the tablet so a customer never sees them:

  GET /sdk-jwt            -> signs a Meeting SDK JWT (from the SDK secret).
                            The app fetches this on launch to init the SDK.
                            Joining a meeting needs only this + a meeting ID.
  GET /oauth/start?state= -> 302 to Zoom's OAuth consent (for hosting).
  GET /oauth/callback     -> exchanges the code, fetches the user's ZAK, and
                            stashes it keyed by state.
  GET /session?state=     -> the app polls this; returns {name, zak} once ready.
  GET /health             -> ok

Dev: run on the Mac; the tablet reaches it over the LAN. No deps (stdlib only).

Env (see backend/.env.example):
  ZOOM_SDK_CLIENT_ID, ZOOM_SDK_CLIENT_SECRET          # Meeting SDK app -> JWT
  ZOOM_OAUTH_CLIENT_ID, ZOOM_OAUTH_CLIENT_SECRET      # OAuth app -> user ZAK
  OAUTH_REDIRECT_BASE   e.g. http://192.168.1.50:8790 # this server's LAN URL
  PORT                  default 8790
"""
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# state -> {"name": str, "zak": str, "ts": float}; ephemeral, in-memory.
SESSIONS = {}
SESSION_TTL = 600


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
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def fetch_user_zak(code: str):
    cid = os.environ["ZOOM_OAUTH_CLIENT_ID"]
    csec = os.environ["ZOOM_OAUTH_CLIENT_SECRET"]
    redirect = os.environ["OAUTH_REDIRECT_BASE"].rstrip("/") + "/oauth/callback"
    basic = base64.b64encode(f"{cid}:{csec}".encode()).decode()
    tok = _http_json(
        "https://zoom.us/oauth/token",
        {"grant_type": "authorization_code", "code": code, "redirect_uri": redirect},
        {"Authorization": f"Basic {basic}",
         "Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )["access_token"]
    auth = {"Authorization": f"Bearer {tok}"}
    me = _http_json("https://api.zoom.us/v2/users/me", headers=auth)
    zak = _http_json("https://api.zoom.us/v2/users/me/token?type=zak", headers=auth)["token"]
    name = (me.get("first_name", "") + " " + me.get("last_name", "")).strip() or "Host"
    return name, zak


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj, ctype="application/json"):
        body = obj.encode() if isinstance(obj, str) else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        try:
            if u.path == "/health":
                self._send(200, {"ok": True})
            elif u.path == "/sdk-jwt":
                self._send(200, {"token": sign_sdk_jwt()})
            elif u.path == "/oauth/start":
                state = q.get("state", [""])[0]
                cid = os.environ["ZOOM_OAUTH_CLIENT_ID"]
                redirect = os.environ["OAUTH_REDIRECT_BASE"].rstrip("/") + "/oauth/callback"
                url = "https://zoom.us/oauth/authorize?" + urllib.parse.urlencode(
                    {"response_type": "code", "client_id": cid,
                     "redirect_uri": redirect, "state": state})
                self.send_response(302)
                self.send_header("Location", url)
                self.end_headers()
            elif u.path == "/oauth/callback":
                code = q.get("code", [""])[0]
                state = q.get("state", [""])[0]
                name, zak = fetch_user_zak(code)
                SESSIONS[state] = {"name": name, "zak": zak, "ts": time.time()}
                self._send(200, "<html><body style='font-family:sans-serif;text-align:"
                           "center;margin-top:30vh'><h2>Signed in ✓</h2>"
                           "<p>Return to the Zoom Room app.</p></body></html>",
                           "text/html")
            elif u.path == "/session":
                state = q.get("state", [""])[0]
                s = SESSIONS.get(state)
                if s and time.time() - s["ts"] < SESSION_TTL:
                    self._send(200, {"ready": True, "name": s["name"], "zak": s["zak"]})
                else:
                    self._send(200, {"ready": False})
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:  # noqa: BLE001 - surface errors to the client in dev
            self._send(500, {"error": str(e)})

    def log_message(self, *a):
        pass


def main():
    port = int(os.environ.get("PORT", "8790"))
    print(f"token_server on :{port}  (redirect base {os.environ.get('OAUTH_REDIRECT_BASE','?')})")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
