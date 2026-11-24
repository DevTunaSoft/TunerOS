#!/usr/bin/env bash
# QA-57 offline AGP compile/assembly of the instrumented mirror. No device invocation.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SCRATCH_N="C:/Users/X1/AppData/Local/hermes/cache/scratch"
LOG="$SCRATCH_N/qa57-agp-compile-$$.log"
export JAVA_HOME="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
export ANDROID_HOME="C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
cd "$ROOT" || exit 2

timeout 900 ./gradlew --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  say "FAIL\tqa57-agp-compile\trc=$rc\tlog=$LOG"
  grep -E 'error:|FAILURE|What went wrong' "$LOG" | head -n 20
  exit 1
fi

APK=$(find "$ROOT/app/build/outputs/apk/androidTest" -name '*debug-androidTest.apk' -type f 2>/dev/null | head -1)
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  say 'FAIL\tqa57-agp-apk-missing'
  exit 1
fi
APK_BYTES=$(wc -c < "$APK" | awk '{print $1}')
APK_SHA=$(sha256sum "$APK" | awk '{print $1}')
say "AGP\tPASS\toffline-compile-and-assemble\tapk_bytes=$APK_BYTES\tapk_sha256=$APK_SHA"

MANIFEST=$(unzip -p "$APK" AndroidManifest.xml 2>/dev/null | tr -d '\000' | grep -c 'Qa57Instrumentation' || true)
if [ "$MANIFEST" -lt 1 ]; then
  say 'FAIL\tqa57-agp-instrumentation-entry'
  exit 1
fi
say 'INSTRUMENTATION_ENTRY\tPASS\tcom.devtunasoft.tuneros.qa57.Qa57Instrumentation'

if command -v "$ANDROID_HOME/platform-tools/adb" >/dev/null 2>&1; then
  DEVICES=$("$ANDROID_HOME/platform-tools/adb" devices 2>/dev/null | grep -c 'device$' || true)
else
  DEVICES=0
fi
if [ "$DEVICES" -gt 0 ]; then
  say 'DEVICE\tBLOCKED\tauthorized-adb-target-not-declared-for-this-task'
else
  say 'DEVICE\tBLOCKED\tno-authorized-adb-target'
fi
say 'INSTRUMENTED_EXECUTION\tNOT_RUN'
say 'VERDICT\tPASS\tqa57-offline-agp-compile'
exit 0
