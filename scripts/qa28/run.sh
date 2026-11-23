#!/usr/bin/env bash
# QA-28 deterministic zero-new-dependency host gate for bounded multiview tile admission.
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
FIXTURES="$ROOT/qa/fixtures/qa28"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa28/Qa28Contract.kt"
GATE="$HERE/Qa28Gate.kt"
MUTATOR="$HERE/mutate.py"
SOURCE_MUTATOR="$HERE/mutate_source.py"
PRIVACY="$HERE/privacy_scan.py"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
POLICY="$CORE/MultiviewPolicy.kt"
SESSION="$CORE/MultiviewSession.kt"
EXPECTED_RED=23

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa28-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk python3 sha256sum cmp cp; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa28-no-tool\t$tool"
    exit 2
  fi
done
for file in "$GATE" "$CONTRACT" "$MUTATOR" "$SOURCE_MUTATOR" "$PRIVACY" "$POLICY" "$SESSION"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa28-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa28-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa28-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/reports" "$BUILD/mutants"
CLASSES="$BUILD/classes"

compile_gate() {
  local policy_src="$1"; local session_src="$2"; local gate_src="$3"; local out="$4"; local log="$5"; local native_out
  mkdir -p "$out"
  native_out="$(native_dir "$out")"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$native_out" -d "$native_out" \
    "$(native_path "$policy_src")" "$(native_path "$session_src")" \
    "$(native_path "$CONTRACT")" "$(native_path "$gate_src")" > "$log" 2>&1
}

