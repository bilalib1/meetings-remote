/**
 * Meetings Remote token backend — Cloudflare Worker port of backend/server.py.
 * (plan: plans/2026-07-08-playstore-and-oauth.md, §19)
 *
 * Storage: D1 (SQLite) replaces Postgres — tables `sessions` + `oauth_pending`
 * (see schema.sql). Custom domains + TLS replace Caddy. Zoom secrets stay off the
 * tablet; the tablet holds only an opaque `sid`. PKCE verifier is kept server-side.
 *
 *   GET  /health                     -> ok
 *   GET  /sdk-jwt                    -> Meeting SDK JWT (join needs only this)
 *   GET  /oauth/start?sid=           -> 302 to Zoom consent (PKCE, state=sid)
 *   GET  /oauth/callback?code&state  -> exchange code, store refresh token, bounce to app
 *   GET  /session?sid=               -> {ready, name, zak, pmi}
 *   GET  /refresh?sid=               -> fresh ZAK via stored (rotating) refresh token
 *   GET  /signout?sid=               -> revoke at Zoom + delete row
 *   GET  /end-stuck-meeting?sid=     -> force-end the signed-in user's stranded PMI
 *   POST /deauthorize                -> Zoom uninstall webhook (delete data + confirm)
 *   GET  /delete /privacy /terms /support /  -> public legal/support pages
 *   GET  /return?sid=                -> App Link landing (fallback HTML if app absent)
 *   GET  /.well-known/assetlinks.json
 *
 * Rate limiting is handled at the Cloudflare edge (zone rule: 50 req/10s per IP on
 * api.*, + Bot Fight Mode) rather than in-process — Workers isolates hold no shared
 * counter. See plan Q1b.
 */

interface Env {
  DB: D1Database;
  ZOOM_SDK_CLIENT_ID: string;
  ZOOM_SDK_CLIENT_SECRET: string;
  ZOOM_OAUTH_CLIENT_ID: string;
  ZOOM_OAUTH_CLIENT_SECRET?: string;
  ZOOM_WEBHOOK_SECRET_TOKEN: string;
  /** Base64url-encoded 32-byte AES key. Set only as a Worker secret. */
  DATA_ENCRYPTION_KEY: string;
  PUBLIC_BASE: string;
  APP_LINK_BASE: string;
  ASSETLINKS_JSON: string;
}

const PENDING_TTL = 600; // sid waiting for the OAuth round-trip (s)
const ZAK_CACHE_TTL = 3600; // ZAK lives ~2h; cache half that (s)
const WEBHOOK_MAX_AGE = 300; // reject signed webhook replays older than 5 min
const ENVELOPE_PREFIX = "enc:v1:";

const enc = new TextEncoder();
const now = () => Date.now() / 1000;

// ---------------------------------------------------------------- encoding
function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function fromB64url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") +
    "=".repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded);
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}
function b64urlJson(obj: unknown): string {
  return b64url(enc.encode(JSON.stringify(obj)));
}
function toHex(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ---------------------------------------------------------------- crypto
async function hmac(secret: string, msg: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(msg)));
}
async function sha256(msg: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(msg)));
}

/**
 * D1 encrypts the database volume, but Marketplace review also requires app
 * secrets and user data to be encrypted before storage. Each value gets a
 * random AES-GCM nonce and row/field-bound additional authenticated data, so
 * ciphertext cannot be copied between sessions or columns.
 */
async function dataKey(env: Env): Promise<CryptoKey> {
  if (!env.DATA_ENCRYPTION_KEY)
    throw new Error("DATA_ENCRYPTION_KEY is not configured");
  const raw = fromB64url(env.DATA_ENCRYPTION_KEY);
  if (raw.length !== 32)
    throw new Error("DATA_ENCRYPTION_KEY must encode exactly 32 bytes");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

const aad = (sid: string, field: string) => enc.encode(`meetingsremote:${sid}:${field}:v1`);

async function seal(env: Env, sid: string, field: string, value: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: aad(sid, field), tagLength: 128 },
    await dataKey(env),
    enc.encode(value),
  );
  return `${ENVELOPE_PREFIX}${b64url(iv)}:${b64url(new Uint8Array(ciphertext))}`;
}

