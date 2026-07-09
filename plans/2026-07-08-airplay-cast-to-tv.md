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
| 3 | Research AirPlay-2 mirroring *sender* protocol + FairPlay feasibility | **completed — but conclusion WRONG for Roku.** Claimed FairPlay avoidable (omit ekey/eiv → unencrypted, AirParrot-style). True for Apple TV / UxPlay; **false for this Roku**, which encrypts all media (proven by capture, step 7 / §11 Q4). Pairing was *a* gate; FairPlay is the real one. |
| 4 | Python prototype: transient (PIN-less) pairing | **completed (negative)** — handshake shape proven (flag `0x10` 1-byte reaches M4) but TV **rejects PIN 3939** (auth err 02) then rate-limits (backoff err 03) ⇒ Roku won't do PIN-less transient |
| 5 | Python prototype: persistent pair-setup (PIN, M1–M6) + pair-verify | **completed** — TV paired (via `pyatv_pair.py` ground truth, code 9985); our **pair-verify passes against the real TV** with those creds (`creds.json`); our pair-setup SRP fixed + proven byte-identical to pyatv (M1/M5-M6 not yet re-run vs TV — optional, pairing already stored) |
| 6 | Port pairing to Kotlin/JNI in the app | not started |
| 7 | Mirror stream: type-110 H.264 → TCP | **BLOCKED — DEAD END (FairPlay)**. Full handshake works vs TV (pair-verify → encrypted RTSP → SETUP×2 → RECORD → type-110 data + feedback, all 200 OK, clean teardown), but **TV stays BLACK**. Tried (a) plaintext H.264 and (b) AES-CTR keyed by RPiPlay derivation — both black. **Live macOS→Roku capture (§11 Q4 UPDATE) proves the receiver encrypts even audio ⇒ type-110 needs a real FairPlay `ekey`**, which needs Apple's non-public secret. The "omit ekey → plaintext" premise (step 3) is **false on Roku**. From-scratch mirror sender to this TV = not feasible. `airplay_mirror.py` kept as the working handshake harness (drives UxPlay-class receivers). |
| 8 | NTP timing responder (UDP) + AAC-ELD audio | **N/A** — capture shows no PTP/NTP timing channel; blocked by step 7 anyway |
| 9 | Wire Cast button → AirPlay sender; store creds; pair-verify on reconnect | blocked by step 7 |
| 10 | Optimize source: off-screen render of far-end video at TV native res/fps | blocked by step 7 |
| — | **DECISION: pick a pivot** (§6 alternatives / §11 Q4-alt) | **not started — awaiting user** (HDMI-stick+UxPlay / AirPlay-HLS / attempt FairPlay / stop) |

---

## 6. Out of Scope / Non-Goals

- Google Cast SDK / Chromecast receiver app; Miracast/Smart View (no peers here, and system-dep).
- ~~AirPlay mirroring encryption avoidance~~ — **this was the fatal wrong assumption.** Roku
  mandates FairPlay-encrypted media; "send unencrypted" does not work (step 7 / §11 Q4).
- TV-joins-Zoom-directly — Roku is closed, no Zoom channel, no sideload.
- Tapping Zoom's incoming encoded H.264 — SDK gives no raw-data entitlement; we re-encode rendered video.
- ~~HLS/`/play` rejected for latency~~ — **back on the table** as a pivot now that mirroring is a
  dead end for this TV (§11 Q4-alt).

**Pivot options (choose one — see §11 Q4-alt for detail):**
- **A. HDMI stick + UxPlay** — cheap dongle runs a receiver our *existing* `airplay_mirror.py`
  sender already drives (plaintext path); low latency; violates "no dongle".
- **B. AirPlay video (HLS `/play`)** — in-app, no dongle, works on this Roku, no FairPlay for
  non-DRM; but ~2–10s latency (poor for a live call).
- **C. Attempt a FairPlay sender** — reverse-engineer `/fp-setup` SAP to encrypt a real ekey; very
  high effort, may be impossible (Apple secret).
- **D. Stop / rethink target** — Miracast-only TVs, or a different appliance.

---

## 7. Architecture

