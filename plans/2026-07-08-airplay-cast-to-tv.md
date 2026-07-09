# AirPlay-2 "Cast to TV" from the Zoom Room app

Add a **Cast** button (right of Invite) in the meeting screen that mirrors the live meeting
(video+audio, <1s latency) to a local TV. Target TV is an **AirPlay-2-only TCL•Roku** — no
Chromecast, no Miracast peers — so the universal in-app path is a hand-rolled **AirPlay-2
mirroring sender** (HomeKit pairing + type-110 H.264 stream), no external apps, no paid SDK.

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

**Goal:** a "Cast" control (right of Invite in `MeetingActivity`) → picker of local TVs → mirror
the live meeting to one, in-app, no external deps, universal.

**Requirements (user):** full-screen mirror to start; compatible with *all* TVs (not just
Samsung/Google); lives entirely in our app (no Samsung Smart View); low latency (~1s).

**Test hardware (on LAN):** TCL•Roku TV `阿鸣家的电视`, IP `192.168.1.233`, model 43S423/C107X,
Roku OS 15.2.4, AirPlay-2 on `:7000`, ECP/DIAL on `:8060`. Tablet SM-P620 @ `192.168.1.154`,
Mac (dev) @ `192.168.1.50`.

**Discovery truth (probed, not assumed):** only cast surface this TV exposes for live video is
**AirPlay 2**. No `_googlecast._tcp` on the LAN; tablet has **no Miracast peers**
(`mWfdEnabled=false`); the OS "Google Cast" panel finds nothing. Roku ECP control is **403**
("Control by mobile apps" = Limited). ⇒ Chromecast/Cast SDK and Miracast are dead ends for
*this* TV; AirPlay is the only path.

**Done looks like:** in a meeting, tap Cast → pick the TV → the far end shows on the TV with
<1s latency; no PC, no dongle, no Smart View, no code prompt after first pairing.

---

## 5. Execution Steps

| # | Task | Status |
|---|------|--------|
| 1 | In-app Cast button + `MediaRouter` picker (Chromecast/Miracast TVs) + system-Cast fallback | **completed** — built, compiles, installed; correct for Cast/Miracast TVs but finds nothing for this AirPlay-only Roku |
| 2 | Identify TV protocols (probe AirPlay/ECP/DIAL) | **completed** — AirPlay-2 only; see §4 |
| 3 | Research AirPlay-2 mirroring *sender* protocol + FairPlay feasibility | **completed** — feasible; **FairPlay avoidable** (omit ekey/eiv → stream unencrypted, AirParrot-style). Pairing is the real gate. Full spec in §9 |
| 4 | Python prototype: transient (PIN-less) pairing | **completed (negative)** — handshake shape proven (flag `0x10` 1-byte reaches M4) but TV **rejects PIN 3939** (auth err 02) then rate-limits (backoff err 03) ⇒ Roku won't do PIN-less transient |
| 5 | Python prototype: persistent pair-setup (PIN, M1–M6) + pair-verify | **BLOCKED / in progress** — code written (`tools/airplay_proto/airplay_hap.py`); **stuck: TV shows NO on-screen code** when triggered, so we can't complete SRP. Need the `Require Code` setting value (§11 Q1) |
| 6 | Port pairing to Kotlin/JNI in the app | not started |
| 7 | Mirror stream: MediaProjection→MediaCodec H.264 → type-110 framing → TCP, unencrypted | not started |
| 8 | NTP timing responder (UDP) + AAC-ELD audio | not started |
| 9 | Wire Cast button → AirPlay sender; store creds; pair-verify on reconnect | not started |
| 10 | Optimize source: off-screen render of far-end video at TV native res/fps (vs whole screen) | not started |

---

## 6. Out of Scope / Non-Goals

- Google Cast SDK / Chromecast receiver app; Miracast/Smart View (no peers here, and system-dep).
- AirPlay **mirroring encryption** (FairPlay ekey generation) — not open-source; we send unencrypted.
- TV-joins-Zoom-directly — Roku is closed, no Zoom channel, no sideload.
- Tapping Zoom's incoming encoded H.264 — SDK gives no raw-data entitlement; we re-encode rendered video.
- HLS/`/play` media path — higher latency; rejected in favor of type-110 mirroring.

