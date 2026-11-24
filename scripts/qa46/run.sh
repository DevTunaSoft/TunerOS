#!/usr/bin/env bash
# QA-46 deterministic zero-dependency host gate runner.
#   run.sh generate   regenerate the pinned scratch fixture byte-for-byte
#   run.sh verify     RED mutants, RESTORE proof, byte identity, GREEN gate
#   run.sh gate-only  byte identity and GREEN gate without negative controls
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != gate-only ]; }; then
  say 'usage: run.sh [generate|verify|gate-only]'
  exit 2
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIX="$ROOT/qa/fixtures/qa46"
PROD="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/BodyLength.kt"
GATE="$HERE/Qa46Gate.java"
MUTATOR="$HERE/mutate.py"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ] || [ ! -x "$JH/javac" ]; then
  say 'BLOCKED\tqa46-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout cygpath; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa46-no-tool\t$tool"
    exit 2
  fi
done
for file in "$PROD" "$GATE" "$MUTATOR"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa46-missing-source\t$(basename "$file")"
    exit 2
  fi
done

CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
find_jar() { find "$CACHE" -name "$1" 2>/dev/null | head -n 1 | while read -r p; do native_path "$p"; done; }
COMPILER="$(find_jar kotlin-compiler-embeddable-2.0.21.jar)"
STDLIB="$(find_jar kotlin-stdlib-2.0.21.jar)"
SCRIPTS="$(find_jar kotlin-script-runtime-2.0.21.jar)"
TROVE="$(find_jar trove4j-1.0.20200330.jar)"
COROUTINES="$(find_jar kotlinx-coroutines-core-jvm-1.6.4.jar)"
ANNOTATIONS="$(find_jar annotations-13.0.jar)"
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ] || [ -z "$SCRIPTS" ] || [ -z "$TROVE" ]; then
  say 'BLOCKED\tqa46-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
K2CP="$COMPILER;$STDLIB;$SCRIPTS;$TROVE;$COROUTINES;$ANNOTATIONS"

SCRATCH="${QA46_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa46-host}"
RUN="$SCRATCH/run-$$"
mkdir -p "$RUN/classes/prod" "$RUN/classes/gate" "$RUN/regen" "$RUN/mut" "$RUN/mut-classes" "$RUN/fx" "$RUN/reports"
RUN_N="$(native_path "$RUN")"
FIX_N="$(native_path "$FIX")"
PROD_N="$(native_path "$PROD")"
GATE_N="$(native_path "$GATE")"
MUTATOR_N="$(native_path "$MUTATOR")"
PROD_CLASSES="$(native_path "$RUN/classes/prod")"
GATE_CLASSES="$(native_path "$RUN/classes/gate")"
CP_GREEN="$PROD_CLASSES;$GATE_CLASSES;$STDLIB"

timeout 300 "$JH/java" -Xmx512m -cp "$K2CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STDLIB" -jvm-target 17 -nowarn -d "$PROD_CLASSES" "$PROD_N" \
  > "$RUN/k2.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa46-compile-production'
  exit 1
fi
timeout 180 "$JH/javac" -encoding UTF-8 -cp "$PROD_CLASSES" -d "$GATE_CLASSES" "$GATE_N" \
  > "$RUN/javac.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa46-compile-gate'
  exit 1
fi
say 'COMPILE\tPASS\tproduction-object-and-zero-dependency-gate'

