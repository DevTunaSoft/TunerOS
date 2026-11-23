#!/usr/bin/env bash
# QA-34: bounded host-only gate for BOTH recorded merge hazards (DVR and MAP).
# Reads pinned git objects only. No emulator, network, product edit, PR or merge.
# Exit codes: 0 PASS, 1 FAIL, 3 MERGE-REVIEW-NEEDED (all checks pass, both hazards present).
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa34"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
markers_file="$fixtures/markers.tsv"
sha_file="$fixtures/sha256.txt"

manifest='app/src/main/AndroidManifest.xml'
scheduler='app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt'
receiver='app/src/main/java/com/devtunasoft/tuneros/RecordingRearmReceiver.kt'
store='app/src/main/java/com/devtunasoft/tuneros/GuideMappingStore.kt'
main_kt='app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt'
qa31_refs='qa/fixtures/qa31/refs.tsv'
qa33_refs='qa/fixtures/qa33/refs.tsv'
qa33_marker='qa/fixtures/qa33/marker.txt'

max_bytes=65536
max_lines=2000
main_max_bytes=262144
main_max_lines=4096
extra_checks=16

for required in "$refs_file" "$expect_file" "$markers_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

native_tmp="$(cygpath -m "${TMPDIR:-/tmp}" 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}")"
work="$native_tmp/qa34-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

scope_path() {
  case "$1" in
    manifest) printf '%s' "$manifest" ;;
    scheduler) printf '%s' "$scheduler" ;;
    receiver) printf '%s' "$receiver" ;;
    store) printf '%s' "$store" ;;
    main) printf '%s' "$main_kt" ;;
    qa31_refs) printf '%s' "$qa31_refs" ;;
    qa33_refs) printf '%s' "$qa33_refs" ;;
    qa33_marker) printf '%s' "$qa33_marker" ;;
    *) return 2 ;;
  esac
}

count_fixed() {
  LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'
}

declare -A QA34_SRC_CACHE=()
qa34_cache_dir=''

