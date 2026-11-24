#!/usr/bin/env bash
# QA-45 offline AGP compile/package attempt. No device execution is performed.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }
redact() { sed -E 's#(https?://|ftp://)[^[:space:]]+#<redacted>#g'; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa45-agp\tno JDK 17'
  exit 2
fi
export JAVA_HOME="${JH%/bin}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
      "C:/Android/Sdk" \
      "$HOME/AppData/Local/Android/Sdk"; do
    if [ -f "$candidate/platforms/android-35/android.jar" ]; then
      SDK="$(cygpath -m "$candidate" 2>/dev/null || printf '%s' "$candidate")"
      break
    fi
  done
fi
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  say 'BLOCKED\tqa45-agp\tno local Android SDK platform 35'
  say 'DEVICE\tBLOCKED\tno authorized adb or emulator target'
  exit 2
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

LOG="${QA45_AGP_LOG:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa45-agp.log}"
timeout 900 "$JH/java" -Dorg.gradle.daemon=false -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  say "FAIL\tqa45-agp\trc=$rc"
  grep -E '(^FAILURE|What went wrong|error:|Could not|Execution failed)' "$LOG" \
    | head -n 12 | redact
  exit 1
fi
say 'PASS\tqa45-agp\toffline compileDebugAndroidTestKotlin and assembleDebugAndroidTest'

APK=$(find "$ROOT/app/build/outputs/apk/androidTest" -type f -name '*androidTest*.apk' 2>/dev/null | head -1)
if [ -n "$APK" ] && [ -f "$APK" ]; then
  SIZE=$(wc -c < "$APK")
  say "APK\tbytes=$SIZE"
  AAPT=$(find "$SDK/build-tools" -name aapt.exe -o -name aapt 2>/dev/null | sort | tail -1)
  if [ -n "$AAPT" ] && "$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null \
      | grep -q 'qa45.Qa45Instrumentation'; then
    say 'APK\tPASS\tinstrumentation-entry-present'
  else
    say 'APK\tUNVERIFIED\tentry-check-unavailable'
  fi
else
  say 'APK\tUNVERIFIED\tno-test-apk-at-expected-path'
fi

ADB="$SDK/platform-tools/adb.exe"
if [ -x "$ADB" ] && timeout 20 "$ADB" devices 2>/dev/null \
    | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' >/dev/null; then
  say 'DEVICE\tNOT RUN\ttarget-present-but-instrumentation-not-executed'
else
  say 'DEVICE\tBLOCKED\tno-authorized-adb-or-emulator-target'
fi
exit 0
