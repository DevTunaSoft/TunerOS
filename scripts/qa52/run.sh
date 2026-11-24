#!/usr/bin/env bash
# QA-52 bounded offline host gate: cross-provider player-reuse header isolation.
#   run.sh generate   regenerate the pinned scratch fixture byte-for-byte
#   run.sh verify     regen proof, RED controls, restore proof, GREEN gate (default)
#   run.sh gate-only  GREEN gate without mutation controls
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa52"
HOST="$HERE/Qa52HostMain.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa52/Qa52Contract.kt"
MUTATOR="$HERE/mutate.py"
PROD="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/PlayerReusePolicy.kt"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa52-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa52-no-tool\t$tool"
    exit 2
  fi
done
for file in "$HOST" "$CONTRACT" "$MUTATOR" "$PROD"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa52-missing-source\t$(basename "$file")"
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
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
if [ -z "$JARS" ] || [ -z "$STDLIB" ]; then
  say 'BLOCKED\tqa52-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done

BUILD="$(native_path "${TMPDIR:-/tmp}/qa52-build-$$")"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/prod-classes" "$BUILD/mutants" "$BUILD/reports" \
         "$BUILD/generated" "$BUILD/inputs/prod" "$BUILD/inputs/fixtures"
CLASSES_N="$(native_dir "$BUILD/classes")"
PROD_CLASSES_N="$(native_dir "$BUILD/prod-classes")"
RUNTIME_CP="$(native_path "$STDLIB");"

compile_gate() {
  local outdir="$1"; local prodfile="$2"; local log="$3"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$outdir" -d "$outdir" \
    "$(native_path "$prodfile")" "$(native_path "$CONTRACT")" "$(native_path "$HOST")" \
    > "$log" 2>&1
}

compile_gate "$CLASSES_N" "$PROD" "$BUILD/compile.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa52-compile\tsee scratch qa52-build/compile.log'
  grep -E 'error:|warning:' "$BUILD/compile.log" | head -n 16
  exit 1
fi
say 'COMPILE\tPASS\tqa52-host-gate\tkotlin2.0.21+jvm17\treal-PlayerReusePolicy'

run_gate() {
  local classes="$1"; local gate_mode="$2"; local fixture_dir="$3"; local report="$4"
  timeout 180 "$JH/java" -Xmx96m -Xss1m -cp "$RUNTIME_CP$classes" \
    com.devtunasoft.tuneros.qa52.Qa52HostMain "$gate_mode" "$(native_path "$fixture_dir")" \
    > "$report" 2>&1
}

if [ "$MODE" = generate ]; then
  run_gate "$CLASSES_N" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa52-generate\tsee scratch generate log'
    exit 1
  fi
  grep -E '^(GENERATE|VERDICT)' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa52-no-fixtures\trun.sh generate first'
  exit 2
fi

NAMES='cases.tsv corrupt.tsv dimensions.tsv sha256.txt README.md'
tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
first_code() { grep '^FIRST' "$1" | awk '{print $2}' | tail -1; }
owned_files() {
  find "$ROOT/scripts/qa52" "$ROOT/app/src/androidTest" -type f | LC_ALL=C sort
}
source_hash() {
  (for f in $(owned_files) "$PROD"; do sha256sum "$f"; done) | sha256sum | awk '{print $1}'
}

cp "$PROD" "$BUILD/inputs/prod/PlayerReusePolicy.kt"
for name in $NAMES; do cp "$FIXTURES/$name" "$BUILD/inputs/fixtures/$name"; done
HASH_BEFORE="$(source_hash)"
FIXTURE_BEFORE="$(tree_hash "$FIXTURES")"

run_gate "$CLASSES_N" generate "$BUILD/generated" "$BUILD/reports/regen.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa52-regen'
  exit 1
fi
REGEN_OK=1
for name in $NAMES; do
  if ! cmp -s "$FIXTURES/$name" "$BUILD/generated/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa52-byte-identity\tcommitted fixture differs from deterministic regeneration'
  exit 1
