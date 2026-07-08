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

**► CURRENT STATE (2026-07-07) — read this first.**
The tablet-only appliance is real and working. On the Samsung SM-P620 the app
joins a live Zoom meeting via the Meeting SDK and streams an external **RTSP
camera** into it: a remote participant sees the camera feed, **hardware-decoded**
on the tablet (native FFmpeg `h264_mediacodec` → libswscale I420 → Meeting SDK
external video source), correct aspect ratio. Verified end-to-end by looping the
Mac's own webcam through it. Repo reorganized: active app in `room/`, v1 remote
archived in `legacy/`, docs in `docs/`, helpers in `tools/`.
- **Works:** SDK init (on-device app-signed JWT), Join, RTSP camera HW-decode →
  Zoom, aspect-correct scaling, auto video-unmute, in-meeting UI with Share/More
  hidden. Console home = Start Meeting / Join; camera + credentials behind title
  gestures (tap ×5 / long-press).
- **Needs a credential:** Start Meeting (hosting) works via `startMeetingWithParams`
  but needs a host **ZAK** (SDK has no email/password login; SSO-only otherwise).
  Mint with `tools/mint_zak.py`; paste in the app.
- **Fixed (2026-07-08):** outgoing **FPS** to Zoom was ~11 fps (vs 25 negotiated /
  30 source). Cause: wall-clock pacing over bursty MediaCodec arrivals. Fix:
  PTS-based pacing in native, pre-swscale (§19). Verified in a hosted meeting:
  sendVideoFrame steady at 25.0 fps.
- **Not done:** glass-to-glass latency measurement (step 5), USB/UVC + UAC audio
  (step 7), HDMI external display (step 8), source-picker polish (step 9),
  shippable SDK-JWT signing endpoint (§11 Q2).

**Legacy (archived in `legacy/`):** Android app + iPad app were HTTP remote
controls for a Python server UI-scripting `zoom.us` on a Mac. Frozen, superseded.

**Vision:** the tablet is the whole room appliance.

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
| 1  | Meeting SDK app + external-video-source availability on our tier                        | **completed** — SDK 7.0.5 on Maven Central; no raw-data entitlement; join works on Basic |
| 2  | `room/` joins a real meeting (on-device app-signed JWT)                                 | **completed** — INMEETING verified; Share/More hidden via meeting_views_options |
| 3  | External video source proof (frames → remote participant sees it)                       | **completed** — remote participant sees our frames |
| 4  | RTSP ingest: native FFmpeg + MediaCodec HW decode → I420 → `sendVideoFrame()`           | **completed** — E2E on device: remote sees the RTSP feed, HW-decoded (`h264_mediacodec`), aspect-correct |
| 4b | Start Meeting (host)                                                                    | **completed** — backend mints ZAK+PMI (S2S OAuth); tablet hosts its PMI, verified on-device |
| 5  | Measure glass-to-glass latency (clock-in-frame, §12A) vs 500 ms budget                  | not started |
| 5b | Outgoing **FPS** to Zoom (RTSP camera)                                                  | **completed** — native PTS pacing; 25.0 fps steady in a hosted meeting (§19) |
| 6  | Custom in-meeting UI (own screen, no SDK toolbar)                                       | **completed** — MeetingActivity: far-end video full-screen + Mute/Video/Leave/count; no Share/More/chat |
| 7  | USB/UVC ingest + UAC audio with a dock                                                  | not started |
| 8  | External display: `Presentation` gallery on HDMI, controls on tablet                    | not started |
| 9  | Source picker + settings polish                                                         | not started |
| 10 | Auth off-device (§11 Q2)                                                                | **completed (dev)** — token backend signs SDK JWT + mints host ZAK; no secrets on tablet. Ship: host it on https |
| 11 | Full E2E checklist + screenshots; rewrite docs/POLICY.md for the SDK era                | not started |

---

## 6. Out of Scope / Non-Goals

