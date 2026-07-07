# Zoom Room Controller — Design & Architecture

This document describes the whole system so any new client (iPad, web, a
hardware panel) or new server backend (Windows, Linux) can be built to match.
It's the spec the iPad app is built against.

## What it is

A tablet acts like a **Zoom Rooms controller** for the *official* Zoom desktop
client running on a nearby PC. The PC's screen shows the meeting (video, shared
content); the tablet is a touch console that starts/joins meetings and drives
the in-meeting controls. They talk over the LAN.

```
┌──────────────┐   HTTP/JSON over Wi-Fi   ┌───────────────────────┐   OS UI automation   ┌────────────┐
│ Tablet app   │ ───────────────────────▶ │ Controller server     │ ───────────────────▶ │ zoom.us    │
│ (Android/iPad)│ ◀─────────────────────── │ (Python, on the PC)   │ ◀─────────────────── │ desktop app│
└──────────────┘   status snapshots        └───────────────────────┘   reads menu state   └────────────┘
```

### Why not the Zoom SDK
The Zoom Meeting SDK builds a *separate* meeting client; it cannot reach into
and control the official `zoom.us` app already running on the PC. Since the
goal is to control the real client on the room PC, we drive that client's own
menus/shortcuts instead — the same technique the Elgato Stream Deck Zoom plugin
and ZoomOSC use. Consequences (see `POLICY.md` for sources):
- No Zoom API/SDK calls, no credentials, no Zoom servers touched beyond the
  normal client. Nothing is attributable to a developer app, so there's no
  usage tracking, metering, or scaling cost — only the host account's normal
  limits (e.g. the 40-min cap on free plans) apply.
- No known ToS problem; Zoom tolerates/embraces this class of controller.

## Components

| Path | Role |
|------|------|
| `server/zoom_controller.py` | `ZoomController` abstract interface + `get_controller()` OS picker. The contract. |
| `server/mac_controller.py` | macOS implementation (AppleScript UI scripting). |
| `server/zoom_control_server.py` | Platform-agnostic HTTP layer; maps endpoints → `controller.method()`. |
| `app/` (Android, Kotlin) | Tablet console client. |
| `ios/` (iPad, SwiftUI) | iPad console client (same protocol). |

The server and clients share **no** platform code. A new OS backend = implement
`ZoomController`. A new client = speak the HTTP protocol below.

## The protocol (client ⇆ server)

Base: `http://<pc-ip>:8765`. All action endpoints accept GET or POST and return
`application/json`. CORS is open (`Access-Control-Allow-Origin: *`).

### Actions → `{ "ok": bool, "action"|"error": string }`
| Endpoint | Effect |
|----------|--------|
| `POST /api/new` | Start an instant meeting (auto-joins computer audio). |
| `POST /api/join?id=<digits>&pwd=<passcode>` | Join a meeting by ID (auto-joins audio). |
| `POST /api/mute` | Toggle personal mute (joins computer audio first if needed). |
| `POST /api/video` | Start/stop camera. |
| `POST /api/participants` | Toggle the participants panel on the PC. |
| `POST /api/hand` | Raise/lower hand (participant only). |
| `POST /api/leave` | Leave the meeting (never "End for all"); verifies it left. |

Known `error` codes (clients render these nicely): `zoom_not_running`,
`accessibility_permission_needed`, `not_in_meeting`, `control_unavailable`,
`audio_unavailable`, `leave_not_confirmed`, `missing_meeting_id`,
`server_error: …`.

### State → `GET /api/status`
```json
{
  "zoom_running": true,
  "accessibility": true,      // OS permission to control Zoom is granted
  "in_meeting": true,
  "audio_joined": true,       // false => mic tile shows "Join Audio"
  "muted": false,
  "video_on": true,
  "hand_raised": null,        // null => not applicable (e.g. solo host) -> grey out
  "topic": "Zoom Meeting"
}
```
Any field the platform can't determine is `null` (never omitted), so a client
can grey out or hide the matching control.

`GET /` serves a minimal browser version of the console (fallback / testing).

### Design notes on the protocol
- **Stateless & pollable.** Clients poll `/api/status` and render from it; the
  menu item *names* in Zoom encode current state (e.g. "Unmute audio" only
  exists while muted), so status is read straight from the live client — no
  separate bookkeeping to drift out of sync.
