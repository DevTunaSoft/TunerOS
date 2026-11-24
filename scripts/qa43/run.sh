#!/usr/bin/env bash
# QA-43 bounded offline source-hygiene gate runner.
# Runs the committed-source scan and the scratch mutation probe, then reports
# exact exit statuses. No scanned source is edited; no secrets are printed.
set -u

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if command -v cygpath >/dev/null 2>&1; then ROOT=$(cygpath -m "$ROOT"); fi
PYTHON=${PYTHON:-python3}
SCRATCH=${TMPDIR:-/tmp}
if command -v cygpath >/dev/null 2>&1; then SCRATCH=$(cygpath -m "$SCRATCH"); fi
GATE="$ROOT/scripts/qa43/source_hygiene_gate.py"

TIMEOUT=""
if command -v timeout >/dev/null 2>&1; then TIMEOUT="timeout 120s"; fi

# shellcheck disable=SC2086
$TIMEOUT "$PYTHON" "$GATE" scan
SCAN=$?
# shellcheck disable=SC2086
$TIMEOUT "$PYTHON" "$GATE" probe --scratch "$SCRATCH"
PROBE=$?

printf 'GATE\tSCAN\t%s\nGATE\tPROBE\t%s\n' "$SCAN" "$PROBE"
if [ "$SCAN" -eq 0 ] && [ "$PROBE" -eq 0 ]; then
  printf 'GATE\tPASS\n'
  exit 0
fi
printf 'GATE\tFAIL\n'
exit 1