```
Intended (BLOCKED at the ✗ — Roku mandates FairPlay on the media stream):
MeetingActivity "Cast" ─┐
                        ▼
             AirPlaySender (Kotlin)
  discovery (NsdManager _airplay._tcp)                              ✓ works
  → pair-setup(PIN, once) / pair-verify(stored)   [HAP/SRP-6a…]     ✓ works vs real TV
  → SETUP type:110 + RECORD + /feedback                            ✓ 200 OK vs real TV
  → H.264 ─ 128B mirror header ─ TCP data channel ─▶ TV        ✗ BLACK: needs FairPlay ekey
```
The whole chain down to the data channel is proven against the TV; only the FairPlay-encrypted
media key is missing, and it can't be generated without Apple's secret. Pivot A–D in §6.

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

Repro pairing: `cd tools/airplay_proto && python3 airplay_hap.py trigger|setup|verify`
(`TV=192.168.1.233`; `setup` waits for the on-TV code via `echo NNNN > pin.txt` — code regenerates
per `/pair-pin-start`, so it can't be a CLI arg). Ground truth: `pyatv_pair.py` (pyatv venv).
Matrix tester: `matrix.py`. Backoff (err 03) clears in ~2–3 min.
Paired creds (DO NOT COMMIT): `creds.json` = `{our_id, ltsk, acc_id="5D:19:23:22:04:83", acc_ltpk}`;
same identity is portable to the tablet app — pairing is keys-only, not device-bound.

---

## 11. Open Questions / Decisions Needed

- ~~Q1~~ **ANSWERED (2026-07-08):** `Require Code = First time only`. Code wasn't showing because
  we never sent **`POST /pair-pin-start`** before pair-setup M1 — that request (empty body, no
  X-Apple-HKP) is what tells the receiver to display the code (pyatv does the same). Fixed in
  `airplay_hap.py` (`Conn.pin_start()`, called by `trigger` and `setup`).
- **Q4 (BLOCKER, pixels):** does this Roku render a type-110 mirror stream with `ekey` omitted?
  Evidence says NO — both plaintext and RPiPlay-derived AES-CTR give a black screen while the whole
  handshake returns 200. `/info` advertises FairPlay `fairplay-4.9.17`. Decisive next test:
  **tcpdump a real macOS → this-Roku Screen-Mirroring session** to confirm whether it sends a
  FairPlay `/fp-setup` + `ekey`, and capture the timing/data channel shape. If FairPlay is
  mandatory, a from-scratch sender needs Apple's FairPlay SAP secret (not open source) ⇒ mirroring
  path is likely infeasible; pivot options in §6/Q4-alt.
- **Q4 UPDATE (2026-07-08, capture done):** captured a live **macOS → this-Roku** mirror
  (`scratchpad/airplay_mac.pcap`, filter `ether host d4:ab:cd:25:99:b4`; note the session runs over
  **IPv6** `fd00:f452:461d:…`, not IPv4 — first captures were empty because we filtered the v4 addr).
  Findings: (a) **no PTP** (nothing on 319/320) and no separate timing channel → our black screen is
  **not** a timing gap; (b) a constant UDP RTP flow (PT 96, ts += 480 @ 48kHz = audio) with
  **non-zero/encrypted** payloads → the receiver encrypts even audio; (c) video on TCP data port.
  ⇒ **media encryption is real**; type-110 almost certainly needs a genuine FairPlay `ekey`, so the
  "omit ekey → plaintext" premise is dead on Roku. From-scratch mirror sender = **not feasible**
  without Apple's FairPlay secret. Recommend pivot.
- **Q4-alt (if FairPlay mandatory):** (1) AirPlay **video** (`/play` HLS) instead of mirroring —
  works without FairPlay for non-DRM content but adds seconds of latency (bad for a live call);
  (2) ship a tiny **UxPlay/RPiPlay receiver on a cheap HDMI stick** and mirror to that (defeats
  "no dongle"); (3) accept Cast/Miracast TVs only (this Roku unsupported).
- **Q2:** enable Roku `Control by mobile apps = Permissive` so we can wake/recover the TV over ECP
  (currently 403; a pairing hang left the panel in `DisplayOff`, needing the physical Power button).
- ~~Q3~~ **ANSWERED (2026-07-08):** our M1 proof was wrong — srptools returns `key_proof`/`key` as
  *hex-encoded bytes* and our `tob()` passed them through raw (128 ASCII chars instead of 64 raw
  bytes). Fixed (`unhex()`); A/M1/K now byte-identical to pyatv on same inputs.

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
- `tools/airplay_proto/pyatv_pair.py` — pyatv ground-truth pairing (needs pyatv venv).
- `tools/airplay_proto/airplay_mirror.py` — full mirror sender prototype (pair-verify → RTSP SETUP×2
  → RECORD → type-110). Works to handshake; blocked on FairPlay for pixels. Drives UxPlay-class recv.
- `tools/airplay_proto/analyze_pcap.py` — summarize a Mac↔TV capture (ports, UDP/PTP, TCP streams).
- `tools/airplay_proto/creds.json`, `creds_pyatv.txt`, `pin.txt`, `test.h264` — secrets/scratch, gitignored.
- `scratchpad/airplay_mac.pcap` (session scratch) — the decisive macOS→Roku capture; `enable_nopasswd_tcpdump.sh`,
  `capture_airplay.sh` — capture helpers (filter by Roku MAC `d4:ab:cd:25:99:b4`, IPv6 session).
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
- **No on-screen code (2026-07-08):** pair-setup M1 alone never makes the TV show its code — the
  sender must first POST `/pair-pin-start`. We skipped it, so the TV ran SRP against a code we
  couldn't see and every PIN guess failed. Fix: `pin_start()` before M1; `setup` waits for the
  code via `pin.txt` after M2 (each `/pair-pin-start` regenerates the code, so it can't be a CLI arg).
- **Hex-as-bytes SRP proof (2026-07-08):** even with the right code, M4 gave err 02 — srptools
  returns `key_proof`/`key` as hex-encoded *bytes*, and `tob()`'s `isinstance(v, bytes)` short-circuit
  sent 128 ASCII hex chars as the proof. Found by diffing byte-for-byte against pyatv (same seed,
  salt, B): A matched (str property), M1/K didn't. Lesson: validate against a known-good sender
  with fixed inputs before blaming the receiver.
- **Pairing hang left TV black (2026-07-08):** repeated pair-setup attempts + backoff pushed the panel
  to `DisplayOff`; ECP wake blocked (403). Recover with the remote Power button. Mitigation: Q2.

## 18. Project History

- **2026-07-08** — Feature started. Built in-app MediaRouter "Cast" button; probed the room TV and
  found it AirPlay-2-only (TCL Roku); chose to build an AirPlay-2 mirroring sender (FairPlay avoided
  via unencrypted stream). Proved HAP pairing shape against the real TV; transient (PIN-less)
  rejected by this Roku; pivoted to persistent PIN pairing. **Stuck:** TV shows no on-screen code —
  need the `Require Code` setting (Q1) to proceed.
- **2026-07-08 (latest)** — **Mirror path proven a DEAD END for this Roku.** Built the full sender
  prototype (`airplay_mirror.py`): pair-verify → encrypted RTSP → SETUP×2 → RECORD → type-110 data,
  all 200 OK vs the real TV, but the TV stayed BLACK with both plaintext and RPiPlay-derived AES-CTR
  video. Captured a live macOS→Roku mirror for ground truth (had to filter by the Roku's **MAC** —
  the session runs over **IPv6**, so IPv4 filters caught nothing). Capture showed **no PTP/timing
  channel** (kills the timing theory) and an **encrypted** RTP audio flow — proving the receiver
  encrypts all media, so type-110 needs a genuine **FairPlay ekey** (Apple secret, not public).
  Conclusion: a from-scratch AirPlay *mirror* sender cannot drive this Roku. Pairing + handshake code
  is kept and works against UxPlay-class receivers. **Next: user picks a pivot (A–D, §6/§11).**
- **2026-07-08 (later)** — **Pairing solved.** Q1 answered (`First time only`); missing piece was
  `POST /pair-pin-start` (makes the code appear). Paired with the TV via pyatv (ground truth);
  fixed our SRP hex-as-bytes proof bug and proved our client byte-identical to pyatv; **our
  pair-verify passes against the real TV with stored creds — no PIN.** Steps 4–5 done; next is the
  Kotlin port (step 6) and the mirror stream (step 7).
