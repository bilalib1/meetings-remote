#!/bin/bash
# Republishes rtsp://127.0.0.1:8554/test as rtsp://<mac>:8554/delayed with a
# REAL transport delay (store-and-forward pipe) — simulates a laggy network
# path for AV-sync re-convergence testing. Note: setpts/asetpts can NOT do
# this (they shift timestamps, not arrival, and shift A/V equally so the mux
# relationship the sync estimator measures is unchanged — found 2026-07-10).
#
#   ./tools/rtsp_delayed_relay.sh [delay-seconds]   # default 0.6
#   Tablet source URL: rtsp://<mac-lan-ip>:8554/delayed
set -euo pipefail
DELAY="${1:-0.6}"
HERE="$(cd "$(dirname "$0")" && pwd)"

ffmpeg -v warning -rtsp_transport tcp -i rtsp://127.0.0.1:8554/test \
    -c copy -f nut - \
  | python3 "$HERE/delay_pipe.py" "$DELAY" \
  | ffmpeg -v warning -f nut -i - -c copy -f rtsp rtsp://127.0.0.1:8554/delayed
