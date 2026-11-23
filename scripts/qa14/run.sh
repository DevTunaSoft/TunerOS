#!/usr/bin/env bash
# QA-14 bounded, offline, zero-new-dependency host gate for reminder scheduling state.
#   run.sh generate    regenerate byte-pinned scratch fixtures
#   run.sh verify      RED mutants, GREEN host checks, regeneration, Kotlin mirror (default)
#   run.sh red-green   explicit alias for the full verify path
# No Gradle, network, device, provider URL or fixture payload byte is used here.
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != red-green ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SRC="$HERE/Qa14Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa14"
MIRROR_DIR="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa14"
STUBS="$HERE/stubs"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/javac" ] || [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa14-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep awk find cmp; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa14-no-shell-tool\t$tool"
    exit 2
  fi
done

BUILD="${TMPDIR:-/tmp}/qa14-build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/instrumentation" "$BUILD/stub-classes" "$BUILD/regen-a" "$BUILD/regen-b"
BUILD_N="$(native_dir "$BUILD")"
CLASSES_N="$BUILD_N/classes"
SRC_N="$HERE/Qa14Gate.java"
FIX_N="$(native_dir "$FIXTURES")"

if ! timeout 120 "$JH/javac" -Xlint:all -d "$CLASSES_N" "$SRC_N" > "$BUILD/compile-java.log" 2>&1; then
  say 'FAIL\tqa14-compile-host-gate\tsee scratch qa14-build/compile-java.log'
  exit 2
fi
say 'COMPILE\tPASS\tqa14-host-gate\tjavac17'

run_gate() {
  local gate_mode="$1"
  local gate_dir="$2"
  local report="$3"
  if timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa14Gate "$gate_mode" "$gate_dir" > "$report" 2>&1; then
    return 0
  fi
  return 1
}

if [ "$MODE" = generate ]; then
  if ! timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa14Gate generate "$FIX_N" > "$BUILD/generate.txt" 2>&1; then
    say 'FAIL\tqa14-generate\tsee scratch qa14-build/generate.txt'
    exit 1
  fi
  say 'GENERATE\tPASS\tbyte-pinned scratch fixtures'
  exit 0
fi

# Kotlin mirror: offline cached compiler jars only; no dependency additions.
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
    say 'BLOCKED\tqa14-mirror\tno offline Kotlin 2.0.21 compiler jars'
    return 2
  fi
  local stub_src
  stub_src=$(find "$STUBS" -name '*.java' | sort | tr '\n' ' ')
  if ! timeout 90 "$JH/javac" -encoding UTF-8 -d "$BUILD_N/stub-classes" $stub_src > "$BUILD/stubs.log" 2>&1; then
    say 'FAIL\tqa14-mirror-stubs\tjavac'
    return 1
  fi
  local mirror_dir_n
  mirror_dir_n="$(native_dir "$MIRROR_DIR")"
  if ! timeout 180 "$JH/java" -Xmx512m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -jvm-target 17 -nowarn \
      -classpath "$COMPILE_CP$BUILD_N/stub-classes" -d "$BUILD_N/instrumentation" \
      "$mirror_dir_n/Qa14Assertions.kt" "$mirror_dir_n/Qa14Instrumentation.kt" \
      > "$BUILD/mirror-compile.log" 2>&1; then
    say 'FAIL\tqa14-mirror-compile\tsee scratch qa14-build/mirror-compile.log'
    return 1
  fi
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$BUILD_N/instrumentation;$STDLIB" \
      com.devtunasoft.tuneros.qa14.Qa14Assertions > "$BUILD/mirror-run.txt" 2>&1; then
    say 'FAIL\tqa14-mirror-run\tsee scratch qa14-build/mirror-run.txt'
    return 1
  fi
  if grep -E '://|Bearer |token=|pin=|secret=' "$BUILD/mirror-run.txt" >/dev/null 2>&1; then
    say 'FAIL\tqa14-mirror-privacy\tmarker in mirror report'
    return 1
  fi
  local digest expected passed failed checks
  digest=$(awk -F '\t' '$1 == "DIGEST" { print $2; exit }' "$BUILD/mirror-run.txt")
  expected=$(awk -F '=' '$1 == "mirror-digest" { print $2; exit }' "$FIXTURES/dimensions.tsv")
  if [ -z "$digest" ] || [ "$digest" != "$expected" ]; then
    say "FAIL\tqa14-mirror-digest\texpected=${expected:-NONE}\tdigest=${digest:-NONE}"
    return 1
  fi
  passed=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  failed=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
  checks=$((passed + failed))
  if [ "$failed" -ne 0 ] || [ "$passed" -lt 11 ]; then
    say "FAIL\tqa14-mirror-checks\tpassed=$passed\tfailed=$failed\tchecks=$checks"
    return 1
  fi
  say "MIRROR\tPASS\tchecks=$passed\tdigest=match"
  return 0
}

