# airplay_sender — AirPlay-2 mirror sender for the tablet

Wraps [doubletake](https://github.com/omarroth/doubletake)'s proven AirPlay-2
mirror sender (Go) into an Android AAR via gomobile. This is the low-latency,
dongle-free "Cast to TV" path — validated against the room's TCL Roku (test
pattern confirmed on screen, 2026-07-08).

## Why this shape

The whole AirPlay protocol (SRP pairing, pair-verify, ChaCha20 stream
encryption, NTP timing, type-110 packetization) is hard to get byte-perfect —
a wrong key derivation cost this project weeks of a false "FairPlay dead end".
So we reuse doubletake's working Go code verbatim and only replace its Linux
capture layer with Android's MediaProjection → MediaCodec.

**This TCL Roku does NOT use FairPlay** (`FPSAP` feature bit not advertised);
media is ChaCha20-Poly1305 keyed from pair-verify. The reproducible build pins
DoubleTake v0.4.0 (`364ea84247ce17a084ae15b9011409910e823e34`) and deletes
`internal/airplay/fairplay.go` plus `internal/fpemu` before compilation. The
build also scans the finished AAR and fails if emulator symbols return.

DoubleTake is licensed LGPL-3.0-or-later. Its exact upstream source is at
https://github.com/omarroth/doubletake/tree/364ea84247ce17a084ae15b9011409910e823e34;
this directory contains the complete, reproducible modifications used for the
Android build. The application source is AGPL-3.0 and permits rebuilding and
relinking with a modified version of the library.

## Files

- `mobile.go` — the gomobile wrapper package (canonical copy; `build_aar.sh`
  copies it into the clone, since the clone is gitignored).
- `airplay_keys.go` — the non-FairPlay stream-key helper retained from
  upstream's mixed-purpose `fairplay.go`.
- `build_aar.sh` — clone doubletake, apply the `StreamFrames(io.Reader)` patch,
  drop in `mobile.go`, `gomobile bind` → `airplaysender.aar`.
- `../../room/libs/airplaysender.aar` — build output consumed by Gradle; run
  the script to reproduce it.

## Go API (exposed to Kotlin as `mobile.Mobile`)

```
Session Start(String host, long port, String pairingID, byte[] ed25519Seed, long fps, long bitrate)
        session.writeH264(byte[] annexB)   // push MediaCodec output
        session.stop()
```

Pairing is assumed to already exist. Current creds (from the earlier pyatv
pairing, reused by doubletake on the Mac): pairingID
`593fe6bf-e58e-4e53-a7ef-b62ee7508a20`, ed25519 seed = the 32-byte `ltsk` in
`tools/airplay_proto/creds.json`, receiver deviceID `5D:19:23:22:04:83`
(`192.168.1.233`). A one-time in-app PIN pairing will replace this later.

## Android side (TODO — step 11)

1. `implementation files("airplaysender.aar")` in `room/build.gradle`.
2. MediaProjection → VirtualDisplay → Surface → MediaCodec (`video/avc`,
   Annex-B, ~4 Mbps, 30 fps, keyframe interval ~1s).
3. Drain MediaCodec output buffers → `session.writeH264(bytes)`.
4. Wire the existing Cast button → `Start(...)`; store creds in DataStore.

## Rebuild

```sh
./build_aar.sh
```