- **Screen share is intentionally omitted.** The room screen *is* Zoom, so
  sharing it back is circular; dropped until there's a real use (a second
  display, a source picker).
- **Leave is safe by construction.** The server matches the dialog element whose
  description starts with "Leave", never "End meeting for all".

## macOS control mechanism (reference backend)

- **Reading state**: the always-present "Meeting" menu bar item. It only exists
  in a meeting, and its item names reveal state. This survives the in-meeting
  toolbar auto-hiding.
- **Toggles** (mute/video/hand): click the matching menu item.
- **Participants**: no menu item exists → `⌘U`.
- **Leave**: focus the meeting window, `⌘W`, click the "Leave meeting" dialog
  element, then confirm by re-reading state.
- **New/Join**: `⌘⌃V` / the `zoommtg://` URL scheme, then a background thread
  auto-joins computer audio (and ticks Zoom's "always join computer audio" box
  so the prompt never blocks the user again).
- **Permission**: the terminal that launches the server needs macOS
  Accessibility. It's attributed to the terminal app, not to python.

A Windows backend would implement the same `ZoomController` methods with
UI Automation / SendKeys and the `zoommtg://` scheme.

## Client design principles (match these in every client)

The Android app is the reference. A good client:

1. **State-driven screens**, chosen only by the polled status — never leave a
   button visible out of context:
   - **Home** (not in a meeting): New Meeting, Join.
   - **In-meeting**: topic + running timer; controls Mute, Video,
     Participants, Raise Hand; a prominent Leave.
   - **Transition**: Starting… / Joining… / Leaving… (spinner) shown *the
     instant* the user acts, resolved when the PC agrees, with a timeout that
     falls back gracefully.
   - **Overlay** for unusable states (can't reach PC / Zoom closed / needs
     Accessibility) so a tap is never silently swallowed.
2. **Feels instant**:
   - Optimistic control updates — a tap flips the tile immediately, then
     reconciles with the next status poll (with a short expiry so a wrong guess
     self-heals).
   - Concurrent networking so a slow action (Leave confirms server-side ~2s)
     never blocks status polls.
   - Adaptive polling: fast (~0.45s) while a transition is pending, relaxed
     (~1.4s) when idle.
   - Smooth cross-fades between screens; press feedback on controls.
3. **State-aware controls**: mic turns red / says "Unmute" when muted; video
   red when off; Raise Hand greys out when `hand_raised` is null (solo host).
4. **Respect the safe area / system bars** (edge-to-edge): pad by insets so
   nothing hides under the status/navigation bars in landscape.
5. **Address config is contextual**: no always-visible settings button; the
   offline screen is tappable to change the PC address (that's when you need
   it), with a hidden long-press affordance as backup.
6. **Leave confirms** (destructive); other controls act immediately.

### Polish checklist (done on Android; required for parity on any client)
- [ ] Home / in-meeting / transition / overlay states, chosen by status
- [ ] Optimistic updates + reconcile
- [ ] Adaptive polling + concurrent networking
- [ ] Cross-fade screen transitions + button press feedback
- [ ] State-aware control colors/labels/icons
- [ ] Edge-to-edge safe-area insets
- [ ] Offline/zoom-closed/accessibility overlays; loud, never silent
- [ ] Auto computer-audio (server-side) so no join prompt blocks the user
- [ ] Leave uses a confirm; Starting/Joining/Leaving transitions are smooth
- [ ] Contextual address config (no settings clutter)
- [ ] Verified on-device against a real meeting, every screen screenshotted

## Setup (macOS PC)
1. Grant the launching terminal macOS Accessibility (System Settings → Privacy
   & Security → Accessibility).
2. `./run_server.sh` — prints the LAN URL, e.g. `http://192.168.1.50:8765`.
3. Point each client at that address (same Wi-Fi/LAN).

## Environment used for development/verification
- PC: Mac, Zoom Basic (free) account → meetings inherit the 40-min cap.
- Tablet: Samsung SM-P620 (Android 16), verified over Wi-Fi (wireless adb) and
  USB.
- iPad: iOS Simulator (Xcode).