---

## 7. Architecture

```
MeetingActivity "Cast" ─┐
                        ▼
             AirPlaySender (Kotlin)
  discovery (NsdManager _airplay._tcp)
  → pair-setup(PIN, once) / pair-verify(stored)   [HAP: SRP-6a, Ed25519, X25519, ChaCha20-Poly1305]
  → SETUP type:110 (omit ekey/eiv = unencrypted)
  → MediaProjection/off-screen ─ MediaCodec H.264 ─ 128B mirror header ─ TCP data channel ─▶ TV
  → NTP responder (UDP)  ;  AAC-ELD audio (later)
```

- **MediaRouter path (step 1) stays** as the Cast/Miracast branch; AirPlay is a second branch chosen by discovery.
- Creds persisted (DataStore/file) keyed by TV `deviceid`+`pk`; pair-verify skips the PIN forever after.

---

## 8. Databases and Schemas

No DB. AirPlay creds JSON per TV: `{our_id(uuid), ltsk(ed25519), acc_id, acc_ltpk}`. Prototype
writes `tools/airplay_proto/creds.json`; app will use DataStore.

---

## 9. Implementation Details (AirPlay-2 sender — from research, verified where noted)

**Pairing (HAP over `/pair-setup`,`/pair-verify`, raw TLV8, header `X-Apple-HKP`):**
- Must stay on **one persistent TCP socket** (else HTTP 470). *(verified)*
- SRP-6a: SHA-512, RFC-5054 **3072-bit** group, user `"Pair-Setup"`. *(prime verified vs srptools)*
- **Transient:** M1 Flags(0x13)=**single byte `0x10`** (receiver reads big-endian, must ==0x10;
  4-byte LE was our bug), PIN `3939`, done at M4. **This TV rejects it** (§5 step 4).
- **Persistent (chosen):** M1–M4 SRP with on-screen PIN → M5/M6 exchange Ed25519 LTPKs
  (ChaCha20-Poly1305, nonces `PS-Msg05/06` = 4 zero bytes + 8 ASCII). Store accessory LTPK.
- **pair-verify** (every connect): X25519 ECDH, verify accessory Ed25519 sig, nonces `PV-Msg02/03`;
  then control keys `Control-Salt`/`Control-Write|Read-Encryption-Key`.
- HKDF-SHA-512, L=32. Salts/infos: `Pair-Setup-Encrypt-*`, `*-Controller-Sign-*`, `Pair-Verify-Encrypt-*`.

**FairPlay:** all open source is receiver/decrypt-only; sender ekey needs Apple secret ⇒ **omit
`ekey`/`eiv` in SETUP → receiver skips decrypt → send plaintext H.264** (how AirParrot ships).
Replay the static 16B+164B `/fp-setup` request only if the TV gates on it.

**Mirror stream:** SETUP stage1 (session plist) → stage2 `streams:[{type:110,streamConnectionID}]`
→ RECORD → `/feedback` heartbeat. Video = **128-byte LE header** (`payloadSize`@0, `payloadType`@4:
0=H.264 VCL AVCC-length-prefixed, 1=SPS/PPS avcC plaintext, 2=heartbeat; `ntpTimestamp`@8) then
payload. Sender also **answers NTP** timing on UDP (legacy port 7010). Audio = AAC-ELD, separate.

**Source (step 10):** encode an off-screen render of the far-end `MobileRTCVideoView` at the TV's
`/info` widthPixels/heightPixels/maxFPS — cleaner + cheaper than whole-screen MediaProjection.

---

## 10. Data Snippets

TV mDNS: `features=0x7F8AD0,0x38BCF46` (bit7 AirPlayScreen, bit48 TransientPairing set),
`flags=0x244`, `pk=3fbe8d854ea3166e95162743e0fb93d2caa3f791b161f5daca7a1a04f6a02df5` (accessory Ed25519).

