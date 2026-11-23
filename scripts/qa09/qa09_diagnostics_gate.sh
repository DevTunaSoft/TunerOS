#!/usr/bin/env bash
# QA-09 bounded, deterministic, zero-new-dependency host gate.
# RED: five file-level mutants must each fail with their pinned first code.
# GREEN: committed fixture must pass every positive check and the four negative controls.
# No network, no device claim, no fixture contents or derived markers are echoed.
set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
ROOT_N=$(native "$ROOT")
FIXTURES="$ROOT/qa/fixtures/qa09"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
VERIFIER="$SCRIPT_DIR/Qa09DiagnosticsGate.kt"
IT_SOURCE="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/Qa09DiagnosticsInstrumentation.kt"
TMP_N=$(native "${TMPDIR:-$HOME}")
BUILD="$TMP_N/qa09-build"
CLASSES="$BUILD/classes"
IT_CLASSES="$BUILD/instrumentation-classes"
MAIN_CLASS=com.devtunasoft.tuneros.qa09.Qa09DiagnosticsGateKt
TIMEOUT_BIN=$(command -v timeout || true)
run_bounded() {
  local seconds=$1; shift
  if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" "$seconds" "$@"; else "$@"; fi
}

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  FALLBACK="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
  if [ -x "$FALLBACK/bin/javac" ]; then export JAVA_HOME="$FALLBACK"; else
    echo 'BLOCKED: no JDK with javac'; exit 2
  fi
fi

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
COMPILE_CP=""
for jar in $JARS; do COMPILE_CP="$COMPILE_CP$(native "$jar");"; done
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB" ]; then
  echo 'BLOCKED: offline Kotlin compiler jars not present in local cache'; exit 2
fi
RUNTIME_CP="$(native "$CLASSES");$(native "$STDLIB")"

rm -rf "$BUILD"
mkdir -p "$CLASSES" "$IT_CLASSES"
KOTLIN_COMPILE="$JAVA_HOME/bin/java -cp $COMPILE_CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn -classpath $COMPILE_CP"

echo "COMPILE\tSTART\tcore-report-functions-and-qa09-verifier"
if ! run_bounded 180 $KOTLIN_COMPILE -d "$(native "$CLASSES")" \
    "$(native "$CORE/Models.kt")" "$(native "$CORE/StreamDoctor.kt")" \
    "$(native "$CORE/GuideDoctor.kt")" "$(native "$VERIFIER")" > "$BUILD/compile.log" 2>&1; then
  echo 'COMPILE\tFAIL\tsee scratch qa09-build/compile.log'; exit 2
fi
echo "COMPILE\tPASS\tproduction core + verifier (offline Kotlin 2.0.21)"

