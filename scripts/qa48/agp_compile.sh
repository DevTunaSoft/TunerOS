#!/usr/bin/env bash
# QA-48 offline AGP compile/assembly attempt. Never claims device execution.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
OUT="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa48-agp"
mkdir -p "$OUT"

JDK_HOME="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
if [ ! -d "$JDK_HOME" ]; then
  printf 'AGP\tBLOCKED\tjdk_missing\n'
  exit 0
fi
export JAVA_HOME="$JDK_HOME"
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"

SDK=""
for candidate in "$HOME/AppData/Local/Android/Sdk" "C:/Users/X1/AppData/Local/Android/Sdk" "C:/Android/Sdk"; do
  if [ -f "$candidate/platforms/android-35/android.jar" ]; then SDK="$candidate"; break; fi
done
if [ -z "$SDK" ]; then
  SDK=$(find "C:/Users/X1/AppData/Local/hermes/cache/scratch" -maxdepth 6 \
    -path '*/platforms/android-35/android.jar' -print 2>/dev/null | head -1)
  if [ -n "$SDK" ]; then SDK="${SDK%/platforms/android-35/android.jar}"; fi
fi
if [ -z "$SDK" ]; then
  printf 'AGP\tBLOCKED\tandroid_sdk_missing\n'
  exit 0
fi
export ANDROID_HOME="$(cygpath -m "$SDK")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
printf 'AGP\tINFO\tsdk_found=yes\n'

set +e
timeout 900 ./gradlew --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest \
  > "$OUT/gradle.log" 2>&1
RC=$?
set -e
printf 'AGP\tRESULT\trc=%s\n' "$RC"
if [ "$RC" -eq 0 ]; then
  APK=$(find app/build/outputs/apk/androidTest -name '*debug-androidTest.apk' -type f 2>/dev/null | head -1)
  if [ -n "$APK" ]; then
    BYTES=$(wc -c < "$APK")
    printf 'AGP\tPASS\tapk_bytes=%s\n' "$BYTES"
    if command -v unzip >/dev/null 2>&1; then
      if unzip -l "$APK" | grep -q 'classes.dex'; then
        printf 'AGP\tPASS\tdex_present=yes\n'
      else
        printf 'AGP\tFAIL\tdex_missing\n'
      fi
    fi
  else
    printf 'AGP\tFAIL\tapk_missing\n'
  fi
else
  printf 'AGP\tBLOCKED\toffline_compile_failed\n'
  grep -E 'FAILURE:|What went wrong|Could not|error:' "$OUT/gradle.log" | head -8 || true
fi

ADB="$ANDROID_HOME/platform-tools/adb"
if [ -x "$ADB" ]; then
  set +e
  timeout 30 "$ADB" devices > "$OUT/devices.log" 2>&1
  DR=$?
  set -e
  if [ "$DR" -eq 0 ] && grep -qE '^[[:space:]]*[A-Za-z0-9_.-]+[[:space:]]+device$' "$OUT/devices.log"; then
    printf 'DEVICE\tINFO\tauthorized_target_present=yes\n'
  else
    printf 'DEVICE\tBLOCKED\tno_authorized_adb_target\n'
  fi
else
  printf 'DEVICE\tBLOCKED\tadb_unavailable\n'
fi
printf 'DEVICE\tNOT_RUN\tinstrumented_execution_requires_authorized_target\n'
