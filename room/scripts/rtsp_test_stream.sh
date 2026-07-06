#!/bin/bash
# Serves a synthetic 720p30 H.264 RTSP stream for testing the room app's
# RTSP camera source without a real camera.
#
#   ./room/scripts/rtsp_test_stream.sh
#   Tablet source URL: rtsp://<mac-lan-ip>:8554/test  (e.g. rtsp://192.168.1.50:8554/test)
#
# Requires: brew install mediamtx ffmpeg
set -euo pipefail

CONF=$(mktemp)
printf 'paths:\n  all_others:\n' > "$CONF"
mediamtx "$CONF" &
MTX=$!
trap 'kill $MTX 2>/dev/null' EXIT
sleep 1

exec ffmpeg -re -f lavfi -i "testsrc2=size=1280x720:rate=30" \
    -c:v libx264 -profile:v baseline -tune zerolatency -g 30 -pix_fmt yuv420p \
    -f rtsp rtsp://127.0.0.1:8554/test
