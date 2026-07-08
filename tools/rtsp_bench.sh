#!/bin/bash
# Publish a parametrized synthetic RTSP stream to a running mediamtx on :8554,
# for benchmarking the room app's RTSP decode pipeline. Motion by default
# (testsrc2) so the encoder emits real, non-trivial frames — unlike a static
# still, which lets mediacodec/HW decode idle and hides true throughput.
#
#   ./tools/rtsp_bench.sh [fps] [size] [profile] [extra ffmpeg args...]
#   ./tools/rtsp_bench.sh 30 1280x720 baseline
#   ./tools/rtsp_bench.sh 60 1920x1080 high -bf 2
#
# Assumes mediamtx is already listening on 127.0.0.1:8554 (tools/rtsp_test_stream.sh
# or a prior run started it). Tablet source URL: rtsp://192.168.1.50:8554/test
set -euo pipefail

FPS="${1:-30}"
SIZE="${2:-1280x720}"
PROFILE="${3:-baseline}"
shift $(( $# < 3 ? $# : 3 )) || true

echo "publishing testsrc2 ${SIZE}@${FPS} profile=${PROFILE} extra=[$*]"
exec ffmpeg -hide_banner -loglevel warning \
    -re -f lavfi -i "testsrc2=size=${SIZE}:rate=${FPS}" \
    -c:v libx264 -profile:v "${PROFILE}" -tune zerolatency \
    -g "${FPS}" -pix_fmt yuv420p "$@" \
    -f rtsp rtsp://127.0.0.1:8554/test
