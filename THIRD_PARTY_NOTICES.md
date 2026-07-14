# Third-party notices

## DoubleTake

The Android application includes a modified subset of DoubleTake, Copyright
its contributors, under LGPL-3.0-or-later.

- Upstream: https://github.com/omarroth/doubletake
- Pinned source: commit `364ea84247ce17a084ae15b9011409910e823e34`
  (release v0.4.0)
- License: LGPL-3.0-or-later; the application package includes the LGPLv3 and
  incorporated GPLv3 texts under `assets/licenses/doubletake/`.
- Corresponding source: the pinned upstream tree plus
  `tools/airplay_sender/airplay_keys.go`, `mobile.go`, and `build_aar.sh` in
  this repository reproduce the modified library and combined Android build.

### Modifications

- Replaced the Linux capture input with an `io.Reader` for Android
  MediaCodec output.
- Added the gomobile API wrapper.
- Removed `internal/airplay/fairplay.go` and the entire `internal/fpemu`
  package, including its embedded snapshot.
- Retained only the generic pair-verify stream-key helper in
  `airplay_keys.go`.

This application is independently developed and is not affiliated with or
endorsed by Apple Inc. AirPlay is a trademark of Apple Inc.
