#!/bin/bash
# Publishes the Mac's FaceTime HD camera as a 720p30 H.264 RTSP stream for the
# room app (the "real camera" counterpart of rtsp_test_stream.sh).
#
#   ./tools/rtsp_mac_camera.sh [device-index]   # default 0 = FaceTime HD Camera
#   Tablet source URL: rtsp://<mac-lan-ip>:8554/test  (e.g. rtsp://192.168.1.50:8554/test)
#
# Starts mediamtx only if one isn't already listening on :8554.
# Requires: brew install mediamtx ffmpeg
set -euo pipefail

DEV="${1:-0}"

if ! nc -z 127.0.0.1 8554 2>/dev/null; then
  WORK=$(mktemp -d)
  printf 'paths:\n  all_others:\n' > "$WORK/mediamtx.yml"
  cd "$WORK"   # mediamtx writes auto-generated certs to cwd; keep them out of the repo
  mediamtx "$WORK/mediamtx.yml" &
  MTX=$!
  trap 'kill $MTX 2>/dev/null' EXIT
  sleep 1
fi

exec ffmpeg -f avfoundation -framerate 30 -video_size 1280x720 -i "$DEV" \
    -c:v libx264 -profile:v baseline -tune zerolatency -g 30 -pix_fmt yuv420p \
    -f rtsp rtsp://127.0.0.1:8554/test
