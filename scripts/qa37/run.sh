#!/usr/bin/env bash
# QA-37: bounded host-only static gate for the FAV-06 bounded series-rule replacement.
# Reads pinned git objects only. No emulator, no network, no product edit.
# Exit codes: 0 PASS, 1 FAIL, 2 SETUP/usage error.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa37"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
marker_file="$fixtures/marker.txt"
sha_file="$fixtures/sha256.txt"

store_rel='app/src/main/java/com/devtunasoft/tuneros/SeriesRuleStore.kt'
test_rel='app/src/test/java/com/devtunasoft/tuneros/fav06/SeriesRuleReplaceTest.kt'

max_bytes=65536
max_lines=2000
extra_checks=9
sensitive='(https?://|www\.|[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])'

for required in "$refs_file" "$expect_file" "$marker_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa37-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

count_fixed() {
  LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'
}

GATE_DIR=''
source_text() {
  # Fixed (ref, path) inputs, read once per gate run; overlay is scoped to one declared (ref, basename) pair.
  local ref="$1" path="$2" out="$3" sha key cache bytes lines
  sha="$(ref_sha "$ref")" || return 2
  key="$(basename "$path")"
  cache="$GATE_DIR/cache/$ref.$key"
  if [[ ! -f "$cache" ]]; then
    if [[ -n "${QA37_OVERLAY_DIR:-}" &&
          "$ref" == "${QA37_OVERLAY_REF:-}" &&
          "$key" == "${QA37_OVERLAY_FILE:-}" ]]; then
      [[ -f "$QA37_OVERLAY_DIR/$key" ]] || return 2
      cp "$QA37_OVERLAY_DIR/$key" "$cache" 2>/dev/null || return 2
    else
      git -C "$repo" show "$sha:$path" > "$cache" 2>/dev/null || return 2
    fi
  fi
  bytes="$(wc -c < "$cache" | tr -d '[:space:]')"
  lines="$(wc -l < "$cache" | tr -d '[:space:]')"
  if (( bytes > max_bytes || lines > max_lines || bytes == 0 )); then return 3; fi
  cp "$cache" "$out" 2>/dev/null || return 2
  return 0
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir/cache" || { printf 'SETUP\tFAIL\tgate-dir\n'; return 2; }
  GATE_DIR="$dir"
  local report="$dir/report.tsv"
  : > "$report"
  local checks=0 passes=0 fails=0 first=NONE

  pass() {
    checks=$((checks + 1)); passes=$((passes + 1))
    printf 'CHECK\tPASS\t%s\t%s\n' "$1" "$2" >> "$report"
  }
  fail() {
    checks=$((checks + 1)); fails=$((fails + 1))
    if [[ "$first" == NONE ]]; then first="$1"; fi
    printf 'CHECK\tFAIL\t%s\t%s\n' "$1" "$2" >> "$report"
  }

  # Pinned fixture bytes: a tampered expectation/ref/marker cannot redefine the gate.
  local want_exp want_refs want_marker got_exp got_refs got_marker
  want_exp="$(awk '{ name = $2; sub(/^\*/, "", name); if (name == "expectations.tsv") print $1 }' "$sha_file")"
  want_refs="$(awk '{ name = $2; sub(/^\*/, "", name); if (name == "refs.tsv") print $1 }' "$sha_file")"
  want_marker="$(awk '{ name = $2; sub(/^\*/, "", name); if (name == "marker.txt") print $1 }' "$sha_file")"
  got_exp="$(sha256sum "$expect_file" | awk '{ print $1 }')"
  got_refs="$(sha256sum "$refs_file" | awk '{ print $1 }')"
  got_marker="$(sha256sum "$marker_file" | awk '{ print $1 }')"
  if [[ -n "$want_exp" && -n "$want_refs" && -n "$want_marker" &&
        "$want_exp" == "$got_exp" && "$want_refs" == "$got_refs" && "$want_marker" == "$got_marker" ]]; then
    pass FIXTURE_SHA256 'digests=3'
  else
    fail FIXTURE_SHA256 'fixture-bytes-changed'
  fi

  local base fix refs_ok=1
  base="$(ref_sha BASE)" || { fail REFS_RESOLVE 'BASE-missing'; refs_ok=0; }
  fix="$(ref_sha FIX)" || { fail REFS_RESOLVE 'FIX-missing'; refs_ok=0; }
  if [[ -n "$base" && -n "$fix" && "$base" != "$fix" ]] &&
     git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$fix^{commit}" 2>/dev/null; then
    :
  else
    refs_ok=0
  fi
  if [[ "$refs_ok" == 1 ]]; then
    pass REFS_RESOLVE 'refs=2 distinct-commits'
  else
    fail REFS_RESOLVE 'refs-invalid'
  fi

  # FIX is the exact C10 commit whose parent is the pinned base.
  local parent
  parent="$(git -C "$repo" rev-parse "$fix^" 2>/dev/null)"
  if [[ "$parent" == "$base" ]]; then
    pass D_PARENT "fix-parent=${base:0:8}"
  else
    fail D_PARENT 'fix-parent-mismatch'
  fi

  # FAV-06 changes exactly the store and its new regression file.
  local fileset expected_files
  expected_files='app/src/main/java/com/devtunasoft/tuneros/SeriesRuleStore.kt;app/src/test/java/com/devtunasoft/tuneros/fav06/SeriesRuleReplaceTest.kt'
  fileset="$(git -C "$repo" diff --name-only "$base" "$fix" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  if [[ "$fileset" == "$expected_files" ]]; then
    pass D_FILESET 'files=2'
  else
    fail D_FILESET 'fix-file-set-mismatch'
  fi

  # The fav06 regression file is new in FIX, not merely edited.
  if git -C "$repo" cat-file -e "$base:$test_rel" 2>/dev/null; then
    fail D_TEST_NEW 'test-present-at-base'
  elif ! git -C "$repo" cat-file -e "$fix:$test_rel" 2>/dev/null; then
    fail D_TEST_NEW 'test-missing-at-fix'
  else
    pass D_TEST_NEW 'test-new-at-fix'
  fi

  # No stale key or credential token is introduced by the FAV-06 added lines.
  local added added_hits
  added="$dir/added.txt"
  git -C "$repo" diff --unified=0 "$base" "$fix" 2>/dev/null \
    | grep -E '^\+' | grep -v '^+++' > "$added" || true
  added_hits="$(LC_ALL=C grep -E -c "$sensitive" "$added" 2>/dev/null || true)"
  added_hits="${added_hits:-0}"
  if [[ "$added_hits" == 0 ]]; then
    pass L_NO_SENSITIVE_ADDED 'sensitive-added-lines=0'
  else
    fail L_NO_SENSITIVE_ADDED "sensitive-added-lines=$added_hits"
  fi

  # Fixed expectations, driven by the committed fixture rows.
  local id ref path kind needle expected n src scope
  local -a known=(BASE FIX)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    src="$dir/src.$id"
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    case "$path" in
      SeriesRuleStore.kt) scope="$store_rel" ;;
      SeriesRuleReplaceTest.kt) scope="$test_rel" ;;
      *) fail "$id" 'unknown-path'; continue ;;
    esac
    if ! source_text "$ref" "$scope" "$src"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    case "$kind" in
      count) n="$(count_fixed "$src" "$needle")" ;;
      absent) n="$(count_fixed "$src" "$needle")" ;;
      *) fail "$id" 'unknown-kind'; continue ;;
    esac
    case "$kind" in
      count)
        if [[ "$n" == "$expected" ]]; then
          pass "$id" "count=$n expected=$expected"
        else
          fail "$id" "count=$n expected=$expected"
        fi
        ;;
      absent)
        if [[ "$n" == 0 && "$expected" == 0 ]]; then
          pass "$id" 'count=0 expected=0'
        else
          fail "$id" "count=$n expected=0"
        fi
        ;;
    esac
  done < "$expect_file"

  # Exactly one bounded synthetic marker line, derived from the pinned marker fixture.
  local marker_text marker_line marker_count marker_bytes
  marker_text="$(tr -d '\r\n' < "$marker_file")"
  marker_line="$(printf 'SENTINEL\t%s\tbase=%s\tfix=%s' "$marker_text" "${base:0:8}" "${fix:0:8}")"
  printf '%s\n' "$marker_line" >> "$report"
  marker_count="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$report")"
  marker_bytes="$(printf '%s' "$marker_line" | wc -c | tr -d '[:space:]')"
  if [[ "$marker_count" == 1 && "$marker_bytes" -le 200 && "$marker_text" == 'SENTINEL-FAV06-QA37' ]]; then
    pass M_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  else
    fail M_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  fi

  # No product file is modified in this worktree by the gate.
  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then
    pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi

  # Diagnostics carry check ids, counts and hashes only: no URL, credential or secret.
  if LC_ALL=C grep -E -q "$sensitive" "$report" "$expect_file" "$refs_file" "$marker_file"; then
    fail P_OUTPUT_PRIVACY 'sensitive-pattern-in-report-or-fixtures'
  else
    pass P_OUTPUT_PRIVACY 'report-and-fixtures-clean'
  fi

  local rows total verdict rc summary
  rows="$(awk 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$expect_file")"
  total=$((rows + extra_checks))
  cat "$report"
  if (( fails == 0 && checks == total )); then
    verdict=PASS; rc=0
  else
    verdict=FAIL; rc=1
  fi
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tbase=%s\tfix=%s\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" "${base:0:8}" "${fix:0:8}")"
  printf '%s\n' "$summary"
  printf '%s\n' "$summary" > "$dir/summary.txt"
  return "$rc"
}