run_gate() {
  local cp="$1"; shift
  timeout 90 "$JH/java" -Xmx64m -XX:-UsePerfData -cp "$cp" Qa46Gate "$@" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

first_code() { grep '^FIRST' "$1" | sed -n 's/^FIRST[[:space:]]*//p' | tail -n 1; }

if [ "$MODE" = generate ]; then
  OUT="$(run_gate "$CP_GREEN" generate "$FIX_N")"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    say 'FAIL\tqa46-generate'
    exit 1
  fi
  printf '%b\n' "$OUT" | grep -E '^(GENERATE|DIGEST)'
  exit 0
fi

if [ ! -d "$FIX" ]; then
  say 'BLOCKED\tqa46-no-fixtures\trun.sh generate first'
  exit 2
fi

BEFORE_FIX="$(tree_hash "$FIX")"
BEFORE_SRC="$(sha256sum "$PROD" | awk '{print $1}')"

run_gate "$CP_GREEN" generate "$(native_path "$RUN/regen")" > "$RUN/reports/regen.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa46-regen'
  exit 1
fi
REGEN_OK=1
for name in dimensions.tsv cases.tsv corrupt.tsv notes.txt sha256.txt; do
  if ! cmp -s "$FIX/$name" "$RUN/regen/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa46-byte-identity\tcommitted fixture differs from regeneration'
  exit 1
fi
say 'BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes'

MUTANTS='prod-expect-length|EXPECT_LENGTH_TABLE
prod-verdict|VERDICT_TABLE
corrupt-accepted|CORRUPT_REJECT
byte-drift|BYTE_IDENTITY
privacy-leak|PRIVACY_SCAN
manifest-drift|MANIFEST_MISMATCH'
RED_TOTAL=0
RED_PASS=0
if [ "$MODE" = verify ]; then
  while IFS='|' read -r mutant expected; do
    [ -z "$mutant" ] && continue
    REPORT="$RUN/reports/red-$mutant.log"
    if [ "${mutant#prod-}" != "$mutant" ]; then
      DIR="$RUN/mut/$mutant"
      OUTDIR="$RUN/mut-classes/$mutant"
      mkdir -p "$DIR" "$OUTDIR"
      python3 "$MUTATOR_N" "$mutant" "$PROD_N" "$(native_path "$DIR/BodyLength.kt")" \
        > "$RUN/reports/mutate-$mutant.log" 2>&1
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa46-mutate\t$mutant"
        RED_TOTAL=$((RED_TOTAL + 1))
        continue
      fi
      timeout 300 "$JH/java" -Xmx512m -cp "$K2CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -classpath "$STDLIB" -jvm-target 17 -nowarn \
        -d "$(native_path "$OUTDIR")" "$(native_path "$DIR/BodyLength.kt")" \
        > "$RUN/reports/k2-$mutant.log" 2>&1
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa46-mut-compile\t$mutant"
        RED_TOTAL=$((RED_TOTAL + 1))
        continue
      fi
      CP="$(native_path "$OUTDIR");$GATE_CLASSES;$STDLIB"
    else
      DIR="$RUN/fx/$mutant"
      mkdir -p "$DIR"
      python3 "$MUTATOR_N" "$mutant" "$FIX_N" "$(native_path "$DIR")" \
        > "$RUN/reports/mutate-$mutant.log" 2>&1
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa46-mutate\t$mutant"
        RED_TOTAL=$((RED_TOTAL + 1))
        continue
      fi
      CP="$CP_GREEN"
    fi
    if [ "${mutant#prod-}" != "$mutant" ]; then
      run_gate "$CP" verify "$FIX_N" > "$REPORT" 2>&1
    else
      run_gate "$CP" verify "$(native_path "$DIR")" > "$REPORT" 2>&1
    fi
    rc=$?
    got="$(first_code "$REPORT")"
    RED_TOTAL=$((RED_TOTAL + 1))
    if [ "$rc" -eq 0 ] || [ "$got" != "$expected" ]; then
      say "FAIL\tqa46-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
    else
      say "RED\tPASS\t$mutant\trc=$rc\tfirst=$got"
      RED_PASS=$((RED_PASS + 1))
    fi
  done <<< "$MUTANTS"
  if [ "$RED_PASS" -ne 6 ] || [ "$RED_TOTAL" -ne 6 ]; then
    say "FAIL\tqa46-red-summary\tpass=$RED_PASS\ttotal=$RED_TOTAL"
    exit 1
  fi
  say "RED\tSUMMARY\tpass=$RED_PASS\ttotal=$RED_TOTAL"
fi

# Restore every scratch production copy from the pristine backup and prove byte identity.
RESTORE_OK=1
for mutant in prod-expect-length prod-verdict; do
  SRC="$RUN/mut/$mutant/BodyLength.kt"
  if [ -f "$SRC" ]; then
    cp "$PROD" "$SRC"
    if ! cmp -s "$PROD" "$SRC"; then RESTORE_OK=0; fi
  fi
done
if [ "$RESTORE_OK" -ne 1 ]; then
  say 'FAIL\tqa46-restore\tscratch-production-copy'
  exit 1
fi

GREEN="$RUN/reports/green.log"
run_gate "$CP_GREEN" verify "$FIX_N" > "$GREEN" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  say "FAIL\tqa46-green\trc=$rc"
  grep '^CHECK.\tFAIL' "$GREEN" | head -n 12
  grep -E '^(COUNTS|FIRST|VERDICT)' "$GREEN"
  exit 1
fi
grep -E '^(COUNTS|HEAP|FIRST|VERDICT)' "$GREEN"
if ! grep -q '^CHECK.PASS.PRIVACY_SCAN' "$GREEN"; then
  say 'FAIL\tqa46-diagnostic-privacy'
  exit 1
fi

AFTER_FIX="$(tree_hash "$FIX")"
AFTER_SRC="$(sha256sum "$PROD" | awk '{print $1}')"
if [ "$BEFORE_FIX" != "$AFTER_FIX" ] || [ "$BEFORE_SRC" != "$AFTER_SRC" ]; then
  say 'FAIL\tqa46-restore\tproduction-or-fixture-bytes-changed'
  exit 1
fi
say "RESTORE\tPASS\tfixture=$AFTER_FIX\tproduction=$AFTER_SRC\tscratch_copies=byte-identical"
say 'GATE\tPASS'
exit 0
