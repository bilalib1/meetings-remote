#!/usr/bin/env python3
"""Mint a Zoom Access Key (ZAK) for the host account, so the tablet's
"Start Meeting" can host a meeting.

Why this exists: the Meeting SDK cannot host with an email/password login
(Zoom removed that) and this account isn't SSO. The supported path is a ZAK —
a short-lived (~2 h) token minted from a Server-to-Server OAuth app.

One-time setup (free):
  1. marketplace.zoom.us -> Develop -> Build App -> "Server-to-Server OAuth".
  2. Add scope: user:read:admin  (or user_zak:read:admin).
  3. Copy the Account ID, Client ID, Client Secret.

Usage:
  ZOOM_ACCOUNT_ID=xxx ZOOM_S2S_CLIENT_ID=yyy ZOOM_S2S_CLIENT_SECRET=zzz \
      python3 tools/mint_zak.py

Paste the printed ZAK into the tablet app (tap the title -> credentials ->
"Host ZAK"). ZAKs expire ~2 h; re-run to refresh.
"""
import base64
import json
import os
import sys
import urllib.parse
import urllib.request


def _post(url, data, headers):
    req = urllib.request.Request(url, data=data.encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def _get(url, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def main():
    account_id = os.environ.get("ZOOM_ACCOUNT_ID")
    client_id = os.environ.get("ZOOM_S2S_CLIENT_ID")
    client_secret = os.environ.get("ZOOM_S2S_CLIENT_SECRET")
    if not all([account_id, client_id, client_secret]):
        sys.exit("Set ZOOM_ACCOUNT_ID, ZOOM_S2S_CLIENT_ID, ZOOM_S2S_CLIENT_SECRET "
                 "(see the header of this file).")

    basic = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    token = _post(
        "https://zoom.us/oauth/token",
        urllib.parse.urlencode({"grant_type": "account_credentials",
                                "account_id": account_id}),
        {"Authorization": f"Basic {basic}",
         "Content-Type": "application/x-www-form-urlencoded"},
    )["access_token"]

    zak = _get("https://api.zoom.us/v2/users/me/token?type=zak",
               {"Authorization": f"Bearer {token}"})["token"]
    print(zak)


if __name__ == "__main__":
    main()
