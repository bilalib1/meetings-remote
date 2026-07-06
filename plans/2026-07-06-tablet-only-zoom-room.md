# Tablet-Only Zoom Room (Meeting SDK Appliance)

Small teams want a TV + external camera + tablet to *be* the whole Zoom room — download one
app, no PC, no server, no plug-ins, no root. Today this repo is a tablet **remote control**
for the official Zoom client on a Mac. This plan replaces that architecture: the tablet app
embeds the **Zoom Meeting SDK** and becomes the Zoom client itself, feeding video from an
external camera (RTSP over LAN, or wired USB/UVC dock) through the SDK's external video
source API, and driving the TV over HDMI.

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

**Current state:** tablet app (`app/`) + iPad app (`ios/`) are HTTP remote controls for a
Python server (`server/`) that UI-scripts the official `zoom.us` client on a Mac. Works, but
needs a PC in the room running the server — not "download an app and go".

**New vision:** the tablet is the whole room appliance.

- Joins real Zoom meetings itself (users' normal Zoom accounts / meeting IDs).
- Video comes from an external camera: **RTSP over LAN** or **wired USB (UVC)** dock
  (Logitech MeetUp class). Tablet's own camera is a fallback, not the point.
- Audio via the dock's speaker/mic (USB Audio Class — Android routes it natively) or
  tablet audio.
- Meeting renders on the TV (HDMI out from the tablet) with touch controls on the tablet.

**Why this is possible without root:** the "can't control what camera the tablet sends"
blocker only applies to the stock Zoom app. The Zoom **Meeting SDK** for Android supports an
external video source (`IZoomSDKVideoSource` / `setExternalVideoSource()`): we push I420
frames from any pipeline and Zoom treats it as the camera. No spoofing, no relay server, no
second streaming hop — one decode on-LAN (~100–300 ms on our own outgoing video only).

**Constraints:**
- Out-of-the-box: no root, no PC, no plug-ins, no per-site cloud infra (see §11 on SDK auth).
- Camera is RTSP or **wired** USB. Wireless-USB is dropped (decided 2026-07-06).
- Dev hardware: Samsung SM-P620 (Android 16), Mac for builds; Zoom Basic account.

**Done looks like:** from a cold tablet, open the app, pick the room camera (RTSP URL or
attached USB dock), join a meeting by ID; a remote participant sees the external camera's
video and hears the dock mic; the meeting shows on the TV; controls (mute/video/leave)
work from the tablet screen.

---

## 5. Execution Steps

Ordered so the riskiest unknowns (licensing, external video source) are proven first.

| #  | Task                                                                                   | Status      |
| -- | -------------------------------------------------------------------------------------- | ----------- |
| 1  | Spike: Marketplace Meeting SDK app; confirm raw-data/external-video-source availability on our account tier | not started |
| 2  | New app module `room/` joins a real meeting with SDK default UI (JWT hardcoded for dev) | not started |
| 3  | External video source proof: synthetic test-pattern frames → remote participant sees it | not started |
| 4  | RTSP ingest: RTSP client → MediaCodec H.264/H.265 decode → I420 → `sendVideoFrame()`    | not started |
| 5  | Measure glass-to-glass latency (clock-in-frame method, §12A); go/no-go vs 500 ms budget | not started |
| 6  | Custom in-meeting UI: controls per DESIGN.md client principles (mute/video/leave/participants) | not started |
| 7  | USB/UVC ingest path (libuvc-based lib) + UAC audio routing verification with a dock     | not started |
| 8  | External display: `Presentation` API renders gallery on HDMI, controls stay on tablet   | not started |
| 9  | Source picker + settings (RTSP URL, USB device, fallback to tablet camera)              | not started |
| 10 | SDK auth for distribution (resolve §11 Q2), sign-in flow                                | not started |
| 11 | E2E on-device verification per §12, screenshots, update DESIGN.md + POLICY.md           | not started |

---

## 6. Out of Scope / Non-Goals

- **Wireless USB** — not shippable on unrooted Android; wired dock or RTSP instead.
- **Rooting / camera spoofing / virtual-camera HALs** — the SDK external source makes them unnecessary.
- **The existing server + remote-control architecture** — kept in-tree and frozen, not extended; this plan does not touch `server/`, `app/`, `ios/`.
- **iPad port of the new app** — Android first; iOS has its own Meeting SDK, port later.
- **Cloud media relay** — double-hop streaming was rejected for latency; only media path is camera → tablet → Zoom.

---

## 7. Architecture

Everything runs in one Android app on the tablet.

```
 ┌───────────┐        ┌───────────────┐
 │ RTSP cam  │        │ USB dock      │
 │ (LAN)     │        │ (UVC + UAC)   │
 └─────┬─────┘        └───┬───────┬───┘
       │ RTP/H.264         │ MJPEG/ │ UAC audio
       ▼                   │ YUV    │ (Android native)
 ┌───────────────┐         ▼        ▼
 │ RTSP client + │   ┌──────────┐ ┌─────────┐
 │ MediaCodec    │   │ UVC lib  │ │ OS audio│
 └───────┬───────┘   └────┬─────┘ │ device  │
         │ NV12/I420      │ frames└────┬────┘
         ▼                ▼            │
      ┌────────────────────────┐       │
      │ Frame pipeline         │       │
      │ (convert→I420, pace fps)│      │
      └───────────┬────────────┘       │
                  │ sendVideoFrame()   │
                  ▼                    ▼
      ┌────────────────────────────────────┐
      │ Zoom Meeting SDK                   │
      │ (external video source, custom UI) │
      └─────┬─────────────────────┬────────┘
            │ meeting media       │ renderers
            ▼                     ▼
      ┌───────────┐    ┌────────────────────┐
      │ Zoom cloud│    │ TV via HDMI        │
      └───────────┘    │ (Presentation API) │
                       └────────────────────┘
      Tablet screen = touch controls (reuse
      state-driven UI principles from DESIGN.md)
```

Key interfaces:
- `VideoSourceProvider` — one interface, three impls (RTSP, UVC, device camera); emits I420 frames + resolution/fps.
- SDK's `IZoomSDKVideoSource` — our adapter holds the `IZoomSDKVideoSender` from `onInitialize()` and forwards pipeline frames in `onStartSend()`.
- Audio needs no pipeline: UAC dock is the system default audio device; the SDK uses it.

---

## 8. Databases and Schemas

No database. App settings in Jetpack DataStore (Preferences): `camera_source`
(`rtsp|usb|internal`), `rtsp_url`, `last_meeting_id`, `display_mode`. SDK credentials per §11 Q2.

---

## 9. Implementation Details

**SDK join (steps 1–2)**

1. Create a Meeting SDK app on the Zoom Marketplace → client ID/secret.
2. Sign an SDK JWT (HS256, `appKey`, `mid`/role, expiry ≤48h). Dev: generate on the Mac and hardcode. Ship: see §11 Q2.
3. `ZoomSDK.initialize()` with JWT → `MeetingService.joinMeetingWithParams(meetingId, passcode)`.

**External video source (step 3)**

1. Implement `IZoomSDKVideoSource`; in `onInitialize(sender, capsList, suggestedCap)` store the sender and pick the capability closest to the camera's native resolution.
2. In `onStartSend()`, start the frame pump; in `onStopSend()`/`onUninitialized()`, stop it.
3. Register via the SDK's video source helper `setExternalVideoSource(ourSource)` before/at join; unmute video.
4. Frames must be I420 (SDK ≥7.1.0 accepts I420Limited/I420Full), stride-aligned, at the negotiated resolution; pace to the negotiated fps (drop, never queue — stale frames are latency).

**RTSP ingest (step 4)**

1. RTSP DESCRIBE/SETUP/PLAY over TCP-interleaved (survives Wi-Fi better than UDP); pull H.264/H.265 RTP, reassemble NAL units. Use an existing client lib (e.g. `pedroSG94/RTSP-Client` class of libs) — do not hand-roll RTP.
2. Feed NALs to `MediaCodec` async decoder, output to `ByteBuffer` (flexible YUV), not a Surface — we need pixels.
3. Convert decoder output (usually NV12) → I420 with libyuv (one memcpy-class pass).
4. Hand frames to the pump. On stream error: auto-reconnect with backoff, show "camera offline" tile meanwhile.

**UVC ingest (step 7)**

1. `UsbManager` device filter for UVC class; standard Android permission dialog (no root).
2. libuvc-based library negotiates format: prefer uncompressed YUYV at 720p/1080p, else MJPEG → decode via `MediaCodec`.
3. Convert → I420, same pump. Audio: none of this — UAC mic/speaker enumerate as system audio automatically.

**External display (step 8)**

1. Listen via `DisplayManager` for an external display (USB-C DP-alt-mode → HDMI).
2. Show a `Presentation` hosting the SDK's video renderers (active speaker/gallery) on it; tablet activity keeps the controls.
3. No external display → single-screen layout on the tablet (still fully functional).

---

## 10. Data Snippets

SDK JWT payload (dev signing):

```json
{ "appKey": "<clientId>", "iat": 1751800000, "exp": 1751886400,
  "tokenExp": 1751886400 }
```

Frame handoff (the one hot path):

```kotlin
// pump thread, per frame
sender.sendVideoFrame(i420Buffer, width, height, /*frameLength*/ w*h*3/2, rotation)
```

RTSP source config (typical PoE/Wi-Fi conference cam):

```
rtsp://user:pass@192.168.1.60:554/h264/ch1/main/av_stream   (1080p30, H.264)
```

---

## 11. Open Questions / Decisions Needed

- **Q1 — Licensing:** does external-video-source / raw-data on Android Meeting SDK require a paid plan or raw-data license on our (Basic) account? Step 1 answers this empirically. If gated: fallback is Zoom **Video SDK** (custom sessions, not real Zoom meetings) — a product change needing user sign-off.
- **Q2 — SDK secret in an "out-of-the-box" app:** the SDK JWT is signed with the client secret, which must not ship in the APK. Smallest fix is a tiny token-signing endpoint (single cloud function) — the only cloud piece in the design, no media through it. Decide: accept that, or dev-only distribution with user-supplied credentials.
- **Q3 — HDMI out on dev tablet:** SM-P620 (Tab S6 Lite class) likely lacks DisplayPort alt-mode. Verify early; if absent, dev with single-screen mode and test HDMI on a Tab S-series/other host device.
- **Q4 — Marketplace review:** publishing a Meeting SDK app requires Zoom review; fine for later, but POLICY.md's "no SDK, no ToS surface" rationale is obsolete under this plan and needs rewriting (step 11).

---

## 12. Test Plan / Acceptance Criteria / Repro Steps

### A. E2E / Human Test Plan

```
1. Point an RTSP camera (or IP-cam app on a phone) at a wall clock with visible seconds.
2. Tablet: open app → source = RTSP URL → Join meeting <id> from the Mac's Zoom account.
3. Mac: join same meeting as a second participant.
   Expect: Mac sees the clock video within ~5 s of tablet join.
4. Latency: photograph the real clock and the Mac's rendering of it in one shot;
   difference = glass-to-glass latency. Expect ≤ 500 ms.
5. Tap Mute / Video / Leave on tablet; Mac participant list reflects each within 2 s.
```

```
USB path: plug UVC dock into tablet via USB-C hub → source = USB.
Expect: dock video visible to Mac; Mac's audio audible on dock speaker; dock mic heard on Mac.
```

### B. Acceptance Criteria

- Fresh install → in a meeting with external camera video, ≤5 user actions, no PC involved.
- Remote participant sees RTSP camera feed; measured latency ≤ 500 ms.
- UVC dock: video + both audio directions work with zero audio-specific code paths.
- Camera drop mid-meeting → "camera offline" indicator + auto-reconnect; app never crashes.
- No root, no secret in the APK (Q2 resolved), no server process on any room machine.

### C. Automated Tests

- Unit: NV12→I420 conversion — odd resolutions/stride padding produce correct plane offsets.
- Unit: frame pacer — 30fps input to 15fps negotiated cap drops frames, never queues >1.
- Integration: RTSP client against a local `mediamtx` test server — reconnect after forced stream kill.
- On-device (manual, scripted checklist): §12A — SDK join/leave can't be meaningfully mocked.

---

## 13. References / Links

- Meeting SDK Android docs: https://developers.zoom.us/docs/meeting-sdk/android/
- External video source how-to: https://www.recall.ai/blog/zoom-sdk-streaming-video-to-meeting and https://www.recall.ai/blog/how-to-send-raw-video-data-into-zoom
- SDK 7.1.0 changelog (I420Limited/Full for external source): https://devforum.zoom.us/t/changelog-meeting-sdk-android-7-1-0/144568
- Zoom devforum on external YUV/PCM sources: https://devforum.zoom.us/t/is-it-possible-to-use-external-video-yuv-audio-pcm-raw-data-instead-of-camera-as-the-source-to-input-zoom-sdk/45789
- Predecessor architecture: `DESIGN.md`, `POLICY.md` (this repo).

---

## 14. File List

Existing (context, mostly untouched):
- `DESIGN.md` — current remote-control architecture; gains a pointer to this plan (step 11).
- `POLICY.md` — ToS rationale premised on *not* using the SDK; rewrite in step 11.
- `app/`, `ios/`, `server/` — legacy remote-control system; frozen, not modified.

New (proposed layout):
- `room/` — new Android app module (Gradle): the appliance app.
- `room/src/main/java/.../sdk/` — SDK init, JWT, join, `IZoomSDKVideoSource` adapter.
- `room/src/main/java/.../source/` — `VideoSourceProvider` + RTSP / UVC / internal impls, frame pump, libyuv conversion.
- `room/src/main/java/.../ui/` — controls activity + external-display `Presentation`.
- `plans/2026-07-06-tablet-only-zoom-room.md` — this plan.

---

## 15. Long Jobs / Backfill

Not applicable.

---

## 16. Rollback Plan

New work is an isolated `room/` module; legacy system stays shipped and untouched. Rollback = stop using the module / revert its commits; no data or infra to unwind.

---

## 17. Postmortems

Not applicable (none yet).

---

## 18. Project History

- **2026-07-06** — Plan forked from `~/code/misc/plan-template.md`. Decisions: embed Zoom Meeting SDK with external video source instead of camera spoofing or a relay server; camera input limited to RTSP or wired USB (wireless-USB requirement dropped); legacy server/remote architecture frozen, not removed.
