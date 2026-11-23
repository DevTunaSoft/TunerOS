#!/usr/bin/env bash
# QA-35: bounded host-only static gate for the GUIDE-07 recycled-row D-pad window-shift fix.
# Reads pinned git objects only. No emulator, no network, no product edit.
# Exit codes: 0 PASS, 1 FAIL, 2 SETUP/usage error.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa35"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
marker_file="$fixtures/marker.txt"
sha_file="$fixtures/sha256.txt"

adapter_rel='app/src/main/java/com/devtunasoft/tuneros/CableGuideAdapter.kt'
view_rel='app/src/main/java/com/devtunasoft/tuneros/ProgramTimelineView.kt'
test_rel='app/src/test/java/com/devtunasoft/tuneros/guide07/GuideRecycledRowFocusTest.kt'
policy_rel='app/src/main/java/com/devtunasoft/tuneros/guide/GuideCellLabelPolicy.kt'

max_bytes=65536
max_lines=2000
extra_checks=9

for required in "$refs_file" "$expect_file" "$marker_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa35-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

count_fixed() {
  LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'
}

source_text() {
  # Fixed (ref, path) inputs; optional overlay is scoped to exactly one declared pair.
  local ref="$1" path="$2" out="$3" sha key bytes lines
  sha="$(ref_sha "$ref")" || return 2
  key="$(basename "$path")"
  if [[ -n "${QA35_OVERLAY_DIR:-}" &&
        "$ref" == "${QA35_OVERLAY_REF:-}" &&
        "$key" == "${QA35_OVERLAY_FILE:-}" ]]; then
    [[ -f "$QA35_OVERLAY_DIR/$key" ]] || return 2
    cp "$QA35_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
  else
    git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
  fi
  bytes="$(wc -c < "$out" | tr -d '[:space:]')"
  lines="$(wc -l < "$out" | tr -d '[:space:]')"
  if (( bytes > max_bytes || lines > max_lines || bytes == 0 )); then return 3; fi
  return 0
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir" || { printf 'SETUP\tFAIL\tgate-dir\n'; return 2; }
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

  local base pre fix ux01
  local refs_ok=1
  base="$(ref_sha BASE)" || { fail REFS_RESOLVE 'BASE-missing'; refs_ok=0; }
  pre="$(ref_sha PRE)" || { fail REFS_RESOLVE 'PRE-missing'; refs_ok=0; }
  fix="$(ref_sha FIX)" || { fail REFS_RESOLVE 'FIX-missing'; refs_ok=0; }
  ux01="$(ref_sha UX01)" || { fail REFS_RESOLVE 'UX01-missing'; refs_ok=0; }
  if [[ "$base" == "$pre" || "$base" == "$fix" || "$base" == "$ux01" ||
        "$pre" == "$fix" || "$pre" == "$ux01" || "$fix" == "$ux01" ]]; then
    fail REFS_RESOLVE 'refs-not-distinct'
    refs_ok=0
  fi
  if [[ "$refs_ok" == 1 ]]; then
    pass REFS_RESOLVE 'refs=4 distinct'
  fi

  # The guide07 regression file must be new in FIX, not merely edited.
  if git -C "$repo" cat-file -e "$pre:$test_rel" 2>/dev/null; then
    fail T_PRE_TEST_ABSENT 'test-present-before-fix'
  else
    pass T_PRE_TEST_ABSENT 'test-absent-before-fix'
  fi

  # All three pinned topic commits descend from the same base.
  local mb common_ok=1 r
  for r in "$pre" "$fix" "$ux01"; do
    mb="$(git -C "$repo" merge-base "$base" "$r" 2>/dev/null)"
    if [[ "$mb" != "$base" ]] || ! git -C "$repo" cat-file -e "$r^{commit}" 2>/dev/null; then
      common_ok=0
    fi
  done
  if [[ "$common_ok" == 1 ]] && git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null; then
    pass D_COMMON_BASE "merge-base=${base:0:8} commits=4"
  else
    fail D_COMMON_BASE 'merge-base-or-commits-invalid'
  fi

  # FIX changes exactly the three GUIDE-07 files relative to its parent.
  local fileset expected_files
  expected_files='app/src/main/java/com/devtunasoft/tuneros/CableGuideAdapter.kt;app/src/main/java/com/devtunasoft/tuneros/ProgramTimelineView.kt;app/src/test/java/com/devtunasoft/tuneros/guide07/GuideRecycledRowFocusTest.kt'
  fileset="$(git -C "$repo" diff --name-only "$pre" "$fix" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  if [[ "$fileset" == "$expected_files" ]]; then
    pass D_FIX_FILESET 'files=3'
  else
    fail D_FIX_FILESET 'fix-file-set-mismatch'
  fi

  # GUIDE-07 touches no draw-time text region: its ProgramTimelineView edits cannot
  # clobber the A3 UX-01 sub-glyph guard when the two subjects are integrated.
  local draw_hits
  draw_hits="$(git -C "$repo" diff "$pre" "$fix" -- "$view_rel" 2>/dev/null \
    | grep -E '^[+-]' | grep -E -c 'horizontalPadding|clipRect|drawText|onDraw|measureText' || true)"
  draw_hits="${draw_hits:-0}"
  if [[ "$draw_hits" == 0 ]]; then
    pass U_FIX_AVOIDS_DRAW_GUARD 'draw-region-changed-lines=0'
  else
    fail U_FIX_AVOIDS_DRAW_GUARD "draw-region-changed-lines=$draw_hits"
  fi

  # Fixed expectations, driven by the committed fixture rows.
  local id ref path kind needle expected n src scope
  local -a known=(PRE FIX UX01)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    src="$dir/src.$id"
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    case "$path" in
      CableGuideAdapter.kt) scope="$adapter_rel" ;;
      ProgramTimelineView.kt) scope="$view_rel" ;;
      GuideRecycledRowFocusTest.kt) scope="$test_rel" ;;
      GuideCellLabelPolicy.kt) scope="$policy_rel" ;;
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
  marker_line="$(printf 'SENTINEL\t%s\tbase=%s\tpre=%s\tfix=%s\tux01=%s' \
    "$marker_text" "${base:0:8}" "${pre:0:8}" "${fix:0:8}" "${ux01:0:8}")"
  printf '%s\n' "$marker_line" >> "$report"
  marker_count="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$report")"
  marker_bytes="$(printf '%s' "$marker_line" | wc -c | tr -d '[:space:]')"
  if [[ "$marker_count" == 1 && "$marker_bytes" -le 200 && "$marker_text" == 'SENTINEL-G07-QA35' ]]; then
    pass M_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  else
    fail M_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src/main core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then
    pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi

  if LC_ALL=C grep -E -q \
      '(https?://|www\.|[[:alnum:]_.-]+@[[:alnum:]_.-]+\.[[:alpha:]]{2,}|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
      "$report" "$expect_file" "$refs_file" "$marker_file"; then
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
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tbase=%s\tpre=%s\tfix=%s\tux01=%s\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" \
    "${base:0:8}" "${pre:0:8}" "${fix:0:8}" "${ux01:0:8}")"
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
  QA35_OVERLAY_DIR="$d/overlay" QA35_OVERLAY_REF=FIX QA35_OVERLAY_FILE=CableGuideAdapter.kt gate red
  local red_rc=$?
  local red="$work/gate.red/report.tsv"
  local red_hits red_first red_marker
  red_hits="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 == "A_PASS_TO_BIND" { n++ } END { print n + 0 }' "$red")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"
  red_marker="$(awk -F '\t' '$1 == "SENTINEL" { n++ } END { print n + 0 }' "$red")"

  python3 "$py" --repo "$repo" --fix "$fix" --dir "$d" restore || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }

  printf '%s\n' '--- RESTORED (same scratch overlay, original bytes) ---'
  QA35_OVERLAY_DIR="$d/overlay" QA35_OVERLAY_REF=FIX QA35_OVERLAY_FILE=CableGuideAdapter.kt gate restored
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
     [[ "$red_first" != 'first=A_PASS_TO_BIND' ||
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