/** Plaintext fallback is intentional for the one-time live D1 migration. */
async function unseal(env: Env, sid: string, field: string, stored: string): Promise<string> {
  if (!stored.startsWith(ENVELOPE_PREFIX)) return stored;
  const parts = stored.slice(ENVELOPE_PREFIX.length).split(":");
  if (parts.length !== 2) throw new Error(`invalid encrypted ${field}`);
  const plaintext = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: fromB64url(parts[0]),
      additionalData: aad(sid, field),
      tagLength: 128,
    },
    await dataKey(env),
    fromB64url(parts[1]),
  );
  return new TextDecoder().decode(plaintext);
}

async function userHash(env: Env, userId: string): Promise<string> {
  // Deterministic, non-reversible lookup for Zoom's deauthorization webhook.
  // Domain separation prevents reuse as a general HMAC oracle.
  return toHex(await hmac(env.DATA_ENCRYPTION_KEY, `zoom-user-id:v1:${userId}`));
}

interface StoredSession {
  refresh_token: string;
  zoom_user_id: string;
  zoom_user_hash: string;
  name: string;
  pmi: string;
  zak: string;
  zak_ts?: number;
}

async function openSession(env: Env, sid: string, row: StoredSession) {
  return {
    refreshToken: await unseal(env, sid, "refresh_token", row.refresh_token),
    zoomUserId: await unseal(env, sid, "zoom_user_id", row.zoom_user_id),
    name: await unseal(env, sid, "name", row.name),
    pmi: await unseal(env, sid, "pmi", row.pmi),
    zak: await unseal(env, sid, "zak", row.zak),
  };
}

async function protectedSession(
  env: Env,
  sid: string,
  value: { refreshToken: string; zoomUserId: string; name: string; pmi: string; zak: string },
) {
  return {
    refreshToken: await seal(env, sid, "refresh_token", value.refreshToken),
    zoomUserId: await seal(env, sid, "zoom_user_id", value.zoomUserId),
    zoomUserHash: await userHash(env, value.zoomUserId),
    name: await seal(env, sid, "name", value.name),
    pmi: await seal(env, sid, "pmi", value.pmi),
    zak: await seal(env, sid, "zak", value.zak),
  };
}

async function signSdkJwt(env: Env, ttl = 48 * 3600): Promise<string> {
  const key = env.ZOOM_SDK_CLIENT_ID;
  const iat = Math.floor(now()) - 30;
  const exp = iat + ttl;
  const header = b64urlJson({ alg: "HS256", typ: "JWT" });
  const payload = b64urlJson({
    appKey: key,
    sdkKey: key,
    role: 0,
    iat,
    exp,
    tokenExp: exp,
  });
  const sig = b64url(await hmac(env.ZOOM_SDK_CLIENT_SECRET, `${header}.${payload}`));
  return `${header}.${payload}.${sig}`;
}

// ---------------------------------------------------------------- Zoom API
async function tokenRequest(env: Env, form: Record<string, string>): Promise<any> {
  const cid = env.ZOOM_OAUTH_CLIENT_ID;
  const csec = env.ZOOM_OAUTH_CLIENT_SECRET || "";
  const headers: Record<string, string> = {
    "Content-Type": "application/x-www-form-urlencoded",
  };
  if (csec) headers["Authorization"] = "Basic " + btoa(`${cid}:${csec}`);
  else form["client_id"] = cid;
  const r = await fetch("https://zoom.us/oauth/token", {
    method: "POST",
    headers,
    body: new URLSearchParams(form).toString(),
  });
  if (!r.ok) throw new Error(`token ${r.status}: ${await r.text()}`);
  return r.json();
}

