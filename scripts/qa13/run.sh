#!/usr/bin/env bash
# QA-13 bounded, offline, zero-new-dependency host gate for timeshift live-window state.
#   run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
#   run.sh verify      RED mutants first, then GREEN host checks and the Kotlin mirror (default)
#   run.sh red-green   explicit alias for the full verify path
# No Gradle, network, device or fixture payload bytes are used in the host path.
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != red-green ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1"; native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SRC="$HERE/Qa13Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa13"
MIRROR="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa13/Qa13Assertions.kt"
IT_SOURCE="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa13/Qa13Instrumentation.kt"
STUBS="$HERE/stubs"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/javac" ] || [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa13-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep awk find sed; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa13-no-shell-tool\t$tool"
    exit 2
  fi
done

BUILD="${TMPDIR:-/tmp}/qa13-build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/instrumentation" "$BUILD/stub-classes"
BUILD_N="$(native_dir "$BUILD")"
CLASSES_N="$BUILD_N/classes"
SRC_N="$(native_dir "$HERE")/Qa13Gate.java"
FIX_N="$(native_dir "$FIXTURES")"

if ! timeout 120 "$JH/javac" -Xlint:all -d "$CLASSES_N" "$SRC_N" > "$BUILD/compile-java.log" 2>&1; then
  say 'FAIL\tqa13-compile-host-gate\tsee scratch qa13-build/compile-java.log'
  exit 2
fi
say 'COMPILE\tPASS\tqa13-host-gate\tjavac17'

run_gate() {
  local mode="$1" local_dir="$2" report="$3"
  if timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa13Gate "$mode" "$local_dir" > "$report" 2>&1; then
    return 0
  fi
  return 1
}

if [ "$MODE" = generate ]; then
  timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa13Gate generate "$FIX_N"
  exit $?
fi

# Kotlin mirror: cached compiler jars only. Typechecks the instrumented source against QA stubs,
# runs the dependency-free assertions on the JVM and pins the artifact digest in the fixture.
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="$COMPILE_CP$(native_path "$jar");"; done
STDLIB=''
if [ -n "$STDLIB_RAW" ]; then STDLIB="$(native_path "$STDLIB_RAW")"; fi

mirror_check() {
  if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB" ]; then
    say 'BLOCKED\tqa13-mirror\tno offline Kotlin 2.0.21 compiler jars'
    return 2
  fi
  local stub_src
  stub_src=$(find "$STUBS" -name '*.java' | sort | tr '\n' ' ')
  if ! timeout 90 "$JH/javac" -encoding UTF-8 -d "$BUILD_N/stub-classes" $stub_src > "$BUILD/stubs.log" 2>&1; then
    say 'FAIL\tqa13-stubs\tjavac'
    return 1
  fi
  if ! timeout 180 "$JH/java" -Xmx512m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -jvm-target 17 -nowarn \
      -classpath "$COMPILE_CP$BUILD_N/stub-classes" -d "$BUILD_N/instrumentation" \
      "$(native_dir "$(dirname "$MIRROR")")/Qa13Assertions.kt" \
      "$(native_dir "$(dirname "$IT_SOURCE")")/Qa13Instrumentation.kt" > "$BUILD/mirror.log" 2>&1; then
    say 'FAIL\tqa13-mirror-compile\tsee scratch qa13-build/mirror.log'
    return 1
  fi
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$BUILD_N/instrumentation;$STDLIB" \
      com.devtunasoft.tuneros.qa13.Qa13Assertions > "$BUILD/mirror-run.txt" 2>&1; then
    say 'FAIL\tqa13-mirror-run\tsee scratch qa13-build/mirror-run.txt'
    return 1
  fi
  if grep -E '://|Bearer |token=|pin=|secret=' "$BUILD/mirror-run.txt" >/dev/null 2>&1; then
    say 'FAIL\tqa13-mirror-privacy\tsensitive marker in mirror report'
    return 1
  fi
  local digest expected passed checks failed
  digest=$(awk -F '\t' '$1 == "DIGEST" { print $2; exit }' "$BUILD/mirror-run.txt")
  expected=$(awk -F '=' '$1 == "mirror-digest" { print $2; exit }' "$FIXTURES/dimensions.tsv")
  if [ -z "$digest" ] || [ "$digest" != "$expected" ]; then
    say "FAIL\tqa13-mirror-digest\texpected=${expected:-NONE}\tdigest=${digest:-NONE}"
    return 1
  fi
  passed=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  failed=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  checks=$((passed + failed))
  if [ "$failed" -ne 0 ] || [ "$passed" -lt 12 ]; then
    say "FAIL\tqa13-mirror-checks\tpassed=$passed\tfailed=$failed\tchecks=$checks"
    return 1
  fi
  say "COMPILE\tPASS\tqa13-androidTest-typechecked-against-QA-stubs\tchecks=$passed\tdigest=match"
  return 0
}

