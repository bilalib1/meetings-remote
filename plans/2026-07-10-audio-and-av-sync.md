# Meeting audio + AV sync for the Meetings Remote appliance

The appliance sends external-camera video (RTSP/USB, hundreds of ms of pipeline latency that
drifts with network) into a Zoom meeting, but had **no audio path of its own**. This adds the
tablet mic as the meeting mic through the Zoom SDK's virtual audio source, with a **controllable
delay** that holds the (instant) mic audio back to match the laggy video — and estimates that
delay at runtime, periodically, across cameras and protocols. Cascade: **GCC-PHAT** when the
camera has a mic, **ML lip-sync (SyncNet)** when it doesn't, **drift tracking** carrying the
value between estimates.

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

## 4. Context & Problem Statement

**Goal:** the tablet mic becomes the meeting mic, delayed to stay in sync with the external
camera's video, with the delay estimated automatically and re-estimated as conditions change.

**Setup:** external webcam (RTSP / USB / UVC-over-WiFi) is the camera; the tablet we run on is
the mic. Camera video reaches Zoom hundreds of ms late (encode + network + FFmpeg decode) and
that latency drifts; tablet mic audio is ~instant. Without correction, audio leads video badly.

**Perceptual budget (EBU R37):** audio within +40/−60 ms of video; err audio-late. Slew delay
changes (crossfade), never step >20–40 ms audibly.

**Research conclusions (3 agents, 2026-07-10):**
- Zoom Meeting SDK Android HAS a virtual mic: `ZoomSDKAudioRawDataHelper.setExternalAudioSource
  (IZoomSDKVirtualAudioMicEvent)` + `IZoomSDKAudioRawDataSender.send(ByteBuffer,len,sampleRate)`
  taking 16-bit mono LE PCM. No raw-data license needed to *send*. Present in bundled SDK 7.0.5.
- **GCC-PHAT beats ML** when the camera has a mic: correlate tablet-mic audio vs the camera's own
  embedded audio (mux-aligned with camera video). ~ms accuracy, ~150 ms CPU/estimate, no model,
  works during speech. This measures the whole camera pipeline latency directly.
- **ML lip-sync (SyncNet)** is the fallback for mic-less cameras. Validated exact offline. User
  chose ML over a flash self-test. **Upgrade in flight:** MTDVocaLiST (same size, +15.7% accuracy).

**Test hardware:** SM-P620 tablet @ `192.168.1.154`; Mac (dev + fake camera) @ `192.168.1.50`;
Zoom Basic account (40-min limit); mediamtx+ffmpeg on the Mac serve `rtsp://…:8554/test`.

**Done looks like:** in a meeting, the far end hears the tablet mic; the applied delay tracks the
camera pipeline latency (GCC-PHAT with a camera mic, SyncNet without), holds through drift, and
stays within the perceptual budget with no audible artifacts on updates.

---

## 5. Execution Steps

| # | Task | Status |
|---|------|--------|
| 1 | Virtual mic: AudioRecord (48k mono s16, AEC on / NS off) → delay ring (crossfade) → `IZoomSDKAudioRawDataSender` | **completed** — sends continuously; far end hears (zoomSend 32kHz, ~30ms RTT, 0% loss with a 2nd participant) |
| 2 | Native camera-audio decode: `rtsp_decoder.c` audio stream → 16k mono s16 FIFO + PTS; expose last video PTS | **completed** — AAC decode + swr verified on device (`audio stream 1: aac 48000 -> 16000 mono s16`) |
| 3 | GCC-PHAT `SyncEstimator`: two wall-clock rings, ±2.5 s sweep, PHAT whitening, confidence gates, median-of-3 | **completed** — LAN offset 155–182 ms applied; +600 ms relay re-converged to 889 ms |
| 4 | `DriftCompensator`: min-filtered (arrival−PTS) mapping tracks latency drift between absolute estimates; re-anchors on reconnect | **completed** — wired through single `RoomSdk.applyMicDelay` anchor path; compiles |
| 5 | Test hooks (`audioDelay`/`audioStats`/`syncNow`/`mlNow`) + Mac tools (`rtsp_mac_camera.sh` audio, `rtsp_delayed_relay.sh` real transport delay) | **completed** |
| 6 | Far-end audible verification (Mac zoom.us participant) | **completed** — 2nd participant joined, zoomSend bandwidth went live, tablet audio flowed |
| 7 | SyncNet → ONNX port + offline validation | **completed** — parity vs torch 1e-6; offset recovery +200ms→+5fr, −320ms→−8fr exact (fp32 + int8) |
| 8 | On-device ML lip-sync `MlSyncEstimator`: BlazeFace crop + Kotlin MFCC + 2 ONNX branches + ±15 sweep; idle while GCC-PHAT owns sync | **completed** — face tracks, crops fill, full sweep runs, coherent minimum, no crash; ~5s CPU/estimate (VSTEP=3) |
| 9 | MTDVocaLiST upgrade port (separable-vs-joint + cost) then swap in | **started (paused)** — port agent halted mid-run; artifacts (if any) under `scratchpad/mtd/` |
| 10 | Absolute on-device ML accuracy check (phase-locked audio+video) | not started — see §11 Q1 |
| 11 | Production hardening: quantize models, gate ML by CPU/thermal, persist per-camera delay | not started |

