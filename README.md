# Zoom Room — tablet-only appliance

Turn an Android tablet + a TV + an external camera into a complete Zoom room.
Download one app, no PC, no server, no plug-ins, no root. The tablet **is** the
Zoom client: it joins real meetings via the Zoom **Meeting SDK** and feeds
video from an external **RTSP camera** through the SDK's external video source.

The RTSP camera is decoded on the tablet's **hardware** video engine via native
FFmpeg (`h264_mediacodec`), converted to I420 in C (libswscale), and pushed to
Zoom — no pixel processing on the JVM.

```
 RTSP camera ──▶ tablet ──────────────────────────────▶ Zoom meeting ──▶ TV (HDMI)
 (H.264/H.265)   FFmpeg demux + MediaCodec HW decode        ▲
                 + swscale → I420 → Meeting SDK             touch controls
```

## Status

- **Working, verified on-device (Samsung SM-P620, Android 16):** **join a meeting
  with no credentials or login** — the app fetches its Zoom SDK token from the
  backend; the remote participant sees the RTSP camera feed, hardware-decoded,
  correct aspect ratio; a **custom in-meeting screen** shows the far end
  full-screen with a minimal Mute / Video / Leave bar (no SDK clutter).
- **Start Meeting (hosting)** signs in with Zoom (OAuth via the backend) — wired
  end-to-end, pending a Zoom OAuth app to exercise.
- **Not yet:** USB/UVC camera, HDMI external display. Tracked in [`plans/`](plans).

## Repository layout

```
room/                 The appliance — Android app (Kotlin + native FFmpeg/JNI)
  src/main/java/.../sdk/       Meeting SDK: init, JWT, join/start, external video source
  src/main/java/.../source/    Video sources: FFmpeg RTSP, test pattern, pacing
  src/main/cpp/                JNI: RTSP → MediaCodec HW decode → I420 (FFmpeg)
  src/main/jniLibs/arm64-v8a/  Prebuilt FFmpeg 6.1.2 (.so, decode-only LGPL)
backend/              Token server — signs the SDK JWT + OAuth→ZAK (no secrets on device)
tools/                Dev helpers (RTSP test stream, standalone ZAK minter)
docs/                 Design + Zoom-ToS policy notes
plans/                Living design/implementation plan (read this first)
legacy/               Archived v1 remote-control system (frozen — see legacy/README.md)
```

## Build & run

Prereqs: Android SDK (platform 36, NDK 25, CMake 3.22), JDK 17. `local.properties`
points `sdk.dir` at your Android SDK.

```bash
./gradlew :room:assembleDebug
adb install -r room/build/outputs/apk/debug/room-debug.apk
```

## How a customer uses it

No Zoom developer credentials ever touch the tablet — a small **token backend**
holds them. So for the person using the room:

- **Join a meeting:** open the app → **Join** → type the meeting ID. No sign-in,
  no account needed. (The app quietly fetches a Meeting SDK token from the
  backend to authorize the SDK.)
- **Start (host) a meeting:** tap **Start Meeting** → **Sign in with Zoom** opens
  Zoom's login in the browser once → you're hosting. The backend turns that
  sign-in into the host token; the tablet never sees a secret.

Room install (one-time, hidden so daily users don't see it): **long-press the
"Zoom Room" title** → set the backend address + room name; **tap the title 5×**
→ set the camera's RTSP URL.

## Running the token backend

The backend (`backend/token_server.py`, stdlib only) signs the SDK JWT and does
the Zoom OAuth that yields the host's ZAK. For dev it runs on the Mac; the
tablet reaches it over the LAN (the app defaults to `http://<mac-ip>:8790`).

```bash
cp backend/.env.example backend/.env        # fill in the two Zoom apps below
set -a; . backend/.env; set +a
python3 backend/token_server.py
```

It needs two Zoom Marketplace apps:
- **Meeting SDK app** → `ZOOM_SDK_CLIENT_ID/SECRET` (signs the JWT that lets the
  tablet join). This alone enables joining.
- **OAuth (General) app** → `ZOOM_OAUTH_CLIENT_ID/SECRET`, with its Redirect URL
  set to `http://<mac-ip>:8790/oauth/callback` and scope `user:read`. Needed
  only for **Start Meeting** (host sign-in → ZAK).

A shipping build points the app at an https backend that we operate, so a
customer just downloads the app and signs in with their own Zoom account.

Local RTSP test camera (Mac): `tools/rtsp_test_stream.sh` publishes a labeled
test pattern to `rtsp://<mac-lan-ip>:8554/test`.
(`tools/mint_zak.py` is a standalone ZAK minter, superseded by the backend.)

## License / codecs

The bundled FFmpeg is a **decode-only LGPL** build (no GPL components). Shipping
H.264/H.265 decoders carries codec-patent considerations.
