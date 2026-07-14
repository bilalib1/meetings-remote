#!/usr/bin/env python3
"""Extensive black-box + crypto test of the deployed Meetings Remote worker.
Verifies HMAC signatures independently (we hold the SDK secret + webhook token),
PKCE derivation against the D1-stored verifier, all error paths, and D1 effects.
Seeds/inspects/cleans up D1 via wrangler, so it needs the same env as deploy.

Run (from backend/cf-worker/):
  export CLOUDFLARE_API_KEY="$(cat ~/tmp/cf_globalkey)" \
         CLOUDFLARE_EMAIL="ibbilal0@gmail.com" \
         CLOUDFLARE_ACCOUNT_ID="3f190e8e67ba21f4454d7c079a42dd71" \
         CFDIR="$PWD" \
         SDK_ID="<zoom sdk client id>" \
         SDK_SECRET="<zoom sdk client secret>" \
         WEBHOOK_SECRET="<value set via `wrangler secret put ZOOM_WEBHOOK_SECRET_TOKEN`>"
  python3 test_worker.py
Bot Fight Mode blocks non-browser UAs, so requests send a browser User-Agent."""
import base64, hashlib, hmac, json, os, subprocess, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

API = "https://api.meetingsremote.app"
APEX = "https://meetingsremote.app"
SDK_ID = os.environ["SDK_ID"]
SDK_SECRET = os.environ["SDK_SECRET"]
WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"]  # placeholder we set
DATA_KEY = base64.urlsafe_b64decode(os.environ["DATA_ENCRYPTION_KEY"] + "===")
if len(DATA_KEY) != 32: raise SystemExit("DATA_ENCRYPTION_KEY must decode to 32 bytes")

PASS = FAIL = 0
def check(name, cond, detail=""):
    global PASS, FAIL
    if cond: PASS += 1; print(f"  \033[32mPASS\033[0m {name}")
    else:    FAIL += 1; print(f"  \033[31mFAIL\033[0m {name}  {detail}")

def b64url(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def decrypt(sid, field, value):
    prefix = "enc:v1:"
    assert value.startswith(prefix)
    iv, ciphertext = value[len(prefix):].split(":", 1)
    dec = lambda x: base64.urlsafe_b64decode(x + "="*(-len(x)%4))
    return AESGCM(DATA_KEY).decrypt(dec(iv), dec(ciphertext),
                                    f"meetingsremote:{sid}:{field}:v1".encode()).decode()

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a): return None  # surface 3xx instead of following
_OPENER = urllib.request.build_opener(_NoRedirect)
def req(method, url, body=None, headers=None):
    h = {"User-Agent": UA}; h.update(headers or {})
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with _OPENER.open(r, timeout=20) as resp:
            return resp.status, dict(resp.headers), resp.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace")

def d1(sql):
    """Run SQL on remote D1, return list of result-rows (first statement)."""
    env = dict(os.environ)
    out = subprocess.run(
        ["npx","wrangler","d1","execute","meetingsremote","--remote","--json","--command",sql],
        cwd=os.environ["CFDIR"], capture_output=True, text=True, env=env)
    txt = out.stdout.strip()
    start = txt.find("[")
    data = json.loads(txt[start:]) if start >= 0 else []
    return data[0].get("results", []) if data else []

print("=== A. Basics / routing / methods ===")
s,h,b = req("GET", f"{API}/health")
check("GET /health 200 ok", s==200 and json.loads(b)=={"ok":True}, f"{s} {b}")
s,_,b = req("GET", f"{APEX}/health")
check("apex host also serves /health", s==200 and json.loads(b)=={"ok":True}, f"{s}")
s,_,b = req("GET", f"{API}/does-not-exist")
check("unknown path -> 404 json", s==404 and json.loads(b)=={"error":"not found"}, f"{s} {b}")
s,_,_ = req("POST", f"{API}/does-not-exist", b"{}")
check("POST unknown -> 404", s==404, str(s))
s,_,_ = req("GET", f"{API}/deauthorize")
check("GET /deauthorize (wrong method) -> 404", s==404, str(s))

