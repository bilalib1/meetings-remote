# Torture Test Round 2 — Repeatable Release-Torture Harness

Round 1 (2026-07-13, `docs/RELEASE_READINESS_2026-07-13.md`) was a manual torture test: it
found four release blockers (RR-01…04, since fixed and recertified) but was driven by hand,
so re-running it costs a full day and its coverage list lives only in prose. It also left a
named gap list untested: network loss, process death, second participant, passcode/waiting
room, RTSP recovery, AirPlay pairing failure, Bluetooth, doze. This plan (a) rebuilds
everything Round 1 did as a scripted, repeatable harness (`tools/torture/`), and (b) extends
it with new torture scenarios covering those gaps — so before every store upload we run one
command and get a pass/fail report with evidence artifacts.

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

- **App:** Meetings Remote (`com.bilal.meetingsremote`), tablet-only Zoom Meeting SDK
  appliance. Device under test (DUT): Samsung SM-P620, Android 16 / API 36, on LAN.
- **Fixed supporting cast:** Mac = build host + fake RTSP camera (`tools/rtsp_*.sh`) +
  second participant via **Zoom web client in Chrome** driven by AppleScript/CDP
  (never Mac desktop zoom.us — standing rule). AirPlay receiver at `192.168.1.233`.
  Token backend = live CF Worker (join needs no creds; host via OAuth).
- **Round 1 state:** RR-01 (relaunch strands meeting), RR-02 (mic-denied silent audio),
  RR-03 (AirPlay teardown ANR), RR-04 (debug-key signing) all closed with evidence.
  But every step was manual, and §"Limited/not exercised" lists real release risks we
  never touched.
- **Problem:** we cannot cheaply re-certify. Any code change (e.g. current `airplay-cast`
  branch work) invalidates the GO verdict and re-testing by hand is a day of work.
- **Done looks like:** `python3 tools/torture/run.py --suite all` on a prepared bench runs
  every Round-1 check plus the new torture scenarios (T-suite below), writes per-scenario
  PASS/FAIL + artifacts to `docs/test-artifacts/<date>-torture-round-2/`, emits a markdown
  report, and a filled-in `docs/RELEASE_READINESS_<date>.md` verdict. Scenarios needing a
  human (physical Wi-Fi router action, visual AirPlay confirmation on TV) are explicit
  `MANUAL:` prompts inside the same run, not out-of-band lore.

---

## 5. Execution Steps

Single source of truth for progress. Keep statuses current.

| #  | Task                                                                        | Status      |
| -- | --------------------------------------------------------------------------- | ----------- |
| 1  | Write this plan; agree scenario list (§9)                                    | completed   |
| 2  | Harness skeleton: `tools/torture/run.py` + `lib.py` (adb, logcat, evidence) | not started |
| 3  | Extend `TestHooksReceiver` with hooks needed by T-suite (§9.3)               | not started |
| 4  | Mac-side participant driver `tools/torture/participant.py` (web client/CDP)  | not started |
| 5  | Implement R-suite (Round-1 replay, R-01…R-10)                                | not started |
| 6  | Implement T-suite lifecycle + concurrency (T-01…T-05)                        | not started |
| 7  | Implement T-suite network + backend (T-06…T-09)                              | not started |
| 8  | Implement T-suite AirPlay + RTSP (T-10…T-13)                                 | not started |
| 9  | Implement T-suite permissions/doze/audio (T-14…T-17)                         | not started |
| 10 | Full `--suite all` run on DUT; triage failures; file/fix blockers            | not started |
| 11 | Write `docs/RELEASE_READINESS_<date>.md` verdict + commit artifacts          | not started |

---

## 6. Out of Scope / Non-Goals

- **No CI/emulator port.** The suite targets the physical bench (real SDK, real AirPlay,
  real RTSP); emulators can't exercise any of the interesting failures.
- **No new product features.** Only test hooks and fixes for defects the suite finds.
- **No Mac desktop zoom.us automation** — standing project rule; second party is the web client.
- **No load/scale testing** (many participants, long-haul soak beyond the 30-min soak in T-05).
- **No Play-Store-listing work** — that lives in `plans/2026-07-08-playstore-and-oauth.md`.

---

## 7. Architecture

