#!/usr/bin/env bash
# QA-69 offline AGP source-level compile and test-APK assembly. Device run is not attempted.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
export JAVA_HOME="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
export ANDROID_HOME="C:/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SCRATCH="$(cygpath -m "${TMPDIR:-/tmp}")/qa69_run"
mkdir -p "$SCRATCH"
cd "$ROOT" || exit 1
timeout 900 ./gradlew --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest \
  > "$SCRATCH/agp.txt" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  printf '%b\n' "AGP\tFAIL\trc=$rc"
  exit 1
fi
APK="$(find "$ROOT/app/build/outputs/apk/androidTest/debug" -type f -name '*.apk' 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
  printf '%b\n' "AGP\tFAIL\tapk-missing"
  exit 1
fi
size="$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK" | tr -d ' ')"
printf '%b\n' "AGP\tPASS\tapk_bytes=$size"
exit 0
