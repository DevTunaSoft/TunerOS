#!/usr/bin/env bash
# QA-26 bounded, offline, zero-new-dependency host gate for DASH MPD planning.
#   run.sh generate    regenerate pinned scratch fixtures byte-for-byte
#   run.sh verify      RED controls + restore proof + GREEN gate + privacy scan
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
FIXTURES="$ROOT/qa/fixtures/qa26"
GATE="$HERE/Qa26Gate.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa26/Qa26Contract.kt"
MUTATOR="$HERE/mutate.py"
SOURCE_MUTATOR="$HERE/mutate_source.py"
PRIVACY="$HERE/privacy_scan.py"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
PROD_MANIFEST="$CORE/DashManifest.kt"
PROD_INDEX="$CORE/DashSegmentIndex.kt"
PROD_HLS="$CORE/HlsPlaylistParserCore.kt"
PROD_BOUNDED="$CORE/BoundedLines.kt"
EXPECTED_RED=15

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa26-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk find python3 sha256sum cmp; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa26-no-tool\t$tool"
    exit 2
  fi
done
for file in "$GATE" "$CONTRACT" "$MUTATOR" "$SOURCE_MUTATOR" "$PRIVACY" \
    "$PROD_MANIFEST" "$PROD_INDEX" "$PROD_HLS" "$PROD_BOUNDED"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa26-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa26-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa26-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/reports" "$BUILD/mutants"
CLASSES="$BUILD/classes"

compile_gate() {
  local manifest_src="$1"; local gate_src="$2"; local out="$3"; local log="$4"; local native_out
  mkdir -p "$out"
  native_out="$(native_dir "$out")"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$native_out" -d "$native_out" \
    "$(native_path "$manifest_src")" "$(native_path "$PROD_INDEX")" \
    "$(native_path "$PROD_HLS")" "$(native_path "$PROD_BOUNDED")" \
    "$(native_path "$CONTRACT")" "$(native_path "$gate_src")" > "$log" 2>&1
}

run_gate() {
  local classes="$1"; local mode="$2"; local fixtures="$3"; local report="$4"; local native_classes
  native_classes="$(native_dir "$classes")"
  timeout 240 "$JH/java" -Xmx96m -Xss1m -cp "$native_classes;$STDLIB" \
    com.devtunasoft.tuneros.qa26.Qa26Gate "$mode" "$(native_path "$fixtures")" \
    > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
sha_file() { sha256sum "$1" | awk '{print $1}'; }

if ! compile_gate "$PROD_MANIFEST" "$GATE" "$CLASSES" "$BUILD/compile.log"; then
  say 'FAIL\tqa26-compile\tsee scratch qa26-build/compile.log'
  grep -E 'error:|Exception' "$BUILD/compile.log" | head -n 8
  exit 1
fi
say "COMPILE\tPASS\tqa26-host-gate\tkotlin2.0.21+jvm17\tfiles=6\theap=768m"

if [ "$MODE" = generate ]; then
  run_gate "$CLASSES" generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa26-generate\tsee scratch qa26-build/reports/generate.log'
    tail -n 12 "$BUILD/reports/generate.log"
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  say "FIXTURE\tbytes=$(wc -c < "$FIXTURES/cases.tsv")"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa26-no-fixtures\trun.sh generate first'
  exit 2
fi

MANIFEST_BEFORE="$(sha_file "$PROD_MANIFEST")"
INDEX_BEFORE="$(sha_file "$PROD_INDEX")"
HLS_BEFORE="$(sha_file "$PROD_HLS")"
BOUNDED_BEFORE="$(sha_file "$PROD_BOUNDED")"
CONTRACT_BEFORE="$(sha_file "$CONTRACT")"
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
    grep -E '^(CHECK|SUMMARY)' "$report" | head -n 3
    fail_total=$((fail_total+1))
  else
    say "RED\tPASS\t$name\tfirst=$pinned"
    red_ok=$((red_ok+1))
  fi
  red_total=$((red_total+1))
}

if [ "$MODE" != gate-only ]; then
  FIXTURE_MUTANTS='byte-drift|verify|BYTE_IDENTITY
sha-drift|verify-digest|MANIFEST_MATCH
header-drift|mutant|HEADER_STRICT
field-count|mutant|FIELD_BOUNDS
fingerprint-drift|mutant|PLAN_FINGERPRINT
corrupt-expect|mutant|CORRUPT_REJECT
cap-shrink|mutant|CAP_BINDING
dimension-drift|verify-digest|DIMENSIONS
leak-inject|mutant|PRIVACY_FIXTURE'
  while IFS='|' read -r name mutant_mode pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/$name"
    report="$BUILD/reports/$name.log"
    python3 "$(native_path "$MUTATOR")" "$(native_path "$FIXTURES")" \
      "$(native_path "$scratch")" "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tMUTANT_SETUP\t$name"
      cat "$BUILD/reports/$name.mutate.log" | head -n 4
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$CLASSES" "$mutant_mode" "$scratch" "$report"
    expect_red "$name" "$report" "$pinned" "$?"
  done <<EOF
$FIXTURE_MUTANTS
EOF

  SOURCE_MUTANTS='dtd-guard|manifest|CORRUPT_REJECT