mutation_probe() {
  local fix py="$script_dir/mutate.py" rc=0
  fix="$(ref_sha FIX)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  local d="$work/mutation"
  mkdir -p "$d" || { printf 'MUTATION\tFAIL\tscratch-unavailable\n'; return 1; }
  python3 "$py" --repo "$repo" --fix "$fix" --dir "$d" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }
  python3 "$py" --repo "$repo" --fix "$fix" --dir "$d" mutate || { printf 'MUTATION\tFAIL\tmutate\n'; return 1; }

  printf '%s\n' '--- RED (mutated scratch overlay) ---'
  QA37_OVERLAY_DIR="$d/overlay" QA37_OVERLAY_REF=FIX QA37_OVERLAY_FILE=SeriesRuleStore.kt gate red
  local red_rc=$?
  local red="$work/gate.red/report.tsv"
  local red_hits red_first red_marker
  red_hits="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 == "S_BOUND_GUARD" { n++ } END { print n + 0 }' "$red")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"
  red_marker="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$red")"

  python3 "$py" --repo "$repo" --fix "$fix" --dir "$d" restore || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }

  printf '%s\n' '--- RESTORED (same scratch overlay, original bytes) ---'
  QA37_OVERLAY_DIR="$d/overlay" QA37_OVERLAY_REF=FIX QA37_OVERLAY_FILE=SeriesRuleStore.kt gate restored
  local restored_rc=$?
  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?

  local restored_marker green_marker restored_verdict green_verdict
  restored_marker="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$work/gate.restored/report.tsv")"
  green_marker="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$work/gate.green/report.tsv")"
  restored_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.restored/summary.txt" 2>/dev/null || true)"
  green_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.green/summary.txt" 2>/dev/null || true)"

  printf 'MUTATION\tRED_RC\t%d\tRED_HITS\t%s\tRED_FIRST\t%s\tRESTORED_RC\t%d\tGREEN_RC\t%d\tRESTORE_MARKER\t%s\tGREEN_MARKER\t%s\n' \
    "$red_rc" "$red_hits" "${red_first:-none}" "$restored_rc" "$green_rc" "$restored_marker" "$green_marker"

  if (( red_rc != 1 || red_hits != 1 || restored_rc != 0 || green_rc != 0 )) ||
     [[ "$red_first" != 'first=S_BOUND_GUARD' ||
        "$red_marker" != 1 || "$restored_marker" != 1 || "$green_marker" != 1 ||
        "$restored_verdict" != 'verdict=PASS' || "$green_verdict" != 'verdict=PASS' ]]; then
    printf 'MUTATION\tFAIL\tsensitivity-not-proven\tred-first=%s\n' "${red_first:-none}"
    rc=1
  else
    printf 'MUTATION\tPASS\tsingle-scratch-mutation-detected-and-restored\tred-first=%s\n' "$red_first"
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only) gate green ;;
  verify) mutation_probe ;;
  *) printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2 ;;
esac