print("=== B. SDK JWT crypto (independent HMAC verify) ===")
s,_,b = req("GET", f"{API}/sdk-jwt")
tok = json.loads(b)["token"]; parts = tok.split(".")
check("jwt has 3 parts", len(parts)==3, tok)
pad = lambda x: x + "="*(-len(x)%4)
hdr = json.loads(base64.urlsafe_b64decode(pad(parts[0])))
pl  = json.loads(base64.urlsafe_b64decode(pad(parts[1])))
check("jwt header alg/typ", hdr=={"alg":"HS256","typ":"JWT"}, str(hdr))
check("jwt appKey==sdkKey==SDK_ID", pl["appKey"]==SDK_ID and pl["sdkKey"]==SDK_ID, str(pl))
check("jwt role 0", pl["role"]==0, str(pl.get("role")))
check("jwt ttl 48h & tokenExp==exp", pl["exp"]-pl["iat"]==48*3600 and pl["tokenExp"]==pl["exp"], str(pl))
check("jwt iat ~ now-30 (±120s)", abs(pl["iat"]-(int(time.time())-30))<120, str(pl["iat"]))
expsig = b64url(hmac.new(SDK_SECRET.encode(), f"{parts[0]}.{parts[1]}".encode(), hashlib.sha256).digest())
check("jwt signature valid under SDK_SECRET", expsig==parts[2], "signature mismatch")

print("=== C. PKCE / oauth start (D1 write + derivation) ===")
s,_,b = req("GET", f"{API}/oauth/start")
check("no sid -> 400", s==400, f"{s} {b}")
s,_,b = req("GET", f"{API}/oauth/start?sid=short")
check("short sid -> 400", s==400, f"{s} {b}")
sid = "testsid_" + b64url(os.urandom(12))
s,h,b = req("GET", f"{API}/oauth/start?sid={sid}")
loc = h.get("Location","")
check("valid sid -> 302", s==302, str(s))
check("302 -> zoom authorize", loc.startswith("https://zoom.us/oauth/authorize?"), loc[:60])
from urllib.parse import urlparse, parse_qs
qp = parse_qs(urlparse(loc).query)
check("authorize params", qp.get("response_type")==["code"] and qp.get("state")==[sid]
      and qp.get("code_challenge_method")==["S256"]
      and qp.get("redirect_uri")==[f"{API}/oauth/callback"], str(qp))
challenge = qp.get("code_challenge",[""])[0]
check("code_challenge is 43-char base64url", len(challenge)==43, challenge)
rows = d1(f"SELECT verifier, created_at FROM oauth_pending WHERE sid='{sid}'")
check("oauth_pending row written to D1", len(rows)==1, str(rows))
if rows:
    check("PKCE verifier is encrypted at rest", rows[0]["verifier"].startswith("enc:v1:"),
          rows[0]["verifier"][:20])
    verifier = decrypt(sid, "oauth_verifier", rows[0]["verifier"])
    exp_ch = b64url(hashlib.sha256(verifier.encode()).digest())
    check("PKCE challenge == b64url(sha256(stored verifier))", exp_ch==challenge, f"{exp_ch} vs {challenge}")
# rotate on repeat
s,h,_ = req("GET", f"{API}/oauth/start?sid={sid}")
rows2 = d1(f"SELECT verifier FROM oauth_pending WHERE sid='{sid}'")
check("repeat /oauth/start rotates encrypted verifier (ON CONFLICT)",
      rows2 and rows2[0]["verifier"]!=rows[0]["verifier"], "")
d1(f"DELETE FROM oauth_pending WHERE sid='{sid}'")

print("=== D. oauth/callback error + pending-consumption ===")
s,_,b = req("GET", f"{API}/oauth/callback")
check("callback no code/state -> 400 HTML expired", s==400 and "Sign-in expired" in b, f"{s}")
s,_,b = req("GET", f"{API}/oauth/callback?code=x&state=unknownstate123")
check("callback unknown state -> 400 HTML", s==400 and "Sign-in expired" in b, f"{s}")
csid = "cbsid_" + b64url(os.urandom(9))
d1(f"INSERT INTO oauth_pending (sid,verifier,created_at) VALUES ('{csid}','vvv',{time.time()})")
s,_,b = req("GET", f"{API}/oauth/callback?code=fakecode&state={csid}")
left = d1(f"SELECT sid FROM oauth_pending WHERE sid='{csid}'")
check("callback w/ valid pending + bad code -> 500 (Zoom rejects)", s==500, f"{s} {b[:80]}")
check("pending row consumed (DELETE...RETURNING) even on failure", len(left)==0, str(left))

