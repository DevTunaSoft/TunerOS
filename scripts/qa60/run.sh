#!/usr/bin/env bash
# QA-60 bounded offline host gate: AbortableSlot cancellation and late-registration slice.
#   run.sh generate    write the pinned scratch fixture byte for byte
#   run.sh verify      regen proof, RED controls, RESTORE proof, GREEN gate (default)
#   run.sh gate-only   GREEN gate without mutation controls
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
FIXTURES="$ROOT/qa/fixtures/qa60"
HOST="$HERE/Qa60HostMain.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa60/Qa60Contract.kt"
MUTATOR="$HERE/mutate.py"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/AbortableSlot.kt"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa60-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa60-no-tool\t$tool"
    exit 2
  fi
done
for file in "$HOST" "$CONTRACT" "$MUTATOR" "$CORE"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa60-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa60-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done
RUNTIME_CP="$(native_path "$STDLIB");"

SCRATCH_N='C:/Users/X1/AppData/Local/hermes/cache/scratch'
BUILD_N="$SCRATCH_N/qa60-build-$$-$MODE"
BUILD="$BUILD_N"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutant-classes" "$BUILD/mutants" "$BUILD/reports" "$BUILD/generated"
CLASSES_N="$(native_dir "$BUILD/classes")"
MUTANT_CLASSES_N="$(native_dir "$BUILD/mutant-classes")"
say "SCRATCH\t$BUILD_N"

compile_gate() {
  local outdir="$1"; local production="$2"; local contract="$3"; local log="$4"
  rm -rf "$outdir"
  mkdir -p "$outdir"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$outdir" -d "$outdir" \
    "$(native_path "$production")" "$(native_path "$contract")" "$(native_path "$HOST")" > "$log" 2>&1
}

compile_gate "$CLASSES_N" "$CORE" "$CONTRACT" "$BUILD/compile.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa60-compile\tsee scratch compile.log'
  grep -E 'error:|warning:' "$BUILD/compile.log" | head -n 24
  exit 1
fi
say 'COMPILE\tPASS\tqa60-host-gate-real-production\tkotlin2.0.21+jvm17'

run_gate() {
  local classes="$1"; local gate_mode="$2"; local fixture_dir="$3"; local report="$4"
  timeout 180 "$JH/java" -Xmx96m -Xss1m -cp "$RUNTIME_CP$classes" \
    com.devtunasoft.tuneros.qa60.Qa60HostMain "$gate_mode" "$(native_path "$fixture_dir")" \
    > "$report" 2>&1
}

if [ "$MODE" = generate ]; then
  if [ ! -d "$FIXTURES" ]; then mkdir -p "$FIXTURES"; fi
  run_gate "$CLASSES_N" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa60-generate\tsee scratch generate log'
    grep -E 'EXCEPTION|FIRST|FAIL' "$BUILD/reports/generate.log" | head -n 10
    exit 1
  fi
  grep -E '^(GENERATE)' "$BUILD/reports/generate.log"
  DIGEST=$(sha256sum "$FIXTURES/rows.tsv" | awk '{print $1}')
  say "GENERATE_DIGEST\t$DIGEST"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa60-no-fixtures\trun.sh generate first'
  exit 2
fi

NAMES='.gitattributes rows.tsv dimensions.tsv corrupt.txt report-copy.txt sha256.txt'
tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
first_code() { grep '^FIRST' "$1" | awk '{print $2}' | tail -1; }
owned_files() {
  find "$ROOT/scripts/qa60" "$ROOT/qa/fixtures/qa60" \
    "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa60" \
    "$ROOT/app/src/androidTest/AndroidManifest.xml" -type f | LC_ALL=C sort
}
source_hash() {
  (for f in $(owned_files); do sha256sum "$f"; done) | sha256sum | awk '{print $1}'
}
production_hash() { sha256sum "$CORE" | awk '{print $1}'; }

HASH_BEFORE="$(source_hash)"
FIXTURE_BEFORE="$(tree_hash "$FIXTURES")"
PROD_BEFORE="$(production_hash)"

run_gate "$CLASSES_N" generate "$BUILD/generated" "$BUILD/reports/regen.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa60-regen'
  exit 1
fi
REGEN_OK=1
for name in $NAMES; do
  if [ "$name" = .gitattributes ]; then
    printf '* -text\n.gitattributes -text\n' > "$BUILD/generated/.gitattributes"
  fi
  if ! cmp -s "$FIXTURES/$name" "$BUILD/generated/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa60-byte-identity\tcommitted fixture differs from deterministic regeneration'
  exit 1
fi
say "BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes\tfiles=6"

if [ "$MODE" != gate-only ]; then
  MUTANTS='byte-drift|MANIFEST_CONSISTENT|verify-manifest|fixture
