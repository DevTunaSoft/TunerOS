#!/usr/bin/env bash
# QA-27 offline AGP compile/package attempt. No device execution is performed.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH_DEFAULT="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
JH="${JAVA_HOME:-$JH_DEFAULT}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa27-agp\tno JDK 17'
  exit 2
fi
export JAVA_HOME="${JAVA_HOME:-$JH_DEFAULT}"
PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
      "$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03" \
      "$HOME/AppData/Local/Android/Sdk" \
      "$HOME/Android/Sdk"; do
    if [ -f "$candidate/platforms/android-35/android.jar" ]; then
      SDK="$(cygpath -m "$candidate" 2>/dev/null || printf '%s' "$candidate")"
      break
    fi
  done
fi
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  say 'BLOCKED\tqa27-agp\tno local Android SDK platform 35'
  exit 2
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

LOG="${TMPDIR:-/tmp}/qa27-agp.log"
if ! timeout 900 "$JH/java" -Dorg.gradle.daemon=false -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1; then
  say 'FAIL\tqa27-agp\toffline compile failed'
  grep -E '(^FAILURE|What went wrong|error:|Could not|Execution failed)' "$LOG" | head -n 12
  exit 1
fi
say 'PASS\tqa27-agp\toffline compileDebugAndroidTestKotlin + assembleDebugAndroidTest'

APK="$(find "$ROOT/app/build/outputs/apk/androidTest" -type f -name '*androidTest*.apk' 2>/dev/null | head -1)"
if [ -n "$APK" ] && [ -f "$APK" ]; then
  SIZE="$(wc -c < "$APK")"
  say "APK\tbytes=$SIZE"
  AAPT="$(find "$SDK/build-tools" -name aapt.exe -o -name aapt 2>/dev/null | sort | tail -1)"
  if [ -n "$AAPT" ] && "$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null \
      | grep -q 'qa27.Qa27Instrumentation'; then
    say 'APK\tPASS\tinstrumentation-entry-present'
  else
    say 'APK\tUNVERIFIED\tinstrumentation-entry-check-unavailable'
  fi
else
  say 'APK\tUNVERIFIED\tno-test-apk-at-expected-output'
fi
exit 0