run_gate() {
  local classes="$1"; local mode="$2"; local fixtures="$3"; local report="$4"; local regen="$5"; local native_classes
  native_classes="$(native_dir "$classes")"
  timeout 240 "$JH/java" -Xmx96m -Xss1m -cp "$native_classes;$STDLIB" \
    com.devtunasoft.tuneros.qa28.Qa28Gate "$mode" "$(native_path "$fixtures")" \
    "$(native_path "$regen")" > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
sha_file() { sha256sum "$1" | awk '{print $1}'; }

if ! compile_gate "$POLICY" "$SESSION" "$GATE" "$CLASSES" "$BUILD/compile.log"; then
  say 'FAIL\tqa28-compile\tsee scratch qa28-build/compile.log'
  grep -E 'error:|Exception' "$BUILD/compile.log" | head -n 8
  exit 1
fi
say "COMPILE\tPASS\tqa28-host-gate\tkotlin2.0.21+jvm17\tfiles=4\theap=768m"

if [ "$MODE" = generate ]; then
  mkdir -p "$BUILD/regen"
  run_gate "$CLASSES" generate "$FIXTURES" "$BUILD/reports/generate.log" "$BUILD/regen"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa28-generate\tsee scratch qa28-build/reports/generate.log'
    tail -n 12 "$BUILD/reports/generate.log"
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  say "FIXTURE\tbytes=$(wc -c < "$FIXTURES/cases.tsv")"
  say "FIXTURE\tsha256=$(sha_file "$FIXTURES/cases.tsv")"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa28-no-fixtures\trun.sh generate first'
  exit 2
fi

POLICY_BEFORE="$(sha_file "$POLICY")"
SESSION_BEFORE="$(sha_file "$SESSION")"
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
digest-drift|digest|MANIFEST_MATCH
header-drift|fixture|HEADER_STRICT
field-drift|fixture|FIELD_BOUNDS
numeric-drift|fixture|NUMERIC_BOUNDS
op-drift|fixture|OP_BOUNDS
token-drift|fixture|TOKEN_BOUNDS
result-drift|fixture|RESULT_BOUNDS
capacity-drift|fixture|CAPACITY_BOUND
corrupt-expect|fixture|CORRUPT_REJECT
dimension-drift|fixture|DIMENSIONS
leak-inject|fixture|PRIVACY_FIXTURE'
  while IFS='|' read -r name mutant_mode pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/$name"
    report="$BUILD/reports/$name.log"
    python3 "$(native_path "$MUTATOR")" "$(native_path "$FIXTURES")" \
      "$(native_path "$scratch")" "$name" > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tMUTANT_SETUP\t$name"
      head -n 4 "$BUILD/reports/$name.mutate.log"
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$CLASSES" "$mutant_mode" "$scratch" "$report" "$BUILD/regen"
    expect_red "$name" "$report" "$pinned" "$?"
  done <<EOF
$FIXTURE_MUTANTS
EOF

  SOURCE_MUTANTS='policy-userlimit-clamp|policy|CAPACITY_BOUND
policy-decoder-floor|policy|CAPACITY_BOUND
policy-provider-floor|policy|CAPACITY_BOUND
session-invalid-guard|session|ADMISSION_SEMANTICS
session-id-boundary|session|ADMISSION_SEMANTICS
session-duplicate-guard|session|ADMISSION_SEMANTICS
session-audio-first|session|AUDIO_EXCLUSIVE
session-focus-guard|session|AUDIO_EXCLUSIVE
session-close-handoff|session|CLOSE_HANDOFF
session-close-earlier|session|CLOSE_HANDOFF
gate-thread-limit|gate|HEAP_THREADS_BOUNDED'
  while IFS='|' read -r name kind pinned; do
    [ -z "$name" ] && continue
    scratch="$BUILD/mutants/source-$name"
    classes="$BUILD/classes-$name"
    report="$BUILD/reports/$name.log"
    mkdir -p "$scratch"
    cp "$POLICY" "$scratch/MultiviewPolicy.kt"
    cp "$SESSION" "$scratch/MultiviewSession.kt"
    cp "$GATE" "$scratch/Qa28Gate.kt"
    policy_src="$POLICY"
    session_src="$SESSION"
    gate_src="$GATE"
    python3 "$(native_path "$SOURCE_MUTATOR")" "$(native_path "$scratch")" "$name" \
      > "$BUILD/reports/$name.mutate.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tSOURCE_MUTANT_SETUP\t$name"
      head -n 4 "$BUILD/reports/$name.mutate.log"
      fail_total=$((fail_total+1))
      continue
    fi
    if [ "$kind" = policy ]; then policy_src="$scratch/MultiviewPolicy.kt"; fi
    if [ "$kind" = session ]; then session_src="$scratch/MultiviewSession.kt"; fi
    if [ "$kind" = gate ]; then gate_src="$scratch/Qa28Gate.kt"; fi
    if ! compile_gate "$policy_src" "$session_src" "$gate_src" "$classes" "$BUILD/reports/$name.compile.log"; then
      say "FAIL\tSOURCE_MUTANT_COMPILE\t$name"
      grep -E 'error:' "$BUILD/reports/$name.compile.log" | head -n 3
      fail_total=$((fail_total+1))
      continue
    fi
    run_gate "$classes" mutant "$FIXTURES" "$report" "$BUILD/regen"
    expect_red "$name" "$report" "$pinned" "$?"
    cp "$POLICY" "$scratch/MultiviewPolicy.kt"
    cp "$SESSION" "$scratch/MultiviewSession.kt"
    cp "$GATE" "$scratch/Qa28Gate.kt"
    if cmp -s "$POLICY" "$scratch/MultiviewPolicy.kt" && \
       cmp -s "$SESSION" "$scratch/MultiviewSession.kt" && \
       cmp -s "$GATE" "$scratch/Qa28Gate.kt"; then
      restore_ok=$((restore_ok+1))
    else
      say "FAIL\tRESTORE_SOURCE\t$name"
      fail_total=$((fail_total+1))
    fi
  done <<EOF
$SOURCE_MUTANTS
EOF
fi

GREEN="$BUILD/reports/green.log"
run_gate "$CLASSES" verify "$FIXTURES" "$GREEN" "$BUILD/regen"
green_status=$?
if [ "$green_status" -ne 0 ]; then
  say 'FAIL\tqa28-green\tsee scratch qa28-build/reports/green.log'
  grep -E '^(CHECK|SUMMARY)' "$GREEN" | head -n 6
  fail_total=$((fail_total+1))
else
  summary=$(grep '^SUMMARY' "$GREEN")
  checks=$(printf '%s' "$summary" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')
  passes=$(printf '%s' "$summary" | sed -n 's/.*pass=\([0-9]*\).*/\1/p')
  failures=$(printf '%s' "$summary" | sed -n 's/.*fail=\([0-9]*\).*/\1/p')
  first=$(printf '%s' "$summary" | sed -n 's/.*first=\([A-Z_0-9]*\).*/\1/p')
  if [ "$failures" != 0 ] || [ "$first" != NONE ] || [ -z "$checks" ] || [ "$passes" != "$checks" ]; then
    say 'FAIL\tqa28-summary\tinconsistent green summary'
    fail_total=$((fail_total+1))
  else
    say "GREEN\tPASS\tchecks=$checks\tpass=$passes\tfail=$failures\theap_limit=96m"
  fi
fi

python3 "$(native_path "$PRIVACY")" "$(native_path "$GREEN")" \
  "$(native_path "$FIXTURES")" > "$BUILD/reports/privacy.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa28-privacy\tscratch qa28-build/reports/privacy.log'
  head -n 4 "$BUILD/reports/privacy.log"
  fail_total=$((fail_total+1))
else
  grep '^PRIVACY' "$BUILD/reports/privacy.log"
fi

POLICY_AFTER="$(sha_file "$POLICY")"
SESSION_AFTER="$(sha_file "$SESSION")"
FIXTURE_AFTER="$(tree_hash "$FIXTURES")"
if [ "$POLICY_AFTER" = "$POLICY_BEFORE" ] && [ "$SESSION_AFTER" = "$SESSION_BEFORE" ] && \
   [ "$FIXTURE_AFTER" = "$FIXTURE_BEFORE" ]; then
  say "RESTORE\tPASS\tproduction-and-fixtures\tpolicy=$POLICY_BEFORE\tsession=$SESSION_BEFORE\tfixtures=$FIXTURE_AFTER\tcopies=$restore_ok"
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
say 'FINAL\tPASS\tqa28-host-gate'
exit 0