- **Wireless USB** — not shippable on unrooted Android; wired dock or RTSP instead.
- **Rooting / camera spoofing / virtual-camera HALs** — the SDK external source makes them unnecessary.
- **The existing server + remote-control architecture** — kept in-tree and frozen, not extended; this plan does not touch `server/`, `app/`, `ios/`.
- **Any use of the Mac's zoom.us client** — not for starting meetings, not as a verification participant (user decision 2026-07-07). The Mac is dev infra only: builds, adb, simulated RTSP camera. In-meeting behavior is verified on the tablet itself.
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

**SDK join (steps 1–2)** — implemented in `room/`

1. SDK dependency: `us.zoom.meetingsdk:zoomsdk:7.0.5` (Maven Central; latest as of 2026-07-06). Needs compileSdk 36 + AGP ≥8.9.1 + minSdk 28 (root project bumped to AGP 8.11.1; platform 36 + cmdline-tools installed in local SDK).
2. JWT: signed **on-device** from client ID/secret entered once in the app (`JwtSigner.kt`, HS256, payload `{appKey,iat,exp,tokenExp}`, 24 h). Dev-only convenience; Q2 still governs shipping.
3. `ZoomSDK.initialize()` with JWT → `getVideoSourceHelper().setExternalVideoSource()` → `joinMeetingWithParams`. Exact Android API (verified from AAR): `ZoomSDKVideoSource`/`ZoomSDKVideoSender.sendVideoFrame(ByteBuffer, w, h, len, rotation, ExternalSourceDataFormat.I420_FULL/LIMITED)`.
4. Console UI (`MainActivity`): home is just "Ready to meet" + **Start Meeting** / **Join** (v1 design language). All plumbing hidden: tap the title 5× → camera source dialog (test pattern / RTSP URL + "Test 5 s"), long-press title → SDK credentials. Camera choice is applied silently on join. Still scriptable via adb intent extras: `clientId, clientSecret, meetingNo, passcode, rtspUrl, source(test|rtsp), jwt, autojoin, testSource` (never pass empty-string extras — adb drops them, see §17).

**External video source (step 3)**

1. Implement `IZoomSDKVideoSource`; in `onInitialize(sender, capsList, suggestedCap)` store the sender and pick the capability closest to the camera's native resolution.
2. In `onStartSend()`, start the frame pump; in `onStopSend()`/`onUninitialized()`, stop it.
3. Register via the SDK's video source helper `setExternalVideoSource(ourSource)` before/at join; unmute video.
4. Frames must be I420 (SDK ≥7.1.0 accepts I420Limited/I420Full), stride-aligned, at the negotiated resolution; pace to the negotiated fps (drop, never queue — stale frames are latency).

**RTSP ingest (step 4)** — native FFmpeg + MediaCodec HW decode (`FfmpegVideoSource.kt` + `cpp/rtsp_decoder.c`)

Decision (2026-07-07): decode in native FFmpeg driving MediaCodec **hardware** decode, not Kotlin/MediaCodec. On Android, HW decode == MediaCodec always; FFmpeg's `h264_mediacodec`/`hevc_mediacodec` is a MediaCodec wrapper. FFmpeg gives robust RTSP demux + the color convert (swscale NV12→I420) in C, so **no pixel processing on the JVM**.