fi
say 'BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes\tfiles=5'

if [ "$MODE" != gate-only ]; then
  MUTANTS='sha-drift|MANIFEST_CONSISTENT|verify
byte-drift|BYTE_IDENTITY|verify
header-drop|HEADER_STRICT|verify-mutant
field-extra|FIELD_COUNT|verify-mutant
corrupt-relax|CORRUPT_FAIL_CLOSED|verify-mutant
privacy-leak|PRIVACY_SCAN|verify-mutant
verdict-flip|CONTRACT_VERDICT|verify-mutant
count-drift|CASE_COUNT|verify-mutant
digest-drift|PINNED_DIGEST|verify
prod-guard|CONTRACT_VERDICT|verify-prod'
  RED_PASS=0
  RED_FAIL=0
  while IFS='|' read -r mutant expected gate_mode; do
    [ -z "$mutant" ] && continue
    python3 "$MUTATOR" "$FIXTURES" "$BUILD/mutants/$mutant" "$mutant" "$PROD" \
      > "$BUILD/reports/mutate-$mutant.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tqa52-mutate\t$mutant"
      RED_FAIL=$((RED_FAIL + 1))
      continue
    fi
    classes="$CLASSES_N"
    if [ "$gate_mode" = verify-prod ]; then
      compile_gate "$PROD_CLASSES_N" "$BUILD/mutants/$mutant/PlayerReusePolicy.kt" \
        "$BUILD/reports/compile-$mutant.log"
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa52-mutant-compile\t$mutant"
        RED_FAIL=$((RED_FAIL + 1))
        continue
      fi
      classes="$PROD_CLASSES_N"
      gate_mode=verify
    fi
    report="$BUILD/reports/red-$mutant.log"
    run_gate "$classes" "$gate_mode" "$BUILD/mutants/$mutant" "$report"
    rc=$?
    got="$(first_code "$report")"
    if [ "$rc" -eq 0 ] || [ "$got" != "$expected" ]; then
      say "FAIL\tqa52-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
      RED_FAIL=$((RED_FAIL + 1))
    else
      say "RED\tPASS\t$mutant\trc=$rc\tfirst=$got"
      RED_PASS=$((RED_PASS + 1))
    fi
  done <<< "$MUTANTS"
  say "RED_SUMMARY\tpass=$RED_PASS\tfail=$RED_FAIL"
  if [ "$RED_FAIL" -ne 0 ]; then
    say 'GATE\tFAIL\tred-controls'
    exit 1
  fi
fi

GREEN="$BUILD/reports/green.log"
run_gate "$CLASSES_N" verify "$FIXTURES" "$GREEN"
rc=$?
if [ "$rc" -ne 0 ]; then
  say 'FAIL\tqa52-green'
  grep -E '^(CHECK|SUMMARY|VERDICT)' "$GREEN" | grep -E 'FAIL|VERDICT' | head -n 16
  exit 1
fi
grep -E '^(SUMMARY|VERDICT)' "$GREEN"

HASH_AFTER="$(source_hash)"
FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
RESTORE_OK=1
for name in $NAMES; do
  if ! cmp -s "$BUILD/inputs/fixtures/$name" "$FIXTURES/$name"; then RESTORE_OK=0; fi
done
if ! cmp -s "$BUILD/inputs/prod/PlayerReusePolicy.kt" "$PROD"; then RESTORE_OK=0; fi
if [ "$HASH_BEFORE" != "$HASH_AFTER" ] || [ "$FIXTURE_BEFORE" != "$FIXTURE_AFTER" ] || [ "$RESTORE_OK" -ne 1 ]; then
  say 'FAIL\tqa52-restore\tsource-or-fixture-changed'
  exit 1
fi
say "RESTORE\tPASS\towned-sources-and-fixtures-byte-identical\tinputs=6"
say 'VERDICT\tPASS\tqa52-host-gate'
exit 0
