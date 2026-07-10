#!/usr/bin/env bash
# Re-runnable identifier rename for the "Zoom Room" -> "Meetings Remote" de-risk.
# Rewrites the Kotlin package/import/FQN identifier com.bilal.zoomroom ->
# com.bilal.meetingsremote and the matching JNI symbol prefix in the C source.
# Idempotent: running twice is a no-op. Does NOT touch user-visible strings
# ("Zoom Room" -> "Meetings Remote"/"Meeting Room") — those are decided per
# surface in the plan and edited by hand.
#
# Usage: tools/rename_package.sh
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Kotlin identifier: com.bilal.zoomroom -> com.bilal.meetingsremote
grep -rl --include='*.kt' 'com\.bilal\.zoomroom' room/src \
  | xargs -r sed -i '' 's/com\.bilal\.zoomroom/com.bilal.meetingsremote/g'

# JNI symbol prefix: Java_com_bilal_zoomroom_ -> Java_com_bilal_meetingsremote_
grep -rl --include='*.c' 'Java_com_bilal_zoomroom_' room/src \
  | xargs -r sed -i '' 's/Java_com_bilal_zoomroom_/Java_com_bilal_meetingsremote_/g'

echo "rename_package.sh: done. Remaining zoomroom identifier hits in room/src:"
grep -rn 'com\.bilal\.zoomroom\|Java_com_bilal_zoomroom_' room/src || echo "  (none)"
