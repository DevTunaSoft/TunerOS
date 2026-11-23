#!/usr/bin/env bash
# QA-12 bounded, offline, zero-new-dependency host gate for crash / partial-write DVR recovery.
#   run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
#   run.sh verify      host checks + RED controls + Kotlin mirror digest (default)
#   run.sh red-green   eleven on-disk mutants (each must go RED with a pinned first failure), then GREEN
# No Gradle, network, device or fixture payload bytes are used in the host path.
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != red-green ]; }; then
  printf '%b\n' "usage: $0 [verify|generate|red-green]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SRC="$HERE/Qa12Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa12"
MIRROR="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa12/Qa12Assertions.kt"
IT_SOURCE="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa12/Qa12Instrumentation.kt"
STUBS="$HERE/stubs"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/javac" ] || [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa12-no-jdk\tset JAVA_HOME to a JDK 17 installation'
  exit 2
fi
for tool in timeout grep sed awk find; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa12-no-shell-tool\t$tool"
    exit 2
  fi
done

BUILD="${TMPDIR:-/tmp}/qa12-build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/stubs" "$BUILD/instrumentation"
BUILD_N="$(native_dir "$BUILD")"
CLASSES_N="$BUILD_N/classes"
SRC_N="$(native_dir "$HERE")/Qa12Gate.java"
FIX_N="$(native_dir "$FIXTURES")"

if ! timeout 120 "$JH/javac" -Xlint:all -d "$CLASSES_N" "$SRC_N" > "$BUILD/compile-java.log" 2>&1; then
  say 'FAIL\tqa12-compile-host-gate\tsee scratch qa12-build/compile-java.log'
  exit 2
fi
say 'COMPILE\tPASS\tqa12-host-gate\tjavac17'

run_gate() {
  local mode="$1"; local dir="$2"; local report="$3"
  if timeout 180 "$JH/java" -Xmx128m -cp "$CLASSES_N" Qa12Gate "$mode" "$dir" > "$report" 2>&1; then
    return 0
  fi
  return 1
}

if [ "$MODE" = generate ]; then
  timeout 90 "$JH/java" -Xmx128m -cp "$CLASSES_N" Qa12Gate generate "$FIX_N"
  exit $?
fi

# Kotlin mirror: offline cached compiler only. Typechecks the instrumented source against QA stubs,
# runs the dependency-free assertions on the JVM, and compares the artifact digest with the fixture.
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
COMPILE_CP=""
for jar in $JARS; do COMPILE_CP="$COMPILE_CP$(native_path "$jar");"; done
STDLIB=""
if [ -n "$STDLIB_RAW" ]; then STDLIB="$(native_path "$STDLIB_RAW")"; fi

