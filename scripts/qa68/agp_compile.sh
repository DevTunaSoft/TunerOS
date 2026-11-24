#!/usr/bin/env bash
# QA-68 offline AGP compile/assembly. No device execution is claimed here.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JDK="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
SDK="C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03"
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"
LOG="$(cygpath -m "${TMPDIR:-/tmp}")/qa68_agp.log"
cd "$ROOT" || exit 1
timeout 900 "$JAVA_HOME/bin/java.exe" -Dorg.gradle.daemon=false \
  -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1
rc=$?
printf 'AGP_COMPILE\t%s\trc=%s\n' "$([ "$rc" -eq 0 ] && printf PASS || printf FAIL)" "$rc"
if [ "$rc" -ne 0 ]; then
  grep -E '^e: |FAILURE:|What went wrong' "$LOG" | head -20
  exit "$rc"
fi
APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [ -f "$APK" ]; then
  printf 'APK\tBYTES=%s\n' "$(wc -c < "$APK")"
  printf 'INSTRUMENTATION\tHITS=%s\n' "$(unzip -p "$APK" AndroidManifest.xml | tr -d '\000' | grep -c 'Qa68Instrumentation')"
else
  printf 'APK\tMISSING\n'
fi
ADB="$SDK/platform-tools/adb.exe"
if [ -x "$ADB" ]; then
  timeout 20 "$ADB" devices > "$(cygpath -m "${TMPDIR:-/tmp}")/qa68_adb.txt" 2>&1
  if grep -Eq '^[[:alnum:]_.:-]+[[:space:]]+device$' "$(cygpath -m "${TMPDIR:-/tmp}")/qa68_adb.txt"; then
    printf 'DEVICE\tPRESENT\tNOT_RUN\tauthorized target present but instrumentation was not executed\n'
  else
    printf 'DEVICE\tBLOCKED\tno authorized adb target\n'
  fi
else
  printf 'DEVICE\tBLOCKED\tno adb binary\n'
fi
printf 'DEVICE_EXECUTION\tUNVERIFIED\n'
exit 0
