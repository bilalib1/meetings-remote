# Meetings Remote

**Turn an Android tablet, a TV, and a camera into a Zoom meeting room — one
app, no PC, no sign-in, no monthly fees.**

![Platform](https://img.shields.io/badge/platform-Android%208%2B-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/status-pre--release-orange)
![License](https://img.shields.io/badge/license-AGPL--3.0-blue)

Meetings Remote turns a spare Android tablet into a dedicated meeting-room
device. Walk into the room, tap **Join**, type the meeting ID, and you're in a
real Zoom meeting — with the room camera on screen and the far end on your TV.
No computer to wake up, no account to sign into, no cables to fumble with.

It's built for small offices, home offices, and anyone who wants a
"walk in and it just works" meeting room without buying dedicated
conference-room hardware.

> Meetings Remote is an independent project and is not affiliated with or
> endorsed by Zoom Video Communications, Inc.

---

## See it in action

<!-- TODO: replace placeholders with real captures before publishing -->

| Home screen | In a meeting |
|---|---|
| _screenshot coming soon_ | _screenshot coming soon_ |

_Demo video: coming soon._

## What you need

- **An Android tablet** — Android 8 or newer. This is the brain of the room;
  it runs the app and joins the meeting.
- **A TV or large screen** — so everyone in the room can see the far end.
- **A network camera** — any camera that streams over your network (RTSP).
  Most IP/security-style cameras and PTZ conference cameras support this.
- **Wi-Fi or Ethernet** — the tablet and camera on the same network.

That's the whole room. No PC, no capture card, no subscription.

## Set it up

1. **Install the app** on the tablet. (A Play Store release is coming — for
   now, build the APK yourself; see [Build it yourself](#build-it-yourself).)
2. **One-time room setup** (hidden so daily users never see it):
   - Long-press the app title → enter your token server address and a room name.
   - Tap the title five times → enter your camera's stream URL
     (e.g. `rtsp://192.168.1.20:554/stream`).
3. **Use the room:**
   - **Join a meeting** — tap **Join**, type the meeting ID. No sign-in needed.
   - **Start a meeting** — tap **Start Meeting** and the room hosts its own
     meeting that others can join.

During a meeting you get a clean full-screen view of the far end with just
three buttons: **Mute**, **Video**, **Leave**.

## Build it yourself

The project has two parts: the **Android app** (the `room/` module) and a tiny
**token server** (`backend/`) that keeps your Zoom developer credentials off
the tablet.

### The Android app

Prerequisites: JDK 17, Android SDK (platform 36, NDK 25, CMake 3.22.1).
Point `local.properties` at your SDK (`sdk.dir=/path/to/android-sdk`), or just
open the project in **Android Studio** and let it handle the rest.

```bash
./gradlew :room:assembleDebug        # day-to-day development build
./gradlew :room:assembleRelease      # non-debuggable build for the room tablet
adb install -r room/build/outputs/apk/release/room-release.apk
```

Install the **release** build on the tablet you'll actually use in the room;
use `assembleDebug` while developing.

### The token server

`backend/token_server.py` is a single-file Python 3 server with no
dependencies. It signs the tokens the app needs, so no secrets ever live on
the tablet.

```bash
cp backend/.env.example backend/.env    # fill in your Zoom app credentials
set -a; . backend/.env; set +a
python3 backend/token_server.py         # listens on port 8790
```

You'll need two (free) apps on the [Zoom App Marketplace](https://marketplace.zoom.us):

- A **Meeting SDK app** → `ZOOM_SDK_CLIENT_ID` / `ZOOM_SDK_CLIENT_SECRET`.
  This alone lets the tablet **join** meetings.
- A **Server-to-Server OAuth app** → `ZOOM_ACCOUNT_ID`,
  `ZOOM_S2S_CLIENT_ID` / `ZOOM_S2S_CLIENT_SECRET` with scopes
  `user:read:token:admin` and `user:read:user:admin`. This enables
  **Start Meeting** (the room hosts under this account).

For development, run the server on any machine on your LAN; the app defaults
to `http://<server-ip>:8790`. No test camera handy? `tools/rtsp_test_stream.sh`
publishes a labeled test pattern you can point the app at.

## Roadmap & status

**Working today** (verified on real hardware — Samsung Galaxy Tab, Android 16):

- Join a meeting with no credentials or login on the tablet
- Host ("Start Meeting") via the token server
- Network (RTSP) camera as the room camera, smooth hardware-accelerated video
- Clean custom in-meeting screen (full-screen far end, Mute / Video / Leave)
- Room audio: tablet microphone with automatic lip-sync correction

**Coming next:**

- **Google Play Store release** — install without building from source
- Sign in with your own Zoom account (so the room hosts as *you*)
- USB webcam support (plug a webcam straight into the tablet)
- HDMI output polish for TV-as-the-main-screen setups

Detailed, living plans are in [`plans/`](plans).

## How it works

The tablet **is** the meeting-room device. The app joins real Zoom meetings
through Zoom's official Meeting SDK and feeds it video from the network
camera, decoded on the tablet's hardware video engine (native FFmpeg +
MediaCodec, no pixel processing on the JVM):

```
 RTSP camera ──▶ tablet ──────────────────────────────▶ Zoom meeting ──▶ TV (HDMI)
 (H.264/H.265)   FFmpeg demux + MediaCodec HW decode        ▲
                 + swscale → I420 → Meeting SDK             touch controls
```

Credentials never touch the tablet: the token server holds the Zoom app
secrets, signs the Meeting SDK token, and mints the host token over
Server-to-Server OAuth.

```
room/       The Android app (Kotlin + native FFmpeg/JNI video pipeline)
backend/    Token server — keeps all Zoom secrets off the device
tools/      Dev helpers (RTSP test stream, standalone token minter)
docs/       Design and policy notes
plans/      Living design/implementation plans
legacy/     Archived v1 remote-control system (frozen)
```

More depth: [`docs/DESIGN.md`](docs/DESIGN.md).

## License & attribution

- Licensed under the **GNU AGPL-3.0** (see [LICENSE](LICENSE)): free to use,
  build, and modify; any distributed or hosted fork must publish its full
  source under the same license.
- **"Meetings Remote"** — the name, branding, and store listings — belongs to
  the author and may not be used by forks or derived apps.
- Bundles a **decode-only LGPL build of FFmpeg** (no GPL components).
- Third-party source, license, and modification details—including the stripped
  DoubleTake AirPlay-compatible sender—are recorded in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
  Shipping H.264/H.265 decoders carries codec-patent considerations.
- Uses the **Zoom Meeting SDK**, which is subject to Zoom's terms of use.
- "Zoom" is a trademark of Zoom Video Communications, Inc. This project is not
  affiliated with or endorsed by Zoom.