Repro pairing: `cd tools/airplay_proto && python3 airplay_hap.py trigger|setup <PIN>|verify`
(`TV=192.168.1.233`). Matrix tester: `matrix.py`. Backoff (err 03) clears in ~2–3 min.

---

## 11. Open Questions / Decisions Needed

- **Q1 (BLOCKER):** TV `Settings → Apple AirPlay and HomeKit → Require Code` value? No code
  appears on screen when we trigger pairing. Determines path:
  - *Off* → PIN-less should work; our transient failing = Roku quirk, test K=H(min S) variant next.
  - *Use password* → pair with that password (persistent), stored.
  - *Every time / First time* → should show a 4-digit code (maybe on a different HDMI input).
- **Q2:** enable Roku `Control by mobile apps = Permissive` so we can wake/recover the TV over ECP
  (currently 403; a pairing hang left the panel in `DisplayOff`, needing the physical Power button).
- **Q3:** srptools `verify_proof` of the server proof fails in loopback (its own quirk) — confirm
  our client M1 proof matches Apple's before porting (loopback shows client proof is standard-correct).

---

## 12. Test Plan / Acceptance Criteria

- **Pairing:** `airplay_hap.py setup <PIN>` prints "PAIRED"; `verify` prints "PAIR-VERIFY OK" with no PIN.
- **Mirror:** TV shows the far-end video; glass-to-glass ≤ ~1s; audio in sync.
- **Reconnect:** kill/rejoin cast uses stored creds (pair-verify), no code prompt.
- **Regression:** MediaRouter Cast button still lists Cast/Miracast TVs; app never hangs the TV.

---

## 13. References / Links

- openairplay spec `pairing/hkp.html`; airplay2-receiver `ap2/pairing/hap.py` (transient flag read big-endian, PIN 3939).
- Emanuele Cozzi "AirPlay 2 Internals"; RPiPlay/UxPlay `lib/` (mirror_buffer.c, raop_rtp_mirror.c, raop_ntp.c — receiver-side byte layouts).
- Sender refs: `ejurgensen/pair_ap` (C pair-setup/verify), `akustikrausch/airplay2-sender-cpp`.
- Local mined C: `…/scratchpad/{mirror_buffer.c,raop_rtp_mirror.c,raop_ntp.c}`.

---

## 14. File List

- `room/.../MeetingActivity.kt` — Cast button (right of Invite), picker dialog (step 1). *(edited)*
- `room/.../CastController.kt` — framework `MediaRouter` (Cast/Miracast) discovery/select (step 1).
- `room/src/main/res/drawable/ic_cast.xml` — cast icon.
- `tools/airplay_proto/airplay_hap.py` — persistent pair-setup + pair-verify prototype (step 5).
- `tools/airplay_proto/{pair.py,matrix.py}` — transient pairing + SRP-variant probes (step 4).
- *(future)* `room/.../airplay/*.kt` + JNI — Kotlin sender (steps 6–9).

---

## 15. Long Jobs / Backfill

Not applicable.

## 16. Rollback Plan

AirPlay is additive (new files + one branch off the Cast button). Rollback = revert those commits;
MediaRouter Cast button and the rest of the app are untouched.

## 17. Postmortems

- **Transient flag endianness (2026-07-08):** sent Flags as 4-byte LE `10 00 00 00`; receiver reads
  big-endian and needs ==`0x10`, so it saw `0x10000000`, ignored transient, expected a real PIN →
  3939 rejected. Fix: single byte `0x10`.
- **Pairing hang left TV black (2026-07-08):** repeated pair-setup attempts + backoff pushed the panel
  to `DisplayOff`; ECP wake blocked (403). Recover with the remote Power button. Mitigation: Q2.

## 18. Project History

- **2026-07-08** — Feature started. Built in-app MediaRouter "Cast" button; probed the room TV and
  found it AirPlay-2-only (TCL Roku); chose to build an AirPlay-2 mirroring sender (FairPlay avoided
  via unencrypted stream). Proved HAP pairing shape against the real TV; transient (PIN-less)
  rejected by this Roku; pivoted to persistent PIN pairing. **Stuck:** TV shows no on-screen code —
  need the `Require Code` setting (Q1) to proceed.