print("=== E. session/refresh/signout/end-stuck with unknown sid ===")
s,_,b = req("GET", f"{API}/session?sid=nope_unknown_1234")
check("/session unknown -> ready:false", s==200 and json.loads(b)=={"ready":False}, f"{s} {b}")
s,_,b = req("GET", f"{API}/refresh?sid=nope_unknown_1234")
check("/refresh unknown -> ready:false", s==200 and json.loads(b)=={"ready":False}, f"{s} {b}")
s,_,b = req("GET", f"{API}/signout?sid=nope_unknown_1234")
check("/signout unknown -> ok:true", s==200 and json.loads(b)=={"ok":True}, f"{s} {b}")
s,_,b = req("GET", f"{API}/end-stuck-meeting?sid=nope_unknown_1234")
check("/end-stuck unknown -> 403 not signed in", s==403 and json.loads(b).get("error")=="not signed in", f"{s} {b}")

print("=== F. session happy read path + legacy plaintext migration ===")
hsid = "happy_" + b64url(os.urandom(9)); nowt = time.time()
d1(f"""INSERT INTO sessions
    (sid,refresh_token,zoom_user_id,zoom_user_hash,name,pmi,zak,zak_ts,created_at,last_used_at)
    VALUES ('{hsid}','fake_rt','U_happy','','Dana Lee','5551234567','ZAKVALUE123',
            {nowt},{nowt},{nowt})""")
s,_,b = req("GET", f"{API}/session?sid={hsid}")
j = json.loads(b)
check("/session fresh -> ready + stored name/pmi/zak", s==200 and j==
      {"ready":True,"name":"Dana Lee","pmi":"5551234567","zak":"ZAKVALUE123"}, f"{s} {b}")
lu = d1(f"""SELECT last_used_at,created_at,refresh_token,zoom_user_id,zoom_user_hash,
                    name,pmi,zak FROM sessions WHERE sid='{hsid}'""")
check("/session bumped last_used_at", lu and lu[0]["last_used_at"]>=lu[0]["created_at"], str(lu))
check("/session rewrote all sensitive fields as AES-GCM envelopes",
      lu and all(lu[0][f].startswith("enc:v1:")
                 for f in ("refresh_token","zoom_user_id","name","pmi","zak")),
      str(lu)[:160])
check("/session created keyed deauthorization lookup", lu and len(lu[0]["zoom_user_hash"])==64,
      str(lu))
d1(f"DELETE FROM sessions WHERE sid='{hsid}'")

print("=== G. deauthorize webhook crypto (independent HMAC) ===")
plain = "plaintok_" + b64url(os.urandom(6))
body = json.dumps({"event":"endpoint.url_validation","payload":{"plainToken":plain}})
s,_,b = req("POST", f"{API}/deauthorize", body.encode(), {"Content-Type":"application/json"})
j = json.loads(b)
exp_enc = hmac.new(WEBHOOK_SECRET.encode(), plain.encode(), hashlib.sha256).hexdigest()
check("url_validation echoes plainToken", j.get("plainToken")==plain, str(j))
check("url_validation encryptedToken == HMAC(secret,plain)", j.get("encryptedToken")==exp_enc, "hmac mismatch")
# bad signature
ev = json.dumps({"event":"app_deauthorized","payload":{"user_id":"U_x","account_id":"A"}})
s,_,b = req("POST", f"{API}/deauthorize", ev.encode(),
            {"Content-Type":"application/json","x-zm-request-timestamp":"1","x-zm-signature":"v0=deadbeef"})
check("app_deauthorized bad signature -> 401", s==401, f"{s} {b}")
# valid HMAC with an expired timestamp must still be rejected (replay defense)
old_ts = "1700000000"
old_sig = "v0=" + hmac.new(WEBHOOK_SECRET.encode(), f"v0:{old_ts}:{ev}".encode(), hashlib.sha256).hexdigest()
s,_,b = req("POST", f"{API}/deauthorize", ev.encode(),
            {"Content-Type":"application/json","x-zm-request-timestamp":old_ts,
             "x-zm-signature":old_sig})
