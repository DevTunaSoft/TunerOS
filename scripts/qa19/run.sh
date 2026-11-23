#!/usr/bin/env bash
# QA-19 bounded, offline, zero-new-dependency host gate for source-feed acquisition.
#   run.sh generate     regenerate the pinned scratch fixtures byte-for-byte
#   run.sh verify       RED mutants, RESTORE proof, GREEN host gate, JVM mirror (default)
#   run.sh red-green    alias for verify
#   run.sh gate-only    GREEN host gate without mutation controls
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
FIXTURES="$ROOT/qa/fixtures/qa19"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa19/Qa19Contract.kt"
SOURCE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/SourceDownloader.kt"
MUTATOR="$HERE/mutate.py"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa19-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk find sha256sum python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa19-no-tool\t$tool"
    exit 2
  fi
done
for file in "$CONTRACT" "$SOURCE" "$MUTATOR"; do
  if [ ! -f "$file" ]; then
    say "BLOCKED\tqa19-missing-source\t$(basename "$file")"
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
  say 'BLOCKED\tqa19-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa19-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mutants" "$BUILD/reports"
BUILD_N="$(native_dir "$BUILD")"
CLASSES_N="$(native_dir "$BUILD/classes")"

CP_NATIVE="$(native_path "$CONTRACT")"
SRC_NATIVE="$(native_path "$SOURCE")"
if ! timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$CLASSES_N" -d "$CLASSES_N" "$SRC_NATIVE" "$CP_NATIVE" \
    > "$BUILD/compile.log" 2>&1; then
  say 'FAIL\tqa19-compile\tsee scratch qa19-build/compile.log'
  exit 2
fi
say "COMPILE\tPASS\tqa19-host-gate\tkotlin2.0.21+jvm17\tfiles=2\theap=96m"

run_gate() {
  local mode="$1"; local dir="$2"; local extra="${3:-}"; local report="$4"
  local -a args
  local native_fixture="$dir"
  if [ -n "$dir" ]; then native_fixture="$(cygpath -m "$dir" 2>/dev/null || printf '%s' "$dir")"; fi
  args=("$mode" "$native_fixture")
  if [ -n "$extra" ]; then args+=("$extra"); fi
  timeout 120 "$JH/java" -Xmx96m -Xss1m -cp "$CLASSES_N;$STDLIB" \
    com.devtunasoft.tuneros.qa19.Qa19Contract "${args[@]}" \
    > "$report" 2>&1
}

tree_hash() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}')
}

if [ "$MODE" = generate ]; then
  run_gate generate "$FIXTURES" '' "$BUILD/reports/generate.log"
  status=$?
  if [ "$status" -ne 0 ]; then
    say 'FAIL\tqa19-generate\tsee scratch qa19-build/reports/generate.log'
    exit 1
  fi
  grep '^GENERATE' "$BUILD/reports/generate.log"
  exit 0
fi

if [ ! -d "$FIXTURES" ]; then
  say 'BLOCKED\tqa19-no-fixtures\trun.sh generate first'
  exit 2
fi

BEFORE="$(tree_hash "$FIXTURES")"

if [ "$MODE" != gate-only ]; then
  # name | gate mode | pinned first failure code
  MUTANTS='bytes-drift|verify|BYTE_IDENTITY
