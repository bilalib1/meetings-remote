#!/bin/bash
# Phase-locked ML lip-sync test rig (plan 2026-07-10-audio-and-av-sync Q1).
#
# Streams a talking-head clip as VIDEO-ONLY RTSP (a mic-less camera) while
# playing the SAME clip's AUDIO out the Mac speaker, both looped and started
# together so what the tablet decodes and what its mic hears advance in
# lockstep. The offset MlSyncEstimator measures is then the (stable) video
# pipeline latency; add EXTRA_MS to shift it by a known amount and verify the
# delta. Video is padded so the face is a realistic fraction of frame
# (BlazeFace won't lock a face filling 100% of frame).
#
#   ./tools/mlsync_test_rig.sh [clip.avi] [EXTRA_MS]
#   Tablet source URL: rtsp://<mac-lan-ip>:8554/mlface
# Requires: mediamtx + ffmpeg (started if :8554 is free), afplay.
set -euo pipefail
CLIP="${1:-/private/tmp/claude-501/-Users-bilalibrahim-code-zoom-custom-android/79e08eb3-7478-40d6-949d-4bba86552173/scratchpad/mlsync/data_example.avi}"
EXTRA_MS="${2:-0}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WAV="$(mktemp -t mlface).wav"
ffmpeg -y -i "$CLIP" -ac 1 -ar 44100 "$WAV" >/dev/null 2>&1

if ! nc -z 127.0.0.1 8554 2>/dev/null; then
  WORK=$(mktemp -d); printf 'paths:\n  all_others:\n' > "$WORK/mediamtx.yml"
  ( cd "$WORK" && mediamtx "$WORK/mediamtx.yml" ) & sleep 1
fi

cleanup() { kill $VPID $APID 2>/dev/null || true; rm -f "$WAV"; }
trap cleanup EXIT

# Video: loop the clip, pad the 224 face into 1280x720 (~30% of frame).
VF="scale=320:320,pad=1280:720:480:200:gray"
ffmpeg -stream_loop -1 -re -i "$CLIP" -an \
    -c:v libx264 -profile:v baseline -tune zerolatency -g 25 -r 25 -pix_fmt yuv420p \
    -vf "$VF" -f rtsp rtsp://127.0.0.1:8554/mlface >/dev/null 2>&1 &
VPID=$!

# Audio: loop the same clip's audio, started ~now (phase-locked to the video
# loop start). EXTRA_MS of leading silence shifts the phase by a known amount.
( if [ "$EXTRA_MS" -gt 0 ]; then
    SIL="$(mktemp -t mlsil).wav"
    ffmpeg -y -f lavfi -i "anullsrc=r=44100:cl=mono" -t "$(echo "$EXTRA_MS/1000" | bc -l)" "$SIL" >/dev/null 2>&1
    afplay "$SIL"; rm -f "$SIL"
  fi
  while true; do afplay "$WAV"; done ) &
APID=$!

echo "rig up: rtsp://<mac>:8554/mlface  extra_ms=$EXTRA_MS  (Ctrl-C to stop)"
wait $VPID
