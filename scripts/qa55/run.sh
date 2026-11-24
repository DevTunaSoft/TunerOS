#!/usr/bin/env bash
# QA-55 bounded offline host gate: XMLTV category/genre token admission.
#   run.sh generate   regenerate the pinned scratch fixture byte for byte
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
FIXTURES="$ROOT/qa/fixtures/qa55"
HOST="$HERE/Qa55HostMain.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa55/Qa55Contract.kt"
MUTATOR="$HERE/mutate.py"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa55-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa55-no-tool\t$tool"
    exit 2
  fi
done
for file in "$HOST" "$CONTRACT" "$MUTATOR" "$CORE/GuideParser.kt" "$CORE/Models.kt" "$CORE/XmltvProlog.kt"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa55-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa55-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done

SCRATCH_ROOT_N="C:/Users/X1/AppData/Local/hermes/cache/scratch"
BUILD_N="$SCRATCH_ROOT_N/qa55-build-$$"
BUILD="$BUILD_N"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutant-classes" "$BUILD/mutants" "$BUILD/reports" \
         "$BUILD/generated" "$BUILD/inputs/prod" "$BUILD/inputs/fixtures"
CLASSES_N="$(native_dir "$BUILD/classes")"
MUTANT_CLASSES_N="$(native_dir "$BUILD/mutant-classes")"
RUNTIME_CP="$(native_path "$STDLIB");"
say "SCRATCH\t$BUILD_N"

cp "$CORE/GuideParser.kt" "$BUILD/inputs/prod/GuideParser.kt"
cp "$CORE/Models.kt" "$BUILD/inputs/prod/Models.kt"
cp "$CORE/XmltvProlog.kt" "$BUILD/inputs/prod/XmltvProlog.kt"

compile_gate() {
  local outdir="$1"; local srcdir="$2"; local log="$3"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$outdir" -d "$outdir" \
    "$(native_path "$srcdir/GuideParser.kt")" \
    "$(native_path "$srcdir/Models.kt")" \
    "$(native_path "$srcdir/XmltvProlog.kt")" \
    "$(native_path "$CONTRACT")" "$(native_path "$HOST")" \
    > "$log" 2>&1
}

compile_gate "$CLASSES_N" "$BUILD/inputs/prod" "$BUILD/compile.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa55-compile\tsee scratch qa55-build/compile.log'
  grep -E 'error:|warning:' "$BUILD/compile.log" | head -n 20
  exit 1
fi
say 'COMPILE\tPASS\tqa55-host-gate\tkotlin2.0.21+jvm17\treal-GuideParser'

run_gate() {
  local classes="$1"; local gate_mode="$2"; local fixture_dir="$3"; local report="$4"
  timeout 180 "$JH/java" -Xmx96m -Xss1m -cp "$RUNTIME_CP$classes" \
    com.devtunasoft.tuneros.qa55.Qa55HostMain "$gate_mode" "$(native_path "$fixture_dir")" \
    > "$report" 2>&1
}

if [ "$MODE" = generate ]; then
  run_gate "$CLASSES_N" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa55-generate\tsee scratch generate log'
    grep -E 'EXCEPTION|error|FAIL' "$BUILD/reports/generate.log" | head -n 10
    exit 1
  fi
  grep -E '^(GENERATE|VERDICT)' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa55-no-fixtures\trun.sh generate first'
  exit 2
fi

NAMES='dimensions.tsv cases.tsv corrupt.txt guide.xmltv report-copy.txt sha256.txt'
tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
first_code() { grep '^FIRST' "$1" | awk '{print $2}' | tail -1; }
owned_files() {
  find "$ROOT/scripts/qa55" "$ROOT/qa/fixtures/qa55" "$ROOT/app/src/androidTest" -type f | LC_ALL=C sort
}
source_hash() {
  (for f in $(owned_files) "$CORE/GuideParser.kt" "$CORE/Models.kt" "$CORE/XmltvProlog.kt"; do
     sha256sum "$f"; done) | sha256sum | awk '{print $1}'
}

HASH_BEFORE="$(source_hash)"
FIXTURE_BEFORE="$(tree_hash "$FIXTURES")"
for name in $NAMES; do cp "$FIXTURES/$name" "$BUILD/inputs/fixtures/$name"; done

run_gate "$CLASSES_N" generate "$BUILD/generated" "$BUILD/reports/regen.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa55-regen'
  exit 1
fi
REGEN_OK=1
for name in $NAMES; do
  if ! cmp -s "$FIXTURES/$name" "$BUILD/generated/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa55-byte-identity\tcommitted fixture differs from deterministic regeneration'
  exit 1
fi
say "BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes\tfiles=6"

