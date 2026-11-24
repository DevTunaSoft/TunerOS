#!/usr/bin/env bash
# QA-67 offline AGP compile and instrumented-APK assembly; no device invocation.
set -uo pipefail

ROOT_RAW="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT_NATIVE="$(cd "$ROOT_RAW" && (pwd -W 2>/dev/null || pwd))"
export JAVA_HOME="${QA67_JDK_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"
export ANDROID_HOME="${QA67_SDK:-C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
LOG="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa67-agp-$$.log"
LOG_BASH="$(cygpath -u "$LOG")"

if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
    printf 'AGP\tBLOCKED\ttoolchain\n'
    exit 2
fi

cd "$ROOT_RAW"
timeout 1200s ./gradlew --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest > "$LOG_BASH" 2>&1
rc=$?
build_line="$(grep -a -E '^BUILD (SUCCESSFUL|FAILED)' "$LOG_BASH" | tail -n 1)"
if [ "$rc" -eq 0 ] && [ "${build_line#BUILD SUCCESSFUL}" != "$build_line" ]; then
    printf 'AGP\tPASS\tcompileDebugAndroidTestKotlin\tassembleDebugAndroidTest\n'
else
    printf 'AGP\tFAIL\trc=%s\t%s\n' "$rc" "${build_line:-no-build-line}"
    printf 'AGP_LOG\t%s\n' "$LOG"
    exit 1
fi

APK="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -type f 2>/dev/null | sort | tail -n 1)"
if [ -n "$APK" ]; then
    bytes="$(wc -c < "$APK" | tr -d ' ')"
    printf 'APK\tPASS\tbytes=%s\n' "$bytes"
else
    printf 'APK\tFAIL\tnot-found\n'
    exit 1
fi
printf 'MANIFEST\t%s\n' "$(unzip -p "$APK" AndroidManifest.xml 2>/dev/null | tr -d '\000' | grep -c 'Qa67Instrumentation' || true)"

ADB="$ANDROID_HOME/platform-tools/adb"
DEV_LOG="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa67-adb-$$.log"
timeout 30s "$ADB" devices > "$(cygpath -u "$DEV_LOG")" 2>&1
count="$(grep -ac $'\tdevice$' "$(cygpath -u "$DEV_LOG")" || true)"
if [ "${count:-0}" -gt 0 ]; then
    printf 'DEVICE\tAVAILABLE\tcount=%s\n' "$count"
else
    printf 'DEVICE\tBLOCKED\tno-authorized-adb-target\n'
fi
