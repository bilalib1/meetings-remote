# Android release-readiness torture test — 2026-07-13

**Verdict: NO-GO.** Tested commit `airplay-cast` on a Samsung SM-P620, Android 16/API 36, 1200×2000, using the debug APK against the configured live Zoom account/backend. App code was not changed.

## Release blockers

### RR-01 — Critical — Launcher relaunch strands an active meeting

1. Start a meeting and wait for `MeetingActivity` (one host participant).
2. Press Home.
3. Launch **Meetings Remote** from the launcher (`monkey -p com.bilal.meetingsremote 1` reproduced it).

**Actual:** `MainActivity` replaces/removes `MeetingActivity` and shows “Ready to meet,” while the SDK remains `MEETING_STATUS_INMEETING`. Back exits to a stale Chrome Custom Tab. There is no route back to meeting controls, so the operator cannot mute/video/leave. Starting again returns `Couldn't start the meeting / Error 101`; five fast taps triggered four concurrent host-auth refreshes. Screenshot: [relaunch-then-start-error.png](test-artifacts/2026-07-13-release-readiness/relaunch-then-start-error.png).

**Recommendation:** On every `MainActivity` create/new intent/resume, if `RoomSdk.isInMeeting()`, route directly to a single existing/new `MeetingActivity`; clean stale Custom Tabs from the task. Reject start/join before any backend call while already in a meeting, and disable/debounce action cards synchronously.

### RR-02 — High — Microphone denial looks unmuted but sends no audio

1. Revoke Camera, Microphone, and Notifications; cold-launch.
2. Tap **Don't allow** for Camera and Microphone.
3. Start a meeting.

**Actual:** Hosting succeeds and the control says **Mute** (the UI represents the room as unmuted), but AudioSystem logs `EX_SECURITY ... missing perms for source 7`; no user-facing warning appears. Camera denial likewise permits **Stop video** while the configured RTSP source is offline, making device-camera permission intent unclear. Evidence: [in-meeting-permissions-denied.png](test-artifacts/2026-07-13-release-readiness/in-meeting-permissions-denied.png) and `rapid-start-logcat.txt` (22:16:58).

**Recommendation:** Block or explicitly downgrade meeting entry when RECORD_AUDIO is denied; show a persistent “Microphone permission required / Open settings” state and make the mute label reflect actual send capability. Request CAMERA only when the selected source needs it.

### RR-03 — High — AirPlay service ANR during active-cast cleanup

While an earlier hosted meeting/cast was active, the debug leave hook was sent and an APK reinstall was started. Android reported:

`ANR in com.bilal.meetingsremote — executing service .AirPlayService, waited 30006ms`

The system then showed both a stale MediaProjection consent dialog and “Meetings Remote isn't responding.” A later clean cold launch completed in 1.8 s, so this is specific to active AirPlay/service teardown, not ordinary startup. Evidence: [startup-anr.png](test-artifacts/2026-07-13-release-readiness/startup-anr.png) and `startup-anr-logcat.txt` lines 8392–8395.

**Recommendation:** ensure AirPlay service commands return immediately, move blocking sender teardown off the main/service callback, cancel pending projection prompts, and add an instrumentation test for leave/stop/reinstall (or process death) while projection is pending/active.

### RR-04 — High — Release artifact is signed with the Android debug key

`apksigner verify --print-certs room-release.apk` reports `C=US, O=Android, CN=Android Debug` (SHA-256 `ec67e82d…e8a9c93`). This artifact is not suitable as the production upload lineage.

**Recommendation:** fail release/bundle tasks when upload-key properties are absent; do not silently fall back to `~/.android/debug.keystore`.

## Other results

- **Passed:** `:room:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `lintDebug`, and release lint-vital. APK 16-KiB zip alignment passed. Lint completed with **62 warnings** (no errors); triage before store submission.
- **Passed:** live host flow, participant roster, camera-offline indicator, 10–11 rapid mute/video taps, Home/background/resume while resuming the existing task, empty meeting-ID rejection, Back from dialogs/transition, and repeated title taps.
- **Configuration:** forced portrait could not be exercised on this Samsung because the activity remained landscape despite locked user rotation; the captured `home-portrait.png` is still landscape and is not evidence of portrait support.
- **Limited/not exercised:** second remote participant/video, passcode/waiting-room/auth cancellation, recording/archive consent, successful RTSP recovery, AirPlay receiver pairing/cast success, network loss, Bluetooth, and true process-death restoration. These need dedicated accounts/receiver/network control before release.
- **Privacy/accessibility note:** the configured RTSP URL is displayed in clear text in the hidden camera dialog; embedded credentials would be visible. Most icon-only/self-preview controls have no accessibility description in UIAutomator output.

Artifacts are under [`docs/test-artifacts/2026-07-13-release-readiness/`](test-artifacts/2026-07-13-release-readiness/). Full logs contain noisy device-wide Android output but no intentionally added credentials.
