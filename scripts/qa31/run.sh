#!/usr/bin/env bash
# QA-31: bounded, host-only deterministic gate for the DVR-03 + REL-01 fix combination.
# Reads pinned git objects only. No emulator, no network, no product edit.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa31"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
sha_file="$fixtures/sha256.txt"
manifest='app/src/main/AndroidManifest.xml'
receiver='app/src/main/java/com/devtunasoft/tuneros/RecordingRearmReceiver.kt'
scheduler='app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt'
max_bytes=65536
max_lines=2000
extra_checks=8

for required in "$refs_file" "$expect_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa31-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir"
  local report="$dir/report.tsv"
  : > "$report"
  local checks=0 passes=0 fails=0 first=NONE
  local base c2 c3

  pass() {
    checks=$((checks + 1)); passes=$((passes + 1))
    printf 'CHECK\tPASS\t%s\t%s\n' "$1" "$2" >> "$report"
  }
  fail() {
    checks=$((checks + 1)); fails=$((fails + 1))
    if [[ "$first" == NONE ]]; then first="$1"; fi
    printf 'CHECK\tFAIL\t%s\t%s\n' "$1" "$2" >> "$report"
  }

  # Pinned fixture bytes: a tampered expectation file cannot redefine the gate.
  local want_exp want_refs got_exp got_refs
  want_exp="$(awk '{ name = $2; sub(/^\*/, "", name); if (name == "expectations.tsv") print $1 }' "$sha_file")"
  want_refs="$(awk '{ name = $2; sub(/^\*/, "", name); if (name == "refs.tsv") print $1 }' "$sha_file")"
  got_exp="$(sha256sum "$expect_file" | awk '{ print $1 }')"
  got_refs="$(sha256sum "$refs_file" | awk '{ print $1 }')"
  if [[ -n "$want_exp" && -n "$want_refs" && "$want_exp" == "$got_exp" && "$want_refs" == "$got_refs" ]]; then
    pass FIXTURE_SHA256 'digests=2'
  else
    fail FIXTURE_SHA256 'fixture-bytes-changed'
  fi

  base="$(ref_sha BASE)" || fail REFS_RESOLVE 'BASE-missing'
  c2="$(ref_sha C2)" || fail REFS_RESOLVE 'C2-missing'
  c3="$(ref_sha C3)" || fail REFS_RESOLVE 'C3-missing'

  source_text() {
    # Fixed three-file input set; an optional overlay is used only by the
    # scratch mutation probe and is capped exactly like git input.
    local ref="$1" path="$2" out="$3" sha key bytes lines
    sha="$(ref_sha "$ref")" || return 2
    key="$(basename "$path")"
    if [[ -n "${QA31_OVERLAY_DIR:-}" &&
          "$ref" == "${QA31_OVERLAY_REF:-}" &&
          "$key" == "${QA31_OVERLAY_FILE:-}" ]]; then
      # Fail closed: a declared but missing substitution must never fall back.
      [[ -f "$QA31_OVERLAY_DIR/$key" ]] || return 2
      cp "$QA31_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
    else
      git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
    fi
    bytes="$(wc -c < "$out" | tr -d '[:space:]')"
    lines="$(wc -l < "$out" | tr -d '[:space:]')"
    if (( bytes > max_bytes || lines > max_lines )); then return 3; fi
    return 0
  }

  count_fixed() {
    LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'
  }

  local id ref path kind needle expected n src
  local -a known=(BASE C2 C3)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    src="$dir/src.$id"
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    if ! source_text "$ref" "$path" "$src"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    n="$(count_fixed "$src" "$needle")"
    case "$kind" in
      count)
        if [[ "$n" == "$expected" ]]; then
          pass "$id" "count=$n expected=$expected"
        else
          fail "$id" "count=$n expected=$expected"
        fi
        ;;
      absent)
        if [[ "$n" == 0 ]]; then
          pass "$id" 'count=0 expected=0'
        else
          fail "$id" "count=$n expected=0"
        fi
        ;;
      *)
        fail "$id" 'unknown-kind'
        ;;
    esac
  done < "$expect_file"

  # Structural: the MY_PACKAGE_REPLACED receiver block is single-purpose,
  # non-exported, and carries exactly one action.
  local block="$dir/manifest.block"
  source_text C3 "$manifest" "$dir/manifest.c3"
  awk 'BEGIN { inside = 0 }
       /<receiver android:name="\.UpgradeRearmReceiver"/ { inside = 1 }
       inside { print }
       inside && /<\/receiver>/ { exit }' "$dir/manifest.c3" > "$block"
  local act act_ok exp_false exp_true boot
  act="$(count_fixed "$block" '<action ')"
  act_ok="$(count_fixed "$block" '<action android:name="android.intent.action.MY_PACKAGE_REPLACED" />')"
  exp_false="$(count_fixed "$block" 'android:exported="false"')"
  exp_true="$(count_fixed "$block" 'android:exported="true"')"
  boot="$(count_fixed "$block" 'BOOT_COMPLETED')"
  if [[ "$act" == 1 && "$act_ok" == 1 && "$exp_false" == 1 && "$exp_true" == 0 && "$boot" == 0 ]]; then
    pass M_SINGLE_NONEXPORTED_RECEIVER 'actions=1 exported=false extra-actions=0'
  else
    fail M_SINGLE_NONEXPORTED_RECEIVER "actions=$act expected-action=$act_ok exported-false=$exp_false exported-true=$exp_true boot-actions=$boot"
  fi

  # Structural: the action guard precedes the single rearm dispatch.
  source_text C3 "$receiver" "$dir/receiver.c3"
  local guard_line dispatch_line
  guard_line="$(LC_ALL=C grep -n -F -m1 'Intent.ACTION_MY_PACKAGE_REPLACED' "$dir/receiver.c3" | cut -d: -f1)"
  dispatch_line="$(LC_ALL=C grep -n -F -m1 'RecordingScheduler.rearmPending(' "$dir/receiver.c3" | cut -d: -f1)"
  if [[ -n "$guard_line" && -n "$dispatch_line" ]] && (( guard_line < dispatch_line )); then
    pass R_GUARD_BEFORE_DISPATCH "guard-line=$guard_line dispatch-line=$dispatch_line"
  else
    fail R_GUARD_BEFORE_DISPATCH "guard-line=${guard_line:-none} dispatch-line=${dispatch_line:-none}"
  fi

  # Structural: each unmerged fix owns its own production delta.
  local actual_c2 actual_c3 expect_c2 expect_c3
  actual_c2="$(git -C "$repo" diff --name-only "$base" "$c2" -- app/src/main/ | LC_ALL=C sort | paste -sd ';' -)"
  actual_c3="$(git -C "$repo" diff --name-only "$base" "$c3" -- app/src/main/ | LC_ALL=C sort | paste -sd ';' -)"
  expect_c2="$scheduler"
  expect_c3="$(printf '%s\n%s\n' "$manifest" "$receiver" | LC_ALL=C sort | paste -sd ';' -)"
  if [[ "$actual_c2" == "$expect_c2" ]]; then
    pass D_C2_TOUCHES_ONLY_SCHEDULER 'changed-paths=1'
  else
    fail D_C2_TOUCHES_ONLY_SCHEDULER 'changed-path-set-mismatch'
  fi
  if [[ "$actual_c3" == "$expect_c3" ]]; then
    pass D_C3_TOUCHES_MANIFEST_AND_RECEIVER 'changed-paths=2'
  else
    fail D_C3_TOUCHES_MANIFEST_AND_RECEIVER 'changed-path-set-mismatch'
  fi

  if [[ "$base" != "$c2" && "$base" != "$c3" && "$c2" != "$c3" ]] &&
     git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c2^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c3^{commit}" 2>/dev/null; then
    pass D_TWO_DISTINCT_FIXES 'commits=3 distinct'
  else
    fail D_TWO_DISTINCT_FIXES 'pinned-commits-invalid'
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- "$manifest" "$receiver" "$scheduler")"
  if [[ -z "$drift" ]]; then
    pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi

  if LC_ALL=C grep -E -q -i \
      '(https?://|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
      "$report" "$expect_file" "$refs_file"; then
    fail P_OUTPUT_PRIVACY 'sensitive-pattern-in-report-or-fixture'
  else
    pass P_OUTPUT_PRIVACY 'report-and-fixtures-clean'
  fi

  local rows total verdict rc
  rows="$(awk 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$expect_file")"
  total=$((rows + extra_checks))
  cat "$report"
  if (( fails == 0 && checks == total )); then
    verdict=PASS; rc=0
  else
    verdict=FAIL; rc=1
  fi
  local summary
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total")"
  printf '%s\n' "$summary"
  printf '%s\n' "$summary" > "$dir/summary.txt"
  return "$rc"
}