size-guard|manifest|CORRUPT_REJECT
contiguity-guard|manifest|CORRUPT_REJECT
token-guard|manifest|TEMPLATE_EXPAND
bandwidth-choice|manifest|PLAN_FINGERPRINT
heap-bound|gate|HEAP_THREADS_BOUNDED'
  while IFS='|' read -r name kind pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/source-$name"
    classes="$BUILD/classes-$name"
    report="$BUILD/reports/$name.log"
    mkdir -p "$scratch"
    if [ "$kind" = manifest ]; then
      cp "$PROD_MANIFEST" "$scratch/DashManifest.kt"
      manifest_src="$scratch/DashManifest.kt"
      gate_src="$GATE"
    else
      cp "$GATE" "$scratch/Qa26Gate.kt"
      manifest_src="$PROD_MANIFEST"
      gate_src="$scratch/Qa26Gate.kt"
    fi
    python3 "$(native_path "$SOURCE_MUTATOR")" "$(native_path "$scratch")" "$name" \
      > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ] || ! compile_gate "$manifest_src" "$gate_src" "$classes" "$BUILD/reports/$name.compile.log"; then
      say "FAIL\tSOURCE_MUTANT_SETUP\t$name"
      grep -E 'error:|MUTATE' "$BUILD/reports/$name.compile.log" "$BUILD/reports/$name.mutate.log" 2>/dev/null | head -n 5
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$classes" mutant "$FIXTURES" "$report"
    expect_red "$name" "$report" "$pinned" "$?"
    if [ "$kind" = manifest ]; then
      cp "$PROD_MANIFEST" "$scratch/DashManifest.kt"
      if cmp -s "$PROD_MANIFEST" "$scratch/DashManifest.kt" && \
         [ "$(sha_file "$scratch/DashManifest.kt")" = "$MANIFEST_BEFORE" ]; then
        restore_ok=$((restore_ok+1))
      else
        say "FAIL\tRESTORE_SOURCE\t$name"
        fail_total=$((fail_total+1))
      fi
    else
      cp "$GATE" "$scratch/Qa26Gate.kt"
      if cmp -s "$GATE" "$scratch/Qa26Gate.kt" && \
         [ "$(sha_file "$scratch/Qa26Gate.kt")" = "$(sha_file "$GATE")" ]; then
        restore_ok=$((restore_ok+1))
      else
        say "FAIL\tRESTORE_SOURCE\t$name"
        fail_total=$((fail_total+1))
      fi
    fi
  done <<EOF
$SOURCE_MUTANTS
EOF
fi

GREEN="$BUILD/reports/green.log"
run_gate "$CLASSES" verify "$FIXTURES" "$GREEN"
green_status=$?
if [ "$green_status" -ne 0 ]; then
  say 'FAIL\tqa26-green\tsee scratch qa26-build/reports/green.log'
  grep -E '^(CHECK|SUMMARY)' "$GREEN" | head -n 6
  fail_total=$((fail_total+1))
else
  summary=$(grep '^SUMMARY' "$GREEN")
  checks=$(printf '%s' "$summary" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')
  passes=$(printf '%s' "$summary" | sed -n 's/.*pass=\([0-9]*\).*/\1/p')
  failures=$(printf '%s' "$summary" | sed -n 's/.*fail=\([0-9]*\).*/\1/p')
  first=$(printf '%s' "$summary" | sed -n 's/.*first=\([A-Z_0-9]*\).*/\1/p')
  if [ "$failures" != 0 ] || [ "$first" != NONE ] || [ -z "$checks" ] || [ "$passes" != "$checks" ]; then
    say 'FAIL\tqa26-summary\tinconsistent green summary'
    fail_total=$((fail_total+1))
  else
    say "GREEN\tPASS\tchecks=$checks\tpass=$passes\tfail=$failures"
  fi
fi

python3 "$(native_path "$PRIVACY")" "$(native_path "$GREEN")" \
  "$(native_path "$FIXTURES")" > "$BUILD/reports/privacy.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa26-privacy\tscratch qa26-build/reports/privacy.log'
  cat "$BUILD/reports/privacy.log" | head -n 4
  fail_total=$((fail_total+1))
else
  grep '^PRIVACY' "$BUILD/reports/privacy.log"
fi

MANIFEST_AFTER="$(sha_file "$PROD_MANIFEST")"
INDEX_AFTER="$(sha_file "$PROD_INDEX")"
HLS_AFTER="$(sha_file "$PROD_HLS")"
BOUNDED_AFTER="$(sha_file "$PROD_BOUNDED")"
FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
if [ "$MANIFEST_AFTER" = "$MANIFEST_BEFORE" ] && [ "$INDEX_AFTER" = "$INDEX_BEFORE" ] && \
   [ "$HLS_AFTER" = "$HLS_BEFORE" ] && [ "$BOUNDED_AFTER" = "$BOUNDED_BEFORE" ] && \
   [ "$FIXTURE_AFTER" = "$FIXTURE_BEFORE" ]; then
  say "RESTORE\tPASS\tproduction-and-fixtures\tmanifest=$MANIFEST_BEFORE\tfixtures=$FIXTURE_AFTER\tcopies=$restore_ok"
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
say 'FINAL\tPASS\tqa26-host-gate'
exit 0