async function revokeToken(env: Env, token: string): Promise<void> {
  const headers: Record<string, string> = {
    "Content-Type": "application/x-www-form-urlencoded",
  };
  const form: Record<string, string> = { token };
  const secret = env.ZOOM_OAUTH_CLIENT_SECRET || "";
  if (secret)
    headers["Authorization"] = "Basic " + btoa(`${env.ZOOM_OAUTH_CLIENT_ID}:${secret}`);
  else form.client_id = env.ZOOM_OAUTH_CLIENT_ID;
  const r = await fetch("https://zoom.us/oauth/revoke", {
    method: "POST",
    headers,
    body: new URLSearchParams(form).toString(),
  });
  if (!r.ok) throw new Error(`revoke ${r.status}: ${await r.text()}`);
}

async function zoomJson(url: string, accessToken: string): Promise<any> {
  const r = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (!r.ok) throw new Error(`Zoom API ${r.status}: ${await r.text()}`);
  return r.json();
}

async function meAndZak(accessToken: string) {
  const me: any = await zoomJson("https://api.zoom.us/v2/users/me", accessToken);
  // ZAK for hosting: the `user:read:zak` scope maps to GET /v2/users/me/zak
  // (the older /users/me/token?type=zak needs the separate `user:read:token`).
  const zakRes: any = await zoomJson("https://api.zoom.us/v2/users/me/zak", accessToken);
  const name =
    `${me.first_name || ""} ${me.last_name || ""}`.trim() || "Host";
  return {
    uid: me.id || "",
    name,
    pmi: String(me.pmi || ""),
    // Coalesce to "" so a transient ZAK miss can never crash the callback
    // (D1 rejects undefined binds); /refresh re-fetches on the next host.
    zak: (zakRes && zakRes.token) || "",
  };
}

// Rotate the refresh token, fetch a fresh ZAK, update the row.
async function refreshSession(env: Env, sid: string) {
  const row = await env.DB.prepare(
    `SELECT refresh_token, zoom_user_id, zoom_user_hash, name, pmi, zak
       FROM sessions WHERE sid=?`,
  )
    .bind(sid)
    .first<StoredSession>();
  if (!row) return null;
  const old = await openSession(env, sid, row);
  const tok = await tokenRequest(env, {
    grant_type: "refresh_token",
    refresh_token: old.refreshToken,
  });
  const { uid, name, pmi, zak } = await meAndZak(tok.access_token);
  const stored = await protectedSession(env, sid, {
    refreshToken: tok.refresh_token,
    zoomUserId: uid,
    name,
    pmi,
    zak,
  });
  const t = now();
  await env.DB.prepare(
    `UPDATE sessions SET refresh_token=?, zoom_user_id=?, zoom_user_hash=?,
       name=?, pmi=?, zak=?, zak_ts=?, last_used_at=? WHERE sid=?`,
  )
    .bind(
      stored.refreshToken,
      stored.zoomUserId,
      stored.zoomUserHash,
      stored.name,
      stored.pmi,
      stored.zak,
      t,
      t,
      sid,
    )
    .run();
  return { name, pmi, zak };
}

// ---------------------------------------------------------------- responses
const J = (code: number, obj: unknown) =>
  new Response(JSON.stringify(obj), {
    status: code,
    headers: { "Content-Type": "application/json" },
  });
const H = (code: number, html: string) =>
  new Response(html, { status: code, headers: { "Content-Type": "text/html" } });
const redirect = (url: string) => new Response(null, { status: 302, headers: { Location: url } });

const PAGE = (title: string, body: string) =>
  `<html><body style='font-family:sans-serif;text-align:center;margin-top:30vh'>` +
  `<h2>${title}</h2><p>${body}</p></body></html>`;

const DOC = (title: string, body: string) =>
  `<!doctype html><html><head><meta charset=utf-8>` +
  `<meta name=viewport content='width=device-width,initial-scale=1'>` +
  `<title>${title} — Meetings Remote</title></head>` +
  `<body style='font-family:system-ui,sans-serif;max-width:720px;margin:40px auto;` +
  `padding:0 16px;line-height:1.55;color:#111'>` +
  `<h1>${title}</h1>${body}` +
  `<hr><p style='color:#666;font-size:13px'>Meetings Remote · ` +
  `<a href='/privacy'>Privacy</a> · <a href='/terms'>Terms</a> · ` +
  `<a href='/support'>Support</a> · <a href='/delete'>Delete my data</a><br>` +
  `Not affiliated with or endorsed by Zoom Video Communications. ` +
  `Contact: ibbilal0@gmail.com · Updated 2026-07-11</p></body></html>`;

