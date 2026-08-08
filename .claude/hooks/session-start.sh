#!/bin/bash
# SessionStart hook: provision the Android SDK for Claude Code on the web.
#
# Remote sessions start from a bare container with a JDK but no Android SDK, so
# every Gradle task fails until one is installed. This installs the command line
# tools, the platform and build-tools matching compileSdk, then points the build
# at them via local.properties. Local machines are left alone.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# Pinned so a session never picks up a tools release the build has not seen.
# Bump from https://developer.android.com/studio#command-line-tools-only
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"

# Track compileSdk instead of hardcoding it, so bumping the build file is enough.
COMPILE_SDK="$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' \
  "$PROJECT_DIR/app/build.gradle.kts" | head -1)"
if [ -z "$COMPILE_SDK" ]; then
  echo "session-start: could not read compileSdk from app/build.gradle.kts" >&2
  exit 1
fi
BUILD_TOOLS="$COMPILE_SDK.0.0"

if [ ! -x "$SDKMANAGER" ]; then
  echo "session-start: installing Android command line tools"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  # The zip unpacks to cmdline-tools/; sdkmanager requires it be named "latest"
  # so it can locate the SDK root two levels up.
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

# sdkmanager re-prompts for licenses it has not recorded; "yes" covers a first
# run and is a no-op once the accepted-license hashes are on disk.
yes 2>/dev/null | "$SDKMANAGER" --licenses > /dev/null || true

echo "session-start: installing platform $COMPILE_SDK / build-tools $BUILD_TOOLS"
"$SDKMANAGER" --install \
  "platform-tools" \
  "platforms;android-$COMPILE_SDK" \
  "build-tools;$BUILD_TOOLS" > /dev/null

# local.properties is gitignored, so it has to be regenerated each session.
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export PATH=\"\$PATH:$ANDROID_HOME/platform-tools\""
  } >> "$CLAUDE_ENV_FILE"
fi

# gradlew ships mode 100644 in some checkouts; Gradle is unusable without this.
chmod +x "$PROJECT_DIR/gradlew"

echo "session-start: Android SDK ready at $ANDROID_HOME"
