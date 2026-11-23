#!/usr/bin/env bash
# SEC-02 deterministic zero-dependency privacy QA driver.
# Compiles the two real production classes against QA-only Android stubs, runs
# the JVM verifier, and type-checks the Android instrumentation as far as the
# local toolchain allows. No device claim is made.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if command -v cygpath >/dev/null 2>&1; then ROOT=$(cygpath -m "$ROOT"); fi
FIXTURES="$ROOT/qa/fixtures/sec02"
SRC="$ROOT/app/src/main/java/com/devtunasoft/tuneros"
STUBS="$ROOT/scripts/sec02/stubs"
TMP_NATIVE=$(cd "${TMPDIR:-$HOME}" && pwd -W 2>/dev/null || pwd)
BUILD="$TMP_NATIVE/sec02-build"
CLASSES="$BUILD/classes"
IT_CLASSES="$BUILD/instrumentation-classes"

native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  FALLBACK_JAVA="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
  if [ -x "$FALLBACK_JAVA/bin/javac" ]; then
    export JAVA_HOME="$FALLBACK_JAVA"
  else
    echo "BLOCKED: no JDK with javac on PATH or in the local cache"
    exit 2
  fi
fi

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
COMPILER=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1)
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.*.jar' 2>/dev/null | sort | tail -1)
SCRIPT_RUNTIME=$(find "$CACHE/org.jetbrains.kotlin/kotlin-script-runtime" -name 'kotlin-script-runtime-*.jar' 2>/dev/null | sort | tail -1)
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name 'trove4j-*.jar' 2>/dev/null | sort | tail -1)
COROUTINES=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null | sort | tail -1)
ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null | sort | tail -1)
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ] || [ -z "$SCRIPT_RUNTIME" ] || [ -z "$TROVE" ] || [ -z "$COROUTINES" ] || [ -z "$ANNOTATIONS" ]; then
  echo "BLOCKED: Kotlin compiler jars are not present in the local Gradle cache"
  exit 2
fi

COMPILER_CP=$(native "$COMPILER"); STDLIB_CP=$(native "$STDLIB")
COMPILE_CP="$COMPILER_CP;$STDLIB_CP;$(native "$SCRIPT_RUNTIME");$(native "$TROVE");$(native "$COROUTINES");$(native "$ANNOTATIONS")"
CLASSES_CP=$(native "$CLASSES"); STDLIB_RUNTIME=$(native "$STDLIB")
RUNTIME_CP="$CLASSES_CP;$STDLIB_RUNTIME"

rm -rf "$BUILD"
mkdir -p "$CLASSES" "$IT_CLASSES"

echo "COMPILE\tQA Android stubs (JVM only)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$CLASSES" $(find "$STUBS" -name '*.java' | sort) || exit 2

echo "COMPILE\treal ScheduledRecordingSecrets.kt + SecureSourceStore.kt + JVM verifier"
"$JAVA_HOME/bin/java" -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -nowarn \
  -classpath "$RUNTIME_CP" -d "$CLASSES" \
  "$SRC/ScheduledRecordingSecrets.kt" "$SRC/SecureSourceStore.kt" \
  "$ROOT/scripts/sec02/Sec02JvmVerifier.kt" || exit 2

echo "COMPILE\tAndroid instrumentation against QA stubs (type-check only)"
IT_SRC=$(find "$ROOT/app/src/androidTest" -name '*.kt' 2>/dev/null | sort)
if [ -n "$IT_SRC" ]; then
  "$JAVA_HOME/bin/java" -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$RUNTIME_CP" -d "$IT_CLASSES" \
    "$SRC/ScheduledRecordingSecrets.kt" "$SRC/SecureSourceStore.kt" $IT_SRC || exit 2
  echo "TYPECHECK\tPASS\tAndroid instrumentation compiled against QA stubs; device run not attempted"
else
  echo "TYPECHECK\tFAIL\tno instrumentation sources found"
  exit 2
fi

OUT="$BUILD/verifier-output.txt"
set +e
"$JAVA_HOME/bin/java" -cp "$RUNTIME_CP" com.devtunasoft.tuneros.sec02.Sec02JvmVerifierKt \
  "$(native "$FIXTURES")" "$(native "$SRC")" > "$OUT" 2>&1
STATUS=$?
set -e
cat "$OUT"

if grep -qF -f "$FIXTURES/secret-markers.txt" "$OUT"; then
  echo "PRIVACY\tFAIL\tverifier output contained a synthetic secret marker"
  exit 1
fi

if command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  echo "DEVICE\tNOT RUN\tan emulator/device run is outside this harness; no device PASS is claimed"
else
  echo "DEVICE\tBLOCKED\tno adb, Android SDK or emulator available; device execution UNVERIFIED"
fi

echo "VERIFIER\tEXIT=$STATUS"
exit "$STATUS"
