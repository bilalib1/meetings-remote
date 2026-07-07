# Legacy — v1 remote-control system (archived, frozen)

This directory holds the **first-generation** project: an Android/iPad tablet
acting as a *remote control* for the official Zoom desktop client running on a
Mac. It works, but it needs a PC in the room. It is **superseded** by the
tablet-only appliance in [`/room`](../room) and is kept here only for
reference — it is not part of the active Gradle build and is not maintained.

- `app/` — Android remote-control console (Kotlin, package `com.bilal.zoomremote`).
- `ios/` — iPad remote-control app.
- `server/` — Python server on the Mac that UI-scripts `zoom.us` via macOS
  Accessibility (`zoom_control_server.py` + controllers).
- `run_server.sh` — launches the Mac server.

Architecture rationale and Zoom-ToS analysis live in
[`/docs/DESIGN.md`](../docs/DESIGN.md) and [`/docs/POLICY.md`](../docs/POLICY.md).

To build the legacy Android app, re-add `include(":app")` (pointing at
`legacy/app`) to the root `settings.gradle.kts`.
