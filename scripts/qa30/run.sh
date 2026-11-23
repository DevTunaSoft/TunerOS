#!/usr/bin/env bash
# QA-30 zero-new-dependency host gate for bounded 206 partial-content range admission.
#   generate    regenerate the pinned scratch fixtures byte-for-byte
#   verify      RED controls + restore proof + GREEN gate + privacy scan
#   red-green   alias for verify
#   gate-only   GREEN gate and privacy scan without mutant controls
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] &&
   [ "$MODE" != red-green ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa30"
GATE="$HERE/Qa30Gate.kt"
MUTATOR="$HERE/mutate.py"
SOURCE_MUTATOR="$HERE/mutate_source.py"
PRIVACY="$HERE/privacy_scan.py"
ANDROID_TEST="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa30"
CONTRACT="$ANDROID_TEST/Qa30Contract.kt"
FIXTURE_SRC="$ANDROID_TEST/Qa30Fixture.kt"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
PROD_RANGE="$CORE/HlsContentRange.kt"
PROD_PLAYLIST="$CORE/HlsPlaylistParserCore.kt"
PROD_BOUNDED="$CORE/BoundedLines.kt"
EXPECTED_RED=15
EXPECTED_GREEN=30

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa30-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk python3 sha256sum cmp cp mkdir; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa30-no-tool\t$tool"
    exit 2
  fi
done
for file in "$GATE" "$CONTRACT" "$FIXTURE_SRC" "$MUTATOR" "$SOURCE_MUTATOR" "$PRIVACY" \
            "$PROD_RANGE" "$PROD_PLAYLIST" "$PROD_BOUNDED"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa30-missing-source\t$(basename "$file")"
    exit 2
  fi
done

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=(
  "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21"/*/*.jar
  "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21"/*/*.jar
  "$CACHE/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21"/*/*.jar
  "$CACHE/org.jetbrains.intellij.deps/trove4j"/*/*/*.jar
  "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm"/*/*/*.jar
  "$CACHE/org.jetbrains/annotations"/*/*/*.jar
)
COMPILE_CP=''
for jar in "${JARS[@]}"; do
  if [ -f "$jar" ]; then COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; fi
done
STDLIB_RAW=$(ls "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21"/*/kotlin-stdlib-2.0.21.jar 2>/dev/null | head -1)
if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB_RAW" ]; then
  say 'BLOCKED\tqa30-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa30-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/reports" "$BUILD/mutants"
CLASSES="$BUILD/classes"

compile_gate() {
  local range_src="$1"; local out="$2"; local log="$3"; local native_out
  mkdir -p "$out"
  native_out="$(native_dir "$out")"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$native_out" -d "$native_out" \
    "$(native_path "$PROD_BOUNDED")" "$(native_path "$PROD_PLAYLIST")" \
    "$(native_path "$range_src")" "$(native_path "$CONTRACT")" \
    "$(native_path "$FIXTURE_SRC")" "$(native_path "$GATE")" > "$log" 2>&1
}

run_gate() {
  local classes="$1"; local mode="$2"; local fixtures="$3"; local report="$4"; local native_classes
  native_classes="$(native_dir "$classes")"
  timeout 240 "$JH/java" -Xmx96m -Xss1m -cp "$native_classes;$STDLIB" \
    com.devtunasoft.tuneros.qa30.Qa30Gate "$mode" "$(native_path "$fixtures")" > "$report" 2>&1
}

sha_file() { sha256sum "$1" | awk '{print $1}'; }
tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

if ! compile_gate "$PROD_RANGE" "$CLASSES" "$BUILD/compile.log"; then
  say 'FAIL\tqa30-compile\tsee scratch qa30-build/compile.log'
  grep -E 'error:|Exception' "$BUILD/compile.log" | head -n 8
  exit 1
fi
say "COMPILE\tPASS\tqa30-host-gate\tkotlin2.0.21+jvm17\tfiles=6\theap=768m"

if [ "$MODE" = generate ]; then
  mkdir -p "$FIXTURES"
  run_gate "$CLASSES" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa30-generate\tsee scratch qa30-build/reports/generate.log'
    tail -n 12 "$BUILD/reports/generate.log"
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  say "FIXTURE\tsha256=$(sha_file "$FIXTURES/sha256.txt")"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa30-no-fixtures\trun.sh generate first'
  exit 2
fi

RANGE_BEFORE="$(sha_file "$PROD_RANGE")"
PLAYLIST_BEFORE="$(sha_file "$PROD_PLAYLIST")"
BOUNDED_BEFORE="$(sha_file "$PROD_BOUNDED")"
CONTRACT_BEFORE="$(sha_file "$CONTRACT")"
FIXTURE_SRC_BEFORE="$(sha_file "$FIXTURE_SRC")"
GATE_BEFORE="$(sha_file "$GATE")"
FIXTURE_TREE_BEFORE="$(tree_hash "$FIXTURES")"
red_total=0
red_ok=0
restore_ok=0
fail_total=0

expect_red() {
  local name="$1"; local report="$2"; local code="$3"; local expected_exit="$4"; local status="$5"
  red_total=$((red_total+1))
  if [ "$status" -eq 0 ]; then
    say "FAIL\tRED_NOT_RED\t$name"
    fail_total=$((fail_total+1))
  elif ! grep -q "first=$code" "$report"; then
    say "FAIL\tRED_WRONG_CODE\t$name\tpinned=$code"
    grep -E '^(FIRST|SUMMARY)' "$report" | head -n 2
    fail_total=$((fail_total+1))
  elif [ "$status" -ne "$expected_exit" ]; then
    say "FAIL\tRED_WRONG_EXIT\t$name\texit=$status\tpinned=$expected_exit"
    fail_total=$((fail_total+1))
  else
    say "RED\tPASS\t$name\tfirst=$code\texit=$status"
    red_ok=$((red_ok+1))
  fi
}

# name|kind(F=fixture,S=production)|gate mode|pinned exit|pinned first code
MUTANTS='leak-inject|F|verify|21|PRIVACY_FIXTURE
header-drift|F|verify|22|HEADER_STRICT
field-drift|F|verify|23|FIELD_BOUNDS
cap-drift|F|verify|24|CASE_CAP
dimension-drift|F|verify|25|DIMENSIONS
manifest-drift|F|verify|26|MANIFEST_MATCH
byte-drift|F|verify|27|BYTE_IDENTITY
src-total|S|mutant|33|GUARD_TOTAL
src-match-entire|S|mutant|34|GUARD_MATCH_ENTIRE
src-from|S|mutant|35|GUARD_FROM
src-to|S|mutant|36|GUARD_TO
expect-accept|F|mutant|37|ACCEPT_EXACT
expect-offset|F|mutant|38|REJECT_OFFSET
expect-malformed|F|mutant|41|REJECT_MALFORMED
corrupt-accept|F|mutant|45|CORRUPT_REJECT'

if [ "$MODE" != gate-only ]; then
  while IFS='|' read -r name kind mutant_mode pinned first; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/$name"
    report="$BUILD/reports/$name.log"
    if [ "$kind" = F ]; then
      python3 "$(native_path "$MUTATOR")" "$(native_path "$FIXTURES")" \
        "$(native_path "$scratch")" "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
      if [ "$?" -ne 0 ]; then
        say "FAIL\tMUTANT_SETUP\t$name"
        head -n 4 "$BUILD/reports/$name.mutate.log"
        fail_total=$((fail_total+1))
        continue
      fi
      run_gate "$CLASSES" "$mutant_mode" "$scratch" "$report"
      expect_red "$name" "$report" "$first" "$pinned" "$?"
    else
      classes="$BUILD/classes-$name"
      mkdir -p "$scratch"
      cp "$PROD_RANGE" "$scratch/HlsContentRange.kt"
      python3 "$(native_path "$SOURCE_MUTATOR")" "$(native_path "$scratch")" "$name" \
        > "$BUILD/reports/$name.mutate.log" 2>&1
      if [ "$?" -ne 0 ]; then
        say "FAIL\tSOURCE_MUTANT_SETUP\t$name"
        head -n 4 "$BUILD/reports/$name.mutate.log"
        fail_total=$((fail_total+1))
        continue
      fi
      if ! compile_gate "$scratch/HlsContentRange.kt" "$classes" "$BUILD/reports/$name.compile.log"; then
        say "FAIL\tSOURCE_MUTANT_COMPILE\t$name"
        grep -E 'error:' "$BUILD/reports/$name.compile.log" | head -n 3
        fail_total=$((fail_total+1))
        continue
      fi
      run_gate "$classes" "$mutant_mode" "$FIXTURES" "$report"
      expect_red "$name" "$report" "$first" "$pinned" "$?"
      cp "$PROD_RANGE" "$scratch/HlsContentRange.kt"
      if cmp -s "$PROD_RANGE" "$scratch/HlsContentRange.kt"; then
        restore_ok=$((restore_ok+1))
      else
        say "FAIL\tRESTORE_SOURCE\t$name"
        fail_total=$((fail_total+1))
      fi
    fi
  done <<EOF
$MUTANTS
EOF
fi

GREEN="$BUILD/reports/green.log"
run_gate "$CLASSES" verify "$FIXTURES" "$GREEN"
green_status=$?
if [ "$green_status" -ne 0 ]; then
  say 'FAIL\tqa30-green\tsee scratch qa30-build/reports/green.log'
  grep -E '^(FIRST|SUMMARY|CHECK\tFAIL)' "$GREEN" | head -n 6
  fail_total=$((fail_total+1))
else
  summary=$(grep '^SUMMARY' "$GREEN")
  checks=$(printf '%s' "$summary" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')
  passes=$(printf '%s' "$summary" | sed -n 's/.*pass=\([0-9]*\).*/\1/p')
  failures=$(printf '%s' "$summary" | sed -n 's/.*fail=\([0-9]*\).*/\1/p')
  first=$(printf '%s' "$summary" | sed -n 's/.*first=\([A-Z_0-9]*\).*/\1/p')
  if [ "$checks" != "$EXPECTED_GREEN" ] || [ "$failures" != 0 ] || \
     [ "$first" != NONE ] || [ "$passes" != "$checks" ]; then
    say "FAIL\tqa30-summary\tchecks=$checks pass=$passes fail=$failures first=$first"
    fail_total=$((fail_total+1))
  else
    say "GREEN\tPASS\tchecks=$checks\tpass=$passes\tfail=$failures\theap_limit=96m"
  fi
fi

python3 "$(native_path "$PRIVACY")" "$(native_path "$GREEN")" \
  "$(native_path "$FIXTURES")" > "$BUILD/reports/privacy.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa30-privacy\tscratch qa30-build/reports/privacy.log'
  head -n 4 "$BUILD/reports/privacy.log"
  fail_total=$((fail_total+1))
else
  grep '^PRIVACY' "$BUILD/reports/privacy.log"
fi

RANGE_AFTER="$(sha_file "$PROD_RANGE")"
PLAYLIST_AFTER="$(sha_file "$PROD_PLAYLIST")"
BOUNDED_AFTER="$(sha_file "$PROD_BOUNDED")"
CONTRACT_AFTER="$(sha_file "$CONTRACT")"
FIXTURE_SRC_AFTER="$(sha_file "$FIXTURE_SRC")"
GATE_AFTER="$(sha_file "$GATE")"
FIXTURE_TREE_AFTER="$(tree_hash "$FIXTURES")"
if [ "$RANGE_BEFORE" = "$RANGE_AFTER" ] && [ "$PLAYLIST_BEFORE" = "$PLAYLIST_AFTER" ] && \
   [ "$BOUNDED_BEFORE" = "$BOUNDED_AFTER" ] && [ "$CONTRACT_BEFORE" = "$CONTRACT_AFTER" ] && \
   [ "$FIXTURE_SRC_BEFORE" = "$FIXTURE_SRC_AFTER" ] && [ "$GATE_BEFORE" = "$GATE_AFTER" ] && \
   [ "$FIXTURE_TREE_BEFORE" = "$FIXTURE_TREE_AFTER" ]; then
  say "RESTORE\tPASS\trange=$RANGE_AFTER\tplaylist=$PLAYLIST_AFTER\tbounded=$BOUNDED_AFTER\tcontract=$CONTRACT_AFTER\tfixture_src=$FIXTURE_SRC_AFTER\tgate=$GATE_AFTER\ttree=$FIXTURE_TREE_AFTER\tcopies=$restore_ok"
else
  say 'FAIL\tRESTORE_TREE\tproduction or fixtures changed'
  fail_total=$((fail_total+1))
fi

if [ "$MODE" != gate-only ]; then
  if [ "$red_ok" -ne "$EXPECTED_RED" ] || [ "$red_total" -ne "$EXPECTED_RED" ]; then
    say "FAIL\tRED_COUNT\tpass=$red_ok\ttotal=$red_total\texpected=$EXPECTED_RED"
    fail_total=$((fail_total+1))
  else
    say "RED\tPASS\tmutants=$red_ok\tcodes=pinned-distinct"
  fi
fi

if [ "$fail_total" -ne 0 ]; then
  say "FINAL\tFAIL\tfailures=$fail_total"
  exit 1
fi
say 'FINAL\tPASS\tqa30-host-gate'
exit 0
