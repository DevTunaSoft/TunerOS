#!/usr/bin/env bash
# QA-30 offline AGP compile/package attempt. No device PASS is ever claimed here.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JDK_CANDIDATES=(
  "${JAVA_HOME:-}"
  "C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
  "C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2"
)
JH=""
for candidate in "${JDK_CANDIDATES[@]}"; do
  if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then JH="$candidate/bin"; export JAVA_HOME="$candidate"; break; fi
done
if [ -z "$JH" ]; then
  say 'BLOCKED\tqa30-agp\tno JDK 17'
  exit 2
fi
PATH="$JH:$PATH"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  for candidate in \
      "C:/Android/Sdk" \
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
  say 'BLOCKED\tqa30-agp\tno local Android SDK platform 35'
  exit 2
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

LOG="${TMPDIR:-/tmp}/qa30-agp.log"
if ! timeout 900 "$JH/java" -Dorg.gradle.daemon=false -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1; then
  say 'FAIL\tqa30-agp\toffline compile failed'
  grep -E '^FAILURE:|error:|What went wrong' "$LOG" | head -n 6
  exit 1
fi
if ! grep -q 'BUILD SUCCESSFUL' "$LOG"; then
  say 'FAIL\tqa30-agp\tmissing BUILD SUCCESSFUL'
  exit 1
fi
say 'AGP\tPASS\tqa30-androidTest-offline-compile'

APK=$(find "$ROOT/app/build/outputs/apk/androidTest" -type f -name '*.apk' 2>/dev/null | head -1)
if [ -n "$APK" ] && [ -f "$APK" ]; then
  bytes=$(wc -c < "$APK")
  entries=$(unzip -l "$APK" 2>/dev/null | tail -n 1 | awk '{print $2}')
  say "APK\tbytes=$bytes\tentries=$entries"
  if command -v aapt >/dev/null 2>&1 || [ -x "$SDK/build-tools/35.0.0/aapt.exe" ]; then
    AAPT="aapt"; [ -x "$SDK/build-tools/35.0.0/aapt.exe" ] && AAPT="$SDK/build-tools/35.0.0/aapt.exe"
    if "$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -q 'qa30.Qa30Instrumentation'; then
      say 'MANIFEST\tPASS\tqa30-instrumentation-entry'
    else
      say 'MANIFEST\tUNVERIFIED\tqa30-instrumentation-entry'
    fi
  fi
else
  say 'APK\tNOT RUN\tno androidTest APK produced'
fi
exit 0
