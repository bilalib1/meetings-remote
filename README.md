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

- **Working, verified on-device (Samsung SM-P620, Android 16):** join a meeting;
  a remote participant sees the RTSP camera feed, hardware-decoded, correct
  aspect ratio. The Mac's own webcam has been looped through end to end.
- **Start Meeting (hosting)** needs a host **ZAK** token — see below.
- **Not yet:** USB/UVC camera path, HDMI/external-display gallery, source picker
  polish. Tracked in [`plans/`](plans).

## Repository layout

```
room/                 The appliance — Android app (Kotlin + native FFmpeg/JNI)
  src/main/java/.../sdk/       Meeting SDK: init, JWT, join/start, external video source
  src/main/java/.../source/    Video sources: FFmpeg RTSP, test pattern, pacing
  src/main/cpp/                JNI: RTSP → MediaCodec HW decode → I420 (FFmpeg)
  src/main/jniLibs/arm64-v8a/  Prebuilt FFmpeg 6.1.2 (.so, decode-only LGPL)
tools/                Dev helpers (RTSP test stream, ZAK minting)
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

First launch: **long-press the "Zoom Room" title** to enter Meeting SDK
credentials (Client ID/secret from a Marketplace Meeting SDK app). **Tap the
title 5×** to set the RTSP camera URL. Then **Join** a meeting.

Local RTSP test camera (Mac): `tools/rtsp_test_stream.sh` publishes a labeled
test pattern to `rtsp://<mac-lan-ip>:8554/test`.

## Hosting a meeting (Start Meeting)

The Meeting SDK can't host with an email/password login (Zoom removed that);
plain login is SSO-only. On a personal/Basic account the supported path is a
**ZAK** (Zoom Access Key), a ~2 h token minted from a free Server-to-Server
OAuth app:

```bash
ZOOM_ACCOUNT_ID=… ZOOM_S2S_CLIENT_ID=… ZOOM_S2S_CLIENT_SECRET=… \
    python3 tools/mint_zak.py
```

Paste the printed ZAK into the app (title → credentials → "Host ZAK"). Then
**Start Meeting** hosts the account's personal meeting. Joining needs no ZAK.

## License / codecs

The bundled FFmpeg is a **decode-only LGPL** build (no GPL components). Shipping
H.264/H.265 decoders carries codec-patent considerations.
