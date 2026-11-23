#!/usr/bin/env bash
# QA-24: offline AGP assembly of the androidTest source set; no device execution.
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)
REPO=$(cd "$SCRIPT_DIR/../.." && pwd -W 2>/dev/null || pwd)

export JAVA_HOME='C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2'
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"
SDK='C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03'
if [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  printf '%b\n' 'AGP\tBLOCKED\treason=no_local_sdk'
  exit 2
fi
export ANDROID_HOME=$(cygpath -m "$SDK")
export ANDROID_SDK_ROOT="$ANDROID_HOME"

WORK_BASH=$(mktemp -d "${TMPDIR:-$HOME}/qa24-agp.XXXXXX") || exit 1
LOG="$WORK_BASH/build.log"
cd "$REPO" || exit 1
timeout 900 java -Dorg.gradle.daemon=false \
  -classpath "$REPO/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG" 2>&1
status=$?
if [ "$status" -ne 0 ]; then
  printf '%b\n' "AGP\tFAIL\texit=$status\tlog=$WORK_BASH/build.log"
  exit 1
fi

apk=$(find "$REPO/app/build/outputs/apk/androidTest/debug" -name '*.apk' -type f 2>/dev/null | head -1)
if [ -z "$apk" ]; then
  printf '%b\n' 'AGP\tFAIL\treason=no_test_apk'
  exit 1
fi
bytes=$(wc -c < "$apk" | tr -d ' ')
aapt=$(find "$SDK/build-tools/35.0.0" -name 'aapt.exe' -o -name 'aapt' 2>/dev/null | head -1)
manifest_status='NOT_RUN'
if [ -n "$aapt" ]; then
  timeout 60 "$aapt" dump xmltree "$apk" AndroidManifest.xml > "$WORK_BASH/manifest.log" 2>&1
  if grep -q 'Qa24Instrumentation' "$WORK_BASH/manifest.log"; then
    manifest_status='PASS'
  else
    manifest_status='FAIL'
  fi
fi
printf '%b\n' "AGP\tPASS\texit=$status\tapk_bytes=$bytes\tmanifest=$manifest_status"
[ "$manifest_status" != 'FAIL' ]