# RED: each mutant must be rejected, and the first failure must be the pinned code.
expect_red() {
  local kind=$1
  local expected=$2
  local mutant="$BUILD/mutant-$kind"
  local out="$BUILD/red-$kind.log"
  rm -rf "$mutant"
  if ! run_bounded 30 "$JAVA_HOME/bin/java" -Xmx64m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
      mutate "$(native "$FIXTURES")" "$kind" "$(native "$mutant")" > "$out" 2>&1; then
    echo "RED\t$kind\tFAIL\tmutation setup error"; RED_FAILURES=$((RED_FAILURES + 1)); return
  fi
  run_bounded 45 "$JAVA_HOME/bin/java" -Xmx64m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
    verify "$(native "$mutant")" "$(native "$ROOT")" > "$out" 2>&1
  local status=$?
  local first
  first=$(awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$out")
  if [ "$status" -ne 0 ] && [ "$first" = "$expected" ]; then
    echo "RED\t$kind\tPASS\texpected=$expected first=$first"
  else
    echo "RED\t$kind\tFAIL\texpected=$expected first=${first:-NONE} exit=$status"
    RED_FAILURES=$((RED_FAILURES + 1))
  fi
}
RED_FAILURES=0
expect_red hash MANIFEST_HASH
expect_red fields STRICT_FIELDS
expect_red leak guide_report_no_sensitive_text
expect_red bound stream_report_bounded
expect_red bytes byte_identical_regeneration
echo "RED_SUMMARY\t$((5 - RED_FAILURES))/5\t$( [ "$RED_FAILURES" -eq 0 ] && echo PASS || echo FAIL )"

# GREEN: the committed fixed-seed fixture must pass all checks.
GREEN_LOG="$BUILD/green.log"
run_bounded 60 "$JAVA_HOME/bin/java" -Xmx64m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
  verify "$(native "$FIXTURES")" "$(native "$ROOT")" > "$GREEN_LOG" 2>&1
GREEN_STATUS=$?
GREEN_SUMMARY=$(awk -F '\t' '$1 == "SUMMARY" { print $2, $3; exit }' "$GREEN_LOG")
GREEN_COUNTS=$(awk -F '\t' '$1 == "DERIVED" { sub(/^DERIVED\t/, ""); print; exit }' "$GREEN_LOG")
if grep -F 'qa09-provider.invalid' "$GREEN_LOG" >/dev/null 2>&1; then
  echo 'PRIVACY\tFAIL\tgate output contained the synthetic host marker'
  GREEN_STATUS=1
fi
echo "GREEN\tSUMMARY=$GREEN_SUMMARY\texit=$GREEN_STATUS"
echo "GREEN\t$GREEN_COUNTS"
if [ "$GREEN_STATUS" -eq 0 ]; then grep -F 'CHECK' "$GREEN_LOG"; fi

# Instrumentation type-check against QA-only stubs (no SDK required).
echo "COMPILE\tSTART\tandroid instrumentation against QA stubs"
if ! run_bounded 120 "$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$(native "$IT_CLASSES")" \
    $(find "$SCRIPT_DIR/stubs" -name '*.java' | sort | while read -r f; do native "$f"; done) > "$BUILD/stubs.log" 2>&1; then
  echo 'COMPILE\tFAIL\tandroid QA stubs'; STUB_STATUS=1
elif ! run_bounded 180 "$JAVA_HOME/bin/java" -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn -classpath "$COMPILE_CP;$(native "$IT_CLASSES")" \
    -d "$(native "$IT_CLASSES")" \
    "$(native "$CORE/Models.kt")" "$(native "$CORE/StreamDoctor.kt")" \
    "$(native "$CORE/GuideDoctor.kt")" "$(native "$IT_SOURCE")" > "$BUILD/it.log" 2>&1; then
  echo 'COMPILE\tFAIL\tinstrumentation source (see scratch qa09-build/it.log)'; STUB_STATUS=1
else
  echo 'COMPILE\tPASS\tinstrumentation type-checked against QA stubs (device NOT RUN)'
  STUB_STATUS=0
fi

# Offline AGP assembly is attempted only when an SDK is actually configured.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/platforms" ]; then
  echo 'AGP\tSTART\toffline assembleDebugAndroidTest'
  run_bounded 600 ./gradlew --offline --no-daemon :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest \
    > "$BUILD/agp.log" 2>&1
  AGP_STATUS=$?
  echo "AGP\texit=$AGP_STATUS\t$( [ "$AGP_STATUS" -eq 0 ] && echo PASS || echo FAIL )"
else
  echo 'AGP\tBLOCKED\tno configured Android SDK; offline APK assembly NOT RUN'
fi

if command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then
  echo 'DEVICE\tNOT RUN\tno authorized emulator/device was configured for this host gate'
else
  echo 'DEVICE\tBLOCKED\tno adb and no SDK; device execution UNVERIFIED'
fi

if [ "$RED_FAILURES" -ne 0 ] || [ "$GREEN_STATUS" -ne 0 ] || [ "$STUB_STATUS" -ne 0 ]; then
  echo 'GATE\tFAIL'
  exit 1
fi
echo 'GATE\tPASS'
exit 0
