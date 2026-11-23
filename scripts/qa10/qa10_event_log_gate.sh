#!/usr/bin/env bash
# QA-10 bounded, deterministic, zero-new-dependency host gate for OnDeviceSelfTest's
# event-log aggregation and bounded shareable summary. No network, no device claim,
# no fixture contents or derived marker values are echoed.
set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
ROOT_N=$(native "$ROOT")
FIXTURES="$ROOT/qa/fixtures/qa10"
VERIFIER="$SCRIPT_DIR/Qa10EventLogGate.kt"
IT_SOURCE="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/Qa10EventLogInstrumentation.kt"
STUBS="$SCRIPT_DIR/stubs"
TMP_N=$(native "${TMPDIR:-$HOME}")
BUILD="$TMP_N/qa10-build"
CLASSES="$BUILD/classes"
IT_CLASSES="$BUILD/instrumentation-classes"
MAIN_CLASS=com.devtunasoft.tuneros.qa10.Qa10EventLogGateKt
MODE="${1:-verify}"
TIMEOUT_BIN=$(command -v timeout || true)
run_bounded() {
  local seconds=$1; shift
  if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" "$seconds" "$@"; else "$@"; fi
}

say() { printf '%b\n' "$1"; }

if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ]; }; then
  say "usage: $0 [verify|generate]"
  exit 2
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  FALLBACK="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
  if [ -x "$FALLBACK/bin/javac" ]; then export JAVA_HOME="$FALLBACK"; else
    say 'BLOCKED\tqa10-no-jdk\tset JAVA_HOME to JDK 17'
    exit 2
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
  say 'BLOCKED\tqa10-no-offline-kotlin\tcompiler jars absent from local cache'
  exit 2
fi
RUNTIME_CP="$(native "$CLASSES");$(native "$STDLIB")"

rm -rf "$BUILD"
mkdir -p "$CLASSES" "$IT_CLASSES"
KOTLIN_COMPILE="$JAVA_HOME/bin/java -cp $COMPILE_CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn -classpath $COMPILE_CP"

say "COMPILE\tSTART\tqa10-verifier"
if ! run_bounded 180 $KOTLIN_COMPILE -d "$(native "$CLASSES")" "$(native "$VERIFIER")" > "$BUILD/compile.log" 2>&1; then
  say 'COMPILE\tFAIL\tqa10-verifier (see scratch qa10-build/compile.log)'
  exit 2
fi
say 'COMPILE\tPASS\tqa10-verifier (offline Kotlin 2.0.21, JDK 17)'

if [ "$MODE" = generate ]; then
  run_bounded 60 "$JAVA_HOME/bin/java" -Xmx96m -cp "$RUNTIME_CP" "$MAIN_CLASS" generate "$(native "$FIXTURES")"
  exit $?
fi

# RED: six file-level mutants must each fail with their pinned first code.
expect_red() {
  local kind=$1 expected=$2
  local mutant="$BUILD/mutant-$kind" out="$BUILD/red-$kind.log"
  rm -rf "$mutant"
  if ! run_bounded 30 "$JAVA_HOME/bin/java" -Xmx96m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
      mutate "$(native "$FIXTURES")" "$kind" "$(native "$mutant")" > "$out" 2>&1; then
    say "RED\t$kind\tFAIL\tmutation-setup-error"
    RED_FAILURES=$((RED_FAILURES + 1)); return
  fi
  run_bounded 60 "$JAVA_HOME/bin/java" -Xmx96m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
    verify "$(native "$mutant")" > "$out" 2>&1
  local status=$?
  local first
  first=$(awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$out")
  if [ "$status" -ne 0 ] && [ "$first" = "$expected" ]; then
    say "RED\t$kind\tPASS\texpected=$expected\tfirst=$first"
  else
    say "RED\t$kind\tFAIL\texpected=$expected\tfirst=${first:-NONE}\texit=$status"
    RED_FAILURES=$((RED_FAILURES + 1))
  fi
}
RED_FAILURES=0
expect_red hash MANIFEST_HASH
expect_red fields STRICT_FIELDS
expect_red leak LEAK_SCAN
expect_red bound BOUNDED_OUTPUT
expect_red latest LATEST_RESULT_WINS
expect_red bytes BYTE_IDENTICAL_REGENERATION
say "RED_SUMMARY\t$((6 - RED_FAILURES))/6\t$([ "$RED_FAILURES" -eq 0 ] && echo PASS || echo FAIL)"

# GREEN: committed fixture must pass every check and every negative control.
GREEN_LOG="$BUILD/green.log"
run_bounded 90 "$JAVA_HOME/bin/java" -Xmx96m -cp "$RUNTIME_CP" "$MAIN_CLASS" \
  verify "$(native "$FIXTURES")" > "$GREEN_LOG" 2>&1
GREEN_STATUS=$?
GREEN_SUMMARY=$(awk -F '\t' '$1 == "SUMMARY" { print $2, $3, $4; exit }' "$GREEN_LOG")
GREEN_DERIVED=$(awk -F '\t' '$1 == "DERIVED" { sub(/^DERIVED\t/, ""); print; exit }' "$GREEN_LOG")
if grep -E '://|pin=|Bearer |token=' "$GREEN_LOG" >/dev/null 2>&1; then
  say 'PRIVACY\tFAIL\tgate output contained a sensitive marker'
  GREEN_STATUS=1
fi
say "GREEN\tSUMMARY=$GREEN_SUMMARY\texit=$GREEN_STATUS"
if [ "$GREEN_STATUS" -eq 0 ]; then
  say "GREEN\t$GREEN_DERIVED"
  say "GREEN\tCHECK_LINES=$(grep -c '^CHECK	PASS' "$GREEN_LOG")"
fi

# Instrumentation type-check against QA-only stubs (device execution is a separate gate).
say "COMPILE\tSTART\tqa10-instrumentation-against-QA-stubs"
if ! run_bounded 120 "$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$(native "$IT_CLASSES")" \
    $(find "$STUBS" -name '*.java' | sort | while read -r stub; do native "$stub"; done) > "$BUILD/stubs.log" 2>&1; then
  say 'COMPILE\tFAIL\tqa10-android-stubs'
  STUB_STATUS=1
elif ! run_bounded 180 $KOTLIN_COMPILE \
    -classpath "$COMPILE_CP;$(native "$IT_CLASSES")" -d "$(native "$IT_CLASSES")" \
    "$(native "$IT_SOURCE")" > "$BUILD/it.log" 2>&1; then
  say 'COMPILE\tFAIL\tqa10-instrumentation-source (see scratch qa10-build/it.log)'
  STUB_STATUS=1
else
  say 'COMPILE\tPASS\tqa10-instrumentation typechecked against QA stubs (device NOT RUN)'
  STUB_STATUS=0
fi

if [ "$RED_FAILURES" -ne 0 ] || [ "$GREEN_STATUS" -ne 0 ] || [ "$STUB_STATUS" -ne 0 ]; then
  say 'GATE\tFAIL'
  exit 1
fi
say 'GATE\tPASS'
exit 0
