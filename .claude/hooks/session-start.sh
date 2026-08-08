#!/bin/bash
set -euo pipefail

# Setup for Claude Code on the web: install the Android SDK and warm the
# Gradle wrapper so builds, tests, and detekt work in remote sessions.

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_SDK_DIR="${HOME}/android-sdk"
CMDLINE_TOOLS_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

# 1. Android command-line tools (idempotent)
if [ ! -x "${ANDROID_SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "${ANDROID_SDK_DIR}/cmdline-tools"
  tmp_zip="$(mktemp /tmp/cmdline-tools-XXXXXX.zip)"
  if ! curl -fsSL "${CMDLINE_TOOLS_ZIP_URL}" -o "${tmp_zip}"; then
    echo "ERROR: could not download the Android command-line tools." >&2
    echo "The cloud environment's network policy must allow dl.google.com" >&2
    echo "(environment settings -> Network access -> Custom -> add dl.google.com," >&2
    echo "keeping the default package-manager allowlist enabled)." >&2
    exit 1
  fi
  unzip -q -o "${tmp_zip}" -d "${ANDROID_SDK_DIR}/cmdline-tools"
  rm -f "${tmp_zip}"
  rm -rf "${ANDROID_SDK_DIR}/cmdline-tools/latest"
  mv "${ANDROID_SDK_DIR}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_DIR}/cmdline-tools/latest"
fi

# 2. SDK packages required by the build (compileSdk 36)
yes | "${ANDROID_SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
"${ANDROID_SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" --install \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" > /dev/null

# 3. Point Gradle at the SDK
echo "sdk.dir=${ANDROID_SDK_DIR}" > "${CLAUDE_PROJECT_DIR}/local.properties"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"${ANDROID_SDK_DIR}\""
    echo "export ANDROID_SDK_ROOT=\"${ANDROID_SDK_DIR}\""
  } >> "${CLAUDE_ENV_FILE}"
fi

# 4. Warm the Gradle wrapper and dependency cache (cached in the container image)
cd "${CLAUDE_PROJECT_DIR}"
./gradlew --no-daemon help > /dev/null

echo "Android SDK ready at ${ANDROID_SDK_DIR}"
