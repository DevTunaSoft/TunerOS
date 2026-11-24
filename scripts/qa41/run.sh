#!/usr/bin/env bash
# QA-41: deterministic offline recording-outcome matrix gate with mutation probe.
# Reads only committed fixtures plus one scratch copy. No device, network,
# product edit, PR or merge. Exit 0 PASS, 1 FAIL, 2 setup error.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/dvr-outcome"
gate="$script_dir/Qa41Gate.py"
mutator="$script_dir/mutate.py"
mode="${1:-verify}"

python_bin="$(command -v python3 || command -v python || true)"
if [[ -z "$python_bin" ]]; then
  printf 'SETUP\tFAIL\tpython-missing\n'
  exit 2
fi
for required in "$gate" "$mutator" "$fixtures/cases.tsv" "$fixtures/markers.tsv" \
                "$fixtures/expectations.tsv" "$fixtures/sha256.txt"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-input\n'
    exit 2
  fi
done

native_tmp="$(cygpath -m "${TMPDIR:-/tmp}" 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}")"
work="$native_tmp/qa41-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

run_gate() {
  "$python_bin" "$gate" --fixtures "$1" --mode "$2" > "$3" 2>&1
  return $?
}

summary_field() {
  awk -F '\t' -v key="$1" '$1 == "SUMMARY" { for (i = 1; i <= NF; i++) { n = split($i, a, "="); if (n == 2 && a[1] == key) print a[2] } }' "$2"
}

first_field() {
  awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$1"
}

if [[ "$mode" == "gate" ]]; then
  run_gate "$fixtures" normal "$work/green.txt"
  rc=$?
  cat "$work/green.txt"
  exit "$rc"
fi
if [[ "$mode" != "verify" ]]; then
  printf 'SETUP\tFAIL\tbad-mode\n'
  exit 2
fi

mkdir -p "$work/fixtures"
cp "$fixtures/cases.tsv" "$fixtures/markers.tsv" "$fixtures/expectations.tsv" \
   "$fixtures/sha256.txt" "$work/fixtures/"

"$python_bin" "$mutator" "$work/fixtures/markers.tsv" > "$work/mutate.txt" 2>&1
mutate_rc=$?
if (( mutate_rc != 0 )); then
  printf 'MUTATE\tFAIL\trc=%d\n' "$mutate_rc"
  cat "$work/mutate.txt"
  exit 1
fi

run_gate "$work/fixtures" mutant "$work/red.txt"
red_rc=$?
red_first="$(first_field "$work/red.txt")"
red_fail="$(summary_field fail "$work/red.txt")"
red_checks="$(summary_field checks "$work/red.txt")"

run_gate "$fixtures" normal "$work/green.txt"
green_rc=$?
green_pass="$(summary_field pass "$work/green.txt")"
green_fail="$(summary_field fail "$work/green.txt")"
green_checks="$(summary_field checks "$work/green.txt")"
green_cases="$(awk -F '\t' '$1 == "COUNTS" { split($2, a, "="); print a[2] }' "$work/green.txt")"

grep '^COUNTS' "$work/green.txt" || true
printf 'RED\trc=%d\tfirst=%s\tfail=%s\tchecks=%s\n' "$red_rc" "$red_first" "$red_fail" "$red_checks"
printf 'GREEN\trc=%d\tpass=%s\tchecks=%s\tfail=%s\tcases=%s\n' \
  "$green_rc" "$green_pass" "$green_checks" "$green_fail" "$green_cases"

ok=1
if (( red_rc != 1 )) || [[ "$red_first" != "POLICY_MARKER" ]] || [[ "$red_fail" != "1" ]]; then
  ok=0
fi
if (( green_rc != 0 )) || [[ "$green_fail" != "0" ]] || [[ "$green_checks" != "41" ]]; then
  ok=0
fi
if (( ok == 1 )); then
  printf 'MUTATION\tPASS\tone-policy-marker-mutant-detected-and-committed-fixtures-untouched\n'
  exit 0
fi
printf 'MUTATION\tFAIL\n'
exit 1
