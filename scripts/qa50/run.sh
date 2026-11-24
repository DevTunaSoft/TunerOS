#!/usr/bin/env bash
# QA-50 bounded offline host gate: EPG import row admission, future window and overflow accounting.
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
FIXTURES="$ROOT/qa/fixtures/qa50"
HOST="$HERE/Qa50HostMain.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa50/Qa50Contract.kt"
MUTATOR="$HERE/mutate.py"
PROD="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa50-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in grep sed awk find sha256sum python3 cmp timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa50-no-tool\t$tool"
    exit 2
  fi
done
for file in "$HOST" "$CONTRACT" "$MUTATOR" "$PROD/GuideParser.kt" "$PROD/Models.kt" "$PROD/XmltvProlog.kt"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa50-missing-source\t$(basename "$file")"
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
if [ -z "$JARS" ]; then
  say 'BLOCKED\tqa50-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done

BUILD="$(native_path "${TMPDIR:-/tmp}/qa50-build-$$")"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutants" "$BUILD/reports" "$BUILD/generated"
CLASSES_N="$(native_dir "$BUILD/classes")"

SOURCES=(
  "$(native_path "$PROD/GuideParser.kt")"
  "$(native_path "$PROD/Models.kt")"
  "$(native_path "$PROD/XmltvProlog.kt")"
  "$(native_path "$CONTRACT")"
  "$(native_path "$HOST")"
)
timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$CLASSES_N" -d "$CLASSES_N" "${SOURCES[@]}" \
    > "$BUILD/compile.log" 2>&1
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa50-compile\tsee scratch qa50-build/compile.log'
  grep -E 'error:|warning:' "$BUILD/compile.log" | head -n 16
  exit 1
fi
say 'COMPILE\tPASS\tqa50-host-gate\tkotlin2.0.21+jvm17\treal-GuideParser'

run_gate() {
  local gate_mode="$1"; local fixture_dir="$2"; local report="$3"
  timeout 180 "$JH/java" -Xmx96m -Xss1m -cp "$COMPILE_CP$CLASSES_N" \
    com.devtunasoft.tuneros.qa50.Qa50HostMain "$gate_mode" "$(native_path "$fixture_dir")" \
    > "$report" 2>&1
}

if [ "$MODE" = generate ]; then
  run_gate generate "$FIXTURES" "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa50-generate\tsee scratch generate log'
    exit 1
  fi
  grep -E '^(GENERATE|VERDICT)' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa50-no-fixtures\trun.sh generate first'
  exit 2
fi

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}
first_code() {
  grep '^FIRST' "$1" | awk '{print $2}' | tail -1
}
STATUS_NAMES='cases.tsv guide.xmltv corrupt.tsv dimensions.tsv sha256.txt README.md'

run_gate generate "$BUILD/generated" "$BUILD/reports/regen.log"
if [ "$?" -ne 0 ]; then
  say 'FAIL\tqa50-regen'
  exit 1
fi
REGEN_OK=1
for name in $STATUS_NAMES; do
  if ! cmp -s "$FIXTURES/$name" "$BUILD/generated/$name"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -ne 1 ]; then
  say 'FAIL\tqa50-byte-identity\tcommitted fixture differs from deterministic regeneration'
  exit 1
fi
say 'BYTE_IDENTITY\tPASS\tcommitted-fixture-equals-regenerated-bytes\tfiles=6'

OWNED_SOURCES="$(find "$ROOT/scripts/qa50" "$ROOT/app/src/androidTest" -type f | LC_ALL=C sort)"
OWNED_HASHES_BEFORE="$(for f in $OWNED_SOURCES "$PROD/GuideParser.kt" "$PROD/Models.kt" "$PROD/XmltvProlog.kt"; do sha256sum "$f"; done | sha256sum | awk '{print $1}')"
FIXTURE_HASH_BEFORE="$(tree_hash "$FIXTURES")"

if [ "$MODE" != gate-only ]; then
  # mutant | pinned FIRST code | host verification mode
  MUTANTS='sha-drift|MANIFEST_CONSISTENT|verify
byte-drift|BYTE_IDENTITY|verify-mutant-byte
header-drop|HEADER_STRICT|verify-mutant
field-extra|FIELD_COUNT|verify-mutant
edge-class|WINDOW_EDGE_CLASS|verify-mutant
admit-flip|CONTRACT_ADMISSION|verify-mutant
omit-count|OVERFLOW_ACCOUNTING|verify-mutant
channel-count|CHANNEL_CAP|verify-mutant
title-cap|TRUNCATION_WARNINGS|verify-mutant
time-count|INVALID_TIME_COUNT|verify-mutant
corrupt-relax|CORRUPT_FAIL_CLOSED|verify-mutant
privacy-leak|PRIVACY_SCAN|verify-mutant'
  RED_FAIL=0
  RED_PASS=0
  while IFS='|' read -r mutant expected gate_mode; do
    [ -z "$mutant" ] && continue
    python3 "$MUTATOR" "$FIXTURES" "$BUILD/mutants/$mutant" "$mutant" \
      > "$BUILD/reports/mutate-$mutant.log" 2>&1
    if [ "$?" -ne 0 ]; then
      say "FAIL\tqa50-mutate\t$mutant"
      RED_FAIL=1
      continue
    fi
    report="$BUILD/reports/red-$mutant.log"
    run_gate "$gate_mode" "$BUILD/mutants/$mutant" "$report"
    rc=$?
    got="$(first_code "$report")"
    if [ "$rc" -eq 0 ] || [ "$got" != "$expected" ]; then
      say "FAIL\tqa50-red\t$mutant\trc=$rc\tfirst=$got\texpected=$expected"
      RED_FAIL=1
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
run_gate verify "$FIXTURES" "$GREEN"
rc=$?
if [ "$rc" -ne 0 ]; then
  say 'FAIL\tqa50-green'
  grep -E '^(CHECK|SUMMARY|VERDICT)' "$GREEN" | grep -E 'FAIL|VERDICT' | head -n 16
  exit 1
fi
grep -E '^(SUMMARY|VERDICT)' "$GREEN"

OWNED_HASHES_AFTER="$(for f in $OWNED_SOURCES "$PROD/GuideParser.kt" "$PROD/Models.kt" "$PROD/XmltvProlog.kt"; do sha256sum "$f"; done | sha256sum | awk '{print $1}')"
FIXTURE_HASH_AFTER="$(tree_hash "$FIXTURES")"
if [ "$OWNED_HASHES_BEFORE" != "$OWNED_HASHES_AFTER" ] || [ "$FIXTURE_HASH_BEFORE" != "$FIXTURE_HASH_AFTER" ]; then
  say 'FAIL\tqa50-restore\tsource-or-fixture-changed'
  exit 1
fi
say 'RESTORE\tPASS\towned-sources-and-fixtures-byte-identical'
say 'VERDICT\tPASS\tqa50-host-gate'
exit 0