```
        ┌─────────────────────────┐
        │ tools/torture/run.py    │  Mac, orchestrator
        │ (scenario registry)     │
        └──┬────────┬─────────┬───┘
     adb   │        │ spawn   │ AppleScript/CDP
           ▼        ▼         ▼
   ┌───────────┐ ┌────────┐ ┌──────────────┐
   │ DUT tablet│ │ RTSP   │ │ Chrome web   │
   │ SM-P620   │ │ fake   │ │ client       │
   │ app + SDK │ │ cam    │ │ participant  │
   └─────┬─────┘ └────────┘ └──────────────┘
         │ broadcast: TestHooksReceiver
         │ logcat / screencap / dumpsys
         ▼
   ┌───────────────────────────────┐
   │ docs/test-artifacts/<run-id>/ │
   │ per-scenario logs + PNG + md  │
   └───────────────────────────────┘
   (side actors: AirPlay receiver
    192.168.1.233; CF Worker backend)
```

- Each scenario = one Python function registered as `("T-06", "wifi loss mid-meeting", fn)`.
- `lib.py` provides: `adb()`, `broadcast(cmd, **extras)`, `logcat_capture(scenario)`,
  `screencap(name)`, `wait_meeting_status(status, timeout)`, `manual(prompt)` (blocks for
  y/n and records the answer), `evidence(name, data)`.
- Meeting state is read via `cmd=dump` broadcast → logcat parse (already logs
  `status=… participants=…`).

---

## 8. Databases and Schemas

Not applicable — evidence is flat files. One `report.json` per run:
`{run_id, git_sha, device, scenario_id, verdict, duration_s, artifacts[], notes}` per row.

---

## 9. Implementation Details

### 9.1 Harness run loop

1. Preflight: `adb devices` shows DUT; app installed from current branch (`assembleDebug`
   for hook-driven scenarios; `assembleRelease` only for R-09 signing check); RTSP fake cam
   up (`tools/rtsp_test_stream.sh`); backend `/health` reachable; record git SHA + `dumpsys battery`.
2. For each selected scenario: start scoped `logcat -T <now>` capture → run steps →
   assert → screencap → write verdict row → **restore baseline** (leave meeting via
   `cmd=leave`, re-grant permissions, Wi-Fi on, force-stop app, 5 s settle).
3. Any scenario that throws = FAIL with traceback; harness continues (no fail-fast) unless
   `--fail-fast`.
4. End: write `report.md` (table) + `report.json`; nonzero exit if any FAIL.
5. Repeatability rules: fixed scenario IDs; `monkey` runs use `-s 20260714` fixed seed;
   all timeouts/hosts/IDs in `tools/torture/config.py` (no literals in scenarios).

### 9.2 R-suite — Round-1 replay (regression-pins the 2026-07-13 findings)

| ID   | What (source in Round-1 doc)                                                          | Pass condition |
| ---- | ------------------------------------------------------------------------------------- | -------------- |
| R-01 | RR-01 relaunch: start meeting → Home → `monkey -p … 1` relaunch                        | back in `MeetingActivity`, status stays INMEETING, controls respond |
| R-02 | RR-01b: 5 rapid Start taps from Ready screen                                           | exactly 1 auth refresh + 1 MeetingActivity (logcat count) |
| R-03 | RR-02: revoke RECORD_AUDIO (`pm revoke`) → cold launch → Start                         | blocked pre-backend with "Microphone permission required" UI |
| R-04 | RR-03: active cast + MediaProjection → `cmd=leave` + immediate reinstall               | no ANR/crash signature in logcat; projection stops |
| R-05 | RR-04: `apksigner verify --print-certs` on fresh release APK + AAB                     | CN=Meetings Remote Upload, fingerprint `01:BF:C2:26…D3:B2`; debug-key build fails closed |
| R-06 | Gradle gates: `testDebugUnitTest assembleDebug assembleRelease bundleRelease lintDebug` | all green; lint errors 0 |
| R-07 | Rapid mute/video: 11 alternating taps in meeting                                       | UI state matches `cmd=dump` SDK state at end |
| R-08 | Empty/garbage meeting-ID join rejection                                                | error surfaced, no crash, Ready screen usable after |
| R-09 | Home/background/resume ×5 in meeting                                                   | same task resumed, no duplicate activities (`dumpsys activity`) |
| R-10 | PMI stranding recovery: `cmd=leaveNoEnd` then Start                                    | error 100/80 recovery path re-hosts (per pmi-stranding hooks) |

### 9.3 T-suite — new torture scenarios

New `TestHooksReceiver` cmds needed: `castStart`/`castStop` (drive AirPlay without UI),
`setBackend --es url` (point at black-hole port for T-08), plus existing
`leave/leaveNoEnd/dump/audioDelay/audioStats/syncNow`.

