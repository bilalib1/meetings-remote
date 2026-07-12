# Telemetry + Error-Handling Hardening (Play Store readiness)

Before publishing `room/` to the Play Store we need to be able to see what breaks in the
field. Today there is **zero telemetry**: no crash reporting SDK, no uncaught-exception
handler, 76 scattered `android.util.Log` calls that die in logcat, ~43 `runCatching` blocks
(many silent), and 4 background threads that can die without anyone knowing. The Cloudflare
Worker backend has one global try/catch → `500 {error:"internal"}` and `console.log` only —
no persistence, no observability config. Target: every error/exception path is logged
through one app-side `Telemetry` facade, batched and uploaded to the existing CF Worker
(`POST /log` → D1), crashes captured and uploaded on next launch, and the worker itself
emits structured logs with per-route error codes. Full audit inventory in §9.

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

**Audit date 2026-07-12** (app: 19 Kotlin files / 6,887 lines + `rtsp_decoder.c`; worker:
`backend/cf-worker/src/index.ts`, 487 lines, 10 routes).

**App today.**
- Logging = raw `android.util.Log`, 10+ ad-hoc tags, mostly message-only (~8 places log a
  throwable). Nothing leaves the device.
- **No crash reporting** (no Crashlytics/Sentry/ACRA in `room/build.gradle.kts`), no
  `Thread.setDefaultUncaughtExceptionHandler`. A field crash is invisible to us.
- ~6 fully silent catches, ~18 catch-log-only sites, 3 ignored SDK error callbacks,
  4 threads (ffmpeg decode, mic capture, ML detector, sync estimator) whose death is silent.
- `RoomBackend.get()` collapses every failure (timeout / 401 / 5xx / DNS) into `null`.

**Worker today.**
- One global try/catch (`index.ts:473-487`) → `500 {error:"internal"}`; two `console.log`
  sites total; logs are ephemeral (`wrangler tail` only).
- `wrangler.toml` has **no** `observability`, no Analytics Engine, no logpush, no cron.
- 3 Zoom API `fetch`es without `.ok` checks (`index.ts:110,114,374`); all D1 queries
  unprotected; `/deauthorize` JSON.parse/hmac can throw uncaught.

**Why now.** A5 (Zoom Marketplace review) and A7–A9 (Play closed test with 12 external
testers) put the app on devices we cannot adb into. Without telemetry, every tester bug
report is "it didn't work".

**Done looks like.** A crash or error on any tablet appears as a queryable row in D1 within
minutes (or on next app launch for crashes); worker errors carry an error code + request id
visible in Cloudflare's dashboard; no exception path in app or worker is silent.

---

## 5. Execution Steps

### Track A — manual (user, via UI)

| #  | Task | Status |
| -- | ---- | ------ |
| A1 | **Play Data Safety update:** declare "App info and performance → crash logs + diagnostics" collected, encrypted in transit, deletable (extends playstore plan A8). Add a diagnostics sentence to the privacy policy page | not started |
| A2 | Cloudflare dashboard: confirm Workers Logs visible after B1 (`observability.enabled`), spot-check `/log` traffic + D1 `telemetry` row counts after B9 | not started |

### Track B — autonomous (code/agent)

