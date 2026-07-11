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

export PATH="$PATH:$HOME/go/bin"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)}"

if [ ! -d "$CLONE/.git" ]; then
  echo "cloning doubletake -> $CLONE"
  git clone --depth 1 "$REPO" "$CLONE"
fi

# Patch 1: let StreamFrames accept any io.Reader (so we can feed MediaCodec
# output through an io.Pipe instead of the GStreamer *ScreenCapture).
perl -0pi -e 's/func \(s \*MirrorSession\) StreamFrames\(ctx context\.Context, capture \*ScreenCapture, startDelay time\.Duration\) error \{/func (s *MirrorSession) StreamFrames(ctx context.Context, capture io.Reader, startDelay time.Duration) error {/' \
  "$CLONE/internal/airplay/mirror.go"

# Patch 2: drop in our gomobile wrapper package.
mkdir -p "$CLONE/mobile"
cp "$HERE/mobile.go" "$CLONE/mobile/mobile.go"

cd "$CLONE"
# gomobile bind requires golang.org/x/mobile in the module graph.
grep -q "golang.org/x/mobile" go.mod || go get golang.org/x/mobile/bind@latest

echo "gomobile bind (android/arm64,arm) ..."
# -extldflags forces 16 KB-aligned ELF LOAD segments so libgojni.so passes
# Play's 16 KB page-size check (B8). Needs the NDK external linker (r27).
gomobile bind -target=android/arm64,android/arm -androidapi 24 \
  -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
  -o "$HERE/airplaysender.aar" ./mobile/

echo "built: $HERE/airplaysender.aar"
ls -la "$HERE/airplaysender.aar"
