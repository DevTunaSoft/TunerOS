#!/usr/bin/env bash
# QA-40: bounded host-only pinned-SHA integration-sequence gate.
# Reads only pinned git objects plus owned fixtures. No network, device, product
# edit, PR or merge. Exit 0 PASS (no hazard), 1 FAIL, 3 MERGE-REVIEW-NEEDED.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa40"
refs_file="$fixtures/refs.tsv"
fixes_file="$fixtures/fixes.tsv"
markers_file="$fixtures/markers.tsv"
expect_file="$fixtures/expectations.tsv"
sha_file="$fixtures/sha256.txt"

for required in "$refs_file" "$fixes_file" "$markers_file" "$expect_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

native_tmp="$(cygpath -m "${TMPDIR:-/tmp}" 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}")"
work="$native_tmp/qa40-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

expect() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$expect_file"
}

rows() {
  awk -F '\t' 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$1"
}

is_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

# Read one pinned git object (or one explicitly declared scratch overlay) into $3.
# A declared but absent overlay fails closed. Bytes and lines are bounded.
object_text() {
  local ref="$1" path="$2" out="$3" sha key bytes lines cap_bytes cap_lines cache
  cap_bytes="$(expect object.max.bytes)" || return 2
  cap_lines="$(expect object.max.lines)" || return 2
  sha="$(ref_sha "$ref")" || return 2
  is_sha "$sha" || return 2
  key="${ref}_$(basename "$path")"
  cache="$QA40_CACHE_DIR/$key"
  if [[ -n "${QA40_OVERLAY_DIR:-}" && "$ref" == "${QA40_OVERLAY_REF:-}" && "$path" == "${QA40_OVERLAY_PATH:-}" ]]; then
    [[ -f "$QA40_OVERLAY_DIR/$key" ]] || return 2
    cp "$QA40_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
  elif [[ -f "$cache" ]]; then
    cp "$cache" "$out" 2>/dev/null || return 2
  else
    git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
    mkdir -p "$QA40_CACHE_DIR"
    cp "$out" "$cache" 2>/dev/null || return 2
  fi
  bytes="$(wc -c < "$out" | tr -d '[:space:]')"
  lines="$(wc -l < "$out" | tr -d '[:space:]')"
  if (( bytes == 0 || bytes > cap_bytes || lines > cap_lines )); then return 3; fi
  return 0
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir"
  QA40_CACHE_DIR="$dir/cache"
  mkdir -p "$QA40_CACHE_DIR"
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

  # 1. Fixture bytes are pinned: a tampered fixture cannot redefine the gate.
  local want_exp want_refs want_fixes want_markers got_exp got_refs got_fixes got_markers
  want_exp="$(awk '$2 == "expectations.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  want_refs="$(awk '$2 == "refs.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  want_fixes="$(awk '$2 == "fixes.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  want_markers="$(awk '$2 == "markers.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  got_exp="$(sha256sum "$expect_file" | awk '{ print $1 }')"
  got_refs="$(sha256sum "$refs_file" | awk '{ print $1 }')"
  got_fixes="$(sha256sum "$fixes_file" | awk '{ print $1 }')"
  got_markers="$(sha256sum "$markers_file" | awk '{ print $1 }')"
  if [[ -n "$want_exp" && -n "$want_refs" && -n "$want_fixes" && -n "$want_markers" &&
        "$want_exp" == "$got_exp" && "$want_refs" == "$got_refs" &&
        "$want_fixes" == "$got_fixes" && "$want_markers" == "$got_markers" ]]; then
    pass FIXTURE_SHA256 'digests=4'
  else
    fail FIXTURE_SHA256 'fixture-bytes-changed'
  fi

  # 2. Every pinned object is a distinct commit object.
  local refs_ok=1 sha key pin_keys='BASE P203_C2 P206_C3 P210_C5 P213_C8 P214_C7 P218_C11 P220_C10 P221_QA34 QA33'
  for key in $pin_keys; do
    sha="$(ref_sha "$key")" || { fail REFS_RESOLVE "$key-missing"; refs_ok=0; continue; }
    is_sha "$sha" || { fail REFS_RESOLVE "$key-not-full-sha"; refs_ok=0; continue; }
    git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null || { fail REFS_RESOLVE "$key-not-commit"; refs_ok=0; }
  done
  local n=${#pin_keys}
  local seen=''
  for key in $pin_keys; do
    sha="$(ref_sha "$key")" || continue
    if [[ " $seen " == *" $sha "* ]]; then refs_ok=0; fi
    seen="$seen $sha"
  done
  if [[ "$refs_ok" == 1 ]]; then pass REFS_RESOLVE "refs=10 distinct-commits"; else
    [[ "$first" == REFS_RESOLVE ]] || fail REFS_RESOLVE 'refs-invalid'
  fi

  # 3. Each PR branch head still equals its pinned SHA (branch-name drift detector).
  local branch key2 want got
  for key2 in P203_C2 P206_C3 P210_C5 P213_C8 P214_C7 P218_C11 P220_C10 P221_QA34; do
    case "$key2" in
      P203_C2)   branch=hermes/worker-c2-dvr03 ;;
      P206_C3)   branch=hermes/worker-c3-upgraderearm ;;
      P210_C5)   branch=hermes/worker-c5-map04 ;;
      P213_C8)   branch=hermes/worker-c8-map05 ;;
      P214_C7)   branch=hermes/worker-c7-guide07 ;;
      P218_C11)  branch=hermes/worker-c11-http07 ;;
      P220_C10)  branch=hermes/worker-c10-fav06 ;;
      P221_QA34) branch=hermes/worker-b42-qa34 ;;
    esac
    want="$(ref_sha "$key2")"
    got="$(git -C "$repo" rev-parse "refs/heads/$branch" 2>/dev/null)"
    if [[ -n "$want" && "$got" == "$want" ]]; then
      pass "PIN_$key2" "head=${got:0:8}"
    else
      fail "PIN_$key2" "head=${got:-none} pinned=${want:0:8}"
    fi
  done

  # 4. Each branch still carries its claimed fix artifact: exact git blob identity
  #    plus presence in the pinned BASE..head added-file set. No semantic re-check
  #    of sibling gates is performed here.
  local id ref path want_blob added src blob base n
  base="$(ref_sha BASE)"
  while IFS=$'\t' read -r id ref path want_blob added; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    src="$dir/src.$id"
    if ! object_text "$ref" "$path" "$src"; then
      fail "FIX_SRC_$id" 'object-unavailable-or-unbounded'
      continue
    fi
    blob="$(git -C "$repo" hash-object "$src" 2>/dev/null)"
    if [[ -n "$want_blob" && "$blob" == "$want_blob" ]]; then
      pass "FIX_BLOB_$id" "blob=${blob:0:12}"
    else
      fail "FIX_BLOB_$id" "blob=${blob:0:12} pinned=${want_blob:0:12}"
    fi
    sha="$(ref_sha "$ref")"
    n="$(git -C "$repo" diff --diff-filter=A --name-only "$base..$sha" -- "$path" 2>/dev/null | wc -l | tr -d '[:space:]')"
    if [[ "$n" == "$added" ]]; then pass "FIX_ADDED_$id" "added=$n"; else
      fail "FIX_ADDED_$id" "added=$n expected=$added"
    fi
  done < "$fixes_file"

  # 5. QA-34's two MERGE-REVIEW-NEEDED markers remain in force, proven from the
  #    pinned QA-34 objects and the pinned QA-33 marker object.
  local q34sha q33sha
  q34sha="$(ref_sha P221_QA34)"
  q33sha="$(ref_sha QA33)"
  local q34markers="$dir/q34.markers" q34refs="$dir/q34.refs" q33marker="$dir/q33.marker"
  if ! object_text P221_QA34 'qa/fixtures/qa34/markers.tsv' "$q34markers"; then
    fail Q34_MARKER_SOURCE 'pinned-marker-object-unavailable'
  fi
  if ! object_text P221_QA34 'qa/fixtures/qa34/refs.tsv' "$q34refs"; then
    fail Q34_REFS_SOURCE 'pinned-refs-object-unavailable'
  fi
  if ! object_text QA33 'qa/fixtures/qa33/marker.txt' "$q33marker"; then
    fail Q33_MARKER_SOURCE 'pinned-qa33-marker-unavailable'
  fi

  local marker_rows marker_expected
  marker_rows="$(rows "$q34markers")"
  marker_expected="$(expect markers.rows)"
  if [[ "$marker_rows" == "$marker_expected" ]]; then
    pass Q34_MARKER_ROWS "rows=$marker_rows"
  else
    fail Q34_MARKER_ROWS "rows=$marker_rows expected=$marker_expected"
  fi

  local hid htext got_text marker_text_ok=1 marker_ids=''
  while IFS=$'\t' read -r hid htext; do
    [[ -z "$hid" || "$hid" == \#* ]] && continue
    marker_ids="$marker_ids $hid"
    got_text="$(awk -F '\t' -v k="$hid" '$1 == k { print $2 }' "$q34markers")"
    [[ "$got_text" == "$htext" ]] || marker_text_ok=0
  done < "$markers_file"
  if [[ "$marker_text_ok" == 1 ]]; then pass Q34_MARKER_TEXT 'ids=2 text=pinned-qa34-fixture'; else
    fail Q34_MARKER_TEXT 'marker-text-mismatch'
  fi

  local mapkey gotref wantref
  for mapkey in DBASE GBASE C2 C3 C5 C8 QA33; do
    case "$mapkey" in
      DBASE|GBASE) mapto=BASE ;;
      C2)  mapto=P203_C2 ;;
      C3)  mapto=P206_C3 ;;
      C5)  mapto=P210_C5 ;;
      C8)  mapto=P213_C8 ;;
      QA33) mapto=QA33 ;;
    esac
    gotref="$(awk -F '\t' -v k="$mapkey" '$1 == k { print $2 }' "$q34refs")"
    wantref="$(ref_sha "$mapto")"
    if [[ -n "$gotref" && "$gotref" == "$wantref" ]]; then
      pass "Q34_REF_$mapkey" "sha=${gotref:0:8}"
    else
      fail "Q34_REF_$mapkey" "sha=${gotref:-none} pinned=${wantref:0:8}"
    fi
  done

  local q33_n
  q33_n="$(grep -o -F -- 'MERGE-REVIEW-NEEDED' "$q33marker" 2>/dev/null | wc -l | tr -d '[:space:]')"
  if [[ "$q33_n" == 1 ]]; then pass Q33_MARKER_PRESENT 'count=1'; else
    fail Q33_MARKER_PRESENT "count=$q33_n"
  fi

  # 6. Fixed row-count expectations (dedicated, after semantic checks).
  local want_rows
  want_rows="$(expect refs.rows)"
  if [[ "$(rows "$refs_file")" == "$want_rows" ]]; then pass EXPECT_REFS_ROWS "rows=$want_rows"; else
    fail EXPECT_REFS_ROWS "expected=$want_rows"
  fi
  want_rows="$(expect fixes.rows)"
  if [[ "$(rows "$fixes_file")" == "$want_rows" ]]; then pass EXPECT_FIXES_ROWS "rows=$want_rows"; else
    fail EXPECT_FIXES_ROWS "expected=$want_rows"
  fi
  want_rows="$(expect markers.rows)"
  if [[ "$(rows "$markers_file")" == "$want_rows" ]]; then pass EXPECT_MARKERS_ROWS "rows=$want_rows"; else
    fail EXPECT_MARKERS_ROWS "expected=$want_rows"
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src/main core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'; else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi
  if LC_ALL=C grep -E -q -i \
      '(https?://|ftp://|ssh://|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
      "$report" "$expect_file" "$refs_file" "$fixes_file" "$markers_file"; then
    fail P_OUTPUT_PRIVACY 'sensitive-pattern-in-report-or-fixtures'
  else
    pass P_OUTPUT_PRIVACY 'report-and-fixtures-clean'
  fi

  # 7. Emit exactly two bounded synthetic hazard markers only when every check
  #    above passed and both QA-34 marker rows are in force. Never a green summary.
  local hazard_count=0 dvr_hits=0 map_hits=0 dvr_bytes=0 map_bytes=0
  local limit maxbytes id2 text2 line b
  limit="$(expect hazard.markers.expected)"
  maxbytes="$(expect marker.max.bytes)"
  if (( fails == 0 )); then
    while IFS=$'\t' read -r id2 text2; do
      [[ -z "$id2" || "$id2" == \#* ]] && continue
      line="$(printf 'HAZARD\t%s\thazard=%s\tbase=%s\tqa34=%s\n' \
        "$text2" "$id2" "${base:0:8}" "${q34sha:0:8}")"
      printf '%s\n' "$line" >> "$report"
      b="$(printf '%s' "$line" | wc -c | tr -d '[:space:]')"
      case "$id2" in
        DVR03_REL01_MERGE_DEPENDENCY) dvr_hits=$((dvr_hits + 1)); dvr_bytes="$b" ;;
        MAP04_MAP05_SHARED_STORE) map_hits=$((map_hits + 1)); map_bytes="$b" ;;
      esac
      hazard_count=$((hazard_count + 1))
    done < "$markers_file"
  fi

  if [[ "$dvr_hits" == 1 && "$dvr_bytes" -le "$maxbytes" ]]; then
    pass HAZARD_BOUNDED_DVR "markers=$dvr_hits bytes=$dvr_bytes"
  else
    fail HAZARD_BOUNDED_DVR "markers=$dvr_hits bytes=$dvr_bytes"
  fi
  if [[ "$map_hits" == 1 && "$hazard_count" == "$limit" && "$map_bytes" -le "$maxbytes" ]]; then
    pass HAZARD_BOUNDED_MAP "markers=$map_hits total=$hazard_count bytes=$map_bytes"
  else
    fail HAZARD_BOUNDED_MAP "markers=$map_hits total=$hazard_count bytes=$map_bytes"
  fi

  local total verdict rc obs=''
  total="$(expect expected.checks)"
  cat "$report"
  for key2 in P203_C2 P206_C3 P210_C5 P213_C8 P214_C7 P218_C11 P220_C10 P221_QA34; do
    sha="$(ref_sha "$key2")"
    obs="$obs ${key2}=${sha:0:8}"
  done
  printf 'OBSERVED%s\n' "$obs"
  if (( fails == 0 && checks == total && hazard_count == limit && dvr_hits == 1 && map_hits == 1 )); then
    verdict=MERGE-REVIEW-NEEDED; rc=3
  else
    verdict=FAIL; rc=1
  fi
  printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected=%d\thazards=%d\tbase=%s\tqa34=%s\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" "$hazard_count" "${base:0:8}" "${q34sha:0:8}" \
    > "$dir/summary.txt"
  printf '%s\n' "$(cat "$dir/summary.txt")"
  return "$rc"
}