**Lifecycle & concurrency**

| ID   | Steps | Pass condition |
| ---- | ----- | -------------- |
| T-01 | Process death: in meeting → `am kill` (SIGKILL, background first) → relaunch | app restores to sane state: either rejoins or clean Ready screen with accurate status; no zombie foreground-service notification |
| T-02 | Force-stop mid-meeting → relaunch → Start new meeting | second meeting hosts fine; backend not left holding stale state |
| T-03 | Monkey storm: `monkey -p … -s 20260714 --pct-syskeys 0 500` on Ready screen, then again in-meeting | no crash/ANR in logcat; app recoverable to Ready via `cmd=leave` |
| T-04 | Start/leave churn: 10 cycles of intent-extra start → wait INMEETING → `cmd=leave` | every cycle completes < 60 s; cycle 10 as fast as cycle 1 (no leak); `dumpsys meminfo` PSS growth < 20% |
| T-05 | 30-min soak: hosted meeting + cast + web participant, sample `dump`/meminfo/thermal every 60 s | no status flap, PSS slope ~flat, no ANR |

**Network & backend**

| ID   | Steps | Pass condition |
| ---- | ----- | -------------- |
| T-06 | Wi-Fi loss mid-meeting: `svc wifi disable` 30 s → enable | app shows reconnecting state, rejoins or fails with actionable error; never fake-live controls |
| T-07 | Airplane-mode blip 10 s during cast + meeting | same as T-06 and cast either resumes or reports stopped |
| T-08 | Backend unreachable: `cmd=setBackend` → black-hole → tap Start | fails fast (< 15 s) with clear error; restore URL → Start succeeds |
| T-09 | Web participant churn: Mac web client joins/leaves ×5 (participant.py) | roster count tracks each change within 10 s (`cmd=dump`) |

**AirPlay & RTSP**

| ID   | Steps | Pass condition |
| ---- | ----- | -------------- |
| T-10 | Cast start/stop loop ×10 via `castStart`/`castStop` | receiver reachable after each stop; no service leak (`dumpsys activity services`) |
| T-11 | Receiver vanishes mid-cast (MANUAL: pull receiver power or block IP via router) | app notices ≤ 30 s, surfaces error, meeting unaffected |
| T-12 | RTSP cam dies mid-meeting: kill `rtsp_test_stream.sh` 60 s → restart | camera-offline indicator shown; video recovers ≤ 30 s after restart, no crash |
| T-13 | RTSP never up at start | meeting hosts audio-only with offline indicator (no hang on start path) |

**Permissions, power, audio**

| ID   | Steps | Pass condition |
| ---- | ----- | -------------- |
| T-14 | Revoke RECORD_AUDIO while INMEETING (`pm revoke`; expect process restart) | app comes back to accurate state; on next start, R-03 gate fires |
| T-15 | Doze: `dumpsys deviceidle force-idle` 3 min mid-meeting, then unidle | audio/meeting survive (foreground service exemption) or degrade with explicit UI |
| T-16 | Battery saver on mid-meeting (`settings put global low_power 1`) | no silent mic/cast death; note any throttling in report |
| T-17 | AV-sync sweep: `cmd=audioDelay` 0/120/240 ms + `syncNow`; parse `audioStats` | estimator converges within ±30 ms of injected delay each step |

### 9.4 Two-party participant driver

1. `participant.py join <meeting_id>`: open CDP Chrome (port per browser-toolkit memory,
   spoofed UA per Cloudflare memory) → Zoom web client URL → join with name `torture-bot`.
2. `leave`, `is_joined` subcommands; screenshots into the run's artifact dir.
3. Used by T-05/T-09; R-suite runs single-party like Round 1.

---

## 10. Data Snippets

`report.json` row (shape, real values filled by run):

```json
{"run_id": "2026-07-14a", "git_sha": "bff35da", "scenario": "T-06",
 "verdict": "PASS", "duration_s": 84,
 "artifacts": ["T-06-logcat.txt", "T-06-after.png"], "notes": "rejoined in 12s"}
```

`cmd=dump` logcat line the harness parses:

```
I/TestHooks: status=MEETING_STATUS_INMEETING participants=[Bilal (Host)]
```

Monkey invocation (fixed seed → repeatable event stream):

```bash
adb shell monkey -p com.bilal.meetingsremote -s 20260714 --pct-syskeys 0 --throttle 50 500
```

