#!/usr/bin/env python3
"""Measure the room app's outgoing video FPS at each pipeline stage from logcat.

Clears logcat, samples for DURATION seconds, then computes FPS from the frame
counters the app already logs:
  - decode+emit : FfmpegVideoSource "emitted N frames"  (post-decode, post-pacer)
  - send-to-zoom: ExternalVideoSource "sendVideoFrame #N" (handed to the SDK)

FPS = (last_counter - first_counter) / (last_ts - first_ts), so it's immune to
where in the window the samples land.

  ./tools/measure_fps.py [duration_s]
"""
import re
import subprocess
import sys
import time

ADB = "/Users/bilalibrahim/Library/Android/sdk/platform-tools/adb"
DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 20

TS = r"^\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d\d\d)"
PATTERNS = {
    "send-to-zoom (SDK)": re.compile(TS + r".*sendVideoFrame #(\d+) (\d+)x(\d+)"),
    "decode+emit":        re.compile(TS + r".*emitted (\d+) frames (\d+)x(\d+)"),
}


def secs(m):
    h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
    return h * 3600 + mi * 60 + s + ms / 1000.0


def main():
    subprocess.run([ADB, "logcat", "-c"], check=True)
    print(f"sampling {DURATION}s...", flush=True)
    time.sleep(DURATION)
    out = subprocess.run(
        [ADB, "logcat", "-d", "-s", "FfmpegVideoSource", "ExternalVideoSource", "ZoomStats"],
        capture_output=True, text=True, check=True).stdout

    # Zoom's own encoder stat (already an fps, not a counter): average the samples.
    zoom = [int(m.group(1)) for m in
            re.finditer(r"video send=(\d+)fps", out)]
    if zoom:
        print(f"{'zoom encoder (wire)':24s}: {sum(zoom)/len(zoom):5.1f} fps  "
              f"({len(zoom)} samples, min={min(zoom)} max={max(zoom)})")
    else:
        print(f"{'zoom encoder (wire)':24s}: no samples (not in a meeting?)")

    for label, pat in PATTERNS.items():
        pts = []
        for line in out.splitlines():
            m = pat.search(line)
            if m:
                pts.append((secs(m), int(m.group(5)), m.group(6), m.group(7)))
        if len(pts) < 2:
            print(f"{label:24s}: <2 samples ({len(pts)}) — not enough data")
            continue
        dt = pts[-1][0] - pts[0][0]
        dn = pts[-1][1] - pts[0][1]
        fps = dn / dt if dt > 0 else 0
        wh = f"{pts[-1][2]}x{pts[-1][3]}"
        print(f"{label:24s}: {fps:5.1f} fps  ({dn} frames / {dt:.1f}s, {wh})")


if __name__ == "__main__":
    main()