| #  | Task | Status |
| -- | ---- | ------ |
| B1 | **Worker structured logging + route error codes:** `logj()` JSON logger (request_id, path, status, ms, error_code); per-route try/catch with stable codes (`zoom_auth_failed`, `db_error`, `bad_input`…); add missing `.ok` checks (`index.ts:110,114,374`); validate `/deauthorize` body; `observability.enabled = true` in `wrangler.toml` | not started |
| B2 | **Worker `POST /log` ingestion:** D1 `telemetry` table (§8), batch insert, sid-or-install_id identity, 64 KB body cap, per-identity throttle, `{ok,pause?}` response kill-switch | not started |
| B3 | **Worker retention + query tooling:** cron trigger (daily) purges rows > 30 d; `tools/telemetry_query.py` canned queries (errors by tag/day, crashes by version, one device's timeline) | not started |
| B4 | **App `Telemetry.kt` facade:** `Tlog.i/w/e(tag, msg, err?)` wraps `Log` + enqueues WARN/ERROR + lifecycle INFO events; disk-backed queue; batch upload (flush ≤50 events / 30 s / on ERROR) with exponential backoff; `install_id` UUID; honors kill-switch | not started |
| B5 | **Crash capture:** default `UncaughtExceptionHandler` → write crash file (stack, version, device) → rethrow; upload pending crash files on next launch; per-thread handlers on the 4 named worker threads → `thread_died` event | not started |
| B6 | **Audit fix pass 1 — never silent:** convert the §9 "silent" list to `Tlog` calls with throwables; log every SDK error code with enum name (RoomSdk join/start, onZoomAuthIdentityExpired → event + user notice) | not started |
| B7 | **Audit fix pass 2 — error taxonomy + surfacing:** `RoomBackend` returns typed result (timeout / http-4xx / http-5xx / no-net) + logs it, retry w/ backoff on transient; mic-capture failure → user notice + event; AirPlay start failure → toast + event; JNI `nativeOpen` returns reason code (timeout vs auth vs unreachable) surfaced in status + event | not started |
| B8 | **Watchdogs:** sync estimators get max-consecutive-failure fallback + event; ffmpeg/mic/ML thread exits emit `thread_died` with last error | not started |
| B9 | **E2E verify:** debug hook forces a crash + an error event on the SM-P620 → rows in D1; meeting cycle emits `session_start/join_ok/leave`; extend `backend/cf-worker/test_worker.py` for `/log` (happy, oversized, throttled, malformed) | not started |

Order: B1→B2 unblock everything app-side; B4→B5 before B6–B8 (fixes need the facade); B3, B9 last.

---

## 6. Out of Scope / Non-Goals

- **No third-party APM** (Crashlytics/Sentry/Datadog). One vendor (Cloudflare) we already
  run; keeps Data Safety minimal and APK dependency-free. Revisit only if D1 pipeline fails
  us (decision D1, §11).
- No metrics dashboards / alerting UI — canned SQL via `tools/telemetry_query.py` is enough
  at this scale.
- No DEBUG/VERBOSE upload — those stay in logcat. No log streaming; batch only.
- No PII in events: no names, emails, raw PMI/meeting IDs, tokens, or sid values (§9
  redaction rules).
- No ANR watchdog, no performance tracing (frame-rate stats stay local).
- Not rewriting working error paths that already log adequately (e.g., `AirPlayService`
  null-projection handling stays as-is, just routed through `Tlog`).

---

## 7. Architecture

```
 Tablet app (room/)
 ┌───────────────────────────────────────────────┐
 │  call sites ──► Tlog.i/w/e(tag,msg,err?) ─────┼──► logcat (always, all levels)
 │                    │  WARN/ERROR + lifecycle   │
 │                    ▼                           │
 │  UncaughtHandler   in-memory queue             │
 │  + thread handlers ──► crash file ─┐           │
 │                    ▼               │           │
 │            disk queue (JSONL) ◄────┘           │
 │                    │ batch: ≤50 ev / 30s /     │
 │                    │ on ERROR / on next launch │
 └────────────────────┼───────────────────────────┘
                      ▼  POST /log  (sid or install_id)
 ┌────────────────────────────────────────────────┐
 │ CF Worker `meetingsremote`                     │
 │  /log: validate → throttle → D1 batch insert   │
 │  all routes: logj() structured console JSON ───┼──► Workers Logs (observability)
 │  cron daily: DELETE telemetry > 30d            │
 └────────────────────┼───────────────────────────┘
                      ▼
        D1 `meetingsremote` → `telemetry` table
                      ▲
        tools/telemetry_query.py (wrangler d1 execute)
```

- **Two planes:** app events → D1 (queryable, 30 d); worker's own request/error logs →
  Workers Logs via `observability.enabled` (built-in retention, no code beyond `logj`).
- Media path untouched; `/log` reuses the existing worker, domain, and edge rate limit
  (50 req/10 s/IP + Bot Fight Mode — app UA already passes, see memory).
- Identity: signed-in → `sid` (verified against `sessions`, stored as `sid8` hash prefix);
  pre-sign-in → random `install_id` UUID (SharedPreferences).

---

## 8. Database Schema

Add to `backend/cf-worker/schema.sql` (D1 db `meetingsremote`):

```sql
CREATE TABLE IF NOT EXISTS telemetry (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          REAL NOT NULL,          -- device event time, epoch s
  ingested_at REAL NOT NULL,          -- server time
  install_id  TEXT NOT NULL,          -- app-install UUID
  sid8        TEXT,                   -- sha256(sid)[0:8] if signed in, else NULL
  app_version TEXT NOT NULL,          -- versionName (versionCode in meta)
  level       TEXT NOT NULL,          -- warn | error | crash | info
  tag         TEXT NOT NULL,          -- source tag: RoomSdk, RoomBackend, crash, ...
  event       TEXT,                   -- stable code: join_failed, thread_died, ...
  message     TEXT,
  stack       TEXT,                   -- truncated to 8 KB
  meta        TEXT                    -- JSON: model, os, extras (error codes etc.)
);
CREATE INDEX IF NOT EXISTS idx_telemetry_ts    ON telemetry (ts);
CREATE INDEX IF NOT EXISTS idx_telemetry_event ON telemetry (level, event, ts);
```

Retention: worker `scheduled()` handler, `crons = ["0 4 * * *"]`, deletes `ts < now-30d`.
`sessions`/`oauth_pending` unchanged.

---

## 9. Implementation Details

**`/log` contract (B2).**
- `POST /log`, body ≤ 64 KB:
  `{v:1, install_id, sid?, app_version, model, os, events:[{ts, level, tag, event?, message, stack?, meta?}]}`
- ≤ 50 events/batch; sid (if present) must exist in `sessions` else treated as anonymous;
  throttle: ≥ 200 events/id/hour → respond `{ok:true, pause:3600}` and drop (count in a
  per-isolate map is fine; precision not needed).
- Always 2xx on well-formed input (telemetry must never break the app); `400` only on
  malformed JSON/schema.

**`Telemetry.kt` (B4).** Object `Tlog`; `init(context, backendBase)` from `MainActivity`.
Upload policy: WARN/ERROR/crash always; INFO only for lifecycle events (`app_start`,
`sign_in_ok/fail`, `session_start/end`, `join_ok/failed`, `cast_start/fail`,
`camera_online/offline`). Disk queue = JSONL file, capped 256 KB (drop oldest). Backoff
30 s → 2 min → 10 min. Kill-switch: `pause` seconds from server persisted; release builds
only upload (debug logs locally unless overridden via test hook).

**Redaction rules (enforced in `Tlog`, checked in review):** never log name, email, ZAK,
refresh token, full sid, raw PMI/meeting number (hash to 8 hex if needed for correlation),
RTSP URL credentials (strip `user:pass@`).

**Crash path (B5).** Handler chain-calls previous handler (so the OS crash dialog/ART
behavior is preserved). Crash file = same event JSON, `level:"crash"`, written synchronously
before rethrow. Named threads to wrap: `FfmpegVideoSource` decode loop, `MicAudioSource`
capture, `MlSyncEstimator` detector/estimator, `SyncEstimator` loop, AirPlay drain.

**Audit inventory — the concrete fix list (B6–B8).**

*Silent catches → log with throwable:*
- `FfmpegVideoSource.kt:158` (sleep interrupt), `SyncEstimator.kt:96`,
  `MeetingWatchService.kt:24-26` (**`leave()` failure here = PMI stranding**, log before dying),
  `FfmpegVideoSource.kt:174-178` (`sysProp` reflection), `MeetingActivity.kt:184-191,205,409-410`
  (videoView lifecycle runCatching), `RoomSdk.kt:50-59,297-310` (SDK config / legal-notice
  runCatching), `CastController.kt:47`, `TestHooksReceiver.kt` (wrap dispatch).

*Ignored/underreported error callbacks:*
- `RoomSdk.kt:64-66` `onZoomAuthIdentityExpired` → event + user notice + sign-in prompt.
- `MainActivity.kt:687-697` join/start error map covers 4 codes → log every code with enum
  name + `join_failed` event; keep friendly text generic.
- `RoomSdk.kt:327,349` `-1` internal-error returns → distinct event.
- `MainActivity.kt:408-427` `/session`/`sdkJwt` nulls → now typed errors from B7.

*Error taxonomy + user surfacing (B7):*
- `RoomBackend.kt:61-70`: `sealed class BackendResult` (Ok / Timeout / Http(code) / NoNet);
  log each; retry ×2 w/ backoff on Timeout/5xx only (401 → sign-in prompt, no retry).
- `MicAudioSource.kt:116-144`: init/read failure → `mic_failed` event + in-meeting banner
  (today: meeting transmits silence with zero indication).
- `AirPlayService.kt:88-100` / `AirPlayCaster.kt:67-68` (null encoder NPE risk — add check):
  failure → `cast_failed` event + toast.
- `FfmpegVideoSource.kt:45` + `rtsp_decoder.c`: `nativeOpen` returns negative reason code
  (−1 timeout / −2 auth / −3 unreachable / −4 codec) instead of 0; Kotlin maps to status
  text + `camera_offline` event meta.
- `ExternalVideoSource.kt:102`: guard `sendVideoFrame` against mid-call `sender` null (race).

*Watchdogs (B8):*
- `SyncEstimator.kt:101-102` / `MlSyncEstimator.kt:177-181`: N=10 consecutive failures →
  stop, `estimator_stalled` event, fall back to last good delay.
- Thread wrappers from B5 emit `thread_died {thread, last_error}` — covers ffmpeg decode
  loop break (`FfmpegVideoSource.kt:144-145`) and ML estimator death.

**Worker hardening (B1).** `logj({request_id, path, status, ms, error_code?, err?})` on every
request (one line, JSON); wrap Zoom fetches `index.ts:110,114,374` with `.ok` checks that
throw typed `ZoomApiError(status, endpoint)`; `/session`,`/refresh`,`/signout`,
`/end-stuck-meeting` validate sid shape (≥16 chars) before DB; global catch keeps generic
`{error:"internal"}` to clients but logs code + request_id.

---

## 10. Data Snippets

Example uploaded batch:

```json
{"v":1,"install_id":"3fae…","sid":null,"app_version":"1.0.3","model":"SM-P620","os":"36",
 "events":[
  {"ts":1784206801.2,"level":"error","tag":"RoomBackend","event":"backend_timeout",
   "message":"GET /session timeout after 5000ms attempt 2"},
  {"ts":1784206810.9,"level":"crash","tag":"crash","message":"NullPointerException…",
   "stack":"at com.bilal.meetingsremote.AirPlayCaster.start(AirPlayCaster.kt:68)…"}]}
```

Canned queries (`tools/telemetry_query.py` → `wrangler d1 execute meetingsremote --remote`):

```sql
-- error volume by event, last 7d
SELECT event, level, COUNT(*) n FROM telemetry
WHERE ts > unixepoch()-604800 AND level IN ('error','crash')
GROUP BY event, level ORDER BY n DESC;
-- one device's timeline
SELECT datetime(ts,'unixepoch'), level, tag, event, message FROM telemetry
WHERE install_id = ? ORDER BY ts DESC LIMIT 200;
```

---

## 11. Open Questions / Decisions Needed

- **D1 (decided): self-hosted via CF Worker + D1, no Crashlytics/Sentry.** Why: backend
  already live + tested; zero new APK deps; Data Safety stays minimal ("diagnostics,
  encrypted, deletable"); our scale (≤ hundreds of devices) is trivially within D1 free
  limits. Cost of losing: pretty dashboards, ANR/native-crash symbolication. Revisit if
  native (FFmpeg/JNI) crashes dominate — those need NDK tombstones that only Play Console
  vitals / Crashlytics NDK capture well; Play Console **Android vitals** is free with no
  SDK and covers native crash + ANR rates, so it's our backstop.
- **Q2:** Workers Analytics Engine for counters (joins/day, error rates) in addition to D1?
  Lean **no** for now — D1 GROUP BY is enough; AE adds a binding + different query surface.
- **Q3:** Should `/log` accept unsigned-in (`install_id`-only) traffic forever, or only
  until sign-in? Lean forever — sign-in failures are exactly what we need to see.
- **Q4:** Delete telemetry rows on account deletion (`/signout`, `/deauthorize`)? We store
  only `sid8` hash + random install_id (no Zoom identity), so arguably out of scope of
  "user data" — but cheap to add `DELETE FROM telemetry WHERE sid8=?`. Lean yes (do in B2).

---

## 12. Test Plan / Acceptance Criteria

### A. E2E / Human
1. Debug build on SM-P620: `adb shell am broadcast` test hook `telemetry.crash` →
   app crashes → relaunch → within 60 s a `level=crash` row in D1 with correct stack.
2. Hook `telemetry.error` → `error` row appears ≤ 60 s (no relaunch).
3. Airplane-mode the tablet, emit errors, restore network → queued events arrive (disk
   queue survives process kill).
4. Full meeting cycle (join → in-meeting → leave) → `session_start/join_ok/session_end`
   INFO rows; kill RTSP cam mid-meeting → `camera_offline` with reason=unreachable.
5. Pull mic permission / break AudioRecord → in-meeting banner shows + `mic_failed` row.
6. `wrangler tail` (or dash Workers Logs) shows one JSON line per request with request_id;
   force a Zoom 401 (bad refresh token in D1) → `error_code=zoom_auth_failed`, not a bare 500.

### B. Acceptance
- Zero remaining catch blocks that neither log nor rethrow (grep audit re-run clean).
- Every `Log.e/w` call site migrated to `Tlog` (grep `android.util.Log` outside Telemetry.kt ≈ 0).
- Crash-to-D1 round trip proven on device; release build uploads, debug doesn't (unless hooked).
- No PII in any D1 row (manual review of a day's rows).
- `test_worker.py` green including new `/log` checks; existing 47 checks still pass.

### C. Automated
- JVM unit tests: disk queue (append/rotate/cap/drain), redaction (URL creds, PMI hashing),
  backoff schedule, batch chunking.
- `test_worker.py` additions: `/log` happy path (row lands in D1), malformed → 400,
  oversized → 413/400, throttle → `pause`, sid-validated vs anonymous, purge query.

---

## 13. References / Links

- `plans/2026-07-08-playstore-and-oauth.md` — backend architecture (§7), D1 schema (§8),
  A8 Data Safety declarations this plan extends.
- `plans/2026-07-06-tablet-only-zoom-room.md` — main appliance plan (error 100/80 lore).
- Worker deploy/auth/test commands: `backend/cf-worker/README.md` (`wrangler deploy`,
  secrets, `test_worker.py`).
- CF docs: Workers Logs (`observability`), D1 limits, cron triggers.

## 14. File List

- `backend/cf-worker/src/index.ts` — B1 `logj` + route hardening; B2 `/log`; B3 `scheduled()`
- `backend/cf-worker/schema.sql` — B2 `telemetry` table
- `backend/cf-worker/wrangler.toml` — `observability.enabled`, `crons`
- `backend/cf-worker/test_worker.py` — B9 `/log` checks
- `tools/telemetry_query.py` — B3 canned queries (new)
- `room/src/main/java/com/bilal/meetingsremote/Telemetry.kt` — B4/B5 facade + crash handler (new)
- Fix-pass touches (B6–B8): `MainActivity.kt`, `MeetingActivity.kt`, `MeetingWatchService.kt`,
  `AirPlayService.kt`, `AirPlayCaster.kt`, `CastController.kt`, `TestHooksReceiver.kt`,
  `sdk/RoomSdk.kt`, `sdk/RoomBackend.kt`, `sdk/ExternalVideoSource.kt`,
  `source/FfmpegVideoSource.kt`, `audio/MicAudioSource.kt`, `audio/SyncEstimator.kt`,
  `audio/MlSyncEstimator.kt`, `room/src/main/cpp/rtsp_decoder.c`

## 15. Long Jobs / Backfill

Not applicable (no historical data to backfill; retention purge is the only recurring job).

## 16. Rollback Plan

- App: `Tlog.UPLOAD_ENABLED` BuildConfig flag — flip off + release to stop uploads; facade
  degrades to plain logcat. Server kill-switch (`pause`) throttles a bad fleet immediately
  without an app release.
- Worker: `/log` is additive — revert commit + `wrangler deploy`; drop table with
  `wrangler d1 execute meetingsremote --remote --command 'DROP TABLE telemetry'` if needed.
  Existing routes/tables untouched by rollback.

## 17. Postmortems

not applicable

## 18. Project History

- **2026-07-12** — Plan created from dual audit (app: 19 Kotlin files + JNI; worker:
  `index.ts` 487 lines). Findings: no crash reporting, ~6 silent catches, 18 log-only
  catches, 4 unguarded threads, 3 ignored SDK callbacks, `RoomBackend` collapses all HTTP
  failures to null; worker: single global catch, 2 console.log sites, no observability
  config, 3 un-checked Zoom fetches. Decision D1: self-host telemetry on existing CF
  Worker + D1 (no Crashlytics/Sentry); Play Console Android vitals as native-crash backstop.