check("app_deauthorized stale signed replay -> 401", s==401 and json.loads(b).get("error")=="stale signature",
      f"{s} {b}")
# good signature + D1 delete-by-user
dsid = "deauth_" + b64url(os.urandom(9)); uid = "U_DEAUTH_TEST"; nowt=time.time()
d1(f"""INSERT INTO sessions
    (sid,refresh_token,zoom_user_id,zoom_user_hash,name,pmi,zak,zak_ts,created_at,last_used_at)
    VALUES ('{dsid}','fake_rt','{uid}','','X','','',{nowt},{nowt},{nowt})""")
# Rewrite the row first, proving deauthorization can find an encrypted user id.
req("GET", f"{API}/session?sid={dsid}")
ev = json.dumps({"event":"app_deauthorized","payload":{"user_id":uid,"account_id":"A1"}})
ts = str(int(time.time()))
sig = "v0=" + hmac.new(WEBHOOK_SECRET.encode(), f"v0:{ts}:{ev}".encode(), hashlib.sha256).hexdigest()
s,_,b = req("POST", f"{API}/deauthorize", ev.encode(),
            {"Content-Type":"application/json","x-zm-request-timestamp":ts,"x-zm-signature":sig})
gone = d1(f"SELECT sid FROM sessions WHERE sid='{dsid}'")
check("app_deauthorized good sig -> 200 ok", s==200 and json.loads(b)=={"ok":True}, f"{s} {b}")
check("app_deauthorized deleted encrypted session by keyed user lookup", len(gone)==0, str(gone))
d1(f"DELETE FROM sessions WHERE sid='{dsid}'")

print("=== H. apex pages / assetlinks / edge TLS ===")
for p in ["/docs","/privacy","/terms","/support","/","/delete","/return"]:
    s,h,b = req("GET", f"{APEX}{p}")
    check(f"apex {p} -> 200 html", s==200 and "text/html" in h.get("Content-Type",""), f"{s}")
s,_,b = req("GET", f"{APEX}/docs")
check("documentation covers add/use/remove",
      s==200 and "Install and authorize" in b and "Configure and use" in b
      and "Sign out, remove access, and delete data" in b, f"{s}")
s,_,b = req("GET", f"{APEX}/privacy")
check("privacy states at-rest encryption + data-subject rights",
      s==200 and "AES-256-GCM" in b and "Your privacy rights" in b
      and "access or obtain a portable" in b, f"{s}")
s,h,b = req("GET", f"{APEX}/.well-known/assetlinks.json")
al = json.loads(b)
check("assetlinks json + content-type", "application/json" in h.get("Content-Type",""), h.get("Content-Type"))
check("assetlinks package + fingerprint", al[0]["target"]["package_name"]=="com.bilal.meetingsremote"
      and al[0]["target"]["sha256_cert_fingerprints"][0].startswith("EC:67:E8:2D"), str(al)[:80])
s,h,b = req("GET", f"{API}/health")
check("HSTS header present", "strict-transport-security" in {k.lower() for k in h}, "")
check("served by cloudflare", h.get("Server","").lower()=="cloudflare", h.get("Server"))

print("=== I. edge rate limit probe (informational; zone rule 50/10s per IP on api.) ===")
# Run concurrently and cover both LAX/SJC, which local traffic alternates
# between. Counters are deliberately per Cloudflare location, so each colo can
# admit 50 requests in the same window.
with ThreadPoolExecutor(max_workers=30) as pool:
    codes = list(pool.map(lambda _: req("GET", f"{API}/health")[0], range(140)))
# Cloudflare's counters update asynchronously. If the initial concurrent wave
# all entered permissively, the immediately following probes see mitigation.
for _ in range(20):
    if 429 in codes: break
    codes.append(req("GET", f"{API}/health")[0])
n429 = codes.count(429); n200 = codes.count(200)
print(f"  140 rapid /health: {n200}x200, {n429}x429  (429 => edge rate-limit active)")
check("edge rate-limit triggers on burst", n429 > 0, "no 429 seen — verify zone rule still on api.* host")
time.sleep(11)  # let the bucket refill so nothing downstream is throttled

print(f"\n=== RESULT: {PASS} passed, {FAIL} failed ===")
sys.exit(1 if FAIL else 0)
