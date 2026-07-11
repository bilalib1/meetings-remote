# Play Store Delivery + "Sign in with Zoom" Auth

Ship the tablet appliance (`room/`) as a public Play Store app any small team can install
on their own tablet. The one hard blocker is **auth**: today a LAN Python box signs the SDK
JWT and mints a host token from *one fixed* Zoom account (Server-to-Server OAuth). That does
not work for strangers. Target: on the tablet, tap **Sign in with Zoom** → a Zoom web page
opens (SSO / password / Google, whatever their org uses) → it bounces back into the app →
they can host meetings as *their own* account. This plan covers that auth swap and the
Play-Store + Zoom-Marketplace gauntlet to publish.

---

## 1. How To Use This Template

**Repo layout:** `~/code` holds all repos, one directory per repo (this template lives in `~/code/misc`). Plans reference paths relative to their own repo root.

Fork into `plans/YYYY-MM-DD-<slug>.md`, one per task. All section headers stay in every fork. **Copy §1–§3 verbatim — they are project-independent working agreements; rewrite the title, intro paragraph, and §4 onward for the specific project**, replacing each italic meta-description with real content.

1. How To Use This Template
2. Maintain This Plan
3. Preferences
4. Context & Problem Statement
5. Execution Steps
6. Out of Scope / Non-Goals
7. Architecture
8. Database Schema — *optional*
9. Implementation Details
10. Data Snippets — *if relevant*
11. Open Questions / Decisions Needed
12. Test Plan / Acceptance Criteria
13. References / Links
14. File List
15. Long Jobs / Backfill — *optional*
16. Rollback Plan — *optional*
17. Postmortems — *default `not applicable`*
18. Project History — **last**

**500 lines max.** Cut prose first when tight.

---

## 2. Maintain This Plan

- **This is a living document — maintain it constantly.** The moment anything changes, update this file: new info, a decision, a course change, an experiment result, a postmortem, a new constraint, a status change. It is a living human↔agent contract — curate context here so we can clear the conversation and resume cold from this file alone.
- **Own the plan.** Update + commit + push *in the same turn* at checkpoints.
- Keep: decisions + *why*, paths, commands, thresholds, acceptance, rollback, next steps.
- Drop: diary text, dead alternatives, "we tried X" narration, excessive reasoning.
- One status table (Execution Steps). Move rows `not started` -> `started (status)` -> `completed`.
- Project History is append-only. Keep the File List current.

---

## 3. Preferences / Best Practices

- **Be autonomous.** Decide and execute; ask when blocked, genuinely ambiguous, or before destructive/irreversible actions.
- **Think before coding.** State assumptions explicitly. Push back against the human when warranted. If multiple interpretations exist, present them — don't pick silently. Surface simpler approaches and tradeoffs.
- **Simplicity first.** Minimum code that solves the problem, nothing speculative — no unrequested features, no abstractions for single-use code, no configurability or error handling for impossible scenarios. If 200 lines could be 50, rewrite. (Boundaries live in §6.)
- **Surgical changes.** Every changed line traces to the request. Refactor when it unblocks the task (duplicated/convoluted code in your path, or to isolate code for a test) — never speculative cleanup of code you're just passing through.
- **Goal-driven execution.** Turn each task into a verifiable goal ("add validation" → "write tests for invalid inputs, then make them pass"). State a brief plan with a *verify* check per step and loop until verified.
- **Read before write.** Verify with data before mutating shared state.
- **Evidence first:** problem, observations, decision, implementation.
- **TDD by default:** cheapest failing test, minimum fix, refactor.
- Plain words. Small steps. Reversible beats clever.
- **Be succinct always.** Both in this doc and in conversation, prefer bullets, numbered lists, and diagrams over paragraphs. Avoid jargon/vocab words.
- **Use git cleverly, especially for debugging.**
  - Commit often, by filename (never `git add .`); keep commits atomic — one logical change each — with grep-searchable titles and descriptions.
  - *Develop with history:* `git log`/`blame` to recover intent, `git diff/show` to ground edits, `bisect` to find the breaking commit, `reflog` to recover lost state.
  - *As useful:* branch/tag a known-good state before risky work; worktrees to explore approaches in parallel.
- **Guard your context.** It degrades as it fills, so spend it deliberately. Reach for `grep -C`/`find`/`sed/tail/head` to pull only the lines you need instead of reading large files or docs whole; delegate big searches to subagents.
- Write python scripts to do tasks we may want to repeat rather than running strings of adhoc commands.
- Delegate all long-running tasks to subagents so as to keep main chat unblocked.

---

> **▲ Copy §1–§3 above verbatim on fork. ▼ Write everything below fresh for this project.**

## 4. Context & Problem Statement

**Current state.** The appliance works on-device: joins a meeting with only a meeting ID,
hosts with a ZAK, streams an RTSP/USB camera into Zoom. Two auth pieces exist, both wrong
for the public:

1. **SDK JWT** — signed by `backend/token_server.py` on the Mac (`/sdk-jwt`). Correct idea
   (secret off-tablet), wrong host (a LAN box on the dev's network).
2. **Hosting token** — `/host-zak` uses **Server-to-Server OAuth**, which mints a ZAK for
   **one fixed account** (the dev's). A stranger installing from the Play Store would host
   *as us*. Wrong. There is also a half-built per-user OAuth path (`/oauth/*` + `/session`)
   that redirects to a LAN http URL — untestable/unshippable as-is.

**Desired state (auth).** User taps **Sign in with Zoom** → a real Zoom web login opens
(their org's SSO / Google / password — Zoom handles it) → on success it returns straight
into the app → the app can now host meetings as *that user*, and stay signed in across
restarts without re-login. No secret in the APK. No box on anyone's LAN.

**Desired state (delivery).** Public Play Store listing; fresh tablet → install → sign in →
run a room. Requires: a hosted https backend (the only server, media never touches it),
a published **Zoom Marketplace** app (mandatory once non-dev users join on their own
accounts — see the auth research in git log / §11 Q4 of the main plan), and Google Play
compliance (data-safety, privacy policy, target API, signing).

**Constraints.** OAuth client secret and SDK secret cannot ship in the APK. Zoom OAuth
redirect URIs must be **https** (no custom-scheme, no http/LAN). ZAK TTL ~2 h → need refresh.

**Done looks like.** Play Store install → tap Sign in with Zoom → Zoom login page → app
returns signed-in → Start Meeting hosts their PMI with the camera streaming → app still
signed in tomorrow without re-login.

---

## 5. Execution Steps

Two tracks. **Track A is manual — you, in web UIs (Zoom Marketplace, Play Console, domain,
cloud). Start it first: the reviews are the long poles** (Zoom review ≈ 4–7 weeks empirically;
Play personal-account gate = 14-day closed test + manual production review). Track B is code
and runs autonomously in parallel. Policy facts behind each row are in §9.5–§9.6.

### Track A — manual (user, via UI). Front-loaded; start now.

| #   | Task                                                                        | Status      |
| --- | --------------------------------------------------------------------------- | ----------- |
| A1  | **App name + applicationId.** Zoom Marketplace app = **"Mobile Remote"**, Play app = **"Meetings Remote"**, applicationId = `com.bilal.meetingsremote`. Names checked free on both stores; neither contains "Zoom" (ToU §7.2); "Meetings Remote for Zoom" allowed in listing copy. **Now fully applied in code** (package/namespace/label, 2026-07-10 — `plans/2026-07-10-derisk-naming-rename.md`; repo renamed `meetings-remote`) | completed |
| A2  | Buy the **domain**: decided **`meetingsremote.app`** (checked unregistered 2026-07-08 via registry RDAP; `.com` also free — optional defensive grab). `.app` is HSTS-preloaded → https-only, matching Zoom's redirect rules. **User action: register it** (any registrar, ~$15/yr), then point DNS at the backend (B1) | started (user to register) |
| A3  | Cloud project for the backend (Cloud Run + Secret Manager + KV/Firestore); load SDK + OAuth secrets. Project **`meetings-remote-app`** created via gcloud 2026-07-11 (account ibbilal0@gmail.com). **Blocked on user: no GCP billing account — add card at console.cloud.google.com/billing** | started (billing blocked) |
| A4  | **Zoom Marketplace app config (auth):** enable Meeting SDK feature + "Use Public Client OAuth" (PKCE, no secret in flow), register `https://<domain>/oauth/callback`, add scopes (`user:read:zak` — auto-added with the SDK feature — plus profile scope **and `meeting:update:status`** for stranded-PMI auto-recovery, see B2; confirm exact strings in the app's Scopes tab), set the **deauthorization endpoint URL** | not started |
| A5  | **Zoom Marketplace submission** (security + review): listing needs privacy policy, Terms of Use, support URL, documentation URL; per-scope justifications; test plan with working test credentials the reviewer can run E2E; for a device-specific app expect to provide a **demo video + APK**; security questionnaire (OWASP-focused, SSDLC evidence if asked). Publishing is **mandatory** — unpublished SDK apps get error 4011 with other accounts' meetings, unpublished OAuth apps only auth same-account users. Submit the moment the flow demos E2E | not started |
| A6  | **Google Play account:** $25 fee + identity verification. Note: personal accounts show your legal name + address publicly on the listing (consider an organization account later; D-U-N-S needed only for orgs) | not started |
| A7  | **Play closed-test gate** (personal accounts created after Nov 2023): ≥12 testers opted in **continuously for 14 days**, then the "apply for production" questionnaire (manual Google review). Recruit the 12 testers early — this is a hard calendar cost | not started |
| A8  | **Play Console declarations:** Data Safety (collect name + user ID, encrypted in transit, not shared), privacy policy URL, **account-deletion URL** (required — we store refresh tokens keyed to the user), **app-access instructions** (working demo Zoom login for reviewers — classic OAuth-app blocker), ads = none, IARC content rating, target audience 13+/18+ (never children/Families), FGS declaration **with demo video** if we ship a foreground service (B10) | not started |
| A9  | **E2E + test errors:** second Zoom account (not the dev's) proves multi-tenant hosting; run §12A on the closed-test build and clear every failure before applying for production | not started |
| A10 | Production rollout, staged ≤20% first                                        | not started |

### Track B — autonomous (code/agent), parallel with A

| #   | Task                                                                        | Status      |
| --- | --------------------------------------------------------------------------- | ----------- |
| B1  | Host token backend on public **https** (Hetzner 16GB box, see Q1); move `/sdk-jwt` there. `backend/server.py` deployed (Caddy+systemd+rate limits+Postgres). **Public https LIVE** at `https://api.meetingsremote.app` (domain+DNS+prod-LE-TLS+CF edge done 2026-07-11). Remaining: **OAuth client id (A4)** for the sign-in path | https live; awaiting OAuth creds (A4) |
| B2  | Per-user **OAuth (PKCE)**: `/oauth/start`, `/oauth/callback`, `/session`, `/refresh`; Postgres session store (32GB box); drop `/host-zak` + S2S. **Implemented in `backend/server.py`** incl. `/signout`, `/deauthorize` webhook + data-compliance call (B3), `/delete` page (B4), `/return` + assetlinks serving (B5), authenticated `/end-stuck-meeting` (sid-gated). **Also migrate `/end-stuck-meeting`** (added 2026-07-09: force-ends a PMI stranded by a crash — the app auto-recovers from start error 100/80 with it) from S2S to the signed-in user's token: user-level granular scope `meeting:update:status` (Zoom's 4711 error names both it and the `:admin` variant), and require a valid `sid` — today the endpoint is unauthenticated, which is fine on a LAN but on a public backend would let anyone end anyone's meeting | not started |
| B3  | **Zoom deauthorization webhook + Data Compliance API:** on uninstall event, delete the user's data within **10 days** and confirm via `POST /oauth/data/compliance` — mandatory for published apps | not started |
| B4  | **Account deletion:** in-app "Sign out & delete my data" *and* a public web page `https://<domain>/delete` (Play requires both; reuse the same revoke+drop path) | not started |
| B5  | Return-to-app: **Android App Link** + hosted `/.well-known/assetlinks.json`  | not started |
| B6  | App auth UI: Custom Tab sign-in (never WebView — both Google and Zoom block embedded webview OAuth), signed-in state, sign-out; delete on-device JWT/creds paths | not started |
| B7  | Manifest/config cleanup. ✅ **rename applied** (A1). Remaining: **remove `READ_PHONE_STATE`** (declared, still unused); scope `usesCleartextTraffic` to debug/LAN-RTSP only (currently global); **move `TestHooksReceiver` to `src/debug/AndroidManifest.xml`** (exported receiver, no-ops unless `BuildConfig.DEBUG` but flags security review — now also carries the audio hooks `audioDelay/audioStats/syncNow/mlNow`); **keep AirPlay pairing creds out of release** (`AIRPLAY_SEED_HEX`/`AIRPLAY_PAIRING_ID` baked into `BuildConfig` from local.properties → pair at runtime instead) | started (rename done; 4 cleanups remain) |
| B8  | **16 KB page-size compliance:** rebuild the FFmpeg `.so`s with NDK r28+ (16 KB-aligned); **also verify the new prebuilt native libs — `onnxruntime-android` + `mediapipe tasks-vision` (B13) — ship 16 KB-aligned `.so`s** (bump versions if not) — Play blocks non-compliant new apps targeting API 35+ | not started |
| B9  | targetSdk 36 before **Aug 31, 2026** + survive API-36 large-screen rules (orientation locks ignored on sw600dp+ tablets — exactly our device). Now concrete: both activities carry `android:screenOrientation="landscape"` (added 2026-07-09 for the AirPlay mirror) — API 36 will ignore it, so the UI must handle portrait or use a runtime alternative | not started |
| B10 | Foreground services — **no longer hypothetical**: the AirPlay mirror already ships `AirPlayService` as a `mediaProjection`-type FGS (+ `FOREGROUND_SERVICE_MEDIA_PROJECTION`), so A8's FGS declaration **+ demo video** is required if AirPlay is in the Play build. Separately decide camera/mic FGS: only if streaming must survive screen-off/backgrounding (types `camera\|microphone`, +`connectedDevice` for USB); if activity-only (kiosk, keepScreenOn) suffices, skip that part | not started |
| B11 | **Legal UI notices — required (Q6 answered):** we use the custom meeting UI (`isCustomizedMeetingUIEnabled = true` in `RoomSdk`, custom `MeetingActivity`), so recording-consent, transcription, and share notices are **on us** to render (SDK suspension risk if missing). Implement before Marketplace submission | not started |
| B12 | AAB + Play App Signing (existing keystore = upload key)                      | not started |
| B13 | **On-device AV-sync ML** (added 2026-07-10): `syncnet_audio/visual.onnx` (~55 MB) + `blaze_face_short_range.tflite` in `assets/` inflate the AAB — decide bundle vs Play Asset Delivery pack. **Models are commercial-clean** (SyncNet MIT, BlazeFace/MediaPipe Apache-2.0, ONNX Runtime MIT; MTDVocaLiST rejected for licensing) → add OSS attribution in-app. Feeds B8 (native `.so` alignment) | not started |

**Ongoing after launch:** Zoom raises the SDK minimum version **quarterly** (Feb/May/Aug/Nov,
3 months notice; below-minimum builds are blocked from joining). Each SDK release is supported
≥9 months → budget **2–3 forced SDK-bump Play releases per year**.

---

## 6. Out of Scope / Non-Goals

- **Keeping the LAN Python box / S2S host path** — replaced by hosted https + per-user OAuth. The dev box stays only for local testing.
- **Custom-scheme deep links for the OAuth redirect** — Zoom requires https redirect URIs; we use https App Links, not `meetingsremote://`.
- **Storing Zoom refresh tokens on the tablet** — they live server-side; the tablet holds only a revocable opaque session id.
- **iOS / App Store** — Android/Play first; iOS is a later port (own Meeting SDK + Apple review).
- **A user database / accounts of our own** — no signup; identity is 100% Zoom OAuth. Minimal server-side session store only.

---

## 7. Architecture

The backend is the only server and **no media crosses it** — camera → tablet → Zoom stays
direct. Backend just holds secrets and brokers OAuth.

```
        ┌──────────────┐
        │ Tablet app   │
        │ (room/)      │
        └───┬──────────┘
   tap sign-in │  (Custom Tab)
            ▼
   ┌────────────────────┐
   │ Backend (https)    │  holds SDK secret + OAuth secret
   │  /sdk-jwt          │  + session store (KV)
   │  /oauth/start      │
   │  /oauth/callback   │
   │  /refresh          │
   └───┬────────────┬───┘
 302 to │            │ code→token→ZAK
        ▼            ▼
  ┌───────────┐  ┌──────────────┐
  │ Zoom login│  │ Zoom OAuth/   │
  │ (SSO/pwd) │  │ API (ZAK,PMI) │
  └─────┬─────┘  └──────┬───────┘
        │ user auths     │ ZAK+refresh
        ▼                ▼
   /oauth/callback stores refresh_token,
   302 → https App Link ────────────────► back INTO the tablet app
                        (carries one-time session id)
        │ app exchanges id
        ▼
   /session → {name, zak};  later  /refresh → new zak
        │
        ▼
   ZoomSDK host meeting (as the user) + RTSP camera
```

Key interfaces:
- **App Link** `https://<domain>/return?sid=…` — verified domain (`assetlinks.json`) so Android opens the app, not a browser tab. This is the "link back to my app" the user asked for.
- `RoomBackend.kt` — already the client; extend with `refresh()`, drop `hostZak()`.
- Session store: `sid → {refresh_token, name, ts}`. ZAK is derived on demand, never stored.

---

## 8. Databases and Schemas

Server-side KV (Cloudflare KV / Firestore / Redis) — `session` namespace. *(new)*

- `PK sid TEXT` — opaque 128-bit random; the only token the tablet holds.
- `refresh_token TEXT` — Zoom OAuth refresh token (secret; server-only).
- `zoom_user_id TEXT` — for revoke/debug.
- `name TEXT` — display name for the app.
- `created_at INT`, `last_used_at INT` — for TTL/cleanup.
- Deletes on sign-out (call Zoom token revoke + drop row). No other tables; no user accounts of ours.

---

## 9. Implementation Details

**Sign-in flow (steps 2–4)**

1. App makes `sid` = random 128-bit + PKCE `code_verifier`; opens a **Chrome Custom Tab** to
   `https://<domain>/oauth/start?sid=<sid>&cc=<code_challenge>`.
2. Backend 302s to `https://zoom.us/oauth/authorize?response_type=code&client_id=…&redirect_uri=https://<domain>/oauth/callback&state=<sid>&code_challenge=…&code_challenge_method=S256`.
   Zoom shows the user's real login (SSO/Google/password — nothing for us to build).
3. Zoom → `/oauth/callback?code&state=<sid>`. Backend exchanges code (+ `code_verifier`
   fetched by `sid`) → access + **refresh** token; calls `/users/me` (name, PMI) and
   `/users/me/token?type=zak` (ZAK). Stores `refresh_token` under `sid`.
4. Backend 302s to the **App Link** `https://<domain>/return?sid=<sid>`. Android opens the
   app directly (verified domain). App stores `sid` in `EncryptedSharedPreferences`.
5. App calls `/session?sid` → `{name, zak, pmi}`; hosts with that ZAK+PMI (existing host path).
6. **Re-host / next day:** app calls `/refresh?sid` → backend uses stored refresh_token →
   new access token → fresh ZAK (Zoom refresh tokens are single-use; store the rotated one).
   No user interaction. Sign-out = `DELETE /session?sid` (revoke + drop).

**Backend hosting (step 1)**

1. Package `backend/token_server.py` for a serverless https host (Cloud Run container is the
   least-rewrite: same stdlib server behind the platform's TLS). Secrets via env/secret-mgr.
2. Add `/refresh`; swap the in-memory `SESSIONS` dict for the KV store (survives restarts,
   needed for persistent login). Remove `/host-zak` and the S2S envs.
3. One redirect URI registered on the OAuth app: `https://<domain>/oauth/callback`.

**Zoom apps needed (step 6)**

1. **Meeting SDK app** — already have (JWT). Must be **published** via Marketplace review
   because non-dev users now join/host on their own accounts (distribution trigger).
2. **User-managed OAuth app** — scopes: `user:read` (name/PMI) + the ZAK token scope
   (`user:read:token` / `user:read:zak` per current scope naming). Least-privilege for review.
   *This replaces the S2S app entirely.*

**Play packaging (step 7)**

1. AAB (not APK) with **Play App Signing**; keep the existing release keystore as the upload key.
2. Privacy policy URL (what we store: Zoom refresh token server-side, nothing else) + Data
   Safety form. Target the current required API level. `minSdk 28` unchanged.
3. Foreground-service disclosure if the camera pump runs as one; declare permissions used.

**§9.5 Zoom policy facts (researched 2026-07-08, sources in §13)**

- **Publishing is mandatory for third-party use** (API ToU §6.1): unpublished SDK apps →
  error 4011 joining other accounts' meetings; unpublished OAuth apps → same-account only.
  "Unlisted" (published, not searchable) is an option and still passes full review.
- **Review:** phases = completeness/branding → functionality → security → remediation.
  No SLA; empirically 4–7+ weeks. Needs privacy policy, ToU, support + documentation URLs,
  scope justifications, runnable test plan w/ credentials, demo video/APK for device-specific
  apps, security questionnaire. Common blockers: unjustified scopes, no working test creds,
  dev instead of production client ID, missing deauth endpoint, "Zoom" in name/icon.
- **Deauthorization:** published apps must take Zoom's uninstall webhook; if the user denies
  retention, delete their data ≤10 days and confirm via `POST /oauth/data/compliance`.
- **Scopes (granular):** `user:read:zak` (auto-enabled, non-removable with the SDK feature) →
  `GET /v2/users/me/zak`; `user:read:token` for the older `/users/{id}/token?type=zak`;
  profile likely `user:read:user` — confirm strings in the Scopes tab (Q3).
- **PKCE public client** is supported ("Use Public Client OAuth" toggle, S256); redirect URIs
  must be https + allow-listed. Our backend-redirect design is the uncontroversial path.
- **Branding:** no "Zoom" (or confusingly similar, incl. package names — ToU §7.2) in app
  name/icon/applicationId; "*<Name>* for Zoom" compatibility phrasing is allowed. No
  "powered by Zoom" attribution required.
- **SDK lifecycle:** minimum version raised quarterly (Feb/May/Aug/Nov); each release
  supported ≥9 months; below-minimum builds can't join meetings.

**§9.6 Google Play policy facts (researched 2026-07-08, sources in §13)**

- **Target API:** 35 required now; **36 required Aug 31, 2026** for new apps/updates
  (verify exact wording in Play Console). At API 36, sw600dp+ devices ignore orientation/
  resizability locks — the app must handle any size/orientation.
- **16 KB page size:** mandatory for new apps targeting 35+ since Nov 2025 — FFmpeg `.so`s
  must be rebuilt NDK r28+ / AGP 8.5.1+, 16 KB-aligned; Play Console blocks otherwise.
- **Personal-account gate:** ≥12 closed testers opted in continuously 14 days → "apply for
  production" questionnaire (manual review). Identity verification; name+address public.
- **Account deletion:** applies to us (third-party sign-in + server-stored refresh token) →
  in-app deletion path **and** a web deletion URL declared in Play Console.
- **Data Safety:** declare Name + User IDs collected (app functionality, encrypted in
  transit, not shared); privacy policy URL mandatory even for test tracks.
- **FGS (if B10):** `foregroundServiceType` + typed permissions + Play Console per-type
  declaration **including a demo video**; camera/mic FGS can't start from background.
- **Camera/mic:** no declaration form needed (not SMS/CallLog-class); runtime prompt +
  Data Safety disclosure suffice; short pre-permission explainer = cheap insurance.
- **On-device ML (new 2026-07-10):** AV-sync processes camera video + mic audio locally
  (SyncNet lip-sync / GCC-PHAT) to align audio delay; nothing is transmitted or stored
  off-device → **Data Safety unchanged** (still Name + user id server-side; the mic/camera
  runtime disclosure already covers it). `RECORD_AUDIO` is now actively used (virtual mic).
- **App access:** reviewers need working sign-in instructions (demo Zoom account).
- **Format:** AAB + Play App Signing mandatory for new apps.

---

## 10. Data Snippets

Sign-in return (App Link into the app):
```
https://room.example.com/return?sid=8f3c…  →  Android opens app, app stores sid
```

`/session?sid=…` response:
```json
{ "ready": true, "name": "Dana Lee", "zak": "eyJ…", "pmi": "5551234567" }
```

`/refresh?sid=…` response (fresh ZAK, no login):
```json
{ "zak": "eyJ…newer…", "expires_in": 7200 }
```

---

## 11. Open Questions / Decisions Needed

- **Q1 — Backend host: DECIDED (2026-07-11) — user's Hetzner 16GB box** (`5.161.56.33`,
  Ashburn), not Cloud Run. Postgres on the 32GB box (`178.156.252.29`, user `bilal`) over
  Hetzner private network `internal` (10.0.0.0/16; 32GB=10.0.0.2, 16GB=10.0.0.3, root SSH
  key installed via rescue mode). PG 16 listens on 10.0.0.2, db/role `meetingsremote`,
  pg_hba+ufw scoped to 10.0.0.3 only. Hetzner cloud firewall `meetingsremote-fw`
  (22/80/443) on the 16GB box. Stack: Caddy (auto-TLS once DNS points) →
  127.0.0.1:8791 `backend/server.py` (systemd `meetingsremote`, hardened unit,
  per-IP token-bucket rate limits). GCP project `meetings-remote-app` now unused.
  *Persistence:* systemd `Restart=always` + boot-enabled — verified by `kill -9`
  (respawn <4s). Matches the 32GB box's architecture (there PM2 supervises node apps
  and systemd supervises PM2; single Python service needs no PM2 layer).
- **Q1b — Edge protection: RESOLVED via API token (2026-07-11).** Went with option (c):
  user hand-created a CF **API token** (`~/tmp/cf_token`, secret), sidestepping the
  headless-dash-auth stall entirely. Surprise upside: the newer Registrar API
  (`/registrar/domain-check` + `POST /registrar/registrations`) *does* support new-domain
  purchase — so the domain buy was **not** manual after all; scripted via curl
  (see Q2). Plan target unchanged: DNS proxied (orange-cloud) → hides origin IP;
  CF edge rate-limit + Bot Fight Mode absorb volumetric abuse (on-box buckets remain as
  app-level backstop). **DONE 2026-07-11** — the `cfat_` account-owned token couldn't grant
  Zone-scoped perms (dead end), so switched to the **Global API Key** (`~/tmp/cf_globalkey`;
  **rotate when infra work done**) and completed everything by curl: proxied A records,
  Full(strict)+HSTS, edge rate-limit (50/10s per IP on `api.`), Bot Fight Mode. Origin IP
  now hidden behind CF edge. Full detail + sequencing (grey-cloud-first for LE certs) in
  Project History 2026-07-11.
  *Toolkit fixes landed in `~/code/misc` (uncommitted): Chrome 150 removed `GET /json`
  (→ `/json/list`) and GET `/json/new` (→ PUT); helpers' hardcoded port 9222 →
  `CDP_PORT` env (main Chrome squats 9222 with a dead debug port; we run on 9333).*
- **Q2 — Domain (A2). REGISTERED: `meetingsremote.app` (2026-07-11).** Bought via CF
  Registrar API (`POST /registrar/registrations`, 1yr, auto_renew=true, privacy=redaction,
  $14.20/yr USD; renewal same). Registration workflow polled to `succeeded`. Zone
  auto-created and **active**, id `9179918ddcf4577d76951a3ae9725698`, NS
  `mark.ns.cloudflare.com` / `ziggy.ns.cloudflare.com`. `.com` was $10.46 but `.app`
  chosen for HSTS-preload (forced HTTPS, fits Zoom redirect rules). **DNS + TLS live**:
  A `api`/`@` → `5.161.56.33` proxied; Full(strict) + HSTS; production LE certs on the box;
  edge rate-limit + Bot Fight Mode on. `https://api.meetingsremote.app` + `…/return` verified.
  All backend URLs in this plan resolve to `https://api.meetingsremote.app` (backend) and
  `https://meetingsremote.app/return|/delete` (App Link + deletion page) unless revised.
- **Q3 — Scope strings.** `user:read:zak` confirmed (auto-added with SDK feature); profile
  scope likely `user:read:user` — verify both in the Marketplace Scopes tab before B2.
- **Q4 — Multi-account on one tablet?** Assume one signed-in host per device for v1; revisit
  if a room is shared.
- **Q5 — FGS (B10/A8): partially decided.** The AirPlay mirror already requires a
  `mediaProjection` FGS, so the Play FGS declaration + demo video is unavoidable if AirPlay
  ships. Still open: camera/mic FGS for screen-off streaming (`keepScreenOn` kiosk mode may
  suffice — note the tablet re-locks on battery, seen 2026-07-09; a room appliance should be
  on power anyway).
- **Q6 — Default vs custom meeting UI: ANSWERED (custom).** `isCustomizedMeetingUIEnabled =
  true`; the Zoom legal UI notices are on us → B11 is mandatory work.
- **Q7 — Play account type.** Personal (12-tester/14-day gate, name+address public) vs
  organization (needs D-U-N-S). Default: personal; user decides.

---

## 12. Test Plan / Acceptance Criteria / Repro Steps

### A. E2E / Human Test Plan
```
1. Fresh install from the Play internal-testing track on the tablet.
2. Tap "Sign in with Zoom" → Custom Tab opens Zoom login → sign in with a Zoom account
   that is NOT the developer's (proves multi-tenant).
3. Expect: browser bounces straight back into the app; app shows "Signed in as <name>".
4. Start Meeting → app hosts THAT user's PMI; RTSP camera streams in (verify on-device).
5. Force-stop the app, reopen next day → still signed in; Start Meeting works with no login
   (proves /refresh).
6. Sign out → /session gone; Start Meeting again requires login.
```

### B. Acceptance Criteria
- No secret in the APK (decompile check): no SDK/OAuth secret strings, and no AirPlay
  pairing creds (`AIRPLAY_SEED_HEX`/`AIRPLAY_PAIRING_ID` must be empty in release — B7).
- A non-developer Zoom account can host; the meeting host is that user, not us.
- Login survives app restart and ZAK expiry via server-side refresh; no re-login for ≥24 h.
- Return-to-app is automatic (App Link), no copy-paste, no LAN URL.
- Backend carries zero media; camera→tablet→Zoom path unchanged.

### C. Automated Tests
- Unit: PKCE challenge/verifier generation matches RFC 7636 test vectors.
- Unit (backend): `/session` returns `ready:false` for unknown/expired `sid`.
- Integration (backend): mock Zoom token endpoint → `/oauth/callback` stores a refresh token
  and `/refresh` rotates it (single-use refresh handled).
- On-device (manual, §12A): OAuth + host can't be meaningfully mocked.

---

## 13. References / Links

- Main project plan: `plans/2026-07-06-tablet-only-zoom-room.md` (§11 Q2/Q4 auth + review).
- Zoom OAuth (user-managed, PKCE): https://developers.zoom.us/docs/integrations/oauth/
- ZAK for hosting: https://developers.zoom.us/docs/meeting-sdk/auth/#start-meetings-and-webinars-with-a-zoom-users-zak-token
- Marketplace review: https://developers.zoom.us/docs/distribute/app-review-process/ ; feature review: https://developers.zoom.us/docs/distribute/sdk-feature-review-requirements/
- Android App Links (verified https deep links): https://developer.android.com/training/app-links
- Play: App Signing https://support.google.com/googleplay/android-developer/answer/9842756 ; Data Safety https://support.google.com/googleplay/android-developer/answer/10787469
- Zoom API License & ToU (publication §6.1, trademarks §7.2): https://www.zoom.com/en/trust/legal/zoom-api-license-and-tou/
- Zoom deauthorization + data compliance: https://developers.zoom.us/docs/integrations/end-user-auth/
- Zoom granular scopes: https://developers.zoom.us/docs/integrations/oauth-scopes-granular/ ; PKCE public client: https://developers.zoom.us/blog/public-pkce/
- Zoom app name/icon rules: https://developers.zoom.us/docs/build-flow/app-listing/app-icon-and-app-name/ ; SDK minimum version: https://developers.zoom.us/docs/meeting-sdk/minimum-version/ ; legal UI notices: https://developers.zoom.us/docs/meeting-sdk/ui-notices/
- Play target API: https://support.google.com/googleplay/android-developer/answer/11926878 ; 12-tester gate: https://support.google.com/googleplay/android-developer/answer/14151465 ; account deletion: https://support.google.com/googleplay/android-developer/answer/13327111 ; FGS declaration: https://support.google.com/googleplay/android-developer/answer/13392821
- 16 KB page size: https://developer.android.com/guide/practices/page-sizes ; API-36 large screen: https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability

---

## 14. File List

- `backend/token_server.py` — the backend; add `/refresh`, KV store, drop `/host-zak`+S2S; deploy to https.
- `room/src/main/java/com/bilal/meetingsremote/sdk/RoomBackend.kt` — client; add `refresh()`/`signOut()`, drop `hostZak()`.
- `room/.../MainActivity.kt` (+ sign-in UI) — Custom Tab launch, signed-in state, sign-out.
- `room/src/main/AndroidManifest.xml` — App Link intent-filter for `https://<domain>/return`.
- `room/src/main/res/…/assetlinks` / hosted `/.well-known/assetlinks.json` — App Link verification.
- `room/build.gradle.kts` — AAB/release signing config for Play App Signing.
- `backend/.env.example` — swap S2S envs for OAuth client id/secret + KV config.
- `backend/token_server.py` (or new module) — `/deauthorize` webhook + data-compliance call; `/delete` public account-deletion page; `/end-stuck-meeting` moved to per-user token + `sid` auth (B2).
- `room/src/main/java/com/bilal/meetingsremote/TestHooksReceiver.kt` + new `room/src/debug/AndroidManifest.xml` — move the debug test-hooks receiver out of the release manifest (B7).
- `room/build.gradle.kts` — `applicationId` done (A1); pending `targetSdk 36`, NDK r28+/16 KB-aligned FFmpeg `.so`s, verify onnxruntime/mediapipe libs (B8/B13).
- `room/src/main/AndroidManifest.xml` — drop `READ_PHONE_STATE`; debug-only cleartext config (label/package already renamed).
- `room/src/main/assets/{syncnet_audio,syncnet_visual}.onnx` + `blaze_face_short_range.tflite` — AV-sync ML (~55 MB); AAB-size + OSS-attribution (B13).
- `room/.../audio/**` (MicAudioSource, SyncEstimator, MlSyncEstimator, DriftCompensator, mlsync/) — audio + AV-sync feature; plan `2026-07-10-audio-and-av-sync.md`.
- `plans/2026-07-06-tablet-only-zoom-room.md` — parent plan; keep §11 Q2/Q4 in sync.

---

## 15. Long Jobs / Backfill

Not applicable.

---

## 16. Rollback Plan

- Auth: keep the old LAN `token_server.py` + on-device paths runnable behind a build flag for
  dev until the hosted flow is verified; the parent appliance is unaffected.
- Store: Play production rollout is staged (start ≤20%); halt rollout in Play Console to stop
  the bleed, no server changes needed. Backend is versioned; redeploy previous revision.

---

## 17. Postmortems

Not applicable.

---

## 18. Project History

- **2026-07-11 (CF edge DONE — domain + DNS + TLS + WAF)** — Q1b/Q2/B1(edge) fully landed.
  User first supplied a hand-made CF **API token** (`cfat_…`, `~/tmp/cf_token`); it could buy
  the domain but was an **account-owned** token, which doesn't expose Zone-scoped permission
  groups (Zone›DNS / Zone Settings simply aren't offered in that editor — dead end, not a
  missing checkbox). Switched to the **Global API Key** (`~/tmp/cf_globalkey`, secret; email
  `ibbilal0@gmail.com`; `X-Auth-Email`+`X-Auth-Key`) for whole-account curl control — **rotate
  when all infra work is done.** Rejected OAuth (CF has none for CLI account control) and the
  official CF **MCP** servers (per-product + OAuth browser login = the headless pain we dodged).
  Done via curl: (1) **Registered `meetingsremote.app`** — newer Registrar API supports
  new-domain purchase (`domain-check` → `POST /registrations`), 1yr, auto-renew, redaction,
  $14.20; workflow polled → `succeeded`. Zone active `9179918ddcf4577d76951a3ae9725698`.
  (2) **A records** `api`+`@` → `5.161.56.33`, created **grey-cloud first** so Caddy (already
  configured on the box for both hostnames → `127.0.0.1:8791`) got **production** Let's Encrypt
  certs (an in-mem *staging* retry loop from earlier testing cleared on `systemctl reload caddy`;
  certs valid → Oct 9). (3) Zone hardening: **SSL Full(strict)** (set *before* proxying to avoid
  the Flexible→redirect loop), Always-Use-HTTPS, min-TLS 1.2, TLS 1.3, **HSTS** 1yr
  includeSubDomains+nosniff (preload off = reversible; `.app` is TLD-preloaded anyway).
  (4) Flipped both records to **proxied** → origin IP hidden (edge IPs 104.21.91.44/172.67.210.50).
  (5) **Rate-limit ruleset**: block 50 req/10s per IP on `api.` host. (6) **Bot Fight Mode** on
  (needed `enable_js` paired). Verified end-to-end through the edge: `/return`→200, api→404
  (backend up), `ssl_verify=0`, `server: cloudflare`, HSTS header present, http→https 301.
  *Archived (superseded):* earlier headless-dash-auth stall — `9300 User session has expired`
  from `dash.cloudflare.com/api/v4/user` even with a fresh `vses2` cookie copy; the credential
  route made it moot.
- **2026-07-11 (infra + publish day)** — Repo made **public** (AGPL-3.0 + trademark
  note; README rewritten consumer-first; gitleaks: 90 commits clean; `main`
  fast-forwarded to `airplay-cast`; description+topics set). **Backend deployed**:
  Hetzner 16GB box (Q1), Postgres on the 32GB box over private net, `backend/server.py`
  (B2–B5 implemented), smoke-tested incl. 429s and systemd respawn. Cloudflare edge
  decided (Q1b) but stalled on headless auth — next steps in Q1b. GCP abandoned.
  **User's remaining manual list:** CF API token (or fresh login w/ main Chrome closed),
  domain purchase if API route fails, Play Console signup ($25+ID), 12 tester emails.
- **2026-07-11 (review vs current code)** — Reconciled after the naming rename + the audio/
  AV-sync feature landed (branch `airplay-cast`). A1 rename now **implemented in code**
  (package/namespace/label `com.bilal.meetingsremote`) → B7 rename done, 4 cleanups remain.
  Added **B13** (on-device AV-sync ML: ~55 MB ONNX/tflite assets → AAB-size decision + OSS
  attribution; models commercial-clean, MTDVocaLiST rejected for licensing). Extended **B8**
  to the new onnxruntime/mediapipe native libs. §9.6 gains the on-device-ML Data-Safety note
  (`RECORD_AUDIO` now active; no new data leaves the device).
- **2026-07-09 (review vs current code)** — Plan updated after the stranded-PMI work and
  the AirPlay branch landed: Q6 answered (custom meeting UI → B11 legal notices mandatory);
  B10/Q5 updated (AirPlay's `mediaProjection` FGS makes the Play FGS declaration + demo
  video unavoidable); B2/A4 gain the `meeting:update:status` scope + authenticated
  `/end-stuck-meeting` migration (error-100 auto-recovery, main plan §17); B7 gains
  strip-`TestHooksReceiver`-from-release and no-AirPlay-creds-in-APK; B9 notes the new
  hard `screenOrientation="landscape"` locks that API 36 will ignore.
- **2026-07-08 (domain)** — Q2 decided: **`meetingsremote.app`** (RDAP-verified free; `.com`
  also free). `.app` HSTS-preload = https-only, fits Zoom redirect rules. User to register.
- **2026-07-08 (naming)** — A1 done: Zoom Marketplace app **"Mobile Remote"**, Play app
  **"Meetings Remote"**, applicationId `com.bilal.meetingsremote`. Availability checked:
  no exact-name app on Play (closest: RSUPPORT "RemoteMeeting", "RemotePC Meeting") and
  none on Zoom Marketplace (closest: Zoom's "Zoom Rooms Controller"). Domain still open (Q2).
- **2026-07-08 (later)** — Policy audit vs current Zoom + Play rules (two research passes,
  facts in §9.5–§9.6). Restructured §5 into Track A (manual/UI, front-loaded: name+domain,
  Zoom Marketplace review, Play account/testing/declarations) and Track B (autonomous code).
  New requirements found: rename app/applicationId (Zoom trademark), deauthorization webhook
  + 10-day data deletion, account-deletion URL, 12-tester/14-day Play gate, 16 KB page-size
  FFmpeg rebuild, targetSdk 36 by Aug 2026, drop `READ_PHONE_STATE`, quarterly SDK-bump cadence.
- **2026-07-08** — Plan forked from `~/code/misc/plan-template.md`. Decision: replace S2S
  `/host-zak` (single-account) with per-user Zoom **OAuth (PKCE)** + a hosted **https**
  backend and an **Android App Link** return, so any user signs in as themselves; publish via
  Zoom Marketplace + Play closed→production. Media path unchanged.