---

## 11. Open Questions / Decisions Needed

- T-11 receiver kill: pull power manually vs. router IP block — pick whichever is
  scriptable on your router; otherwise stays `MANUAL:`.
- Passcode/waiting-room flows (Round-1 gap) need a second Zoom account/meeting config —
  in scope only if the test account supports it without new spend; otherwise log as
  still-untested in the verdict doc. **Decision needed.**
- Does `pm revoke` on API 36 still kill the process (T-14 assumes yes)? Verify on DUT first.

---

## 12. Test Plan / Acceptance Criteria / Repro Steps

### A. E2E / Human Test Plan

```bash
# One command, full bench run (Mac, repo root; tablet on LAN, RTSP + receiver up)
python3 tools/torture/run.py --suite all --out docs/test-artifacts/$(date +%F)-torture-round-2
# Expect: R-01..R-10, T-01..T-17 rows, PASS on all, MANUAL prompts for T-11 (+ any router steps)
```

```bash
# Quick regression subset (pre-commit, ~10 min, no manual steps)
python3 tools/torture/run.py --suite regression   # R-01,R-02,R-03,R-07,T-04,T-08
```

### B. Acceptance Criteria

- `--suite all` completes on the bench; every scenario yields PASS/FAIL/MANUAL-verdict —
  none silently skipped; report.md + report.json + artifacts written.
- Running the same suite twice back-to-back gives the same verdicts (repeatability).
- All R-suite scenarios PASS (Round-1 regressions stay fixed). Any T-suite FAIL is either
  fixed or written up as a blocker in the new `RELEASE_READINESS` doc before upload.
- No harness step requires reading this plan to execute — `run.py --help` + MANUAL prompts suffice.

### C. Automated Tests

- Unit (Mac, pure Python): logcat parser — `dump` line with 0/1/many participants; report
  writer — FAIL row forces nonzero exit.
- Instrumentation (from Round-1 RR-03 recommendation, still owed): leave/stop while
  MediaProjection pending — no ANR.
- Integration: `run.py --suite smoke` (R-06 gradle gates + R-08 bad-ID) in one go.
- `main` is currently green; no known red tests.

---

## 13. References / Links

- Round-1 record: `docs/RELEASE_READINESS_2026-07-13.md` (+ artifacts dir referenced there).
- Related plans: `plans/2026-07-12-telemetry-and-error-handling.md`,
  `plans/2026-07-08-playstore-and-oauth.md`, `plans/2026-07-08-airplay-cast-to-tv.md`,
  `plans/2026-07-10-audio-and-av-sync.md`.
- Template source: `~/code/misc/plan-template.md`.

---

## 14. File List

- `tools/torture/run.py` — orchestrator + scenario registry *(new)*.
- `tools/torture/lib.py` — adb/logcat/evidence helpers *(new)*.
- `tools/torture/config.py` — device serial, hosts, timeouts, monkey seed *(new)*.
- `tools/torture/participant.py` — Mac web-client second party *(new)*.
- `room/src/main/java/com/bilal/meetingsremote/TestHooksReceiver.kt` — extend with
  `castStart/castStop/setBackend` (debug-only).
- `tools/rtsp_test_stream.sh`, `tools/rtsp_delayed_relay.sh` — fake camera (existing).
- `docs/test-artifacts/<date>-torture-round-2/` — run outputs *(new per run)*.
- `docs/RELEASE_READINESS_<date>.md` — verdict doc produced by step 11.

---

## 15. Long Jobs / Backfill

- `--suite all` is ~2–3 h wall-clock (soak + manual steps). Run under a background agent
  that tails `report.md` and pings on MANUAL prompts/failures; artifacts stream to disk
  per scenario so a killed run keeps completed evidence (`--resume <run_id>` re-runs only
  missing rows).

---

## 16. Rollback Plan

Not applicable — additive test tooling + debug-only hooks. If a hook misbehaves, revert its
commit; release builds never include `TestHooksReceiver` paths (`BuildConfig.DEBUG` guard —
R-05 also verifies release artifact identity).

---

## 17. Postmortems

Not applicable yet. Trigger: if a T-suite scenario finds a shipped-severity defect after we
have published, write it up in `postmortems/` per template rules.

---

## 18. Project History

- **2026-07-14** — Plan created: script Round-1 torture coverage (R-01…R-10) and add
  T-01…T-17 covering the gaps Round 1 left untested; one-command repeatable bench run.