mutation_probe() {
  local c2 c3 py="$script_dir/mutate.py" rc=0
  c2="$(ref_sha C2)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c3="$(ref_sha C3)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  local d="$work/mutation"
  mkdir -p "$d"
  python3 "$py" --repo "$repo" --c2 "$c2" --c3 "$c3" --dir "$d" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }
  python3 "$py" --repo "$repo" --c2 "$c2" --c3 "$c3" --dir "$d" mutate || { printf 'MUTATION\tFAIL\tmutate\n'; return 1; }

  printf '%s\n' '--- RED (mutated scratch overlay) ---'
  QA31_OVERLAY_DIR="$d/overlay" QA31_OVERLAY_REF=C3 QA31_OVERLAY_FILE=RecordingRearmReceiver.kt gate red
  local red_rc=$?
  local red="$work/gate.red/report.tsv"
  local red_hits red_first
  red_hits="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 == "R_DISPATCH_EXACTLY_ONE" { n++ } END { print n + 0 }' "$red")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"

  python3 "$py" --repo "$repo" --c2 "$c2" --c3 "$c3" --dir "$d" restore || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }

  printf '%s\n' '--- RESTORED (same scratch overlay, original bytes) ---'
  QA31_OVERLAY_DIR="$d/overlay" QA31_OVERLAY_REF=C3 QA31_OVERLAY_FILE=RecordingRearmReceiver.kt gate restored
  local restored_rc=$?
  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?

  printf 'MUTATION\tRED_RC\t%d\tRED_HITS\t%s\tRESTORED_RC\t%d\tGREEN_RC\t%d\n' \
    "$red_rc" "$red_hits" "$restored_rc" "$green_rc"
  if (( red_rc == 0 || red_hits != 1 || restored_rc != 0 || green_rc != 0 )) ||
     [[ "$red_first" != 'first=R_DISPATCH_EXACTLY_ONE' ]]; then
    printf 'MUTATION\tFAIL\tsensitivity-not-proven\tred-first=%s\n' "${red_first:-none}"
    rc=1
  else
    printf 'MUTATION\tPASS\tsingle-scratch-mutation-detected-and-restored\tred-first=%s\n' "$red_first"
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only)
    gate green
    ;;
  verify)
    mutation_probe
    ;;
  *)
    printf 'USAGE\t%s\tgate-only|verify\n' "$0"
    exit 2
    ;;
esac