byte-flip-remanifested|PINNED_DIGEST|verify-pinned|fixture
digest-drift|PINNED_DIGEST|verify-pinned|fixture
cap-drift|CAP_BINDING|verify-semantic|fixture
verdict-drift|CONTRACT_VERDICTS|verify-semantic|fixture
corrupt-relax|CORRUPT_FAIL_CLOSED|verify-semantic|fixture
privacy-leak|PRIVACY_SCAN|verify-semantic|fixture
late-register-escapes|LATE_REGISTRATION|verify-semantic|source
reset-keeps-abort|RESET_CLEARS_ABORT|verify-semantic|source
stale-release-clears|RELEASE_STALE|verify-semantic|source
abort-twice|ABORT_IDEMPOTENT|verify-semantic|source
reset-keeps-current|RESET_FORGETS_CURRENT|verify-semantic|source'
  RED_PASS=0
  RED_FAIL=0
  while IFS='|' read -r mutant expected gate_mode kind; do
    [ -z "$mutant" ] && continue
    QA60_PROD_SRC="$CORE" python3 "$MUTATOR" "$FIXTURES" "$BUILD/mutants/$mutant" "$mutant" \
      > "$BUILD/reports/mutate-$mutant.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tqa60-mutate\t$mutant"
      grep 'MUTATE' "$BUILD/reports/mutate-$mutant.log" | head -n 3
      RED_FAIL=$((RED_FAIL + 1))
      continue
    fi
    classes="$CLASSES_N"
    if [ "$kind" = source ]; then
      compile_gate "$MUTANT_CLASSES_N" "$BUILD/mutants/$mutant/prod/AbortableSlot.kt" \
        "$CONTRACT" "$BUILD/reports/compile-$mutant.log"
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa60-mutant-compile\t$mutant"
        grep -E 'error:' "$BUILD/reports/compile-$mutant.log" | head -n 8
        RED_FAIL=$((RED_FAIL + 1))
        continue
      fi
      classes="$MUTANT_CLASSES_N"
    fi
    report="$BUILD/reports/red-$mutant.log"
    run_gate "$classes" "$gate_mode" "$BUILD/mutants/$mutant/fixture" "$report"
    rc=$?
    got="$(first_code "$report")"
    if [ "$rc" -eq 0 ] || [ "$got" != "$expected" ]; then
      say "FAIL\tqa60-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
      RED_FAIL=$((RED_FAIL + 1))
    else
      say "RED\tPASS\t$mutant\tfirst=$got\trc=$rc"
      RED_PASS=$((RED_PASS + 1))
    fi
  done <<EOF
$MUTANTS
EOF
  say "RED_SUMMARY\tpass=$RED_PASS\tfail=$RED_FAIL"
  if [ "$RED_FAIL" -ne 0 ] || [ "$RED_PASS" -ne 12 ]; then
    say 'FAIL\tqa60-red-incomplete'
    exit 1
  fi

  HASH_AFTER="$(source_hash)"
  FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
  PROD_AFTER="$(production_hash)"
  if [ "$HASH_BEFORE" != "$HASH_AFTER" ] || [ "$FIXTURE_BEFORE" != "$FIXTURE_AFTER" ] || \
    [ "$PROD_BEFORE" != "$PROD_AFTER" ]; then
    say 'FAIL\tqa60-restore\towned-or-fixture-or-production-bytes-changed'
    exit 1
  fi
  say "RESTORE\tPASS\towned_sources_and_fixtures_and_production_byte_identical\tsha=$HASH_AFTER\tprod=$PROD_AFTER\tfixture=$FIXTURE_AFTER"
  if ! git -C "$ROOT" diff --quiet; then
    say 'FAIL\tqa60-tracked-diff'
    exit 1
  fi
  say 'RESTORE\tPASS\ttracked_diff_empty'
fi

run_gate "$CLASSES_N" verify "$FIXTURES" "$BUILD/reports/green.log"
GREEN_RC=$?
grep -E '^(CHECK|MISMATCH|MEM|THREADS|FIRST|SUMMARY|VERDICT)' "$BUILD/reports/green.log"
if [ "$GREEN_RC" -ne 0 ]; then
  say 'FAIL\tqa60-green'
  exit 1
fi
PASSES=$(awk -F '\t' '$1=="CHECK" && $3=="PASS" {n++} END{print n+0}' "$BUILD/reports/green.log")
FAILS=$(awk -F '\t' '$1=="CHECK" && $3=="FAIL" {n++} END{print n+0}' "$BUILD/reports/green.log")
FIRST=$(grep '^FIRST' "$BUILD/reports/green.log" | awk '{print $2}' | tail -1)
HEAP_MAX=$(grep '^MEM' "$BUILD/reports/green.log" | sed -n 's/.*heapMax=\([0-9]*\).*/\1/p')
if [ -z "$HEAP_MAX" ] || [ "$HEAP_MAX" -gt 100663296 ]; then
  say "FAIL\tqa60-heap-bound\theapMax=$HEAP_MAX"
  exit 1
fi
say "HEAP\tPASS\tlimit=100663296\tobservedMax=$HEAP_MAX"
THREAD_BEFORE=$(grep '^THREADS' "$BUILD/reports/green.log" | sed -n 's/.*before=\([0-9]*\).*/\1/p')
THREAD_AFTER=$(grep '^THREADS' "$BUILD/reports/green.log" | sed -n 's/.*after=\([0-9]*\).*/\1/p')
if [ "$THREAD_BEFORE" != "$THREAD_AFTER" ]; then
  say "FAIL\tqa60-thread-drift\tbefore=$THREAD_BEFORE\tafter=$THREAD_AFTER"
  exit 1
fi
if [ "$FIRST" != NONE ] || [ "$FAILS" -ne 0 ] || [ "$PASSES" -ne 17 ]; then
  say "FAIL\tqa60-green-summary\tfirst=$FIRST\tpass=$PASSES\tfail=$FAILS"
  exit 1
fi
say "THREAD_DRIFT\tPASS\tdrift=0\tbefore=$THREAD_BEFORE\tafter=$THREAD_AFTER"
say "GREEN_SUMMARY\tchecks=$PASSES\tfail=$FAILS"
say 'VERDICT\tPASS\tqa60-host-gate'
exit 0
