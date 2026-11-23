#!/usr/bin/env bash
# QA-25 bounded offline AGP compile/assemble. Never prints provider URLs from build output.
set -euo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SCRATCH="${QA25_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa25}"
mkdir -p "$SCRATCH"
LOG="$SCRATCH/agp-compile.log"

JDK=C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2
if command -v java >/dev/null 2>&1; then JDK="$(cd "$(dirname "$(command -v java)")/.." && native_pwd)"; fi
export JAVA_HOME="$JDK"
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"

SDK="${ANDROID_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03}"
if command -v cygpath >/dev/null 2>&1; then SDK="$(cygpath -m "$SDK")"; fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

set +e
(cd "$ROOT" && timeout 600 ./gradlew --offline --no-daemon \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest) > "$LOG" 2>&1
RC=$?
set -e

if [ "$RC" -ne 0 ]; then
  printf 'AGP\tFAIL\trc=%s\terrors=%s\n' "$RC" \
    "$(grep -c -E 'FAILURE:|error:' "$LOG" || true)"
  exit "$RC"
fi

APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [ ! -f "$APK" ]; then
  printf 'AGP\tFAIL\treason=apk-missing\n'
  exit 1
fi
BYTES="$(stat -c '%s' "$APK")"
printf 'AGP\tPASS\trc=0\tapk_bytes=%s\n' "$BYTES"