# RED first: each on-disk mutant must fail with its own pinned FIRST code.
MUTANTS=(flip manifest header fields truncated bounds leak cap overlap seekbound unknownmode corruptaccepted mirror)
EXPECTED=(BYTE_IDENTICAL_REGENERATION MANIFEST_SHA256_MATCH STRICT_WINDOWS_HEADER STRICT_WINDOWS_FIELDS STRICT_SEEKS_TERMINATOR STRICT_WINDOWS_RANGE LEAK_SCAN WINDOW_CAP_BINDS OVERLAP_COUNT STRICT_SEEKS_RANGE STRICT_SEEKS_MODE CORRUPT_REJECT_bad-channel MIRROR_DIGEST_PINNED)
RED_FAILURES=0
for i in "${!MUTANTS[@]}"; do
  kind="${MUTANTS[$i]}"
  expected="${EXPECTED[$i]}"
  work="$BUILD/mutant-$kind"
  rm -rf "$work"
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa13Gate mutate \
      "$FIX_N" "$kind" "$BUILD_N/mutant-$kind" > "$BUILD/mutate-$kind.log" 2>&1; then
    say "RED\t$kind\tFAIL\tmutation-setup-error"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  mode=verify-mutant
  if [ "$kind" = flip ]; then mode=verify; fi
  if [ "$kind" = manifest ]; then mode=verify-manifest; fi
  out="$BUILD/red-$kind.txt"
  if run_gate "$mode" "$BUILD_N/mutant-$kind" "$out"; then
    say "RED\t$kind\tFAIL\tmutant-unexpectedly-green"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  first=$(awk -F '\t' '$1 == "FIRST" { print $3; exit }' "$out")
  if [ "$first" != "$expected" ]; then
    say "RED\t$kind\tFAIL\texpected=$expected\tfirst=${first:-NONE}"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  say "RED\t$kind\tPASS\tfirst=$first"
done
MUTANT_TOTAL=${#MUTANTS[@]}
MUTANT_PASS=$((MUTANT_TOTAL - RED_FAILURES))
say "RED_SUMMARY\t$MUTANT_PASS/$MUTANT_TOTAL\t$([ "$RED_FAILURES" -eq 0 ] && printf 'PASS' || printf 'FAIL')"

# GREEN: the committed fixture must pass every check and keep output secret-free.
GREEN="$BUILD/green.txt"
GREEN_STATUS=0
run_gate verify "$FIX_N" "$GREEN" || GREEN_STATUS=$?
if grep -E '://|Bearer |token=|pin=|secret=' "$GREEN" >/dev/null 2>&1; then
  say 'FAIL\tqa13-green-privacy\tsensitive marker in host report'
  GREEN_STATUS=1
fi
POS=$(awk -F '\t' '$1 == "SUMMARY" { split($2,a,"="); print a[2]; exit }' "$GREEN")
NEG=$(awk -F '\t' '$1 == "SUMMARY" { split($3,a,"="); print a[2]; exit }' "$GREEN")
TOTAL=$(awk -F '\t' '$1 == "SUMMARY" { split($4,a,"="); print a[2]; exit }' "$GREEN")
FIRST=$(awk -F '\t' '$1 == "FIRST" { print $3; exit }' "$GREEN")
CHECKS=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$GREEN")
FAILS=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$GREEN")
if [ "$GREEN_STATUS" -ne 0 ] || [ "$FAILS" -ne 0 ] || [ "$CHECKS" -ne "$TOTAL" ]; then
  say "GREEN\tFAIL\texit=$GREEN_STATUS\tpositive=${POS:-NONE}\tnegative-red=${NEG:-NONE}\ttotal=${TOTAL:-NONE}\tfirst=${FIRST:-NONE}"
  grep -E '^(FIRST|CHECK.*FAIL|SUMMARY)' "$GREEN" | head -n 8
else
  say "GREEN\tPASS\tpositive=$POS\tnegative-red=$NEG\ttotal=$TOTAL\tfirst=$FIRST"
fi

MIRROR_STATUS=0
mirror_check || MIRROR_STATUS=$?

if [ "$RED_FAILURES" -ne 0 ] || [ "$GREEN_STATUS" -ne 0 ] || [ "$MIRROR_STATUS" -ne 0 ]; then
  say 'GATE\tFAIL'
  exit 1
fi
say 'GATE\tPASS'
exit 0