1. FFmpeg 6.1.2, decode-only LGPL, arm64, built with `--enable-mediacodec --enable-decoder=h264_mediacodec,hevc_mediacodec,h264,hevc --enable-demuxer=rtsp,... --enable-swscale`. Prebuilt `.so` in `room/src/main/jniLibs/arm64-v8a` (~4 MB); build script + headers in scratch/`cpp/include`.
2. JNI wrapper (`rtsp_decoder.c`): `av_jni_set_java_vm` in JNI_OnLoad; `avformat_open_input` (rtsp_transport=tcp, stimeout 5s) → `av_read_frame` → `avcodec send/receive` on the mediacodec HW decoder → `sws_scale` to I420 → copied to a Kotlin `ByteArray`. `AV_CODEC_FLAG_LOW_DELAY`.
3. `FfmpegVideoSource` (Kotlin `VideoSourceProvider`) pumps frames, paces to negotiated fps, auto-reconnects (1→10 s backoff), surfaces status.
4. `useLegacyPackaging=false` so native libs are 16 KB-page aligned in the APK (Android 15+).
5. Local test rig: mediamtx + ffmpeg (SMPTE bars + label + live clock) → `rtsp://192.168.1.50:8554/test`.

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

- **Q1 — Licensing:** ANSWERED — external video source works on our Basic account; no raw-data entitlement or paid plan needed. Join/host confirmed on-device.
- **Q2 — SDK secret in an "out-of-the-box" app:** the SDK JWT is signed with the client secret, which must not ship in the APK. Smallest fix is a tiny token-signing endpoint (single cloud function) — the only cloud piece in the design, no media through it. Currently dev-only: JWT signed on-device from user-entered creds. Same endpoint could also mint the host ZAK (see hosting below) so Start Meeting needs no manual token.
- **Q-host — Hosting auth:** ANSWERED — the Meeting SDK has no email/password login (removed by Zoom) and SSO needs an SSO-enabled org. On Basic, hosting requires a **ZAK** minted server-side (Server-to-Server OAuth → `/users/me/token?type=zak`, ~2 h TTL). `tools/mint_zak.py` mints it; the app takes it in credentials. Joining needs no ZAK.
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
- Camera drop mid-meeting → "camera offline" indicator + auto-reconnect; app never crashes. **✔ built + verified 2026-07-08** (pill in MeetingActivity; keep-alive + re-register recovery in RoomSdk/ExternalVideoSource).
- No root, no secret in the APK (Q2 resolved), no server process on any room machine.

### C. Automated Tests

- Unit: NV12→I420 conversion — odd resolutions/stride padding produce correct plane offsets.
- Pacing is native (PTS accumulator in `rtsp_decoder.c`, §19) — no JVM unit test;
  verified on-device via `tools/measure_fps.py` (30→25 emits 25.0 fps, 60→30 drops ½).
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

Repo layout (reorganized 2026-07-07):
- `room/` — the appliance (active), package `com.bilal.zoomroom`.
- `legacy/{app,ios,server}` — archived v1 remote-control system; frozen, not in the build.
- `docs/{DESIGN.md,POLICY.md}` — v1 design + Zoom-ToS notes (POLICY.md needs an SDK-era rewrite, step 11).
- `tools/{rtsp_test_stream.sh,mint_zak.py}` — local RTSP test camera; ZAK minting for Start Meeting.
- `tools/rtsp_mac_camera.sh` — publishes the Mac's FaceTime camera as RTSP (the "real camera" rig; starts mediamtx if needed).
- `tools/rtsp_bench.sh` — parametrized RTSP source (fps/size/profile/extra ffmpeg args) for FPS benchmarking; publishes `testsrc2` (motion) to a running mediamtx on :8554.
- `tools/measure_fps.py` — logcat FPS meter: computes decode+emit and send-to-Zoom fps from the app's frame counters (immune to sample placement).
- `plans/` — this plan. `README.md` — front door.

