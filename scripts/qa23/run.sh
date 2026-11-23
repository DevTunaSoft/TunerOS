#!/usr/bin/env bash
# QA-23 bounded, offline, zero-new-dependency host gate for DASH sidx admission.
#   run.sh generate    regenerate pinned scratch fixtures byte-for-byte
#   run.sh verify      RED controls + restore proof + GREEN gate + privacy scan (default)
#   run.sh red-green   alias for verify
#   run.sh gate-only   GREEN gate and privacy scan without mutant controls
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
FIXTURES="$ROOT/qa/fixtures/qa23"
GATE="$HERE/Qa23Gate.kt"
MUTATOR="$HERE/mutate.py"
SOURCE_MUTATOR="$HERE/mutate_source.py"
PRIVACY="$HERE/privacy_scan.py"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
PROD_INDEX="$CORE/DashSegmentIndex.kt"
PROD_MODELS="$CORE/HlsPlaylistParserCore.kt"
PROD_BOUNDED="$CORE/BoundedLines.kt"
EXPECTED_RED=9

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa23-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk find python3 sha256sum cmp; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa23-no-tool\t$tool"
    exit 2
  fi
done
for file in "$GATE" "$MUTATOR" "$SOURCE_MUTATOR" "$PRIVACY" "$PROD_INDEX" "$PROD_MODELS" "$PROD_BOUNDED"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa23-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa23-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa23-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/reports" "$BUILD/mutants"
CLASSES="$BUILD/classes"

compile_gate() {
  local index_src="$1"; local out="$2"; local log="$3"; local native_out
  mkdir -p "$out"
  native_out="$(native_dir "$out")"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$native_out" -d "$native_out" \
    "$(native_path "$GATE")" "$(native_path "$index_src")" \
    "$(native_path "$PROD_MODELS")" "$(native_path "$PROD_BOUNDED")" > "$log" 2>&1
}

