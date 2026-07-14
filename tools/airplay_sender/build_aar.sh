#!/usr/bin/env bash
# Build airplaysender.aar — doubletake's AirPlay-2 mirror sender, wrapped for
# Android via gomobile. Reproducible: clones doubletake, applies our patch +
# mobile wrapper, and binds an AAR.
#
# Prereqs (macOS): brew install go gst-plugins-ugly gst-libav
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   go install golang.org/x/mobile/cmd/gobind@latest
#   Android SDK + NDK (set ANDROID_HOME / ANDROID_NDK_HOME below).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CLONE="${HERE}/../doubletake"           # gitignored working clone
REPO="https://github.com/omarroth/doubletake"
OUTPUT="${HERE}/../../room/libs/airplaysender.aar"
# v0.4.0: first release explicitly documented as LGPL-3.0-or-later.
# Never build a release artifact from a moving branch.
DOUBLETAKE_COMMIT="364ea84247ce17a084ae15b9011409910e823e34"
GOMOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"

export PATH="$PATH:$HOME/go/bin"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)}"

if [ ! -d "$CLONE/.git" ]; then
  echo "cloning doubletake -> $CLONE"
  git clone "$REPO" "$CLONE"
fi
git -C "$CLONE" fetch origin "$DOUBLETAKE_COMMIT"
git -C "$CLONE" checkout --detach "$DOUBLETAKE_COMMIT"
git -C "$CLONE" reset --hard "$DOUBLETAKE_COMMIT"
git -C "$CLONE" clean -fdx

# This Android sender supports receivers that derive stream keys from
# pair-verify (our Roku). Do not distribute DoubleTake's FairPlay emulator or
# its embedded binary snapshot. fairplay.go is the only production import of
# internal/fpemu; removing both makes accidental inclusion a build failure.
rm -f "$CLONE/internal/airplay/fairplay.go"
rm -rf "$CLONE/internal/fpemu"
cp "$HERE/airplay_keys.go" "$CLONE/internal/airplay/airplay_keys.go"

# Patch 1: let StreamFrames accept any io.Reader (so we can feed MediaCodec
# output through an io.Pipe instead of the GStreamer *ScreenCapture).
perl -0pi -e 's/func \(s \*MirrorSession\) StreamFrames\(ctx context\.Context, capture \*ScreenCapture, startDelay time\.Duration\) error \{/func (s *MirrorSession) StreamFrames(ctx context.Context, capture io.Reader, startDelay time.Duration) error {/' \
  "$CLONE/internal/airplay/mirror.go"

# Patch 2: drop in our gomobile wrapper package.
mkdir -p "$CLONE/mobile"
cp "$HERE/mobile.go" "$CLONE/mobile/mobile.go"

cd "$CLONE"
# gomobile bind requires golang.org/x/mobile in the module graph.
grep -q "golang.org/x/mobile" go.mod || go get "golang.org/x/mobile/bind@$GOMOBILE_VERSION"

# Accompany the Android application with the precise LGPL and incorporated GPL
# terms governing the pinned DoubleTake source.
LICENSE_DIR="${HERE}/../../room/src/main/assets/licenses/doubletake"
mkdir -p "$LICENSE_DIR"
cp "$CLONE/LICENSE" "$LICENSE_DIR/LGPL-3.0.txt"
cp "$CLONE/COPYING.GPL" "$LICENSE_DIR/GPL-3.0.txt"

echo "gomobile bind (android/arm64,arm) ..."
mkdir -p "$(dirname "$OUTPUT")"
# -extldflags forces 16 KB-aligned ELF LOAD segments so libgojni.so passes
# Play's 16 KB page-size check (B8). Needs the NDK external linker (r27).
gomobile bind -target=android/arm64,android/arm -androidapi 24 \
  -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
  -o "$OUTPUT" ./mobile/

# Defense in depth: reject the artifact if FairPlay emulator symbols or the
# snapshot package ever return through another dependency.
if unzip -p "$OUTPUT" jni/arm64-v8a/libgojni.so \
    | strings | grep -Eq 'doubletake/internal/fpemu|FPSAPExchangeM3|snapshotData'; then
  echo "ERROR: FairPlay emulator material is present in airplaysender.aar" >&2
  exit 1
fi

echo "built: $OUTPUT"
ls -la "$OUTPUT"
