# Audio into the meeting + AV sync (2026-07-10)

## Problem

The appliance sends external camera video (RTSP/USB, hundreds of ms of pipeline
latency, drifts with network conditions) but has no audio path of its own — the
tablet mic is routed by the OS through the Zoom SDK's default VoIP capture with
~0 latency, so once audio exists it leads the video badly. We need (a) meeting
audio from the tablet mic, (b) a controllable delay on it, (c) a way to
*estimate* the audio→video offset at runtime, periodically, across cameras and
protocols.

## Research findings (3 agents, 2026-07-10)

- **Zoom Meeting SDK Android has a virtual mic**: `ZoomSDKAudioRawDataHelper
  .setExternalAudioSource(IZoomSDKVirtualAudioMicEvent)`;
  `IZoomSDKAudioRawDataSender.send(ByteBuffer, len, sampleRate)` takes 16-bit
  signed mono little-endian PCM (10 ms chunks recommended). No raw-data license
  needed for *sending*. Gotchas: only send after `onMicStartSend()`; set the
  source **before** joining; a mute/unmute cycle may be needed after connect
  (devforum 96707); Zoom applies its own NS to injected PCM.
- **Sync estimation: GCC-PHAT beats ML.** Cross-correlate tablet-mic audio with
  the *camera's own audio track* (mux-aligned with camera video), with the
  camera audio mapped onto the wall-clock time its same-PTS video leaves for
  Zoom. Yields the full pipeline latency to ~ms accuracy, ~100 ms CPU per
  estimate, no model, works during normal speech. SyncNet-family ML (52 MB+,
  ±600 ms range, 40 ms resolution, face-dependent, never run on-device) is the
  fallback only for mic-less cameras; a flash+tone self-test is the other
  fallback. RTCP SR NTP mapping is unreliable across real cameras.
- Perceptual budget: EBU R37 = audio +40/−60 ms vs video; err audio-late.
  Slew delay changes (crossfade), don't step >20–40 ms audibly.

## Design

```
tablet mic ──AudioRecord(VOICE_COMMUNICATION, 48k mono s16)──► delay ring ──► IZoomSDKAudioRawDataSender
                        │ (tap, undelayed, wall-ns)                 ▲
                        ▼                                           │ setDelayMs (crossfaded)
                  SyncEstimator (GCC-PHAT @16k, every 30s) ◄────────┘
                        ▲
                        │ (camera audio s16 @16k + wall-ns via PTS→video-send mapping)
RTSP cam ──rtsp_decoder.c: audio stream decode + swr 16k mono──► FfmpegVideoSource.audioTap
```

- `audio/MicAudioSource.kt` — virtual mic + delay ring (10 s), crossfade on
  delay change, mic tap for the estimator.
- `rtsp_decoder.c` — also decode the audio stream (AAC/G.711…) → 16 kHz mono
  s16 FIFO with per-frame PTS (µs, mux timeline); expose last *emitted* video
  PTS. Kotlin maps: audio_wall = video_send_wall + (audio_pts − video_pts).
- `audio/SyncEstimator.kt` — two 16 kHz rings on the wall-clock timeline,
  GCC-PHAT over ~8 s window, lag search ±2.5 s, parabolic sub-sample peak,
  peak-ratio + RMS gating, median-of-3, applies delay when >20 ms off.
- `RoomSdk` — registers virtual mic after init (before join), wires estimator
  when the provider is an FfmpegVideoSource with an audio stream, mute/unmute
  kick after VoIP connect.
- No camera audio → estimator idle; delay stays at manual/default
  (`debug.room.audiodelay` ms, test hook `audioDelay`). Flash+tone self-test
  and ML lip-sync: future work.

## Test plan

- Mac streams camera+mic via mediamtx (`tools/rtsp_mac_camera.sh` grows an
  audio flag). Tablet joins PMI; Mac browser participant (per two-party
  automation memory) listens.
- Verify: virtual mic sends (logcat MicAudioSource/onMicStartSend), far end
  hears; estimator log shows offset within camera pipeline latency and
  confident peaks; `audioStats` hook dumps state.
- Sync sanity: speak near both tablet and Mac mic; offset estimate should
  match measured video latency (clock-in-frame). Artificial latency knob on
  the Mac stream to test re-convergence.

## Status

- [x] research (SDK API, sync methods)
- [x] MicAudioSource + RoomSdk wiring
- [x] native camera-audio decode + taps
- [x] SyncEstimator (GCC-PHAT)
- [x] hooks + tools
- [x] e2e on tablet — verified 2026-07-10 (see findings below)
- [ ] far-end audible check (Mac locked itself mid-test; SDK-side evidence
      only: send()=Success continuously, participant audioMuted=false.
      `audioStats` now dumps `zoomSend` bandwidth — needs a 2nd participant
      to go nonzero, re-check when a far end is available)

## E2E findings (2026-07-10, SM-P620 + Mac ffmpeg/mediamtx camera+mic)

- **Registration timing**: `setExternalAudioSource` right after SDK init
  returns `MobileRTCRawData_Uninitialized`; registering at audio-connect time
  (in-meeting) returns Success and `onMicInitialize/onMicStartSend` fire.
  RoomSdk now registers at both points.
- **Samsung NS gates non-speech to digital zero** (micRms=0 even with room
  audio). NS is now created-but-disabled on our AudioRecord; AEC stays on.
  Zoom's own NS still applies to sent PCM.
- **Timeline jitter kills correlation**: per-frame wall anchors for camera
  audio (bursty frame arrivals, dt max >100 ms) and AudioRecord read-return
  times both smear the GCC-PHAT ridge. Fixed with a min-filtered PTS→wall
  mapping (FfmpegVideoSource) and an anchored sample-counter clock
  (MicAudioSource).
- **Reverb spreads the true peak ~±30 ms** — the peak-ratio guard must be
  wide (80 ms) or the ridge's own shoulders read as rival peaks.
- **Test-signal traps**: macOS `say` repeats words as identical waveforms →
  false correlation peaks at repetition lags (worst case a stable phantom at
  -730 ms). Use non-repeating dictionary words with randomized voice/rate, or
  human speech. Synthetic noise doesn't survive device NS.
- **setpts does NOT simulate network latency** (shifts timestamps, not
  arrival, A/V equally) — `tools/rtsp_delayed_relay.sh` (store-and-forward
  pipe, `tools/delay_pipe.py`) is the real thing.
- **Measured**: direct LAN stream offset 155–182 ms across runs (applied
  124–173 ms per-connect variance); with the 600 ms relay the estimator
  re-converged and applied 889 ms. CPU ~120–220 ms per estimate (6 s window,
  ±2.5 s search, Kotlin FFT). Gates: peakRatio ≥1.35 + floorRatio ≥8 for
  instant accept (garbage windows measured 1.06–1.19); 3 consecutive
  borderline windows within 250 ms accept their median (jittery transports).