---

## 6. Out of Scope / Non-Goals

- **Receiving** meeting audio for transcription (only sending is in scope).
- Speaker separation / multi-source sync — not needed: GCC-PHAT correlates the whole mixture and
  is inherently robust to simultaneous talkers; ML pauses new estimates during sustained overlap
  and rides the drift tracker (§7).
- Flash/tone self-test — considered, **rejected by user** in favor of ML.
- Acoustic echo beyond the platform AEC on capture; Zoom's own NS on sent PCM is accepted.
- USB/UVC audio ingest via a UAC dock (that path needs no app pipeline — the dock is the system
  default device; tracked in the tablet-only plan §7, not here).

---

## 7. Architecture

```
tablet mic ─AudioRecord(48k mono s16, AEC on/NS off)─► delay ring ──► IZoomSDKAudioRawDataSender ─► Zoom
                 │ (undelayed tap, anchored wall-ns)        ▲
                 ├──────────────► SyncEstimator (GCC-PHAT @16k, 30s) ─┐  applyMicDelay(ms)
                 └──────────────► MlSyncEstimator (SyncNet, 60s) ─────┤  (anchors DriftCompensator)
RTSP cam ─rtsp_decoder.c─► video frames ─► Zoom external video source │
   │  ├─ audio stream → 16k mono s16 + PTS ─► SyncEstimator.onCameraAudio
   │  └─ min-filtered (arrival−PTS) map ────► DriftCompensator ────────┘ (adjusts between estimates)
   └─ analysisTap (I420) ─► MlSyncEstimator: BlazeFace → 224 BGR mouth crop
```

**Cascade / ownership:**
- Camera **has** an audio track AND GCC-PHAT locked-or-in-grace → GCC-PHAT owns sync; ML idles
  (`hasCamAudio = provider.hasAudio && syncEstimator.recentlyConfident()`).
- Camera **has no** audio track, or embedded mic is dead/silent (GCC-PHAT never locks) → ML runs.
- Between absolute estimates (either method) → `DriftCompensator` follows the (arrival−PTS)
  mapping and adjusts the applied delay; re-anchors on reconnect (PTS origin changes).

**Timeline mapping (the load-bearing trick):** camera audio and video share the RTSP mux
timeline, so audio with PTS *a* is placed on the wall clock at the moment the equal-PTS video
frame leaves for Zoom → the measured audio-vs-audio (or lip-vs-audio) lag *is* the video pipeline
latency the mic must match.

---

## 9. Implementation Details

- **Registration timing:** `setExternalAudioSource` right after SDK init returns
  `MobileRTCRawData_Uninitialized`; must (re-)register at audio-connect time in-meeting. `RoomSdk`
  does both; plus a mute/unmute kick after VoIP connect (devforum 96707).
- **Samsung NS zeroes non-speech** (micRms=0) → `NoiseSuppressor` explicitly disabled on our
  capture; AEC stays on. Zoom's own NS still applies to sent PCM.
