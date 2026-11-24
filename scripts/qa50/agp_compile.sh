#!/usr/bin/env bash
# QA-50 offline AGP compile/package attempt plus an honest device probe.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"
if [ ! -x "$JH/bin/java" ]; then
  say 'AGP\tBLOCKED\tqa50-no-jdk'
  exit 2
fi
export JAVA_HOME="$(native_path "$JH")"
if [ -z "${ANDROID_HOME:-}" ] || [ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]; then
  if [ -f "C:/Android/Sdk/platforms/android-35/android.jar" ]; then
    export ANDROID_HOME="C:/Android/Sdk"
  fi
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]; then
  say 'AGP\tBLOCKED\tqa50-no-android-35-sdk'
  exit 2
fi

export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"
cd "$ROOT" || exit 2
LOG="$(native_path "${TMPDIR:-/tmp}/qa50-agp-$$.log")"
timeout 900 java -Dorg.gradle.daemon=false -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  say "AGP\tFAIL\trc=$rc"
  grep -E '^e: |FAILURE:|What went wrong|Execution failed|error:' "$LOG" | head -n 24
  exit 1
fi
say 'AGP\tPASS\toffline-compileDebugAndroidTestKotlin+assembleDebugAndroidTest'

APK=$(find "$ROOT/app/build/outputs/apk/androidTest" -type f -name '*debug-androidTest.apk' 2>/dev/null | head -1)
if [ -n "$APK" ]; then
  APK_N="$(native_path "$APK")"
  SIZE=$(wc -c < "$APK" | tr -d ' ')
  SHA=$(sha256sum "$APK" | awk '{print $1}')
  say "APK\tPASS\tbytes=$SIZE\tsha256=$SHA"
  AAPT=$(find "$ANDROID_HOME/build-tools" -type f -name 'aapt.exe' | LC_ALL=C sort | tail -1)
  if [ -n "$AAPT" ]; then
    AAPT_N="$(native_path "$AAPT")"
    if "$AAPT_N" dump xmltree "$APK_N" AndroidManifest.xml 2>/dev/null | grep -q 'qa50.Qa50Runner'; then
      say 'MANIFEST\tPASS\tqa50-instrumentation-entry'
    else
      say 'MANIFEST\tFAIL\tqa50-instrumentation-entry-not-found'
    fi
  else
    say 'MANIFEST\tUNVERIFIED\tno-aapt'
  fi
else
  say 'APK\tFAIL\tnot-found'
fi

ADB="$ANDROID_HOME/platform-tools/adb"
if [ -x "$ADB" ]; then
  if "$ADB" devices 2>/dev/null | grep -Eq '^([A-Za-z0-9_-]+)[[:space:]]+device$'; then
    say 'DEVICE\tUNVERIFIED\tan-authorized-adb-target-is-present-but-was-not-executed'
  else
    say 'DEVICE\tBLOCKED\tno-authorized-adb-target'
  fi
else
  say 'DEVICE\tBLOCKED\tno-adb'
fi
exit 0