mirror_check() {
  if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB" ]; then
    say 'BLOCKED\tqa12-mirror\tno offline Kotlin 2.0.21 compiler jars'
    return 2
  fi
  local stub_src
  stub_src=$(find "$STUBS" -name '*.java' | sort | tr '\n' ' ')
  if ! timeout 90 "$JH/javac" -encoding UTF-8 -d "$BUILD_N/stubs" $stub_src > "$BUILD/stubs.log" 2>&1; then
    say 'FAIL\tqa12-stubs\tjavac'
    return 1
  fi
  if ! timeout 180 "$JH/java" -Xmx512m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -jvm-target 17 -nowarn \
      -classpath "$COMPILE_CP$BUILD_N/stubs" -d "$BUILD_N/instrumentation" \
      "$(native_dir "$(dirname "$MIRROR")")/Qa12Assertions.kt" \
      "$(native_dir "$(dirname "$IT_SOURCE")")/Qa12Instrumentation.kt" > "$BUILD/mirror.log" 2>&1; then
    say 'FAIL\tqa12-mirror-compile\tsee scratch qa12-build/mirror.log'
    return 1
  fi
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$BUILD_N/instrumentation;$STDLIB" \
      com.devtunasoft.tuneros.qa12.Qa12Assertions > "$BUILD/mirror-run.txt" 2>&1; then
    say 'FAIL\tqa12-mirror-run\tsee scratch qa12-build/mirror-run.txt'
    return 1
  fi
  local digest expected
  digest=$(awk -F '\t' '$1 == "DIGEST" { print $2; exit }' "$BUILD/mirror-run.txt")
  expected=$(awk -F '=' '$1 == "mirror_digest" { print $2; exit }' "$FIXTURES/dimensions.tsv")
  if [ -z "$digest" ] || [ "$digest" != "$expected" ]; then
    say "FAIL\tqa12-mirror-digest\texpected=$expected\tdigest=${digest:-NONE}"
    return 1
  fi
  local passed checks
  passed=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  checks=$(awk -F '\t' '$1 == "CHECK" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  if [ "$passed" -ne "$checks" ] || [ "$checks" -lt 9 ]; then
    say "FAIL\tqa12-mirror-checks\tpassed=$passed\tchecks=$checks"
    return 1
  fi
  say "COMPILE\tPASS\tqa12-androidTest-typechecked-against-QA-stubs\tchecks=$passed\tdigest=match"
  return 0
}

GREEN="$BUILD/green.txt"
if ! run_gate verify "$FIX_N" "$GREEN"; then
  say 'FAIL\tqa12-green\thost-gate-not-green'
  grep -E '^(FIRST|FAIL|SUMMARY)' "$GREEN" | head -n 8
  exit 1
fi
if grep -E '://|Bearer |token=|secret=' "$GREEN" >/dev/null 2>&1; then
  say 'FAIL\tqa12-privacy\thost report contained a sensitive marker'
  exit 1
fi
summary_field() {
  local report="$1"; local field="$2"
  sed -n "s/^SUMMARY.*${field}=\\([^[:space:]]*\\).*/\\1/p" "$report" | head -1
}
POS=$(summary_field "$GREEN" positive)
NEG=$(summary_field "$GREEN" negative-red)
TOTAL=$(summary_field "$GREEN" total)
say "GREEN\tpositive=$POS\tnegative-red=$NEG\ttotal=$TOTAL"

MIRROR_STATUS=0
mirror_check || MIRROR_STATUS=$?

if [ "$MODE" = verify ]; then
  if [ "$MIRROR_STATUS" -eq 0 ]; then say 'GATE\tPASS'; exit 0; fi
  if [ "$MIRROR_STATUS" -eq 2 ]; then say 'GATE\tBLOCKED\tmirror-unavailable'; exit 2; fi
  say 'GATE\tFAIL'
  exit 1
fi

# RED first: 11 on-disk mutants, each with a pinned FIRST failure code.
MUTANTS="flip empty-completed duplicate-completed missing-restored oversize-shrunk journal-cap leak-url leak-credential leak-pin leak-secret leak-payload"
EXPECTED="fixture-byte-identical red-lenient-empty-accepted red-duplicate-lax-finalization missing-media-no-completion oversize-rejected journal-bounded diagnostics-secret-free diagnostics-secret-free diagnostics-secret-free diagnostics-secret-free diagnostics-secret-free"
INDEX=0
RED_FAILURES=0
for kind in $MUTANTS; do
  INDEX=$((INDEX + 1))
  expected=$(printf '%s\n' $EXPECTED | sed -n "${INDEX}p")
  work="$BUILD/mutant-$kind"
  rm -rf "$work"
  if ! timeout 60 "$JH/java" -Xmx128m -cp "$CLASSES_N" Qa12Gate mutate "$FIX_N" "$(native_dir "$BUILD")/mutant-$kind" "$kind" > "$BUILD/mutate-$kind.log" 2>&1; then
    say "RED\t$kind\tFAIL\tmutation-setup-error"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  mode=verify-mutant
  if [ "$kind" = flip ]; then mode=verify; fi
  out="$BUILD/red-$kind.txt"
  if run_gate "$mode" "$(native_dir "$work")" "$out"; then
    say "RED\t$kind\tFAIL\tmutant-unexpectedly-green"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  first=$(awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$out")
  if [ "$first" != "$expected" ]; then
    say "RED\t$kind\tFAIL\texpected=$expected\tfirst=${first:-NONE}"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  say "RED\t$kind\tPASS\tfirst=$first"
done
say "RED_SUMMARY\t$((11 - RED_FAILURES))/11\t$([ "$RED_FAILURES" -eq 0 ] && printf 'PASS' || printf 'FAIL')"

if [ "$RED_FAILURES" -ne 0 ] || [ "$MIRROR_STATUS" -ne 0 ]; then
  say 'GATE\tFAIL'
  exit 1
fi
say 'GATE\tPASS'
exit 0