- **Timeline stability for correlation:** min-filtered (arrival−PTS) map for camera audio (bursty
  frame arrivals, dt max >100 ms); anchored sample-counter clock for mic blocks (AudioRecord
  read-return jitter). Both smear GCC-PHAT / lip-sync otherwise.
- **GCC-PHAT gates:** 80 ms peak guard (reverb spreads the true peak ±30 ms); peakRatio ≥1.35 +
  floorRatio ≥8 instant accept (garbage windows measured 1.06–1.19); 3-consecutive-windows-
  within-250 ms consistency accept for jittery transports; skip stalled rings.
- **SyncNet preprocessing (docs/syncnet-preprocessing.md):** audio (B,1,13,20) MFCC (python_
  speech_features defaults, coeff0=log energy, raw int16); visual (B,3,5,224,224) BGR 0–255 full
  224 face crop; offset = argmin of mean-L2 curve, 40 ms/frame; sign: shift<0 ⇒ audio leads ⇒
  delay mic. Kotlin `Mfcc` unit-tested vs python to 0.02.
- **ML cost:** visual branch dominates (~130 ms/window on tablet CPU). VSTEP=3 keeps a 5 s
  segment to ~40 windows ≈ ~5 s/estimate at 60 s cadence. TAIL_FRAMES=40 leaves the forward
  +shift audio margin already in the ring (else the window reaches into the future).

---

## 11. Open Questions / Decisions Needed

- **Q1 (test rig):** on-device *absolute* ML accuracy is unproven because the improvised rig
  (independent afplay + RTSP loops) isn't phase-locked, so mic-audio and video content only
  intermittently correspond → low confidence, correctly not applied. Algorithm accuracy is proven
  offline. **Options:** (a) accept offline proof + on-device plumbing proof; (b) build a
  phase-locked rig (single ffplay playback screen-captured to RTSP so audio+video share one
  source). Leaning (a) unless a phase-locked number is wanted.
- **Q2 (MTDVocaLiST):** is it separable (cheap, like SyncNet) or a joint per-shift classifier
  (31× forward passes/window, likely too slow on tablet CPU)? Port agent was answering this when
  paused. Decides whether the upgrade is viable on-device or SyncNet stays.
- **Q3 (thermal/CPU):** ~5 s CPU/60 s for ML is fine functionally; gate by thermal state before
  shipping so a hot tablet doesn't drop video frames.

---

## 12. Test Plan / Acceptance Criteria

- **Unit:** `SyncEstimatorTest` (recovers 0/473/2100 ms ±5 ms; silence rejected); `MfccTest`
  (parity vs python_speech_features to 0.02). Both green.
- **GCC-PHAT e2e:** Mac streams camera+mic (`rtsp_mac_camera.sh 0 0`); tablet joins; speak varied
  non-repeating words → estimator applies within camera latency; inject `rtsp_delayed_relay.sh
  0.6` → re-converges. ✅ done (155–182 ms direct; 889 ms with +600 ms relay).
- **Far end:** 2nd participant (Mac zoom.us) hears tablet audio; `audioStats` zoomSend bandwidth
  nonzero. ✅ done.
- **ML e2e:** stream a talking head video-only (no audio track) padded so the face is a realistic
  fraction; BlazeFace tracks, crops fill, `mlNow` runs a full sweep → coherent minimum, no crash.
  ✅ done. Absolute accuracy → Q1.
- **Acceptance:** far end hears mic; applied delay within perceptual budget of measured latency;
  no audible artifact on delay change; ML idles when camera has a mic.

---

## 13. References / Links

- Zoom virtual mic javadocs: `IZoomSDKAudioRawDataHelper`, `IZoomSDKVirtualAudioMicEvent`,
  `IZoomSDKAudioRawDataSender` (marketplacefront.zoom.us/sdk/meeting/android).
- devforum: 100571 (send format), 96707 (mute/unmute kick), 110080 (A/V sync is hard).
- SyncNet: github.com/joonson/syncnet_python (weights: Oxford VGG). MTDVocaLiST:
  github.com/xjchenGit/MTDVocaLiST. BlazeFace: MediaPipe face_detector short-range.
