#!/usr/bin/env python3
"""Zoom Room Controller — HTTP server (platform-agnostic).

Exposes a small HTTP API that a tablet console (or any browser) drives. All
Zoom control is delegated to a ZoomController chosen by the OS
(see zoom_controller.py / mac_controller.py), so this file and the Android app
contain no platform-specific code — porting to Windows is just implementing the
ZoomController interface.

The controller makes no Zoom API/SDK calls; it drives the real desktop client,
so it introduces no tracking, metering, or limits beyond the account's normal
ones.

Usage:  python3 zoom_control_server.py [--port 8765]
"""

import argparse
import json
import re
import socket
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from zoom_controller import get_controller

controller = get_controller()

# endpoint name -> (query dict) -> result dict
ACTIONS = {
    "status":       lambda q: controller.status(),
    "new":          lambda q: controller.new_meeting(),
    "join":         lambda q: controller.join(q.get("id", [""])[0], q.get("pwd", [""])[0]),
    "mute":         lambda q: controller.toggle_mute(),
    "video":        lambda q: controller.toggle_video(),
    "share":        lambda q: controller.toggle_share(),
    "participants": lambda q: controller.toggle_participants(),
    "record":       lambda q: controller.toggle_record(),
    "hand":         lambda q: controller.toggle_hand(),
    "leave":        lambda q: controller.leave(),
}

WEB_UI = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Zoom Room</title><style>
body{background:#0b0d10;color:#e8eaed;font-family:-apple-system,Roboto,sans-serif;
margin:0;padding:20px;text-align:center}h1{color:#9aa0a6;font-weight:600}
#st{color:#8ab4f8;min-height:22px}button{font-size:18px;padding:22px;margin:6px;
border:0;border-radius:14px;background:#22262b;color:#e8eaed;width:44%}
#leave{background:#8b1a1a;color:#fff;width:92%}.on{background:#2d8cff}</style></head>
<body><h1>Zoom Room</h1><div id="st">…</div><div id="home" style="display:none">
<button onclick="a('new')">New Meeting</button>
<button onclick="var i=prompt('Meeting ID');if(i)fetch('/api/join?id='+i.replace(/\\D/g,''),{method:'POST'}).then(p)">Join</button></div>
<div id="meet" style="display:none">
<button id="mute" onclick="a('mute')">Mute</button><button id="video" onclick="a('video')">Video</button>
<button id="share" onclick="a('share')">Share</button><button id="record" onclick="a('record')">Record</button>
<button id="leave" onclick="if(confirm('Leave?'))a('leave')">Leave</button></div>
<script>
function a(x){fetch('/api/'+x,{method:'POST'}).then(p)}
function p(){fetch('/api/status').then(r=>r.json()).then(s=>{
document.getElementById('home').style.display=s.in_meeting?'none':'block';
document.getElementById('meet').style.display=s.in_meeting?'block':'none';
var e=document.getElementById('st');
e.textContent=!s.zoom_running?'Zoom not running':(!s.accessibility?'Grant Accessibility on PC':(s.in_meeting?(s.topic||'In meeting'):'Ready'));
if(s.in_meeting){m('mute',s.muted,'Unmute','Mute');m('video',s.video_on,'Stop Video','Start Video');m('share',s.sharing,'Stop Share','Share');m('record',s.recording,'Stop Rec','Record');}
}).catch(_=>document.getElementById('st').textContent='Server unreachable')}
function m(id,on,a,b){var x=document.getElementById(id);x.textContent=on?a:b;x.className=on?'on':''}
setInterval(p,2000);p();
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def _handle(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            return self._send(200, WEB_UI, "text/html; charset=utf-8")
        m = re.fullmatch(r"/api/(\w+)", parsed.path)
        if not m or m.group(1) not in ACTIONS:
            return self._send(404, json.dumps({"ok": False, "error": "unknown"}))
        q = urllib.parse.parse_qs(parsed.query)
        try:
            result = ACTIONS[m.group(1)](q)
        except Exception as e:  # never leak a stack trace to the tablet
            result = {"ok": False, "error": "server_error: " + str(e)}
        self._send(200, json.dumps(result))

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.address_string(), fmt % args))


def _lan_ip():
    """Best-effort LAN IP, cross-platform (no external traffic sent)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "this-machine"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("Zoom Room Controller on http://%s:%d  (web UI at /)" % (_lan_ip(), args.port))
    print("Actions:", ", ".join(sorted(ACTIONS)))
    srv.serve_forever()


if __name__ == "__main__":
    main()