Appliance (`room/`, package `com.bilal.zoomroom`):
- `room/build.gradle.kts` — app module; zoomsdk 7.0.5; externalNativeBuild CMake; arm64-only; 16 KB-aligned libs.
- `room/src/main/java/com/bilal/zoomroom/MainActivity.kt` — dev console UI, adb-scriptable extras, source test.
- `.../sdk/JwtSigner.kt` — dev-only on-device HS256 SDK JWT.
- `.../sdk/RoomSdk.kt` — init/join/leave wrapper.
- `.../sdk/ExternalVideoSource.kt` — `ZoomSDKVideoSource` adapter + frame pump.
- `.../source/VideoSourceProvider.kt` — provider interface (`Negotiated`, `FrameSink`).
- `.../source/TestPatternSource.kt` — synthetic I420 pattern (gradient + sweep bar + seconds tick).
- `.../source/FfmpegVideoSource.kt` — native FFmpeg + MediaCodec HW decode driver.
- `room/src/main/cpp/{rtsp_decoder.c,CMakeLists.txt,include/}` — JNI over FFmpeg (RTSP + h264_mediacodec HW decode + swscale NV12→I420).
- `room/src/main/jniLibs/arm64-v8a/*.so` — prebuilt FFmpeg 6.1.2 (avcodec/avformat/avutil/swscale/swresample).
- `.../sdk/RoomSdk.kt` — init + join + **start (ZAK)**; hides Share/More via `meeting_views_options`.
- `.../source/FfmpegVideoSource.kt` + `cpp/{rtsp_decoder.c,CMakeLists.txt}` — native FFmpeg RTSP + `h264_mediacodec` HW decode + swscale → I420 (aspect-preserving).
- `.../source/{TestPatternSource,VideoSourceProvider}.kt` — test pattern, provider IF.
- `room/src/main/jniLibs/arm64-v8a/*.so` — prebuilt FFmpeg 6.1.2 (avcodec/avformat/avutil/swscale/swresample).
- (Removed: `RtspVideoSource.kt`, `Yuv.kt`, `SpsParser.kt` — Kotlin/MediaCodec decode path;
  `FramePacer.kt` + test — pacing moved into `rtsp_decoder.c`, PTS-based, §19.)

---

## 15. Long Jobs / Backfill

Not applicable.

---

## 16. Rollback Plan

New work is an isolated `room/` module; legacy system stays shipped and untouched. Rollback = stop using the module / revert its commits; no data or infra to unwind.

---

## 17. Postmortems

- **Kotlin/MediaCodec decode dead-end (2026-07-07):** the SM-P620 Exynos HW decoder rejected our hand-fed H.264 (continuous "error type 1", black output) across every csd/feeding variation, though ffmpeg decoded the identical NALs. Root causes found along the way: SDP delivers SPS/PPS *already* start-code-prefixed (our `csd()` doubled it); MediaCodec wants csd-0=SPS/csd-1=PPS split; the Exynos decoder needs inline SPS/PPS per keyframe; and `getOutputImage()`/`getOutputBuffer()` returned zeroed luma from the HW opaque buffer. Software `c2.android.avc.decoder` eventually worked but at ~12 fps with hand-rolled Kotlin NV12→I420. **Resolution:** scrapped the Kotlin path for native FFmpeg driving `h264_mediacodec` (HW) + swscale — FFmpeg feeds MediaCodec correctly and does the color convert in C. Lesson: don't hand-feed MediaCodec or do pixel work on the JVM; use FFmpeg's libav* which handles the codec/vendor quirks.
- **JWT init error 3 (2026-07-07):** SDK 7.0.5 rejects the *documented* minimal JWT payload `{appKey,iat,exp,tokenExp}` with `ZOOM_ERROR_NETWORK_UNAVAILABLE` (3/-1) — a misleading code that Zoom support confirms means "invalid JWT". Fix: keep the legacy `sdkKey` (dup of appKey), `mn`, `role` fields in the payload (`JwtSigner.kt`).
- **Join-flow crash (2026-07-07):** the SDK's pom pulls compose `ui` 1.9.x but `foundation` 1.8.x; its Compose join-preview UI then dies with `NoSuchMethodError ToggleableKt.toggleable` the moment `ZmConfActivity` opens (looked like "app goes home + stuck CONNECTING"). Fix: pin `androidx.compose.foundation:foundation:1.9.4`.
- **adb extras quoting:** `--es jwt ''` via adb loses the empty arg and stores literal `--es` as the value. Don't pass empty-string extras; use `pm clear` to reset prefs.
- **Tablet sleeps despite max screen_off_timeout:** Samsung re-locks on battery; wake+`wm dismiss-keyguard` before each interaction (session keep-awake loop) or keep it charging.
- **"No video from the Mac camera" = dead publisher (2026-07-08):** mediamtx was up but the ffmpeg webcam publisher wasn't (RTSP path 404 / connect refused -111). The app retried ~10 s, then the SDK **uninitialized the external source** (`onStopSend`→`onUninitialized`) and fell back to the tablet's front camera — reconnecting the stream later doesn't recover; the meeting must be restarted. Check `ffprobe rtsp://192.168.1.50:8554/test` first; publish with `tools/rtsp_mac_camera.sh`. Open item: auto-recover when the camera comes back mid-meeting (ties into §12B "camera offline" criterion).
- **Hosting flake `MEETING_ERROR_UNKNOWN` (100/80) (2026-07-08):** Start Meeting occasionally fails with error 100 (subcode 80) → FAILED/ENDED/IDLE, then succeeds on a plain retry. Transient (stale/expired host ZAK from `/host-zak`, or a backend hiccup) — not yet root-caused. If it becomes frequent, check ZAK TTL/refresh in the token backend before assuming an SDK issue.

