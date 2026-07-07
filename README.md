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
  the remote participant sees the RTSP camera feed, hardware-decoded, correct
  aspect ratio; a **custom in-meeting screen** shows the far end full-screen with
  a minimal Mute / Video / Leave bar (no SDK Share / More / chat clutter).
- **Start Meeting (hosting)** works with a host **ZAK** token — see Signing in.
- **Not yet:** USB/UVC camera, HDMI external display, a hosted token endpoint so
  no credentials are typed on-device. Tracked in [`plans/`](plans).

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

## Signing in (first run)

The app authorizes itself to Zoom with a **Meeting SDK app**, not a personal
Zoom login — there is no username/password screen (Zoom removed SDK
email/password login; only SSO or a token remain). Setup is one-time and hidden
behind gestures on the title so the day-to-day screen stays "Ready to meet":

1. **SDK credentials** — at [marketplace.zoom.us](https://marketplace.zoom.us)
   create a *Meeting SDK* app and copy its **Client ID** + **Client Secret**.
   In the app, **long-press the "Zoom Room" title** → paste them → Save. The app
   signs the SDK JWT on-device from these (dev convenience; a production build
   would sign it on a small server so the secret never ships).
2. **Camera** — **tap the title 5×** → enter the room camera's RTSP URL.
3. **Join** a meeting by ID + passcode. That's all that's needed to attend — no
   host account, works on a free/Basic Zoom account.

### Hosting a meeting (Start Meeting) — the ZAK

To *host* (not just join), Zoom requires the room to act as a specific host
user. Since there's no password login, that identity comes from a **ZAK** (Zoom
Access Key): a short-lived (~2 h) token for the host account. Mint one from a
free **Server-to-Server OAuth** app (Account ID + Client ID/Secret, scope
`user:read:admin`):

```bash
ZOOM_ACCOUNT_ID=… ZOOM_S2S_CLIENT_ID=… ZOOM_S2S_CLIENT_SECRET=… \
    python3 tools/mint_zak.py            # prints the ZAK
```

Paste it into the app (long-press title → **Host ZAK**). **Start Meeting** then
hosts that account's personal meeting. The ZAK expires — re-mint to refresh.
(A shipping build would fetch the ZAK automatically from the same token server
as the JWT, so nothing is pasted by hand.)

Local RTSP test camera (Mac): `tools/rtsp_test_stream.sh` publishes a labeled
test pattern to `rtsp://<mac-lan-ip>:8554/test`.

## License / codecs

The bundled FFmpeg is a **decode-only LGPL** build (no GPL components). Shipping
H.264/H.265 decoders carries codec-patent considerations.
