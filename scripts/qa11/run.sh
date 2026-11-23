#!/usr/bin/env bash
# QA-11 bounded, offline, zero-dependency host gate.
#   run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
#   run.sh verify      run the gate on committed fixtures (default)
#   run.sh red-green   run six on-disk mutants (each MUST go RED with a pinned first failure), then GREEN
# No Gradle, Android SDK, network or third-party library is used. Only counts, ids and hashes are printed.
set -euo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != red-green ]; }; then
  printf '%b\n' "usage: $0 [verify|generate|red-green]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
SRC="$HERE/Qa11Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa11"

if [ ! -f "$SRC" ]; then
  printf '%b\n' "FAIL\tqa11-harness-missing\tQa11Gate.java"
  exit 2
fi

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
for tool in "$JH/javac" "$JH/java"; do
  if [ ! -x "$tool" ]; then
    printf '%b\n' "FAIL\tqa11-no-jdk\tset JAVA_HOME to a JDK 17 installation"
    exit 2
  fi
done
for tool in timeout tee grep sed; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf '%b\n' "FAIL\tqa11-no-shell-tool\t$tool"
    exit 2
  fi
done

RUN_DIR="${TMPDIR:-/tmp}/qa11-run"
CLASSES="$RUN_DIR/classes"
mkdir -p "$CLASSES"
find "$CLASSES" -type f -name '*.class' -delete
timeout 120 "$JH/javac" -Xlint:all -d "$CLASSES" "$SRC"

run_verify() {
  local dir="$1"; local report="$2"; local mode="${3:-verify}"
  mkdir -p "$(dirname "$report")"
  if timeout 180 "$JH/java" -Xmx128m -cp "$CLASSES" Qa11Gate "$mode" "$dir" | tee "$report"; then
    return 0
  fi
  return 1
}

summary_field() {
  local report="$1"; local field="$2"
  local value
  value=$(grep '^SUMMARY' "$report" | sed -n "s/.*${field}=\([0-9]*\)\/\([0-9]*\).*/\1\/\2/p" | head -1)
  if [ -n "$value" ]; then printf '%s' "$value"; return; fi
  grep '^SUMMARY' "$report" | sed -n "s/.*${field}=\([0-9]*\).*/\1/p" | head -1
}

if [ "$MODE" = generate ]; then
  mkdir -p "$FIXTURES"
  timeout 90 "$JH/java" -Xmx128m -cp "$CLASSES" Qa11Gate generate "$FIXTURES"
  exit 0
fi

if [ "$MODE" = verify ]; then
  run_verify "$FIXTURES" "$RUN_DIR/host-report.txt"
  exit $?
fi

# RED first: each mutant is copied to scratch, mutated, and re-manifested when needed so the named
# semantic check is exercised instead of a generic digest mismatch.
MUTANTS="flip header url credential escape cap"
EXPECTED="fixture-regeneration-byte-identical backup-header-valid backup-raw-url-free backup-raw-credential-free backup-raw-escapes-valid backup-record-cap-binds"
kind_index=0
for kind in $MUTANTS; do
  kind_index=$((kind_index + 1))
  expected=$(printf '%s\n' $EXPECTED | sed -n "${kind_index}p")
  work="$RUN_DIR/mutants/$kind"
  rm -rf "$work"
  timeout 60 "$JH/java" -Xmx128m -cp "$CLASSES" Qa11Gate mutate "$FIXTURES" "$work" "$kind" >/dev/null
  report="$RUN_DIR/mutants/$kind.report"
  mode=verify-mutant
  if [ "$kind" = flip ]; then mode=verify; fi
  if run_verify "$work" "$report" "$mode" >/dev/null; then
    printf '%b\n' "FAIL\tqa11-red-$kind\tmutant unexpectedly GREEN"
    exit 1
  fi
  first=$(grep '^FIRST' "$report" | head -1 || true)
  if [ "$first" != "FIRST	$expected" ]; then
    printf '%b\n' "FAIL\tqa11-red-$kind\twrong-first-failure"
    exit 1
  fi
  if ! grep -q 'result=FAIL' "$report"; then
    printf '%b\n' "FAIL\tqa11-red-$kind\tmissing-summary-fail"
    exit 1
  fi
  printf '%b\n' "RED\t$kind\t$expected"
done

GREEN_REPORT="$RUN_DIR/green-report.txt"
if ! run_verify "$FIXTURES" "$GREEN_REPORT" >/dev/null; then
  printf '%b\n' "FAIL\tqa11-green\tcommitted-fixtures-not-green"
  exit 1
fi
POS=$(summary_field "$GREEN_REPORT" positive)
NEG=$(summary_field "$GREEN_REPORT" negative-red)
TOTAL=$(summary_field "$GREEN_REPORT" total)
printf '%b\n' "GREEN\tpositive=$POS\tnegative-red=$NEG\ttotal=$TOTAL"
