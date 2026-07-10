#!/bin/bash
# Publishes the Mac's FaceTime HD camera as a 720p30 H.264 RTSP stream for the
# room app (the "real camera" counterpart of rtsp_test_stream.sh).
#
#   ./tools/rtsp_mac_camera.sh [device-index] [audio-index]
#     device-index  default 0 = FaceTime HD Camera
#     audio-index   default none; pass e.g. 1 (see `ffmpeg -f avfoundation
#                   -list_devices true -i ""`) to mux the Mac mic in as AAC —
#                   this is the "camera with a mic" case the AV-sync estimator
#                   (plan 2026-07-10-audio-and-av-sync) correlates against.
#   Extra latency: LATENCY_MS=500 ./tools/rtsp_mac_camera.sh 0 1
#     buffers the whole stream by ~500 ms (video+audio together, like a slow
#     network path) to exercise sync re-convergence.
#
#   Tablet source URL: rtsp://<mac-lan-ip>:8554/test  (e.g. rtsp://192.168.1.50:8554/test)
#
# Starts mediamtx only if one isn't already listening on :8554.
# Requires: brew install mediamtx ffmpeg
set -euo pipefail

DEV="${1:-0}"
AUDIO="${2:-}"
LATENCY_MS="${LATENCY_MS:-0}"

if ! nc -z 127.0.0.1 8554 2>/dev/null; then
  WORK=$(mktemp -d)
  printf 'paths:\n  all_others:\n' > "$WORK/mediamtx.yml"
  cd "$WORK"   # mediamtx writes auto-generated certs to cwd; keep them out of the repo
  mediamtx "$WORK/mediamtx.yml" &
  MTX=$!
  trap 'kill $MTX 2>/dev/null' EXIT
  sleep 1
fi

INPUT="$DEV"
AOPTS=()
FILTERS=()
if [[ -n "$AUDIO" ]]; then
  INPUT="$DEV:$AUDIO"
  AOPTS=(-c:a aac -b:a 96k -ar 48000 -ac 1)
fi
if [[ "$LATENCY_MS" -gt 0 ]]; then
  # Delay both PTS streams equally: mux stays AV-aligned, arrival is late —
  # exactly what a laggy network looks like to the tablet.
  SEC=$(echo "$LATENCY_MS/1000" | bc -l)
  FILTERS+=(-vf "setpts=PTS+${SEC}/TB")
  [[ -n "$AUDIO" ]] && FILTERS+=(-af "asetpts=PTS+${SEC}/TB")
fi

exec ffmpeg -f avfoundation -framerate 30 -video_size 1280x720 -i "$INPUT" \
    -c:v libx264 -profile:v baseline -tune zerolatency -g 30 -pix_fmt yuv420p \
    "${AOPTS[@]+"${AOPTS[@]}"}" "${FILTERS[@]+"${FILTERS[@]}"}" \
    -f rtsp rtsp://127.0.0.1:8554/test
