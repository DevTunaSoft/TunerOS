#!/usr/bin/env bash
# QA-13 offline AGP compile attempt. Device execution is deliberately NOT attempted: without an
# attached authorized device/emulator it stays BLOCKED/UNVERIFIED. No config file is modified.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa13-agp\tno JDK 17'
  exit 2
fi
export JAVA_HOME="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
      "$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03" \
      "$HOME/AppData/Local/Android/Sdk" \
      "$HOME/Android/Sdk" \
      "C:/Program Files/Android/Android Studio/sdk" \
      "C:/Android/Sdk"; do
    if [ -f "$candidate/platforms/android-35/android.jar" ]; then SDK="$candidate"; break; fi
  done
fi
if [ -z "$SDK" ]; then
  SDK=$(find "$HOME/AppData/Local" "$HOME/.gradle" -maxdepth 6 \
      -path '*/platforms/android-35/android.jar' -print -quit 2>/dev/null | sed 's#/platforms/android-35/android.jar##')
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then SDK="$(cd "$SDK" && native_pwd)"; fi
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  say 'BLOCKED\tqa13-agp\tno Android SDK platform 35 found locally'
  say 'DEVICE\tBLOCKED\tno authorized emulator or adb device was used'
  exit 2
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

LOG="${TMPDIR:-/tmp}/qa13-agp.log"
if ! timeout 600 "$JH/java" -Dorg.gradle.daemon=false -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1; then
  say 'FAIL\tqa13-agp\toffline compile failed; see scratch qa13-agp.log'
  grep -E '(^FAILURE|What went wrong|error:|Could not|Execution failed)' "$LOG" | head -n 12
  exit 1
fi
say 'PASS\tqa13-agp\toffline compileDebugAndroidTestKotlin + assembleDebugAndroidTest'

APK=$(find "$ROOT/app/build/outputs/apk/androidTest" -name '*androidTest*.apk' -o -name '*debug-androidTest.apk' 2>/dev/null | head -1)
if [ -n "$APK" ] && [ -f "$APK" ]; then
  SIZE=$(wc -c < "$APK")
  say "APK\tbytes=$SIZE\tpath=scratch-build-output"
  AAPT=$(find "$SDK/build-tools" -name aapt.exe -o -name aapt 2>/dev/null | sort | tail -1)
  if [ -n "$AAPT" ]; then
    if "$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -q 'qa13.Qa13Instrumentation'; then
      say 'APK\tPASS\tinstrumentation-entry-present'
    else
      say 'APK\tFAIL\tinstrumentation-entry-missing'
      exit 1
    fi
  fi
else
  say 'APK\tUNVERIFIED\tno test APK found at expected output path'
fi
say 'DEVICE\tBLOCKED\tno authorized emulator or adb device was used'
exit 0