const PRIVACY_BODY = `
<p><b>Meetings Remote</b> is a meeting-room appliance app. It hosts and joins
Zoom meetings on a tablet using a camera you connect. This policy explains the
little data it handles.</p>
<h3>What we collect and store</h3>
<ul>
<li><b>Your Zoom display name and Zoom user ID</b> — to show who is signed in and
to host meetings as you.</li>
<li><b>A Zoom OAuth refresh token</b> — stored on our server, encrypted in
transit, so the app can get a fresh hosting token without you logging in each
time. It is never placed on the tablet.</li>
<li><b>An opaque session ID</b> — a random, revocable identifier kept on the
tablet. It is not your Zoom password and grants only that device's session.</li>
</ul>
<h3>What we do NOT collect</h3>
<ul>
<li>No meeting audio, video, chat, or recordings ever reach our servers — media
goes tablet↔Zoom directly.</li>
<li>Camera and microphone are processed <b>on the device only</b> (for
audio/video sync); nothing from them is transmitted to or stored by us.</li>
<li>No advertising identifiers, no location, no contacts, no analytics/tracking.</li>
<li>We never sell or share your data with third parties.</li>
</ul>
<h3>Retention and deletion</h3>
<p>We keep your session and refresh token until you sign out or uninstall. In the
app, <b>Room settings → Sign out &amp; delete my data</b> revokes Zoom access and
deletes your data immediately. When Zoom notifies us that you removed the app
(deauthorization), we delete your data within <b>10 days</b> and confirm to Zoom.
You can also request deletion at <a href='/delete'>/delete</a> or by emailing
ibbilal0@gmail.com from your Zoom account's email.</p>
<h3>Third parties</h3>
<p>Signing in uses <b>Zoom</b>'s own login and API (see Zoom's privacy policy).
Our server runs on Cloudflare. That's it.</p>
`;

const TERMS_BODY = `
<p>By using <b>Meetings Remote</b> you agree to these terms.</p>
<h3>What it is</h3>
<p>An open-source appliance app that turns a tablet + connected camera into a
Zoom meeting room. You sign in with your own Zoom account and host meetings as
yourself. You are responsible for your Zoom account and for complying with Zoom's
own terms.</p>
<h3>Open source &amp; trademarks</h3>
<p>The app is licensed under AGPL-3.0. "Zoom" is a trademark of Zoom Video
Communications, Inc.; this project is <b>not affiliated with, sponsored by, or
endorsed by Zoom</b>. It uses Zoom's official Meeting SDK under Zoom's developer
terms.</p>
<h3>Acceptable use</h3>
<p>Don't use the app to break the law, infringe rights, or violate Zoom's terms
(including recording-consent and meeting-notice rules). Obtain consent from
participants where required.</p>
<h3>No warranty / liability</h3>
<p>The app is provided "as is", without warranty of any kind. To the maximum
extent permitted by law, we are not liable for any damages arising from its use.</p>
`;

const SUPPORT_BODY = `
<h3>Getting started</h3>
<ol>
<li>Install Meetings Remote on the room tablet.</li>
<li>Tap <b>Start Meeting</b> and choose <b>Sign in with Zoom</b> — sign in with
your own Zoom account. You return to the app automatically.</li>
<li>Tap <b>Start Meeting</b> to host, or <b>Join</b> with a meeting ID.</li>
</ol>
<h3>Camera setup</h3>
<p>Tap the title 5 times for camera source (test pattern or an RTSP camera URL).
Long-press the title for room settings and sign-out.</p>
<h3>Contact</h3>
<p>Questions, bugs, or data requests: <b>ibbilal0@gmail.com</b>. Source code and
issues: the project's public repository.</p>
`;