- Memory: [[audio-av-sync-pipeline]], [[pmi-stranding-and-test-hooks]], [[local-dev-setup]],
  [[two-party-test-automation]].

---

## 14. File List

- `room/src/main/java/com/bilal/meetingsremote/audio/MicAudioSource.kt` — virtual mic + delay ring
- `room/src/main/java/com/bilal/meetingsremote/audio/SyncEstimator.kt` — GCC-PHAT estimator
- `room/src/main/java/com/bilal/meetingsremote/audio/DriftCompensator.kt` — latency drift tracker
- `room/src/main/java/com/bilal/meetingsremote/audio/mlsync/MlSyncEstimator.kt` — SyncNet lip-sync
- `room/src/main/java/com/bilal/meetingsremote/audio/mlsync/Mfcc.kt` — python-parity MFCC
- `room/src/main/cpp/rtsp_decoder.c` — video + camera-audio decode, PTS taps
- `room/src/main/java/com/bilal/meetingsremote/source/FfmpegVideoSource.kt` — audio tap + PTS map
- `room/src/main/java/com/bilal/meetingsremote/sdk/RoomSdk.kt` — wiring, `applyMicDelay` anchor
- `room/src/main/java/com/bilal/meetingsremote/sdk/ExternalVideoSource.kt` — analysis tap
- `room/src/main/java/com/bilal/meetingsremote/TestHooksReceiver.kt` — audio/sync/ml hooks
- `room/src/main/assets/` — `syncnet_audio.onnx`, `syncnet_visual.onnx`, `blaze_face_short_range.tflite`
- `docs/syncnet-preprocessing.md` — exact SyncNet I/O spec
- `tools/rtsp_mac_camera.sh` (audio flag), `tools/rtsp_delayed_relay.sh`, `tools/delay_pipe.py`
- Tests: `room/src/test/.../SyncEstimatorTest.kt`, `.../mlsync/MfccTest.kt`
- Commits: de4b16d, d9b07fc (GCC-PHAT + fixes), 4a326c0 (drift + ML scaffold), 58adf6b (ML estimator)

---

## 17. Postmortems

- **Registration Uninitialized (2026-07-10):** registering the virtual mic at SDK-init time
  silently fails; only sticks once a meeting connection exists. Register at connect too.
- **Samsung NS ate the signal (2026-07-10):** device NoiseSuppressor gates non-speech to digital
  zero → correlation tap saw micRms=0. Disable NS on our capture.
- **`say` repeats fool correlation (2026-07-10):** identical TTS waveforms per word create phantom
  peaks at repetition lags (stable −730 ms ghost). Test with unique dictionary words / human speech.
- **setpts ≠ network delay (2026-07-10):** shifts timestamps not arrival, and A/V equally, so the
  sync estimator (correctly) sees no change. Use a store-and-forward relay for real latency.
- **Full-frame face undetected (2026-07-10):** BlazeFace short-range won't lock a face filling
  100% of frame; pad the test source so the face is a realistic fraction (real room cams already are).
- **PMI restrand on guest-leave (2026-07-10):** the Mac guest leaving collapsed the meeting and
  restranded the host PMI (error 100/80); backend force-end + retries recovered after ~minutes.
  Known issue ([[pmi-stranding-and-test-hooks]]).

---

## 18. Project History

- **2026-07-10** — Feature built end-to-end. Researched Zoom virtual-mic API + AV-sync methods
  (3 agents). Implemented virtual mic + delay ring, native camera-audio decode, GCC-PHAT
  estimator, drift tracker, test hooks + Mac tooling. Verified on SM-P620: mic sends, far end
  hears, GCC-PHAT applies 155–182 ms on LAN and re-converges to 889 ms with a 600 ms relay.
  Ported SyncNet to ONNX (exact offline offset recovery) and built the on-device `MlSyncEstimator`
  fallback (BlazeFace + Kotlin MFCC + ONNX sweep) — pipeline runs on device, coherent minimum, no
  crash; absolute on-device accuracy pending a phase-locked rig (§11 Q1). Started MTDVocaLiST
  upgrade port (separable-vs-joint + cost verdict) — **paused** mid-run at user request.