run_gate() {
  local classes="$1"; local mode="$2"; local fixtures="$3"; local report="$4"; local native_classes
  native_classes="$(native_dir "$classes")"
  timeout 120 "$JH/java" -Xmx96m -Xss1m -cp "$native_classes;$STDLIB" \
    com.devtunasoft.tuneros.qa23.Qa23Gate "$mode" "$(native_path "$fixtures")" \
    > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

sha_file() { sha256sum "$1" | awk '{print $1}'; }

if ! compile_gate "$PROD_INDEX" "$CLASSES" "$BUILD/compile.log"; then
  say 'FAIL\tqa23-compile\tsee scratch qa23-build/compile.log'
  grep -E 'error:|Exception' "$BUILD/compile.log" | head -n 6
  exit 1
fi
say "COMPILE\tPASS\tqa23-host-gate\tkotlin2.0.21+jvm17\tfiles=4\theap=768m"

if [ "$MODE" = generate ]; then
  run_gate "$CLASSES" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa23-generate\tsee scratch qa23-build/reports/generate.log'
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa23-no-fixtures\trun.sh generate first'
  exit 2
fi

INDEX_BEFORE="$(sha_file "$PROD_INDEX")"
MODELS_BEFORE="$(sha_file "$PROD_MODELS")"
BOUNDED_BEFORE="$(sha_file "$PROD_BOUNDED")"
FIXTURE_BEFORE="$(tree_hash "$FIXTURES")"
red_total=0
red_ok=0
restore_ok=0
fail_total=0

expect_red() {
  local name="$1"; local report="$2"; local pinned="$3"; local status="$4"
  if [ "$status" -eq 0 ]; then
    say "FAIL\tRED_NOT_RED\t$name"
    fail_total=$((fail_total+1))
  elif ! grep -q "first=$pinned" "$report"; then
    say "FAIL\tRED_WRONG_CODE\t$name\tpinned=$pinned"
    grep -E '^(FAIL|CHECK|SUMMARY)' "$report" | head -n 3
    fail_total=$((fail_total+1))
  else
    say "RED\tPASS\t$name\tfirst=$pinned"
    red_ok=$((red_ok+1))
  fi
  red_total=$((red_total+1))
}

if [ "$MODE" != gate-only ]; then
  FIXTURE_MUTANTS='byte-drift|verify|BYTE_IDENTITY
sha-drift|verify-mutant-raw|MANIFEST_MISMATCH
expect-drift|verify-mutant|EXPECT_CONSISTENCY
cap-shrink|verify-mutant|CAP_BINDING'
  while IFS='|' read -r name mutant_mode pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/$name"
    report="$BUILD/reports/$name.log"
    python3 "$(native_path "$MUTATOR")" "$(native_path "$FIXTURES")" \
      "$(native_path "$scratch")" "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tMUTANT_SETUP\t$name"
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$CLASSES" "$mutant_mode" "$scratch" "$report"
    expect_red "$name" "$report" "$pinned" "$?"
  done <<EOF
$FIXTURE_MUTANTS
EOF

  SOURCE_MUTANTS='version-guard|VERSION_REJECT
hier-guard|HIER_REJECT
empty-guard|EMPTY_REF_REJECT
dur-guard|ZERO_DUR_REJECT'
  while IFS='|' read -r name pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/source-$name"
    classes="$BUILD/classes-$name"
    report="$BUILD/reports/$name.log"
    mkdir -p "$scratch"
    cp "$PROD_INDEX" "$scratch/DashSegmentIndex.kt"
    python3 "$(native_path "$SOURCE_MUTATOR")" "$(native_path "$scratch/DashSegmentIndex.kt")" \
      "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ] || ! compile_gate "$scratch/DashSegmentIndex.kt" "$classes" "$BUILD/reports/$name.compile.log"; then
      say "FAIL\tSOURCE_MUTANT_SETUP\t$name"
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$classes" verify-mutant "$FIXTURES" "$report"
    expect_red "$name" "$report" "$pinned" "$?"
    cp "$PROD_INDEX" "$scratch/DashSegmentIndex.kt"
    if cmp -s "$PROD_INDEX" "$scratch/DashSegmentIndex.kt" && \
       [ "$(sha_file "$scratch/DashSegmentIndex.kt")" = "$INDEX_BEFORE" ]; then
      restore_ok=$((restore_ok+1))
    else
      say "FAIL\tRESTORE_SOURCE\t$name"
      fail_total=$((fail_total+1))
    fi
  done <<EOF
$SOURCE_MUTANTS
EOF

  run_gate "$CLASSES" verify-leak "$FIXTURES" "$BUILD/reports/leak-inject.log"
  expect_red leak-inject "$BUILD/reports/leak-inject.log" PRIVACY_SCAN "$?"
fi

GREEN="$BUILD/reports/green.log"
run_gate "$CLASSES" verify "$FIXTURES" "$GREEN"
green_status=$?
if [ "$green_status" -ne 0 ]; then
  say 'FAIL\tqa23-green\tsee scratch qa23-build/reports/green.log'
  grep -E '^(FAIL|CHECK|SUMMARY)' "$GREEN" | head -n 5
  fail_total=$((fail_total+1))
else
  summary=$(grep '^SUMMARY' "$GREEN")
  checks=$(printf '%s' "$summary" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')
  passes=$(printf '%s' "$summary" | sed -n 's/.*pass=\([0-9]*\).*/\1/p')
  failures=$(printf '%s' "$summary" | sed -n 's/.*fail=\([0-9]*\).*/\1/p')
  first=$(printf '%s' "$summary" | sed -n 's/.*first=\([A-Z_0-9]*\).*/\1/p')
  if [ "$failures" != 0 ] || [ "$first" != NONE ] || [ -z "$checks" ] || [ "$passes" != "$checks" ]; then
    say 'FAIL\tqa23-summary\tinconsistent green summary'
    fail_total=$((fail_total+1))
  else
    say "GREEN\tPASS\tchecks=$checks\tpass=$passes\tfail=$failures"
  fi
fi

python3 "$(native_path "$PRIVACY")" "$(native_path "$GREEN")" \
  "$(native_path "$FIXTURES")" > "$BUILD/reports/privacy.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa23-privacy\tscratch qa23-build/reports/privacy.log'
  fail_total=$((fail_total+1))
else
  grep '^PRIVACY' "$BUILD/reports/privacy.log"
fi

INDEX_AFTER="$(sha_file "$PROD_INDEX")"
MODELS_AFTER="$(sha_file "$PROD_MODELS")"
BOUNDED_AFTER="$(sha_file "$PROD_BOUNDED")"
FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
if [ "$INDEX_AFTER" = "$INDEX_BEFORE" ] && [ "$MODELS_AFTER" = "$MODELS_BEFORE" ] && \
   [ "$BOUNDED_AFTER" = "$BOUNDED_BEFORE" ] && \
   [ "$FIXTURE_AFTER" = "$FIXTURE_BEFORE" ]; then
  say "RESTORE\tPASS\tproduction-and-fixtures\tindex=$INDEX_BEFORE\tmodels=$MODELS_BEFORE\tbounded=$BOUNDED_BEFORE\tfixtures=$FIXTURE_AFTER\tcopies=$restore_ok"
else
  say 'FAIL\tRESTORE_TREE\tproduction or fixtures changed'
  fail_total=$((fail_total+1))
fi

if [ "$MODE" != gate-only ]; then
  if [ "$red_ok" -ne "$EXPECTED_RED" ] || [ "$red_total" -ne "$EXPECTED_RED" ]; then
    say "FAIL\tRED_COUNT\tpass=$red_ok\ttotal=$red_total\texpected=$EXPECTED_RED"
    fail_total=$((fail_total+1))
  else
    say "RED\tPASS\tcontrols=$red_ok"
  fi
fi

if [ "$fail_total" -ne 0 ]; then
  say "FINAL\tFAIL\tfailures=$fail_total"
  exit 1
fi
say 'FINAL\tPASS\tqa23-host-gate'
exit 0