sha-drift|verify-mutant|MANIFEST_MISMATCH
header-missing|verify-mutant|HEADER_STRICT
field-count|verify-mutant|FIELD_COUNT
int-bounds|verify-mutant|INT_BOUNDS
family-unknown|verify-mutant|FAMILY_CODE
cap-unbound|verify-mutant|REJECT_CAP_READ
downgrade-drift|verify-mutant|REJECT_DOWNGRADE
corrupt-relaxed|verify-mutant|CORRUPT_REJECT
leak|verify-mutant|PRIVACY_SCAN'
  RED_FAIL=0
  RED_COUNT=0
  while IFS='|' read -r mname mmode mcode; do
    [ -z "$mname" ] && continue
    scratch="$BUILD/mutants/$mname"
    cp -R "$FIXTURES" "$scratch"
    if ! python3 "$MUTATOR" "$(native_dir "$scratch")" "$mname" > "$BUILD/reports/mutate-$mname.log" 2>&1; then
      say "FAIL\tqa19-mutate\t$mname"
      RED_FAIL=1
      continue
    fi
    report="$BUILD/reports/red-$mname.log"
    if [ "$mmode" = verify-mutant ]; then
      run_gate verify-mutant "$scratch" "$mname" "$report"
    else
      run_gate verify "$scratch" '' "$report"
    fi
    status=$?
    first="$(awk -F '\t' '$1 == "FIRST" {print $2; exit}' "$report")"
    if [ "$status" -eq 0 ] || [ "$first" != "$mcode" ]; then
      say "FAIL\tqa19-red\tname=$mname\texpected=$mcode\tfirst=${first:-NONE}\texit=$status"
      RED_FAIL=1
    else
      RED_COUNT=$((RED_COUNT + 1))
      say "MUTANT\tPASS\tname=$mname\tfirst=$mcode"
    fi
  done <<< "$MUTANTS"
  say "RED\tSUMMARY\tmutants=10\tpass=$RED_COUNT\tfail=$((10 - RED_COUNT))"

  AFTER="$(tree_hash "$FIXTURES")"
  if [ "$BEFORE" != "$AFTER" ]; then
    say 'FAIL\tqa19-restore\tcommitted fixture changed'
    exit 1
  fi
  say "RESTORE\tPASS\ttree_sha256=$AFTER"
else
  say "RESTORE\tSKIP\tgate-only"
fi

GREEN_REPORT="$BUILD/reports/green.log"
run_gate verify "$FIXTURES" '' "$GREEN_REPORT"
green_status=$?
summary="$(awk -F '\t' '$1 == "SUMMARY" {print $0; exit}' "$GREEN_REPORT")"
fail_count="$(printf '%s' "$summary" | sed -n 's/.*fail=\([0-9]*\).*/\1/p')"
check_count="$(printf '%s' "$summary" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')"
pass_count="$(printf '%s' "$summary" | sed -n 's/.*pass=\([0-9]*\).*/\1/p')"
first="$(awk -F '\t' '$1 == "FIRST" {print $2; exit}' "$GREEN_REPORT")"
if [ "$green_status" -ne 0 ] || [ "${fail_count:-x}" != 0 ]; then
  say "FAIL\tqa19-green\tstatus=$green_status\tfirst=${first:-NONE}\t$summary"
  exit 1
fi
say "GREEN\tPASS\tchecks=${check_count:-0}\tpass=${pass_count:-0}\tfail=0\tfirst=NONE"

MIRROR_REPORT="$BUILD/reports/mirror.log"
run_gate jvm-mirror '' '' "$MIRROR_REPORT"
mirror_status=$?
mirror_summary="$(awk -F '\t' '$1 == "SUMMARY" {print $0; exit}' "$MIRROR_REPORT")"
if [ "$mirror_status" -ne 0 ]; then
  say "FAIL\tqa19-jvm-mirror\t$mirror_summary"
  exit 1
fi
say "MIRROR\tPASS\t$mirror_summary"

if [ "${RED_FAIL:-0}" -ne 0 ]; then
  say 'FINAL\tFAIL\tqa19-gate\tred control failed'
  exit 1
fi
if [ "$MODE" != gate-only ]; then
  say "FINAL\tPASS\tqa19-gate\tmutants=10/10\tgreen=${check_count:-0}/${pass_count:-0}\trestore=$AFTER"
else
  say "FINAL\tPASS\tqa19-gate\tmode=gate-only\tgreen=${check_count:-0}/${pass_count:-0}"
fi
exit 0
