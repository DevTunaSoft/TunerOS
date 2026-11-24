#!/usr/bin/env bash
# QA-44 bounded offline host gate for the timeshift ring filesystem seam.
#   run.sh generate     regenerate the pinned scratch fixture byte-for-byte
#   run.sh verify       RED mutants, RESTORE proof, byte identity, GREEN host gate (default)
#   run.sh gate-only    GREEN host gate without mutation controls
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] &&
   [ "$MODE" != gate-only ] && [ "$MODE" != red-green ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa44"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa44/Qa44Contract.kt"
HOST="$HERE/Qa44HostMain.kt"
STUB="$HERE/stubs/com/devtunasoft/tuneros/core/TimeshiftRing.kt"
PRODUCTION="$ROOT/app/src/main/java/com/devtunasoft/tuneros/AndroidTimeshiftRing.kt"
MUTATOR="$HERE/mutate.py"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa44-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa44-no-tool\t$tool"
    exit 2
  fi
done
for file in "$CONTRACT" "$HOST" "$STUB" "$PRODUCTION" "$MUTATOR"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa44-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa44-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="$(native_path "${TMPDIR:-/tmp}/qa44-build-$$")"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutants" "$BUILD/reports" "$BUILD/generated"
BUILD_N="$(native_dir "$BUILD")"
CLASSES_N="$(native_dir "$BUILD/classes")"

timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$CLASSES_N" -d "$CLASSES_N" \
    "$(native_path "$PRODUCTION")" "$(native_path "$STUB")" \
    "$(native_path "$CONTRACT")" "$(native_path "$HOST")" \
    > "$BUILD/compile.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa44-compile\tsee scratch qa44-build/compile.log'
  grep -E 'error:|warning:' "$BUILD/compile.log" | head -n 12
  exit 1
fi
say "COMPILE\tPASS\tqa44-host-gate\tkotlin2.0.21+jvm17\theap=96m"

run_gate() {
  local mode="$1"; local dir="$2"; local mutant="${3:-}"; local report="$4"
  local -a args
  args=("$mode" "$(native_path "$dir")" "$(native_path "$PRODUCTION")")
  if [ -n "$mutant" ]; then args+=("$mutant"); fi
  timeout 120 "$JH/java" -Xmx96m -Xss1m -cp "$CLASSES_N;$STDLIB" \
    com.devtunasoft.tuneros.qa44.Qa44HostMainKt "${args[@]}" > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

first_code() {
  grep '^VERDICT' "$1" | sed -n 's/.*first=\([A-Z_0-9]*\).*/\1/p' | tail -1
}

if [ "$MODE" = generate ]; then
  run_gate generate "$FIXTURES" '' "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa44-generate\tsee scratch generate log'
    exit 1
  fi
  grep -E '^(GENERATE|VERDICT)' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa44-no-fixtures\trun.sh generate first'
  exit 2
fi

BEFORE_FIX="$(tree_hash "$FIXTURES")"
BEFORE_SRC="$(sha256sum "$PRODUCTION" | awk '{print $1}')"

# Independent byte-identical regeneration proof into scratch (committed fixture is read-only here).
run_gate generate "$BUILD/generated" '' "$BUILD/reports/regen.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa44-regen'
  exit 1
fi
REGEN_OK=1
for name in contract.txt dimensions.tsv cases.tsv playlist.txt sha256.txt; do
  if ! cmp -s "$FIXTURES/$name" "$BUILD/generated/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa44-byte-identity\tcommitted fixture differs from deterministic regeneration'
  exit 1
fi
say 'BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes'

if [ "$MODE" != gate-only ]; then
  # name | pinned FIRST code
  MUTANTS='sha-drift|MANIFEST_MISMATCH
byte-drift|BYTE_IDENTITY
header-drop|HEADER_STRICT
field-extra|FIELD_COUNT
family-unknown|FAMILY_CODE
expect-flip|CORRUPT_EXPECTATION
cap-loosen|INT_BOUNDS
privacy-leak|DIAG_LEAK
atomicity-marker|ATOMICITY_MARKER'
  RED_FAIL=0
  while IFS='|' read -r mutant expected; do
    [ -z "$mutant" ] && continue
    python3 "$MUTATOR" "$FIXTURES" "$BUILD/mutants/$mutant" "$mutant" > "$BUILD/reports/mutate-$mutant.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tqa44-mutate\t$mutant"
      RED_FAIL=1
      continue
    fi
    report="$BUILD/reports/red-$mutant.log"
    run_gate verify-mutant "$BUILD/mutants/$mutant" "$mutant" "$report"
    rc=$?
    got="$(first_code "$report")"
    if [ "$rc" -eq 0 ] || [ "$got" != "$expected" ]; then
      say "FAIL\tqa44-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
      RED_FAIL=1
    else
      say "RED\tPASS\t$mutant\trc=$rc\tfirst=$got"
    fi
  done <<< "$MUTANTS"
  if [ "$RED_FAIL" -ne 0 ]; then
    say 'GATE\tFAIL\tred-controls'
    exit 1
  fi
fi

GREEN="$BUILD/reports/green.log"
run_gate verify "$FIXTURES" '' "$GREEN"
rc=$?
if [ "$rc" -ne 0 ]; then
  say 'FAIL\tqa44-green'
  grep -E '^(CHECK|VERDICT|HEAP|BOUNDS)' "$GREEN" | grep -E 'FAIL|VERDICT' | head -n 12
  exit 1
fi
grep -E '^(COUNTS|BOUNDS|HEAP|SUMMARY|VERDICT|DIAG_SCAN)' "$GREEN"
if ! grep -q '^DIAG_SCAN.PASS' "$GREEN"; then
  say 'FAIL\tqa44-diagnostic-privacy'
  exit 1
fi

AFTER_FIX="$(tree_hash "$FIXTURES")"
AFTER_SRC="$(sha256sum "$PRODUCTION" | awk '{print $1}')"
if [ "$BEFORE_FIX" != "$AFTER_FIX" ] || [ "$BEFORE_SRC" != "$AFTER_SRC" ]; then
  say 'FAIL\tqa44-restore\tproduction-or-fixture-bytes-changed'
  exit 1
fi
say "RESTORE\tPASS\tfixture=$AFTER_FIX\tproduction=$AFTER_SRC"
say 'GATE\tPASS'
exit 0