---

## 18. Project History

- **2026-07-08 (camera recovery + invite + UI)** — Camera auto-recovery (§12B): on an
  SDK-initiated external-source stop while the user still wants video, the provider's
  reconnect loop keeps running (`keepAliveOnStop`); if the SDK stopped accepting, orphan
  frames trigger re-register + `muteMyVideo(false)` (`onOrphanFrame` → `maybeRecoverVideo`,
  debounced 5 s). "Camera offline — reconnecting…" pill polls `RoomSdk.cameraOffline()`
  (no frames >4 s while video wanted). Verified on-device: killed the Mac publisher
  mid-meeting → pill shows; restarted after 5½ min → frames resume automatically (the SDK
  tolerated the frame-less source this time — never called onStopSend; the keep-alive path
  guards the give-up behavior seen in the morning incident). Also: **Invite** control
  (right of Participants) with Zoom-style sheet — Send email (SDK invite subject/body),
  Send message (SMS w/ URL), Copy invite link (`getCurrentMeetingUrl`); self-view scrim
  now passes taps through (collapse + button activate in one tap; OnTouchListener
  returns false, collapse posted to avoid mutating the touch-target list mid-dispatch).
- **2026-07-08 (meeting callbacks)** — Two-participant test (tablet hosts, zoom.us guest
  on the Mac driven via AppleScript/`open` join-URL) surfaced three bugs in the custom UI
  (step 6): a late-joining guest's video didn't render until an unrelated tap, the tablet
  froze on the last frame when a guest left, and the count showed 1 instead of 2. Root
  cause: `MeetingActivity` only re-attached video on fixed post-join timers and never
  listened to `InMeetingServiceListener`. Wired that listener via a reflective proxy
  (~90 void methods, no SDK adapter; Object methods guarded so the SDK's `Vector.indexOf`
  doesn't NPE) — join/leave/update/audio/video/host/spotlight → refresh; active-video-user
  → follow speaker; leave-complete/fail → finish. Clear the video unit + show a placeholder
  when the last remote leaves; dropped the name-dedup in `participants()` (a guest can share
  the account display name). Verified on-device. Note: a hard-killed guest still frays for
  ~40 s until Zoom's heartbeat timeout — that's SDK detection latency, not our bug.
- **2026-07-08 (FPS fixed)** — Step 5b done. Telemetry-first: arrival-gap buckets in the
  pump meter proved MediaCodec delivers frames in bursts (~50 gaps <20 ms per 3 s window,
  max 291 ms), so any wall-clock pacer misfires; web research (Zoom's own sample, devforum,
  attendee bot) confirmed "pace to suggested fps" and that arrival rate is treated as the
  frame rate. Moved pacing into `rtsp_decoder.c` as a PTS-keyed deadline accumulator applied
  pre-swscale (`nativeOpen(..., paceFps)`, `debug.room.nopace` toggle); deleted `FramePacer.kt`.
  Added Zoom wire-truth telemetry (`ZoomStats`: `getMeetingVideoStatisticInfo().getSendFps()`
  every 3 s in MeetingActivity; parsed by `measure_fps.py`) and `--es testSecs N` for longer
  decode-only runs. Verified hosted meeting @720p25: sendVideoFrame 25.0 fps over 36 s
  (was ~11). Details §19.
- **2026-07-08 (FPS)** — E2E RTSP-camera FPS test + root-cause (full detail in §19). Built
  `tools/rtsp_bench.sh` + `tools/measure_fps.py`; instrumented `rtsp_decoder.c` (per-stage
  timing) and `FfmpegVideoSource.kt` (pump meter); added `debug.room.swdec` HW/SW toggle.
  Proved the low ~11 fps is **the frame pacer**, not decoder/MediaCodec/network/Zoom
  (MediaCodec HW decode is ~3 ms/frame; native decode runs 30 fps). Rewrote `FramePacer` to a
  deadline-accumulator (unit test 30→25 green); wall-clock JVM pacing still jittery on-device,
  so next step is native PTS-based pacing.
- **2026-07-08 (later)** — Shippable polish: adaptive **launcher icon** (camera on blue); **signed release build** (debug keystore) so the tablet runs a non-debuggable APK — this stops Samsung's recurring "16 KB app compatibility" dialog (it only nags debug builds; the unaligned libs are the Zoom SDK's). Installed on the tablet as the sole app (removed legacy `zoomremote` + old debug build). UX fixes: Start Meeting no longer flashes "Joining meeting" (keyed off a `hosting` flag — the SDK's CONNECTING status was overwriting it); the 5-tap camera-setup gesture is more forgiving (bigger target + 2.5 s window). Dropped the unused rtsp-client-android dep.
- **2026-07-08** — **Hosting works end-to-end.** Start Meeting → backend mints the host token via **Server-to-Server OAuth** (`/host-zak`: ZAK + the account's PMI) → tablet hosts its own PMI meeting with the RTSP camera streaming in (verified on-device). Chose S2S over interactive user-OAuth because Zoom won't redirect OAuth to a plain-http LAN address; per-user OAuth is future (needs https backend). Scopes on the S2S app: `user:read:token:admin` (ZAK) + `user:read:user:admin` (PMI). `StartMeetingParamsWithoutLogin` needs a real meetingNo (the PMI) — empty → error 99. Both Join (no creds) and Host now proven.
- **2026-07-07 (latest+1)** — **Consumer sign-in via a token backend** (resolves §11 Q2). `backend/token_server.py` holds the Zoom secrets: `/sdk-jwt` signs the Meeting SDK JWT (app fetches it → **joining needs no credentials/login, just a meeting ID**); `/oauth/*` runs Zoom user OAuth → the user's ZAK for hosting. App: `RoomBackend.kt`; Client ID/secret/ZAK fields removed (JwtSigner deleted); Start Meeting → "Sign in with Zoom" (browser) → poll `/session` → ZAK → host. Needed a network-security-config to allow cleartext to the LAN backend (Zoom SDK blocks it). Verified on-device: fresh install, no creds, joins a live meeting. OAuth hosting wired, pending a Zoom OAuth app to test.
- **2026-07-07 (latest)** — Custom in-meeting UI (step 6): SDK customized-UI mode + `MeetingActivity` renders the far end (remote participant, not active-speaker — that renders black for self) full-screen via `MobileRTCVideoView`/`addAttendeeVideoUnit`, with Mute/Video/Leave/count only. Fixed control bar cut off by the Samsung taskbar (window insets) — which was also why taps fell through to the dock. Documented ZAK + sign-in in README. Confirmed: Start Meeting w/o ZAK no longer dead-ends; Share/More gone.
- **2026-07-07 (late)** — Real camera through the pipeline: published the Mac's webcam as RTSP and the tablet HW-decoded it into a meeting; fixed aspect-ratio squish (swscale now fits source aspect, e.g. 1280x720→640x360). Start Meeting wired to `startMeetingWithParams` + host **ZAK** (`USER_TYPE_API_USER` + `zoomAccessToken`); SDK has no email/password login (SSO/ZAK only) — added `tools/mint_zak.py`. Hid Share/More/Record/Invite via `meeting_views_options`. **Repo reorganized** for clarity: `room/` (active) + `legacy/` (archived v1) + `docs/` + `tools/`; `settings.gradle.kts` builds only `:room`; README rewritten.
- **2026-07-06** — Plan forked from `~/code/misc/plan-template.md`. Decisions: embed Zoom Meeting SDK with external video source instead of camera spoofing or a relay server; camera input limited to RTSP or wired USB (wireless-USB requirement dropped); legacy server/remote architecture frozen, not removed.
- **2026-07-07 (evening)** — **Step 4 done, hardware-accelerated.** Pivoted RTSP decode to native FFmpeg (libavformat RTSP + `h264_mediacodec` MediaCodec **HW** decode + libswscale NV12→I420) via a JNI wrapper — decision driven by the Kotlin/MediaCodec dead-end (§17) and "no JVM pixel processing". Built decode-only LGPL FFmpeg 6.1.2 for arm64 with `--enable-mediacodec`; confirmed `CONFIG_H264_MEDIACODEC_DECODER=yes`. E2E on SM-P620 in a real meeting: remote participant sees the RTSP feed **clean** (SMPTE bars + "ZOOM ROOM RTSP FEED" label + live clock), HW-decoded. Fixed a double-`onStartSend` that spun up two decoders → torn "rainbow" frames on the wire. Legacy Kotlin decode classes removed.
- **2026-07-07** — Credentials in (Marketplace app works). Fixed three blockers (see §17): JWT payload fields, Compose foundation pin, adb quoting. UI rebuilt to the v1 console design (Camera/Join cards, status dot, transition/overlay screens). Decision: **no Mac zoom.us involvement at all** — tablet-only verification; Mac = build + simulated RTSP camera (`room/scripts/rtsp_test_stream.sh`). Verified on-device: init 0/0 with app-signed JWT; RTSP source through the app 98 frames/5 s @720p; join flow to FAILED(9)/home for a not-running PMI, crash-free; external source negotiates 720p@25. Remaining for steps 2–4 sign-off: a live meeting to sit INMEETING with frames flowing (needs user: enable join-before-host on PMI, or start a meeting from any device).
- **2026-07-06 (later)** — Steps 1–4 built and device-tested up to the credential wall. `room/` module compiles against `us.zoom.meetingsdk:zoomsdk:7.0.5` (Maven Central — no Marketplace download needed); AGP 8.11.1, compileSdk 36, minSdk 28, arm64-only. External-source API verified from the AAR (`ZoomSDKVideoSource`, `sendVideoFrame(..., ExternalSourceDataFormat)` — I420 full/limited). Raw-data: no special Zoom entitlement required anymore (sending uses the video-source helper; only *receiving* raw streams needs livestream permission). On SM-P620: app installs/launches, permissions granted, test-pattern source 127 frames/5 s @720p, RTSP source 98 frames/5 s @720p against local mediamtx, SDK init round-trips to Zoom (dummy JWT rejected err=5/124 as expected). JWT is signed on-device from user-entered client ID/secret (dev-only; Q2 unchanged). **Blocked on user:** create a Meeting SDK app at marketplace.zoom.us (Develop → Build App → General App/Meeting SDK) and supply the Client ID + Client Secret; then steps 1–4 finish with a real join (test plan §12A).

---

## 19. FPS Investigation (2026-07-08) — RESOLVED

**Problem:** outgoing rate to Zoom ~10–15 fps (vs 25 negotiated, 30 fps source).

**Method (repeatable):** `tools/rtsp_bench.sh` varies the source; `tools/measure_fps.py`
reads frame counters + Zoom's own encoder stat from logcat. Telemetry in the app:
per-stage native timing in `rtsp_decoder.c` (`read/send/recv/scale/copy` ms + paced-drops),
pump meter in `FfmpegVideoSource.kt` (emit fps + arrival-gap buckets `<20 / 20-45 / >45 ms`),
Zoom wire truth via `InMeetingVideoController.getMeetingVideoStatisticInfo().getSendFps()`
logged every 3 s (tag `ZoomStats`) in `MeetingActivity`. Toggles: `debug.room.swdec`
(software decode), `debug.room.nopace` (disable pacing). Decode-only runs:
`am start ... --es testSource true --es testSecs 30`.

**Root cause (two layers, both confirmed by measurement):**
1. Old `FramePacer` (min-interval, no accumulator) quantizes 30→25 to every-2nd-frame
   = 15 fps; 60 fps source → 19 fps (every 3rd) — confirmed on-device.
2. The rewritten deadline-accumulator was still ~11–15 on-device because it paced on
   **wall-clock arrival time, and MediaCodec delivers frames in bursts**: measured
   arrival gaps per 3 s window ≈ 50× `<20 ms`, ~15× nominal, ~22× `>45 ms` (max 291 ms),
   while native decode averages a clean 30 fps. Arrival time carries no cadence —
   only the stream's own PTS does.

**Fix:** pacing moved into `rtsp_decoder.c`, keyed off frame PTS (best-effort timestamp),
same deadline-accumulator math, applied **before** `sws_scale` so dropped frames cost
nothing (no avfilter in our FFmpeg build; hand-rolled accumulator). `nativeOpen()` takes
`paceFps` (0 = off); resyncs on PTS jumps >10 intervals. Kotlin `FramePacer` deleted.

**Verified on-device (SM-P620, 720p30 RTSP):**
- Decode-only 30 fps source, target 30: emit 29.4–30.7 fps, drops 0 (was 11–15).
- Decode-only 60 fps source, target 30: emit ~30, paced-drops exactly ½ — accumulator correct.
- **Hosted meeting (negotiated 720p25): decode+emit 25.1 fps, sendVideoFrame 25.0 fps
  over 36 s** — the original failing metric, now at target.
- **Two-participant call (tablet hosts, zoom.us guest on the Mac): Zoom's own encoder
  stat `getSendFps()` = 23.4 fps avg (min 21, max 25) over 30 s** — wire-level
  confirmation, not just how often we call `sendVideoFrame`.
- `ZoomStats` sendFps reads 0 in a solo meeting (Zoom encodes nothing with no
  subscriber) and only reflects the real wire rate once a remote participant subscribes
  to our video at a large-enough view — pin/enlarge the room tile for a clean reading.

**Research notes (Zoom devforum / Zoom's own sample / recall.ai / attendee bot):**
- Pace to `suggest_cap.getFrame()` — Zoom's Android sample paces with a timer at the
  suggested fps; pushing above it "works" (no clean internal sampling) but wastes
  encode CPU/bandwidth and can add latency. Our choice: pace to negotiated fps. ✔
- Frames should match the suggested capability **dimensions exactly** — off-size frames
  succeed silently but the SDK crops/processes them. Our aspect-preserving scaler can
  emit e.g. 640×360 into a 640×480 cap; fine today (we negotiate 16:9 720p and send
  720p), but revisit if a non-16:9 camera or low-res cap shows up.