// ---------------------------------------------------------------- routes
async function handleGet(url: URL, env: Env): Promise<Response> {
  const path = url.pathname;
  const sid = url.searchParams.get("sid") || "";

  if (path === "/health") return J(200, { ok: true });

  if (path === "/sdk-jwt") return J(200, { token: await signSdkJwt(env) });

  if (path === "/oauth/start") {
    if (!(sid && sid.length >= 16)) return J(400, { error: "bad sid" });
    const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
    const challenge = b64url(await sha256(verifier));
    await env.DB.prepare("DELETE FROM oauth_pending WHERE created_at < ?")
      .bind(now() - PENDING_TTL)
      .run();
    const storedVerifier = await seal(env, sid, "oauth_verifier", verifier);
    await env.DB.prepare(
      `INSERT INTO oauth_pending (sid, verifier, created_at) VALUES (?,?,?)
         ON CONFLICT(sid) DO UPDATE SET verifier=excluded.verifier,
         created_at=excluded.created_at`,
    )
      .bind(sid, storedVerifier, now())
      .run();
    const q = new URLSearchParams({
      response_type: "code",
      client_id: env.ZOOM_OAUTH_CLIENT_ID,
      redirect_uri: env.PUBLIC_BASE + "/oauth/callback",
      state: sid,
      code_challenge: challenge,
      code_challenge_method: "S256",
    });
    return redirect("https://zoom.us/oauth/authorize?" + q.toString());
  }

  if (path === "/oauth/callback") {
    const code = url.searchParams.get("code") || "";
    const state = url.searchParams.get("state") || "";
    const row = await env.DB.prepare(
      "DELETE FROM oauth_pending WHERE sid=? AND created_at>? RETURNING verifier",
    )
      .bind(state, now() - PENDING_TTL)
      .first<{ verifier: string }>();
    if (!(code && row))
      return H(400, PAGE("Sign-in expired", "Go back to the app and try again."));
    const tok = await tokenRequest(env, {
      grant_type: "authorization_code",
      code,
      redirect_uri: env.PUBLIC_BASE + "/oauth/callback",
      code_verifier: await unseal(env, state, "oauth_verifier", row.verifier),
    });
    const { uid, name, pmi, zak } = await meAndZak(tok.access_token);
    const stored = await protectedSession(env, state, {
      refreshToken: tok.refresh_token,
      zoomUserId: uid,
      name,
      pmi,
      zak,
    });
    const t = now();
    await env.DB.prepare(
      `INSERT INTO sessions
         (sid, refresh_token, zoom_user_id, zoom_user_hash, name, pmi, zak,
          zak_ts, created_at, last_used_at)
         VALUES (?,?,?,?,?,?,?,?,?,?)
         ON CONFLICT(sid) DO UPDATE SET refresh_token=excluded.refresh_token,
         zoom_user_id=excluded.zoom_user_id, zoom_user_hash=excluded.zoom_user_hash,
         name=excluded.name, pmi=excluded.pmi, zak=excluded.zak,
         zak_ts=excluded.zak_ts, last_used_at=excluded.last_used_at`,
    )
      .bind(
        state,
        stored.refreshToken,
        stored.zoomUserId,
        stored.zoomUserHash,
        stored.name,
        stored.pmi,
        stored.zak,
        t,
        t,
        t,
      )
      .run();
    return redirect(
      (env.APP_LINK_BASE || env.PUBLIC_BASE) + "/return?sid=" + encodeURIComponent(state),
    );
  }

  if (path === "/return")
    return H(200, PAGE("Signed in ✓", "Return to the Meetings Remote app."));

  if (path === "/session") {
    const row = await env.DB.prepare(
      `SELECT refresh_token, zoom_user_id, zoom_user_hash, name, pmi, zak, zak_ts
         FROM sessions WHERE sid=?`,
    )
      .bind(sid)
      .first<StoredSession>();
    if (!row) return J(200, { ready: false });
    if (now() - (row.zak_ts || 0) > ZAK_CACHE_TTL) {
      const s = await refreshSession(env, sid);
      return J(200, { ready: true, ...s });
    }
    const opened = await openSession(env, sid, row);
    // Rewrite legacy plaintext on first use. The one-time migration script
    // handles inactive rows; this keeps deploys backward-compatible.
    const isProtected = row.zoom_user_hash &&
      [row.refresh_token, row.zoom_user_id, row.name, row.pmi, row.zak]
        .every((value) => value.startsWith(ENVELOPE_PREFIX));
    if (isProtected) {
      await env.DB.prepare("UPDATE sessions SET last_used_at=? WHERE sid=?")
        .bind(now(), sid)
        .run();
    } else {
      const stored = await protectedSession(env, sid, opened);
      await env.DB.prepare(
        `UPDATE sessions SET refresh_token=?, zoom_user_id=?, zoom_user_hash=?,
         name=?, pmi=?, zak=?, last_used_at=? WHERE sid=?`,
      )
        .bind(
          stored.refreshToken,
          stored.zoomUserId,
          stored.zoomUserHash,
          stored.name,
          stored.pmi,
          stored.zak,
          now(),
          sid,
        )
        .run();
    }
    return J(200, {
      ready: true,
      name: opened.name,
      pmi: opened.pmi,
      zak: opened.zak,
    });
  }

  if (path === "/refresh") {
    const s = await refreshSession(env, sid);
    return J(200, { ready: !!s, ...(s || {}) });
  }

  if (path === "/signout") {
    const row = await env.DB.prepare(
      `DELETE FROM sessions WHERE sid=?
       RETURNING refresh_token, zoom_user_id, zoom_user_hash, name, pmi, zak`,
    )
      .bind(sid)
      .first<StoredSession>();
    if (row) {
      try {
        const opened = await openSession(env, sid, row);
        // Zoom's documented revocation endpoint accepts an access token. Use
        // the refresh token once to obtain one, then revoke it. Local deletion
        // already happened and remains guaranteed if Zoom is unavailable.
        const tok = await tokenRequest(env, {
          grant_type: "refresh_token",
          refresh_token: opened.refreshToken,
        });
        await revokeToken(env, tok.access_token);
      } catch {
        /* best-effort */
      }
    }
    return J(200, { ok: true });
  }

  if (path === "/end-stuck-meeting") {
    const row = await env.DB.prepare(
      `SELECT refresh_token, zoom_user_id, zoom_user_hash, name, pmi, zak
       FROM sessions WHERE sid=?`,
    )
      .bind(sid)
      .first<StoredSession>();
    if (!row) return J(403, { ok: false, error: "not signed in" });
    const opened = await openSession(env, sid, row);
    const tok = await tokenRequest(env, {
      grant_type: "refresh_token",
      refresh_token: opened.refreshToken,
    });
    const encryptedRefresh = await seal(env, sid, "refresh_token", tok.refresh_token);
    await env.DB.prepare("UPDATE sessions SET refresh_token=? WHERE sid=?")
      .bind(encryptedRefresh, sid)
      .run();
    const auth = { Authorization: `Bearer ${tok.access_token}` };
    const me: any = await (
      await fetch("https://api.zoom.us/v2/users/me", { headers: auth })
    ).json();
    const pmi = String(me.pmi || "");
    if (!pmi) return J(200, { ok: false, error: "account has no PMI" });
    const r = await fetch(`https://api.zoom.us/v2/meetings/${pmi}/status`, {
      method: "PUT",
      headers: { ...auth, "Content-Type": "application/json" },
      body: JSON.stringify({ action: "end" }),
    });
    if (r.ok) return J(200, { ok: true });
    const detail = (await r.text()).slice(0, 200);
    let zoomCode: number | null = null;
    try {
      zoomCode = JSON.parse(detail).code;
    } catch {
      /* non-JSON */
    }
    // 3001 = meeting not started; nothing to end == success for us.
    return J(200, { ok: zoomCode === 3001, detail });
  }

  if (path === "/privacy") return H(200, DOC("Privacy Policy", PRIVACY_BODY));
  if (path === "/terms") return H(200, DOC("Terms of Use", TERMS_BODY));
  if (path === "/support" || path === "/" || path === "")
    return H(200, DOC("Support", SUPPORT_BODY));

  if (path === "/delete")
    return H(
      200,
      PAGE(
        "Delete your Meetings Remote data",
        "In the app: Settings → Sign out &amp; delete my data.<br>" +
          "Or email <a href='mailto:ibbilal0@gmail.com'>ibbilal0@gmail.com</a> " +
          "from your Zoom account's email and we delete your session and refresh " +
          "token within 10 days. We store nothing else.",
      ),
    );

  if (path === "/.well-known/assetlinks.json") {
    if (env.ASSETLINKS_JSON)
      return new Response(env.ASSETLINKS_JSON, {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    return J(404, { error: "not configured" });
  }

  return J(404, { error: "not found" });
}

// Zoom webhook: URL-validation challenge + app_deauthorized event.
async function handleDeauthorize(raw: string, req: Request, env: Env): Promise<Response> {
  const secret = env.ZOOM_WEBHOOK_SECRET_TOKEN;
  const body: any = JSON.parse(raw || "{}");
  if (body.event === "endpoint.url_validation") {
    const plain = body.payload.plainToken;
    const encrypted = toHex(await hmac(secret, plain));
    return J(200, { plainToken: plain, encryptedToken: encrypted });
  }
  // verify x-zm-signature: v0=HMAC(secret, "v0:{ts}:{body}")
  const ts = req.headers.get("x-zm-request-timestamp") || "";
  const sig = req.headers.get("x-zm-signature") || "";
  const timestamp = Number(ts);
  if (!Number.isFinite(timestamp) || Math.abs(now() - timestamp) > WEBHOOK_MAX_AGE)
    return J(401, { error: "stale signature" });
  const expect = "v0=" + toHex(await hmac(secret, `v0:${ts}:${raw}`));
  // constant-time compare
  if (sig.length !== expect.length) return J(401, { error: "bad signature" });
  let diff = 0;
  for (let i = 0; i < sig.length; i++) diff |= sig.charCodeAt(i) ^ expect.charCodeAt(i);
  if (diff !== 0) return J(401, { error: "bad signature" });

  if (body.event === "app_deauthorized") {
    const p = body.payload;
    const uid = p.user_id || "";
    // New rows use a deterministic keyed hash for lookup because the user id
    // itself is encrypted. Fall back to scanning legacy plaintext/encrypted
    // rows created before zoom_user_hash existed; the migration removes them.
    await env.DB.prepare("DELETE FROM sessions WHERE zoom_user_hash=?")
      .bind(await userHash(env, uid))
      .run();
    const legacy = await env.DB.prepare(
      "SELECT sid, zoom_user_id FROM sessions WHERE zoom_user_hash=''",
    ).all<{ sid: string; zoom_user_id: string }>();
    for (const row of legacy.results || []) {
      try {
        if (await unseal(env, row.sid, "zoom_user_id", row.zoom_user_id) === uid)
          await env.DB.prepare("DELETE FROM sessions WHERE sid=?").bind(row.sid).run();
      } catch (e) {
        console.log(`WARN legacy deauth row ${row.sid}:`, e);
      }
    }
    // Confirm deletion to Zoom (mandatory when retention is denied).
    try {
      const cid = env.ZOOM_OAUTH_CLIENT_ID;
      const csec = env.ZOOM_OAUTH_CLIENT_SECRET || "";
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (csec) headers["Authorization"] = "Basic " + btoa(`${cid}:${csec}`);
      await fetch("https://api.zoom.us/oauth/data/compliance", {
        method: "POST",
        headers,
        body: JSON.stringify({
          client_id: cid,
          user_id: p.user_id || "",
          account_id: p.account_id || "",
          deauthorization_event_received: p,
          compliance_completed: true,
        }),
      });
    } catch (e) {
      console.log("WARN data-compliance:", e);
    }
  }
  return J(200, { ok: true });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    try {
      if (req.method === "GET") return await handleGet(url, env);
      if (req.method === "POST" && url.pathname === "/deauthorize") {
        const raw = (await req.text()).slice(0, 64 * 1024);
        return await handleDeauthorize(raw, req, env);
      }
      return J(404, { error: "not found" });
    } catch (e) {
      console.log(`ERROR ${url.pathname}:`, e);
      return J(500, { error: "internal" });
    }
  },
};
