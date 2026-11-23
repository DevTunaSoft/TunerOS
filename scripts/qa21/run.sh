#!/usr/bin/env bash
# QA-21 offline, zero-new-dependency bounded host gate.
#   run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
#   run.sh verify      RED mutants + RESTORE proof + GREEN gate + JVM mirror (default)
#   run.sh red-green   alias for verify
#   run.sh gate-only   GREEN gate without mutation controls
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] &&
   [ "$MODE" != red-green ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa21"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa21/Qa21Contract.kt"
MUTATOR="$HERE/mutate.py"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa21-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk find python3 sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa21-no-tool\t$tool"
    exit 2
  fi
done
for file in "$CONTRACT" "$MUTATOR"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa21-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa21-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa21-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutants" "$BUILD/reports"
CLASSES_N="$(native_dir "$BUILD/classes")"

if ! timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$CLASSES_N" -d "$CLASSES_N" "$(native_path "$CONTRACT")" \
    > "$BUILD/compile.log" 2>&1; then
  say 'FAIL\tqa21-compile\tsee scratch qa21-build/compile.log'
  exit 2
fi
say "COMPILE\tPASS\tqa21-host-gate\tkotlin2.0.21+jvm17\tfiles=1\theap=768m"

run_gate() {
  local mode="$1"; local dir="$2"; local report="$3"
  local native_fixture
  if [ -n "$dir" ]; then native_fixture="$(native_path "$dir")"; else native_fixture=''; fi
  timeout 120 "$JH/java" -Xmx96m -Xss1m -cp "$CLASSES_N;$STDLIB" \
    com.devtunasoft.tuneros.qa21.Qa21Contract "$mode" $native_fixture \
    > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

if [ "$MODE" = generate ]; then
  run_gate generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa21-generate\tsee scratch qa21-build/reports/generate.log'
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa21-no-fixtures\trun.sh generate first'
  exit 2
fi

BEFORE="$(tree_hash "$FIXTURES")"

if [ "$MODE" != gate-only ]; then
  # name | gate mode | pinned first failure code
  MUTANTS='byte-drift|verify|BYTE_IDENTITY
sha-drift|verify-mutant-raw|MANIFEST_MISMATCH
header-missing|verify-mutant|HEADER_STRICT
field-count|verify-mutant|FIELD_COUNT
int-bounds|verify-mutant|INT_BOUNDS
enum-unknown|verify-mutant|ENUM_STRICT
corrupt-accept|verify-mutant|CORRUPT_REJECT
outcome-mismatch|verify-mutant|OUTCOME_MISMATCH
cap-shrink|verify-mutant|CAP_BINDING
leak-inject|verify-mutant|PRIVACY_LEAK'

  red_count=0
  while IFS='|' read -r name mutant_mode pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/$name"
    python3 "$(native_path "$MUTATOR")" "$(native_path "$FIXTURES")" \
      "$(native_path "$scratch")" "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
    mstat=$?
    if [ "$mstat" -ne 0 ]; then
      say "FAIL\tMUTANT_SETUP\t$name"
      red_count=$((red_count+1))
      continue
    fi
    run_gate "$mutant_mode" "$scratch" "$BUILD/reports/$name.report"
    gstat=$?
    first=$(grep '^FIRST' "$BUILD/reports/$name.report" 2>/dev/null | head -1 | awk -F '\t' '{print $2}')
    if [ "$gstat" -ne 0 ] && [ "$first" = "$pinned" ]; then
      say "MUTANT\tPASS\t$name\tmode=$mutant_mode\tfirst=$first"
    else
      say "MUTANT\tFAIL\t$name\tmode=$mutant_mode\texit=$gstat\tfirst=${first:-NONE}\twant=$pinned"
      red_count=$((red_count+1))
    fi
  done <<< "$MUTANTS"

  AFTER="$(tree_hash "$FIXTURES")"
  if [ "$BEFORE" = "$AFTER" ]; then
    say "RESTORE\tPASS\tfixture-tree-sha256=$AFTER"
  else
    say "RESTORE\tFAIL\tbefore=$BEFORE\tafter=$AFTER"
    exit 1
  fi
  if [ "$red_count" -ne 0 ]; then
    say "RED\tFAIL\tmutants_with_wrong_first=$red_count"
    exit 1
  fi
  say 'RED\tPASS\tmutants=10\tpinned_first_codes=10'
fi

run_gate verify "$FIXTURES" "$BUILD/reports/green.report"
gstat=$?
if [ "$gstat" -ne 0 ]; then
  say 'FAIL\tqa21-green\tsee scratch qa21-build/reports/green.report'
  grep -E '^(CHECK|SUMMARY)' "$BUILD/reports/green.report" | head -n 12
  exit 1
fi
grep -E '^(CHECK|SUMMARY)' "$BUILD/reports/green.report"

first=$(grep '^FIRST' "$BUILD/reports/green.report" | head -1 | awk -F '\t' '{print $2}')
summary=$(grep '^SUMMARY' "$BUILD/reports/green.report" | head -1)
pass_count=$(printf '%s' "$summary" | awk -F '\t' '{for(i=1;i<=NF;i++) if($i ~ /^pass=/) {split($i,a,"="); print a[2]}}')
fail_count=$(printf '%s' "$summary" | awk -F '\t' '{for(i=1;i<=NF;i++) if($i ~ /^fail=/) {split($i,a,"="); print a[2]}}')
check_count=$(printf '%s' "$summary" | awk -F '\t' '{for(i=1;i<=NF;i++) if($i ~ /^checks=/) {split($i,a,"="); print a[2]}}')
if [ "$first" != 'NONE' ] || [ "$pass_count" != '10' ] || [ "$fail_count" != '0' ] || [ "$check_count" != '10' ]; then
  say "GREEN\tFAIL\tfirst=$first\tpass=$pass_count\tfail=$fail_count\tchecks=$check_count"
  exit 1
fi
say "GREEN\tPASS\tpass=$pass_count\tfail=$fail_count\tchecks=$check_count\tfirst=$first"

run_gate digest '' "$BUILD/reports/digest.report"
actual_mirror=$(grep '^DIGEST' "$BUILD/reports/digest.report" | head -1 | awk -F 'mirror=' '{print $2}' | awk -F '\t' '{print $1}')
if [ "$actual_mirror" != '785c0d4daa352bce671f5736333ee4c500c67d1b898e693249313add188b0512' ]; then
  say "MIRROR\tFAIL\tactual=$actual_mirror"
  exit 1
fi
say "MIRROR\tPASS\tsha256=$actual_mirror"

run_gate device '' "$BUILD/reports/device.report"
dev_pass=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" {n++} END {print n+0}' "$BUILD/reports/device.report")
dev_fail=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" {n++} END {print n+0}' "$BUILD/reports/device.report")
if [ "$dev_pass" -ne 10 ] || [ "$dev_fail" -ne 0 ]; then
  say "JVM_MIRROR\tFAIL\tpass=$dev_pass\tfail=$dev_fail"
  exit 1
fi
say "JVM_MIRROR\tPASS\tpass=$dev_pass\tfail=$dev_fail"
if [ "$MODE" = gate-only ]; then
  say 'GATE\tPASS\tQA-21\thost=PASS\tred=NOT RUN\trestore=NOT RUN\tmirror=PASS'
else
  say 'GATE\tPASS\tQA-21\thost=PASS\tred=PASS\trestore=PASS\tmirror=PASS'
fi
exit 0