# RED first: every on-disk mutant must exit nonzero with its own pinned FIRST code.
MUTANTS=(flip manifest header fields truncated range zone duplicate expired fired order cap corruptaccepted leak mirror)
EXPECTED=(BYTE_IDENTICAL_REGENERATION MANIFEST_SHA256_MATCH STRICT_REMINDERS_HEADER STRICT_REMINDERS_FIELDS STRICT_REMINDERS_TERMINATOR STRICT_REMINDERS_RANGE STRICT_REMINDERS_ZONE DUPLICATE_REJECTION EXPIRED_REJECTION FIRED_REJECTION ORDER_DIGEST_PINNED PAGE_CAP_PINNED CORRUPT_REJECT_bad-zone LEAK_SCAN MIRROR_DIGEST_PINNED)
RED_FAILURES=0
for i in "${!MUTANTS[@]}"; do
  kind="${MUTANTS[$i]}"
  expected="${EXPECTED[$i]}"
  work="$BUILD/mutant-$kind"
  rm -rf "$work"
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa14Gate mutate \
      "$FIX_N" "$BUILD_N/mutant-$kind" "$kind" > "$BUILD/mutate-$kind.log" 2>&1; then
    say "RED\t$kind\tFAIL\tmutation-setup-error"
    RED_FAILURES=$((RED_FAILURES + 1))
    continue
  fi
  gate_mode=verify-mutant
  if [ "$kind" = flip ]; then gate_mode=verify; fi
  if [ "$kind" = manifest ]; then gate_mode=verify-manifest; fi
  if [ "$kind" = mirror ]; then gate_mode=verify-no-byte; fi
  out="$BUILD/red-$kind.txt"
  if run_gate "$gate_mode" "$BUILD_N/mutant-$kind" "$out"; then
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

# GREEN: the committed fixture must pass every positive and negative control.
GREEN="$BUILD/green.txt"
GREEN_STATUS=0
run_gate verify "$FIX_N" "$GREEN" || GREEN_STATUS=$?
if grep -E '://|Bearer |token=|pin=|secret=' "$GREEN" >/dev/null 2>&1; then
  say 'FAIL\tqa14-green-privacy\tmarker in host report'
  GREEN_STATUS=1
fi
POS=$(awk -F '\t' '$1 == "SUMMARY" { split($2,a,"="); print a[2]; exit }' "$GREEN")
NEG=$(awk -F '\t' '$1 == "SUMMARY" { split($3,a,"="); print a[2]; exit }' "$GREEN")
TOTAL=$(awk -F '\t' '$1 == "SUMMARY" { split($4,a,"="); print a[2]; exit }' "$GREEN")
FAILED=$(awk -F '\t' '$1 == "SUMMARY" { split($5,a,"="); print a[2]; exit }' "$GREEN")
CHECKS=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$GREEN")
if [ "$GREEN_STATUS" -ne 0 ] || [ "${FAILED:-1}" -ne 0 ] || [ "$CHECKS" -ne "$TOTAL" ]; then
  say "GREEN\tFAIL\texit=$GREEN_STATUS\tpositive=${POS:-NONE}\tnegative-red=${NEG:-NONE}\ttotal=${TOTAL:-NONE}\tfailed=${FAILED:-NONE}"
  grep -E '^(FIRST|CHECK.*FAIL|SUMMARY)' "$GREEN" | head -n 8
else
  say "GREEN\tPASS\tpositive=$POS\tnegative-red=$NEG\ttotal=$TOTAL\tfailed=$FAILED"
fi

# Independent byte-identity reproduction into two empty scratch directories.
REGEN_STATUS=0
for scratch in "$BUILD_N/regen-a" "$BUILD_N/regen-b"; do
  if ! timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N" Qa14Gate generate "$scratch" \
      > "$BUILD/regen-$(basename "$scratch").log" 2>&1; then
    REGEN_STATUS=1
  fi
done
for name in reminders.tsv corrupt.tsv fired.tsv dimensions.tsv sha256.txt .gitattributes; do
  if ! cmp -s "$FIXTURES/$name" "$BUILD/regen-a/$name" \
      || ! cmp -s "$BUILD/regen-a/$name" "$BUILD/regen-b/$name"; then
    REGEN_STATUS=1
  fi
done
if [ "$REGEN_STATUS" -eq 0 ]; then
  say 'REGEN\tPASS\ttwo scratch regenerations byte-identical to committed fixture'
else
  say 'REGEN\tFAIL\tscratch regeneration diverged'
fi

MIRROR_STATUS=0
mirror_check || MIRROR_STATUS=$?
say 'DEVICE\tBLOCKED\tno authorized adb device or emulator was used'

if [ "$RED_FAILURES" -ne 0 ] || [ "$GREEN_STATUS" -ne 0 ] || [ "$REGEN_STATUS" -ne 0 ] || [ "$MIRROR_STATUS" -ne 0 ]; then
  say 'GATE\tFAIL'
  exit 1
fi
say 'GATE\tPASS'
exit 0
