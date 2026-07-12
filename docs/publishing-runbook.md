# Publishing Runbook — Zoom Marketplace + Google Play

Your step-by-step for the manual (web-UI) tasks. All code/infra is done and
verified; this is the part only you can do (your Zoom + Google logins). Plan of
record: `plans/2026-07-08-playstore-and-oauth.md`.

Everything below in `code font` is a value to paste verbatim.

---

## 0. Confirmed live infrastructure (nothing to do — reference)

| Thing | Value |
| --- | --- |
| Backend (https, PKCE OAuth) | `https://api.meetingsremote.app` |
| App-Link return + policy host | `https://meetingsremote.app` |
| OAuth redirect URI | `https://api.meetingsremote.app/oauth/callback` |
| Deauthorization endpoint | `https://api.meetingsremote.app/deauthorize` |
| Privacy Policy URL | `https://meetingsremote.app/privacy` |
| Terms of Use URL | `https://meetingsremote.app/terms` |
| Support URL | `https://meetingsremote.app/support` |
| Account-deletion URL | `https://meetingsremote.app/delete` |
| App Link (verified) | `https://meetingsremote.app/return` |
| Play app name / package | **Meetings Remote** / `com.bilal.meetingsremote` |
| Zoom Marketplace app name | **Mobile Remote** (no "Zoom" per ToU §7.2) |

App Links are already domain-verified (Google Digital Asset Links confirms the
`assetlinks.json` on the apex matches the app signing cert).

---

## 1. A4 — Zoom Marketplace OAuth app  ✅ DONE 2026-07-11 (reference only)

**Completed autonomously via the CDP browser toolkit.** App = User-managed General
app `POxCyhnPSaCvcZyURH4x7w`. `ZOOM_OAUTH_CLIENT_ID = FjwVN3LIRy6OGxS9FJkHrA`
(the **Public Client ID**, PKCE/no-secret) and `ZOOM_WEBHOOK_SECRET_TOKEN =
z0ZPrYaiTyi-k6QaFC_c-g` are **set on the CF worker**; the full OAuth round-trip is
**E2E-verified with a real Zoom account** (`/session` returns real name/PMI/ZAK).
Scopes: `user:read:zak` + `user:read:user` + `meeting:update:status`. Redirect +
strict allow-list = `https://api.meetingsremote.app/oauth/callback`.
**Still to do at submission (A5): rename app → "Mobile Remote" + register the
deauthorization URL** (`https://api.meetingsremote.app/deauthorize`) — both live in
the App-Listing/submit wizard. Original how-to kept below for reference.

The blocker *was* a placeholder `ZOOM_OAUTH_CLIENT_ID` (Zoom rejected `4702 Invalid
client_id`). One real client id made it a live Zoom login.

**Recommended:** create ONE Zoom **General App** that has BOTH the user-managed
OAuth flow and the Meeting SDK feature (Zoom supports both on a General App).
Simplest path:

1. developers.zoom.us → **Develop → Build App → General App**. Name it
   **Mobile Remote**.
2. **OAuth**:
   - **App type / OAuth**: **User-managed app**.
   - Toggle **"Use Public Client OAuth"** ON (PKCE, no client secret in the flow).
     Our backend keeps the PKCE verifier server-side; there is no client secret.
   - **Redirect URL for OAuth**: `https://api.meetingsremote.app/oauth/callback`
   - **Add allow list** (OAuth allow list): `https://api.meetingsremote.app`
3. **Deauthorization** (Information/Feature tab → subscription/webhook):
   - **Deauthorize endpoint / Secret Token** area → set the deauthorization
     notification URL to `https://api.meetingsremote.app/deauthorize`
   - Copy the app's **Secret Token** — you'll paste it as
     `ZOOM_WEBHOOK_SECRET_TOKEN` in step 1b (used to verify webhook signatures).
4. **Add the Meeting SDK feature** to this app (Feature tab → enable Meeting SDK).
   This gives you a **Client ID / Client Secret** for the SDK JWT. If you'd rather
   keep the existing Meeting SDK app for `/sdk-jwt`, you can — just use this app's
   OAuth client id for sign-in. (The box already has a working SDK client id.)
5. **Scopes** (Scopes tab — add these granular scopes; confirm exact strings in
   the tab, they can vary slightly by account):
   - `user:read:zak` — **auto-added** with the Meeting SDK feature; needed to
     host as the user (non-removable).
   - `user:read:user` — profile: display name + PMI (shown in-app, used to host).
   - `meeting:update:status` — force-end a crash-stranded PMI (the app's
     auto-recovery from start error 100/80). If the account is admin-only, the
     equivalent is `meeting:update:status:admin`.
