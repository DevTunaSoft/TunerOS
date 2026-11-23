#!/usr/bin/env bash
# QA-23 bounded JVM mirror run plus device-availability probe. No device execution here.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
MIRROR="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa23/Qa23Contract.kt"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa23-device-mirror\tno JDK 17'
  exit 2
fi
for file in "$MIRROR" "$CORE/DashSegmentIndex.kt" "$CORE/HlsPlaylistParserCore.kt" "$CORE/BoundedLines.kt"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa23-device-mirror\tmissing $(basename "$file")"
    exit 2
  fi
done

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' \
       -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done
if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB_RAW" ]; then
  say 'BLOCKED\tqa23-device-mirror\tno offline Kotlin compiler'
  exit 2
fi

BUILD="${TMPDIR:-/tmp}/qa23-device-mirror"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
NATIVE_CLASSES="$(native_dir "$BUILD/classes")"
if ! timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$NATIVE_CLASSES" -d "$NATIVE_CLASSES" \
    "$(native_path "$MIRROR")" "$(native_path "$CORE/DashSegmentIndex.kt")" \
    "$(native_path "$CORE/HlsPlaylistParserCore.kt")" "$(native_path "$CORE/BoundedLines.kt")" \
    > "$BUILD/compile.log" 2>&1; then
  say 'FAIL\tqa23-device-mirror-compile\tsee scratch qa23-device-mirror/compile.log'
  grep -E 'error:|Exception' "$BUILD/compile.log" | head -n 6
  exit 1
fi

timeout 60 "$JH/java" -Xmx96m -Xss1m -cp "$NATIVE_CLASSES;$(native_path "$STDLIB_RAW")" \
  com.devtunasoft.tuneros.qa23.Qa23Contract > "$BUILD/report.log" 2>&1
status=$?
if [ "$status" -ne 0 ]; then
  say 'FAIL\tqa23-device-mirror-run\tsee scratch qa23-device-mirror/report.log'
  head -n 10 "$BUILD/report.log"
  exit 1
fi
count=$(awk -F '\t' '$1=="CHECK" {n++} END {print n+0}' "$BUILD/report.log")
failed=$(awk -F '\t' '$1=="CHECK" && $2=="FAIL" {n++} END {print n+0}' "$BUILD/report.log")
if [ "$count" -ne 8 ] || [ "$failed" -ne 0 ]; then
  say "FAIL\tqa23-device-mirror-count\tchecks=$count\tfail=$failed"
  head -n 12 "$BUILD/report.log"
  exit 1
fi
say "MIRROR\tPASS\tchecks=$count\tpass=$count\tfail=0\theap=96m"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03/platforms/android-35/android.jar" ]; then
  SDK="$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03"
fi
ADB=""
if [ -n "$SDK" ]; then
  for candidate in "$SDK/platform-tools/adb.exe" "$SDK/platform-tools/adb"; do
    if [ -x "$candidate" ]; then ADB="$candidate"; break; fi
  done
fi
if [ -n "$ADB" ] && timeout 20 "$ADB" devices 2>/dev/null \
    | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' >/dev/null; then
  say 'DEVICE\tNOT RUN\ttarget present but QA-23 does not auto-execute instrumentation'
else
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target'
fi
exit 0