if [ "$MODE" != gate-only ]; then
  MUTANTS='byte-drift|BYTE_IDENTITY|verify-byte|fixture
sha-drift|MANIFEST_CONSISTENT|verify-manifest|fixture
digest-drift|PINNED_DIGEST|verify-pinned|fixture
header-drop|HEADER_STRICT|verify-mutant|fixture
field-extra|ROW_BOUNDS|verify-mutant|fixture
case-count|CAP_BINDING|verify-mutant|fixture
norm-flip|TOKEN_NORMALIZATION|verify-mutant|fixture
corrupt-relax|CORRUPT_FAIL_CLOSED|verify-mutant|fixture
privacy-leak|PRIVACY_SCAN|verify-mutant|fixture
prod-depth-guard|PROD_CORRUPT_REJECT|verify-mutant|production
prod-blank-title|PROD_ADMISSION|verify-mutant|production'
  RED_PASS=0
  RED_FAIL=0
  while IFS='|' read -r mutant expected gate_mode kind; do
    [ -z "$mutant" ] && continue
    python3 "$MUTATOR" "$FIXTURES" "$BUILD/mutants/$mutant" "$mutant" "$BUILD/inputs/prod" \
      > "$BUILD/reports/mutate-$mutant.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tqa55-mutate\t$mutant"
      RED_FAIL=$((RED_FAIL + 1))
      continue
    fi
    classes="$CLASSES_N"
    if [ "$kind" = production ]; then
      compile_gate "$MUTANT_CLASSES_N" "$BUILD/mutants/$mutant/prod" "$BUILD/reports/compile-$mutant.log"
      if [ "$?" -ne 0 ]; then
        say "FAIL\tqa55-mutant-compile\t$mutant"
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
      say "FAIL\tqa55-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
      RED_FAIL=$((RED_FAIL + 1))
    else
      say "RED\tPASS\t$mutant\tfirst=$got\trc=$rc"
      RED_PASS=$((RED_PASS + 1))
    fi
  done <<EOF
$MUTANTS
EOF
  say "RED_SUMMARY\tpass=$RED_PASS\tfail=$RED_FAIL"
  if [ "$RED_FAIL" -ne 0 ] || [ "$RED_PASS" -ne 11 ]; then
    say 'FAIL\tqa55-red-incomplete'
    exit 1
  fi

  HASH_AFTER="$(source_hash)"
  FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
  if [ "$HASH_BEFORE" != "$HASH_AFTER" ] || [ "$FIXTURE_BEFORE" != "$FIXTURE_AFTER" ]; then
    say 'FAIL\tqa55-restore\towned-or-production-bytes-changed'
    exit 1
  fi
  say "RESTORE\tPASS\towned_sources_and_fixtures_byte_identical\tsha=$HASH_AFTER"
  if ! git -C "$ROOT" diff --quiet -- "$CORE/GuideParser.kt" "$CORE/Models.kt" "$CORE/XmltvProlog.kt"; then
    say 'FAIL\tqa55-production-diff'
    exit 1
  fi
  say 'RESTORE\tPASS\tproduction_diff_empty'
fi

run_gate "$CLASSES_N" verify "$FIXTURES" "$BUILD/reports/green.log"
GREEN_RC=$?
grep -E '^(CHECK|MEM|THREADS|FIRST|SUMMARY|VERDICT)' "$BUILD/reports/green.log"
if [ "$GREEN_RC" -ne 0 ]; then
  say 'FAIL\tqa55-green'
  exit 1
fi
PASSES=$(awk -F '\t' '$1=="CHECK" && $3=="PASS" {n++} END{print n+0}' "$BUILD/reports/green.log")
FAILS=$(awk -F '\t' '$1=="CHECK" && $3=="FAIL" {n++} END{print n+0}' "$BUILD/reports/green.log")
HEAP_MAX=$(grep '^MEM' "$BUILD/reports/green.log" | sed -n 's/.*heapMax=\([0-9]*\).*/\1/p')
if [ -z "$HEAP_MAX" ] || [ "$HEAP_MAX" -gt 100663296 ]; then
  say "FAIL\tqa55-heap-bound\theapMax=$HEAP_MAX"
  exit 1
fi
say "HEAP\tPASS\tlimit=100663296\tobservedMax=$HEAP_MAX"
if awk -F '\t' '$1=="CHECK" && $2=="THREAD_DRIFT" && $3=="PASS" {found=1} END{exit !found}' "$BUILD/reports/green.log"; then
  say 'THREAD_DRIFT\tPASS\tdrift=0'
else
  say 'FAIL\tqa55-thread-drift'
  exit 1
fi
say "GREEN_SUMMARY\tchecks=$PASSES\tfail=$FAILS"
say 'VERDICT\tPASS\tqa55-host-gate'
exit 0