source_text() {
  # Explicit (ref, path) overlay scope: a declared but missing substitution fails closed.
  # Cache is per gate invocation, so a mutated overlay cannot leak between runs.
  local ref="$1" path="$2" out="$3" sha key bytes lines cap_bytes cap_lines cache_key
  sha="$(ref_sha "$ref")" || return 2
  key="${ref}_$(basename "$path")"
  cap_bytes="$max_bytes"
  cap_lines="$max_lines"
  if [[ "$key" == C5_MainActivity.kt || "$key" == C8_MainActivity.kt ]]; then
    cap_bytes="$main_max_bytes"
    cap_lines="$main_max_lines"
  fi
  cache_key="$ref|$path"
  if [[ -n "${QA34_SRC_CACHE[$cache_key]:-}" && -f "${QA34_SRC_CACHE[$cache_key]}" ]]; then
    cp "${QA34_SRC_CACHE[$cache_key]}" "$out" 2>/dev/null || return 2
  elif [[ -n "${QA34_OVERLAY_DIR:-}" &&
          "$ref" == "${QA34_OVERLAY_REF:-}" &&
          "$path" == "${QA34_OVERLAY_PATH:-}" ]]; then
    [[ -f "$QA34_OVERLAY_DIR/$key" ]] || return 2
    cp "$QA34_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
    mkdir -p "$qa34_cache_dir"
    cp "$out" "$qa34_cache_dir/$key" 2>/dev/null || return 2
    QA34_SRC_CACHE["$cache_key"]="$qa34_cache_dir/$key"
  else
    git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
    mkdir -p "$qa34_cache_dir"
    cp "$out" "$qa34_cache_dir/$key" 2>/dev/null || return 2
    QA34_SRC_CACHE["$cache_key"]="$qa34_cache_dir/$key"
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
  qa34_cache_dir="$dir/cache"
  QA34_SRC_CACHE=()
  mkdir -p "$qa34_cache_dir"
  local report="$dir/report.tsv"
  : > "$report"
  local checks=0 passes=0 fails=0 first=NONE
  local refs_ok=1 dbase c2 c3 gbase c5 c8 qa31 qa33
  local x31_c2_n=-1 x31_c3_n=-1 x33_c5_n=-1 x33_c8_n=-1 x33_marker_n=-1
  local dbase_guards_n=0 c3_guards_n=0 c3_dispatch_n=-1 c2_receiver_n=-1
  local c5_retain_n=-1 c8_seams_n=-1 c8_fail_closed_n=-1
  local dvr_base_ok=0 map_base_ok=0 d_c2_paths_ok=0 d_c3_paths_ok=0 d_guard_order_ok=0
  local c5_touches=0 c8_touches=0 hazard_dvr=0 hazard_map=0

  pass() {
    checks=$((checks + 1)); passes=$((passes + 1))
    printf 'CHECK\tPASS\t%s\t%s\n' "$1" "$2" >> "$report"
  }
  fail() {
    checks=$((checks + 1)); fails=$((fails + 1))
    if [[ "$first" == NONE ]]; then first="$1"; fi
    printf 'CHECK\tFAIL\t%s\t%s\n' "$1" "$2" >> "$report"
  }

  # Pinned fixture bytes: a tampered fixture cannot redefine the gate.
  local want_exp want_refs want_markers got_exp got_refs got_markers
  want_exp="$(awk '$2 == "expectations.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  want_refs="$(awk '$2 == "refs.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  want_markers="$(awk '$2 == "markers.tsv" { sub(/^\*/, "", $2); print $1 }' "$sha_file")"
  got_exp="$(sha256sum "$expect_file" | awk '{ print $1 }')"
  got_refs="$(sha256sum "$refs_file" | awk '{ print $1 }')"
  got_markers="$(sha256sum "$markers_file" | awk '{ print $1 }')"
  if [[ -n "$want_exp" && -n "$want_refs" && -n "$want_markers" &&
        "$want_exp" == "$got_exp" && "$want_refs" == "$got_refs" &&
        "$want_markers" == "$got_markers" ]]; then
    pass FIXTURE_SHA256 'digests=3'
  else
    fail FIXTURE_SHA256 'fixture-bytes-changed'
  fi

  dbase="$(ref_sha DBASE)" || { fail REFS_RESOLVE 'DBASE-missing'; refs_ok=0; }
  c2="$(ref_sha C2)" || { fail REFS_RESOLVE 'C2-missing'; refs_ok=0; }
  c3="$(ref_sha C3)" || { fail REFS_RESOLVE 'C3-missing'; refs_ok=0; }
  gbase="$(ref_sha GBASE)" || { fail REFS_RESOLVE 'GBASE-missing'; refs_ok=0; }
  c5="$(ref_sha C5)" || { fail REFS_RESOLVE 'C5-missing'; refs_ok=0; }
  c8="$(ref_sha C8)" || { fail REFS_RESOLVE 'C8-missing'; refs_ok=0; }
  qa31="$(ref_sha QA31)" || { fail REFS_RESOLVE 'QA31-missing'; refs_ok=0; }
  qa33="$(ref_sha QA33)" || { fail REFS_RESOLVE 'QA33-missing'; refs_ok=0; }
  if [[ "$dbase" != "$c2" && "$dbase" != "$c3" && "$c2" != "$c3" &&
        "$c5" != "$c8" && "$c5" != "$c2" && "$c8" != "$c3" &&
        "$qa31" != "$qa33" && "$qa31" != "$qa33" && "$dbase" == "$gbase" ]]; then
    for sha in "$dbase" "$c2" "$c3" "$c5" "$c8" "$qa31" "$qa33"; do
      git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null || refs_ok=0
    done
  else
    refs_ok=0
  fi
  if [[ "$refs_ok" == 1 ]]; then
    pass REFS_RESOLVE 'refs=8 dbase=base distinct-fixes'
  else
    fail REFS_RESOLVE 'refs-invalid'
  fi

  local id ref path kind needle expected n src scope
  local -a known=(DBASE C2 C3 GBASE C5 C8 QA31 QA33)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    scope="$(scope_path "$path")" || { fail "$id" 'unknown-path'; continue; }
    src="$dir/src.$id"
    if ! source_text "$ref" "$scope" "$src"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    n="$(count_fixed "$src" "$needle")"
    case "$id" in
      X_QA31_PINS_C2) x31_c2_n="$n" ;;
      X_QA31_PINS_C3) x31_c3_n="$n" ;;
      X_QA33_PINS_C5) x33_c5_n="$n" ;;
      X_QA33_PINS_C8) x33_c8_n="$n" ;;
      X_QA33_MARKER) x33_marker_n="$n" ;;
      D_DBASE_NO_INPROGRESS|D_DBASE_NO_EXACTDONE) dbase_guards_n=$((dbase_guards_n + n)) ;;
      D_C3_REC_DISPATCH) c3_dispatch_n="$n" ;;
      D_C3_SCHED_NO_INPROGRESS|D_C3_SCHED_NO_EXACTDONE) c3_guards_n=$((c3_guards_n + n)) ;;
      D_C2_MAN_NO_REPLACED) c2_receiver_n="$n" ;;
      M_C5_NO_RETAIN) c5_retain_n="$n" ;;
      M_C8_NO_SEAMS) c8_seams_n="$n" ;;
      M_C8_FAIL_CLOSED) c8_fail_closed_n="$n" ;;
    esac
    case "$kind" in
      count)
        if [[ "$n" == "$expected" ]]; then pass "$id" "count=$n expected=$expected"
        else fail "$id" "count=$n expected=$expected"; fi
        ;;
      absent)
        if [[ "$n" == 0 && "$expected" == 0 ]]; then pass "$id" 'count=0 expected=0'
        else fail "$id" "count=$n expected=0"; fi
        ;;
      *) fail "$id" 'unknown-kind' ;;
    esac
  done < "$expect_file"

  # DVR chain: common base, separate production deltas, guard-before-dispatch.
  local mb_dvr
  mb_dvr="$(git -C "$repo" merge-base "$c2" "$c3" 2>/dev/null)"
  if [[ "$mb_dvr" == "$dbase" && "$refs_ok" == 1 ]]; then
    pass D_MERGE_BASE "merge-base=${dbase:0:8} commits=2"
    dvr_base_ok=1
  else
    fail D_MERGE_BASE 'merge-base-or-commits-invalid'
  fi

  local actual_c2 actual_c3 expect_c2 expect_c3
  actual_c2="$(git -C "$repo" diff --name-only "$dbase" "$c2" -- app/src/main/ | LC_ALL=C sort | paste -sd ';' -)"
  actual_c3="$(git -C "$repo" diff --name-only "$dbase" "$c3" -- app/src/main/ | LC_ALL=C sort | paste -sd ';' -)"
  expect_c2="$scheduler"
  expect_c3="$(printf '%s\n%s\n' "$manifest" "$receiver" | LC_ALL=C sort | paste -sd ';' -)"
  if [[ "$actual_c2" == "$expect_c2" ]]; then
    pass D_C2_ONLY_SCHEDULER 'changed-paths=1'
    d_c2_paths_ok=1
  else
    fail D_C2_ONLY_SCHEDULER 'changed-path-set-mismatch'
  fi
  if [[ "$actual_c3" == "$expect_c3" ]]; then
    pass D_C3_MANIFEST_RECEIVER 'changed-paths=2'
    d_c3_paths_ok=1
  else
    fail D_C3_MANIFEST_RECEIVER 'changed-path-set-mismatch'
  fi

  local guard_line dispatch_line
  guard_line="$(LC_ALL=C grep -n -F -m1 'Intent.ACTION_MY_PACKAGE_REPLACED' "$dir/src.D_C3_REC_DISPATCH" 2>/dev/null | cut -d: -f1)"
  dispatch_line="$(LC_ALL=C grep -n -F -m1 'RecordingScheduler.rearmPending(' "$dir/src.D_C3_REC_DISPATCH" 2>/dev/null | cut -d: -f1)"
  if [[ -n "$guard_line" && -n "$dispatch_line" ]] && (( guard_line < dispatch_line )); then
    pass D_C3_GUARD_BEFORE_DISPATCH "guard-line=$guard_line dispatch-line=$dispatch_line"
    d_guard_order_ok=1
  else
    fail D_C3_GUARD_BEFORE_DISPATCH "guard-line=${guard_line:-none} dispatch-line=${dispatch_line:-none}"
  fi

  if [[ "$dvr_base_ok" == 1 && "$d_c2_paths_ok" == 1 && "$d_c3_paths_ok" == 1 &&
        "$d_guard_order_ok" == 1 && "$x31_c2_n" == 1 && "$x31_c3_n" == 1 &&
        "$c3_dispatch_n" == 1 && "$c3_guards_n" == 0 &&
        "$c2_receiver_n" == 0 && "$dbase_guards_n" == 0 ]]; then
    pass H_DVR03_REL01 'upgrade-calls-unguarded-scheduler-rearm-in-other-branch'
    hazard_dvr=1
  else
    fail H_DVR03_REL01 'hazard-conditions-unmet'
  fi

  # MAP chain: common base and both branches rewriting the same store file.
  local mb_map
  mb_map="$(git -C "$repo" merge-base "$c5" "$c8" 2>/dev/null)"
  if [[ "$mb_map" == "$gbase" && "$refs_ok" == 1 ]]; then
    pass M_MERGE_BASE "merge-base=${gbase:0:8} commits=2"
    map_base_ok=1
  else
    fail M_MERGE_BASE 'merge-base-or-commits-invalid'
  fi
  local d5 d8
  d5="$(git -C "$repo" diff --name-only "$gbase" "$c5" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  d8="$(git -C "$repo" diff --name-only "$gbase" "$c8" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  if [[ ";$d5;" == *";$store;"* ]]; then
    pass M_C5_TOUCHES_STORE 'shared-file=1'
    c5_touches=1
  else
    fail M_C5_TOUCHES_STORE 'shared-file-missing'
  fi
  if [[ ";$d8;" == *";$store;"* ]]; then
    pass M_C8_TOUCHES_STORE 'shared-file=1'
    c8_touches=1
  else
    fail M_C8_TOUCHES_STORE 'shared-file-missing'
  fi
  if [[ "$map_base_ok" == 1 && "$c5_touches" == 1 && "$c8_touches" == 1 &&
        "$x33_c5_n" == 1 && "$x33_c8_n" == 1 && "$x33_marker_n" == 1 &&
        "$c5_retain_n" == 0 && "$c8_seams_n" == 0 && "$c8_fail_closed_n" == 1 ]]; then
    pass H_MAP04_MAP05 'two-branch-shared-store-rewrite'
    hazard_map=1
  else
    fail H_MAP04_MAP05 'hazard-conditions-unmet'
  fi

  # Exactly two bounded synthetic markers, text taken from the pinned QA-33 marker.
  source_text QA33 "$qa33_marker" "$dir/qa33.marker" || fail X_MARKER_SOURCE 'pinned-marker-unavailable'
  local qa33_text marker_dvr marker_map marker_rows
  qa33_text="$(tr -d '\r\n' < "$dir/qa33.marker" 2>/dev/null)"
  marker_dvr="$(awk -F '\t' '$1 == "DVR03_REL01_MERGE_DEPENDENCY" { print $2 }' "$markers_file")"
  marker_map="$(awk -F '\t' '$1 == "MAP04_MAP05_SHARED_STORE" { print $2 }' "$markers_file")"
  marker_rows="$(awk -F '\t' 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$markers_file")"
  if [[ "$hazard_dvr" == 1 ]]; then
    printf 'HAZARD\t%s\thazard=DVR03_REL01_MERGE_DEPENDENCY\tbase=%s\tc2=%s\tc3=%s\n' \
      "$marker_dvr" "${dbase:0:8}" "${c2:0:8}" "${c3:0:8}" >> "$report"
  fi
  if [[ "$hazard_map" == 1 ]]; then
    printf 'HAZARD\t%s\thazard=MAP04_MAP05_SHARED_STORE\tbase=%s\tc5=%s\tc8=%s\n' \
      "$marker_map" "${gbase:0:8}" "${c5:0:8}" "${c8:0:8}" >> "$report"
  fi
  if [[ "$marker_rows" == 2 && -n "$qa33_text" && "$marker_dvr" == "$qa33_text" &&
        "$marker_map" == "$qa33_text" ]]; then
    pass X_MARKER_ROWS 'rows=2 text=pinned-qa33-marker'
  else
    fail X_MARKER_ROWS 'marker-fixture-mismatch'
  fi

  local dvr_hits map_hits all_hits dvr_bytes map_bytes dvr_line map_line
  dvr_hits="$(awk -F '\t' '$1 == "HAZARD" && $3 == "hazard=DVR03_REL01_MERGE_DEPENDENCY" { n++ } END { print n + 0 }' "$report")"
  map_hits="$(awk -F '\t' '$1 == "HAZARD" && $3 == "hazard=MAP04_MAP05_SHARED_STORE" { n++ } END { print n + 0 }' "$report")"
  all_hits="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$report")"
  dvr_line="$(awk -F '\t' '$1 == "HAZARD" && $3 == "hazard=DVR03_REL01_MERGE_DEPENDENCY" { print; exit }' "$report")"
  map_line="$(awk -F '\t' '$1 == "HAZARD" && $3 == "hazard=MAP04_MAP05_SHARED_STORE" { print; exit }' "$report")"
  dvr_bytes="$(printf '%s' "$dvr_line" | wc -c | tr -d '[:space:]')"
  map_bytes="$(printf '%s' "$map_line" | wc -c | tr -d '[:space:]')"
  if [[ "$dvr_hits" == 1 && "$dvr_bytes" -le 200 &&
        "$dvr_line" == "HAZARD	$qa33_text	hazard=DVR03_REL01_MERGE_DEPENDENCY"* ]]; then
    pass H_DVR_MARKER_BOUNDED "markers=$dvr_hits bytes=$dvr_bytes"
  else
    fail H_DVR_MARKER_BOUNDED "markers=$dvr_hits bytes=$dvr_bytes"
  fi
  if [[ "$map_hits" == 1 && "$map_bytes" -le 200 && "$all_hits" == 2 &&
        "$map_line" == "HAZARD	$qa33_text	hazard=MAP04_MAP05_SHARED_STORE"* ]]; then
    pass H_MAP_MARKER_BOUNDED "markers=$map_hits total=$all_hits bytes=$map_bytes"
  else
    fail H_MAP_MARKER_BOUNDED "markers=$map_hits total=$all_hits bytes=$map_bytes"
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src/main core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else fail P_NO_PRODUCTION_DRIFT 'production-path-modified'; fi

  if LC_ALL=C grep -E -q -i \
      '(https?://|ftp://|ssh://|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
      "$report" "$expect_file" "$refs_file" "$markers_file"; then
    fail P_OUTPUT_PRIVACY 'sensitive-pattern-in-report-or-fixtures'
  else
    pass P_OUTPUT_PRIVACY 'report-and-fixtures-clean'
  fi

  local rows total verdict rc summary
  rows="$(awk 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$expect_file")"
  total=$((rows + extra_checks))
  cat "$report"
  if (( fails == 0 && checks == total && hazard_dvr == 1 && hazard_map == 1 && dvr_hits == 1 && map_hits == 1 )); then
    verdict=MERGE-REVIEW-NEEDED; rc=3
  else
    verdict=FAIL; rc=1
  fi
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tdbase=%s\tc2=%s\tc3=%s\tgbase=%s\tc5=%s\tc8=%s\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" \
    "${dbase:0:8}" "${c2:0:8}" "${c3:0:8}" "${gbase:0:8}" "${c5:0:8}" "${c8:0:8}")"
  printf '%s\n' "$summary"
  printf '%s\n' "$summary" > "$dir/summary.txt"
  return "$rc"
}

