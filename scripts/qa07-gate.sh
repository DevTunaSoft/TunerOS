#!/usr/bin/env bash
set -euo pipefail
# QA-07 bounded host gate. Offline and JDK-only: no Gradle, no Android SDK, no network, no dependencies.
#   scripts/qa07-gate.sh generate   regenerate the checked-in scratch fixtures byte-for-byte
#   scripts/qa07-gate.sh verify     run the gate (default); exit 0 only when all positive checks pass
#                                   and every negative mutation control fails as required.
# The runner bounds compile/run time and heap. All output is produced by Qa07Gate and contains no PIN.

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ]; }; then
  echo "usage: $0 [verify|generate]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/.." && native_pwd)"
SRC="$HERE/qa07/Qa07Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa07"

if [ ! -f "$SRC" ]; then
  echo "FAIL\tqa07-harness-missing\tQa07Gate.java"
  exit 2
fi

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
for tool in "$JH/javac" "$JH/java"; do
  if [ ! -x "$tool" ]; then
    echo "FAIL\tqa07-no-jdk\tset JAVA_HOME to a JDK 17 installation"
    exit 2
  fi
done
for tool in timeout tee; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "FAIL\tqa07-no-shell-tool\t$tool"
    exit 2
  fi
done

RUN_DIR="${TMPDIR:-/tmp}/qa07-run"
CLASSES="$RUN_DIR/classes"
mkdir -p "$CLASSES"

# Bounded compilation. Cleaning only our own scratch class directory keeps repeat runs deterministic.
find "$CLASSES" -type f -name '*.class' -delete
timeout 90 "$JH/javac" -Xlint:all -d "$CLASSES" "$SRC"

if [ "$MODE" = generate ]; then
  mkdir -p "$FIXTURES"
  timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES" Qa07Gate generate "$FIXTURES"
else
  mkdir -p "$RUN_DIR"
  timeout 180 "$JH/java" -Xmx96m -cp "$CLASSES" Qa07Gate verify "$FIXTURES" | tee "$RUN_DIR/host-report.txt"
fi