6. Copy the **Client ID** (this is `ZOOM_OAUTH_CLIENT_ID`).

### 1b. Put the real values on the backend  ✅ DONE (CF Workers, not SSH)

Backend is now Cloudflare Workers (the old Hetzner/SSH note is obsolete). Values were
set with:
```
cd backend/cf-worker
export CLOUDFLARE_API_KEY=$(cat ~/tmp/cf_globalkey) CLOUDFLARE_EMAIL=ibbilal0@gmail.com CLOUDFLARE_ACCOUNT_ID=3f190e8e67ba21f4454d7c079a42dd71
printf %s FjwVN3LIRy6OGxS9FJkHrA | npx wrangler secret put ZOOM_OAUTH_CLIENT_ID
printf %s z0ZPrYaiTyi-k6QaFC_c-g | npx wrangler secret put ZOOM_WEBHOOK_SECRET_TOKEN
```
No `ZOOM_OAUTH_CLIENT_SECRET` (public/PKCE client). SDK id/secret unchanged (existing
Meeting SDK app signs `/sdk-jwt`).

**Verified:** full OAuth E2E against the live worker returns a real ZAK. On the tablet,
Start Meeting → Sign in with Zoom now opens a real Zoom login (no `4702`).

---

## 2. A5 — Zoom Marketplace submission (long pole: ~4–7 weeks review)

Submit the moment §1 demos E2E. The listing needs:
- **Privacy Policy**: `https://meetingsremote.app/privacy`
- **Terms of Use**: `https://meetingsremote.app/terms`
- **Support URL**: `https://meetingsremote.app/support`
- **Documentation URL**: the GitHub repo README, or `https://meetingsremote.app/support`
- **Per-scope justifications** (paste these):
  - `user:read:zak` — "Obtain the signed-in user's ZAK to start (host) their own
    meeting from the room appliance via the Meeting SDK."
  - `user:read:user` — "Show who is signed in and host their Personal Meeting ID."
  - `meeting:update:status` — "Force-end the user's own meeting if it is left
    stranded 'in progress' by an app crash, so they can start again immediately."
- **Test plan + working test credentials** the reviewer can run E2E (a Zoom test
  account). For a device-specific app, expect to also provide a **demo video** and
  possibly an **APK**.
- **Security questionnaire** (OWASP/SSDLC). Note: no secrets in the APK (verified);
  refresh tokens are server-side only; TLS Full(strict) + HSTS at the edge.
- Publishing is **mandatory** (unpublished SDK apps hit error 4011 with other
  accounts). "Unlisted" (published, not searchable) is allowed and still fine.

---

## 3. Google Play (parallel with §2)

- **A6 — Account**: $25 + identity verification. Personal account shows your legal
  name + address publicly on the listing (org account avoids this but needs D-U-N-S).
- **A7 — Closed-test gate** (personal accounts): **≥12 testers opted in for 14
  continuous days**, then apply for production (manual Google review). **Recruit 12
  tester emails now** — this is a hard calendar cost. Send me the emails and I'll
  wire the internal-testing track.
- **A8 — Console declarations**:
  - **Data Safety**: collects **Name** + **User ID**; encrypted in transit; not
    shared; not sold. Camera/mic used, processed **on-device only** (AV-sync), not
    collected. (Details in `https://meetingsremote.app/privacy`.)
  - **Privacy policy URL**: `https://meetingsremote.app/privacy`
  - **Account-deletion URL**: `https://meetingsremote.app/delete` (+ in-app: Room
    settings → "Sign out & delete my data" — already built).
  - **App-access instructions**: give reviewers a working demo Zoom login (Sign in
    with Zoom → Start Meeting).
  - **Foreground service (FGS)**: the app ships a `mediaProjection` FGS (AirPlay
    mirror) → declare it **with a demo video** of the AirPlay feature.
  - Ads = none. IARC rating. Target audience 13+/18+ (never children/Families).
- **A9 — E2E on the closed-test build** with a **non-developer** Zoom account
  (proves multi-tenant hosting). Run plan §12A; clear every failure.
- **A10 — Production rollout**, staged ≤20% first.

---

## 4. What I still need from you to go further

1. **The real `ZOOM_OAUTH_CLIENT_ID`** (+ webhook Secret Token) from §1 — the one
   hard blocker for E2E sign-in.
2. **12 Play tester emails** — to start the 14-day clock ASAP.
3. **Play account type** decision (personal vs org).
4. **Production signing key** decision for the AAB (see plan B12) — I can generate
   an upload key and build the AAB; you keep/back it up. Play App Signing means the
   upload key is resettable, so this is low-risk.