verify() {
  local py="$script_dir/mutate.py" d="$work/mutation" rc=0
  mkdir -p "$d"
  python3 "$py" --repo "$repo" --fixtures "$fixtures" --dir "$d" prepare || {
    printf 'MUTATION\tFAIL\tprepare\n'; return 1; }
  local target=FIX_P220_C10 overlay_ref=P220_C10
  local path='app/src/test/java/com/devtunasoft/tuneros/fav06/SeriesRuleReplaceTest.kt'
  local expect_id=FIX_BLOB_FIX_P220_C10
  python3 "$py" --repo "$repo" --fixtures "$fixtures" --dir "$d" mutate --target "$target" || {
    printf 'MUTATION\tFAIL\tmutate\n'; return 1; }

  printf '%s\n' "--- RED ($target: $expect_id) ---"
  QA40_OVERLAY_DIR="$d/overlay" QA40_OVERLAY_REF="$overlay_ref" QA40_OVERLAY_PATH="$path" gate "red" > "$d/red.log"
  local red_rc=$?
  local red_first red_marker red_hits
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$d/red.log" 2>/dev/null | tail -1 || true)"
  red_marker="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$d/red.log")"
  red_hits="$(awk -F '\t' -v id="$expect_id" '$1 == "CHECK" && $2 == "FAIL" && $3 == id { n++ } END { print n + 0 }' "$d/red.log")"

  local restore_log="$d/restore.log" identical
  python3 "$py" --repo "$repo" --fixtures "$fixtures" --dir "$d" restore > "$restore_log" || {
    printf 'MUTATION\tFAIL\trestore\n'; return 1; }
  identical="$(awk -F '\t' '$1 == "RESTORE" && $3 == "BYTE_IDENTICAL" { n++ } END { print n + 0 }' "$restore_log")"

  printf '%s\n' '--- RESTORED (overlay re-read from pinned objects) ---'
  QA40_OVERLAY_DIR="$d/overlay" QA40_OVERLAY_REF="$overlay_ref" QA40_OVERLAY_PATH="$path" gate "restored" > "$d/restored.log"
  local restored_rc=$?
  local restored_hits restored_verdict
  restored_hits="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$d/restored.log")"
  restored_verdict="$(grep -o 'verdict=[A-Z-]*' "$d/restored.log" 2>/dev/null | tail -1 || true)"

  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green > "$d/green.log"
  local green_rc=$?
  local green_hits green_verdict green_checks green_pass green_fail
  green_hits="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$d/green.log")"
  green_verdict="$(grep -o 'verdict=[A-Z-]*' "$d/green.log" 2>/dev/null | tail -1 || true)"
  green_checks="$(grep -o 'checks=[0-9]*' "$d/green.log" | tail -1 | cut -d= -f2)"
  green_pass="$(grep -o 'pass=[0-9]*' "$d/green.log" | tail -1 | cut -d= -f2)"
  green_fail="$(grep -o 'fail=[0-9]*' "$d/green.log" | tail -1 | cut -d= -f2)"

  printf 'MUTATION\t%s\tRED_RC\t%d\tRED_FIRST\t%s\tRED_HITS\t%d\tRED_MARKERS\t%d\tRESTORED_RC\t%d\tRESTORED_MARKERS\t%d\tRESTORED_VERDICT\t%s\tIDENTICAL\t%d\n' \
    "$target" "$red_rc" "${red_first:-first=NONE}" "$red_hits" "$red_marker" "$restored_rc" \
    "$restored_hits" "${restored_verdict:-verdict=NONE}" "$identical"
  printf 'MUTATION\tGREEN_RC\t%d\tMARKERS\t%d\tCHECKS\t%s\tPASS\t%s\tFAIL\t%s\tVERDICT\t%s\n' \
    "$green_rc" "$green_hits" "$green_checks" "$green_pass" "$green_fail" "${green_verdict:-verdict=NONE}"

  if (( red_rc != 1 || red_hits != 1 || red_marker != 0 || restored_rc != 3 || restored_hits != 2 || identical != 11 )); then
    printf 'MUTATION\tFAIL\tsensitivity-not-proven\n'; rc=1
  fi
  if [[ "$red_first" != "first=$expect_id" || "$restored_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ]]; then
    printf 'MUTATION\tFAIL\twrong-code-or-verdict\n'; rc=1
  fi
  if (( green_rc != 3 || green_hits != 2 || green_fail != 0 )) || \
     [[ "$green_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ]]; then
    printf 'MUTATION\tFAIL\tgreen-not-dual-marker\n'; rc=1
  fi
  if (( rc == 0 )); then
    printf 'MUTATION\tPASS\tone-scratch-mutation-detected-and-restored-byte-identical\n'
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only) gate green ;;
  verify) verify ;;
  *) printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2 ;;
esac