mutation_probe() {
  local dbase c2 c3 gbase c5 c8 qa31 qa33 py="$script_dir/mutate.py" d="$work/mutation"
  dbase="$(ref_sha DBASE)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c2="$(ref_sha C2)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c3="$(ref_sha C3)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  gbase="$(ref_sha GBASE)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c5="$(ref_sha C5)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c8="$(ref_sha C8)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  qa31="$(ref_sha QA31)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  qa33="$(ref_sha QA33)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  mkdir -p "$d"
  local common=(--repo "$repo" --dbase "$dbase" --c2 "$c2" --c3 "$c3" \
    --gbase "$gbase" --c5 "$c5" --c8 "$c8" --qa31 "$qa31" --qa33 "$qa33" --dir "$d")
  python3 "$py" "${common[@]}" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }

  local rc=0 target overlay_ref path expect_id hazard_id red_rc red_hits red_first red_marker
  local restored_rc restored_hits restored_verdict restored_log identical_n
  for target in dvr map; do
    if [[ "$target" == dvr ]]; then
      overlay_ref='C3'; path="$receiver"; expect_id='D_C3_REC_DISPATCH'
      hazard_id='hazard=DVR03_REL01_MERGE_DEPENDENCY'
    else
      overlay_ref='C8'; path="$store"; expect_id='M_C8_FAIL_CLOSED'
      hazard_id='hazard=MAP04_MAP05_SHARED_STORE'
    fi
    python3 "$py" "${common[@]}" mutate --target "$target" || { printf 'MUTATION\tFAIL\tmutate-%s\n' "$target"; return 1; }
    printf '%s\n' "--- RED ($target: $expect_id) ---"
    QA34_OVERLAY_DIR="$d/overlay" QA34_OVERLAY_REF="$overlay_ref" QA34_OVERLAY_PATH="$path" gate "red.$target"
    red_rc=$?
    red_hits="$(awk -F '\t' -v id="$expect_id" '$1 == "CHECK" && $2 == "FAIL" && $3 == id { n++ } END { print n + 0 }' "$work/gate.red.$target/report.tsv")"
    red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red.$target/summary.txt" 2>/dev/null || true)"
    red_marker="$(awk -F '\t' -v h="$hazard_id" '$1 == "HAZARD" && $3 == h { n++ } END { print n + 0 }' "$work/gate.red.$target/report.tsv")"
    restored_log="$d/restore.$target.log"
    python3 "$py" "${common[@]}" restore > "$restored_log" || { printf 'MUTATION\tFAIL\trestore-%s\n' "$target"; return 1; }
    identical_n="$(awk -F '\t' '$1 == "RESTORE" && $2 != "ALL" && $3 == "BYTE_IDENTICAL" { n++ } END { print n + 0 }' "$restored_log")"
    printf '%s\n' "--- RESTORED ($target) ---"
    QA34_OVERLAY_DIR="$d/overlay" QA34_OVERLAY_REF="$overlay_ref" QA34_OVERLAY_PATH="$path" gate "restored.$target"
    restored_rc=$?
    restored_hits="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$work/gate.restored.$target/report.tsv")"
    restored_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.restored.$target/summary.txt" 2>/dev/null || true)"
    printf 'MUTATION\t%s\tRED_RC\t%d\tRED_HITS\t%d\tRED_FIRST\t%s\tRED_MARKER\t%d\tRESTORED_RC\t%d\tRESTORED_MARKERS\t%d\tRESTORE_IDENTICAL\t%d\n' \
      "$target" "$red_rc" "$red_hits" "${red_first:-none}" "$red_marker" "$restored_rc" "$restored_hits" "$identical_n"
    if (( red_rc != 1 || red_hits != 1 || red_marker != 0 || restored_rc != 3 || restored_hits != 2 || identical_n != 13 )) || \
       [[ "$red_first" != "first=$expect_id" || "$restored_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ]]; then
      printf 'MUTATION\tFAIL\tsensitivity-not-proven\t%s\tred-first=%s\n' "$target" "${red_first:-none}"
      rc=1
    fi
  done

  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?
  local green_hits green_verdict green_checks green_pass green_fail
  green_hits="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$work/gate.green/report.tsv")"
  green_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.green/summary.txt" 2>/dev/null || true)"
  green_checks="$(grep -o 'checks=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"
  green_pass="$(grep -o 'pass=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"
  green_fail="$(grep -o 'fail=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"
  printf 'MUTATION\tGREEN_RC\t%d\tMARKERS\t%d\tCHECKS\t%s\tPASS\t%s\tFAIL\t%s\tVERDICT\t%s\n' \
    "$green_rc" "$green_hits" "$green_checks" "$green_pass" "$green_fail" "$green_verdict"
  if (( green_rc != 3 || green_hits != 2 || green_fail != 0 )) || \
     [[ "$green_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ]]; then
    printf 'MUTATION\tFAIL\tgreen-not-dual-marker\n'
    rc=1
  fi
  if (( rc == 0 )); then
    printf 'MUTATION\tPASS\ttwo-single-scratch-mutations-detected-and-restored\tverdict=MERGE-REVIEW-NEEDED\n'
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only) gate green ;;
  verify) mutation_probe ;;
  *) printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2 ;;
esac
