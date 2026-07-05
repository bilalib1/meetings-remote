# Zoom Room Controller

An Android tablet that acts like a **Zoom Rooms controller** for the official
Zoom desktop client on a Mac: the video stays on the PC's screen, the tablet
is a touch console that starts/joins meetings and drives the controls. It
talks to a tiny server on the Mac over Wi-Fi; the server performs each action
on Zoom's real menus/dialogs via macOS Accessibility. No Zoom SDK, no Zoom
account credentials, no Zoom servers touched beyond the normal client — so no
usage tracking, metering, or limits beyond the host account's normal ones
(see `POLICY.md`).

```
[Tablet console] --HTTP/Wi-Fi--> [Mac: zoom_control_server.py] --Accessibility--> [zoom.us]
```

## The UI (state-driven, like a room console)

The tablet shows exactly one of these, chosen by the live state polled from
the PC — a button is never shown out of context:

- **Home** (not in a meeting): big **New Meeting** and **Join** tiles.
- **In-meeting**: meeting topic + a running timer + recording indicator, a grid
  of **state-aware** controls (Mute/Unmute, Start/Stop Video, Share, Participants,
  Record, Raise/Lower Hand), and a red **Leave Meeting** bar. Controls reflect
  reality — e.g. the mic tile turns red and says "Unmute" when muted, and Raise
  Hand greys out when you're the solo host (it doesn't exist then).
- **Overlay** for unusable states so a tap never silently fails: "Can't reach
  the room PC" (with the address), "Zoom isn't open", "Grant Accessibility".
  A status dot in the header is green (ready/in-meeting), amber (needs
  attention), or red (offline).

The header gear opens a dialog to set the PC address. State polls every ~1.5 s,
and each action re-polls immediately so tiles snap to the new state.

## Server API (`server/zoom_control_server.py`, macOS)

GET or POST; all return JSON `{ok, ...}` except status.

- `/api/status` — full state: `zoom_running, accessibility, in_meeting,
  audio_joined, muted, video_on, sharing, hand_raised, recording, topic`
- `/api/new` — start an instant meeting (auto-joins computer audio, see below)
- `/api/join?id=<id>&pwd=<pwd>` — join by meeting ID (auto-joins computer audio)
- `/api/mute` — toggle personal mute (auto-joins computer audio first)
- `/api/video` — start/stop video
- `/api/share` — start/stop screen share
- `/api/participants` — toggle the participants panel on the PC
- `/api/record` — start/stop recording
- `/api/hand` — raise/lower hand (participant only)
- `/api/leave` — leave the meeting (never "End for all"); verifies it left
- `/` — a browser version of the same console (fallback / testing)

## Setup

### Mac (the room PC)
1. **Accessibility permission** is attributed to the *terminal app* that
   launches the server, not to python. On this machine that's **Ghostty**,
   already granted — run the server from Ghostty and there's no prompt. From a
   different terminal you'd approve it once at System Settings → Privacy &
   Security → Accessibility.
2. Start it:  `./run_server.sh`  (prints the LAN URL, e.g. `http://192.168.1.50:8765`).

### Tablet
- Build/install: `./gradlew assembleDebug` then
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Tap the header **gear** and set the address to the Mac's LAN IP:port
  (default `192.168.1.50:8765`). Tablet and Mac must share a Wi-Fi/LAN.
- USB fallback (no Wi-Fi): `adb reverse tcp:8765 tcp:8765`, then set the
  address to `127.0.0.1:8765`.

## Verified

Exercised end-to-end from the tablet over Wi-Fi (tablet 192.168.1.154 →
Mac 192.168.1.50): Home → New Meeting → in-meeting console appears → Join
Audio → Mute → Unmute (tiles track state) → Leave (confirm) → back to Home
with Zoom actually out of the meeting; plus the offline overlay when the
server is down and auto-recovery when it returns.

## Computer audio

Starting or joining a meeting auto-joins computer audio in the background, so
the "Join with Computer Audio" prompt never blocks you. It also ticks Zoom's
own "Automatically join computer audio when joining" checkbox once, so from
then on Zoom auto-joins for every meeting — including ones you start by hand.

## Notes / limits

- The in-meeting toolbar auto-hides on macOS, so live *participant count* isn't
  reliably readable and is intentionally not shown (better than showing wrong
  data). Mute/video/share/record/hand state and the topic are read from Zoom's
  always-available Meeting menu.
- The server binds `0.0.0.0` with no auth — fine for a trusted room LAN; don't
  expose it to the internet.
- macOS only. A Windows port would keep the same API and use UI Automation.
