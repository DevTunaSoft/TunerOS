#!/usr/bin/env bash
# QA-39 consolidated host gate over pinned QA deliveries.
# Modes: gate-only (report, exit 0 PASS / 1 FAIL), verify (scratch mutation proof).
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
mode="${1:-}"
case "$mode" in
  gate-only|verify) ;;
  *) printf 'usage: run.sh gate-only|verify\n'; exit 2 ;;
esac
exec python3 "$script_dir/Qa39Gate.py" "$mode"
