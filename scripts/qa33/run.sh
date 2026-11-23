#!/usr/bin/env bash
# QA-33: bounded host-only static gate for the MAP-05 stale-mapping pruning fix.
# Reads pinned git objects only. No emulator, no network, no product edit.
# Exit codes: 0 PASS, 1 FAIL, 3 MERGE-REVIEW-NEEDED (all checks pass, shared-file hazard present).
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa33"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
marker_file="$fixtures/marker.txt"
sha_file="$fixtures/sha256.txt"
store_rel='app/src/main/java/com/devtunasoft/tuneros/GuideMappingStore.kt'
main_rel='app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt'
test_rel='app/src/test/java/com/devtunasoft/tuneros/map05/GuideMappingRetainTest.kt'
max_bytes=65536
max_lines=2000
main_max_bytes=262144
main_max_lines=4096
window_max_lines=40
extra_checks=11

for required in "$refs_file" "$expect_file" "$marker_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa33-work-$$"
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
  local ref="$1" path="$2" out="$3" sha key bytes lines cap_bytes cap_lines
  sha="$(ref_sha "$ref")" || return 2
  key="$(basename "$path")"
  cap_bytes="$max_bytes"
  cap_lines="$max_lines"
  if [[ "$key" == 'MainActivity.kt' ]]; then
    cap_bytes="$main_max_bytes"
    cap_lines="$main_max_lines"
  fi
  if [[ -n "${QA33_OVERLAY_DIR:-}" &&
        "$ref" == "${QA33_OVERLAY_REF:-}" &&
        "$key" == "${QA33_OVERLAY_FILE:-}" ]]; then
    [[ -f "$QA33_OVERLAY_DIR/$key" ]] || return 2
    cp "$QA33_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
  else
    git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
  fi
  bytes="$(wc -c < "$out" | tr -d '[:space:]')"
  lines="$(wc -l < "$out" | tr -d '[:space:]')"
  if (( bytes > cap_bytes || lines > cap_lines || bytes == 0 )); then return 3; fi
  return 0
}

extract_window() {
  # Bounded onSuccess..onFailure slice anchored on the unique channels assignment.
  awk -v anchor='channels = it.channels' -v open='result.onSuccess {' -v cl='}.onFailure {' '
    { ln[NR] = $0 }
    END {
      ch = 0
      for (i = 1; i <= NR; i++) if (index(ln[i], anchor)) { ch = i; break }
      if (!ch) exit 1
      s = 0
      for (i = ch; i >= 1; i--) if (index(ln[i], open)) { s = i; break }
      if (!s) exit 2
      e = 0
      for (i = ch; i <= NR; i++) if (index(ln[i], cl)) { e = i; break }
      if (!e || e <= s) exit 3
      for (i = s; i <= e; i++) print ln[i]
    }' "$1" > "$2"
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir"
  local report="$dir/report.tsv"
  : > "$report"
  local checks=0 passes=0 fails=0 first=NONE
  local base c5 c8 merge_ok=0 d5_has=0 d8_has=0
  local c5_retain_n=-1 c8_seams_n=-1
  local hazard=0

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

  local refs_ok=1
  base="$(ref_sha BASE)" || { fail REFS_RESOLVE 'BASE-missing'; refs_ok=0; }
  c5="$(ref_sha C5)" || { fail REFS_RESOLVE 'C5-missing'; refs_ok=0; }
  c8="$(ref_sha C8)" || { fail REFS_RESOLVE 'C8-missing'; refs_ok=0; }
  if [[ "$base" == "$c5" || "$base" == "$c8" || "$c5" == "$c8" ]]; then
    fail REFS_RESOLVE 'refs-not-distinct'
    refs_ok=0
  fi
  if [[ "$refs_ok" == 1 ]]; then
    pass REFS_RESOLVE 'refs=3 distinct'
  fi

  # Build the bounded onSuccess window before any window-scoped row is read.
  source_text C8 "$main_rel" "$dir/main.full"
  extract_window "$dir/main.full" "$dir/main.window"

  local id ref path kind needle expected n src scope
  local -a known=(BASE C5 C8)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    src="$dir/src.$id"
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    case "$path" in
      GuideMappingStore.kt) scope="$store_rel" ;;
      MainActivity.kt) scope="$main_rel" ;;
      GuideMappingRetainTest.kt) scope="$test_rel" ;;
      *) fail "$id" 'unknown-path'; continue ;;
    esac
    if ! source_text "$ref" "$scope" "$src"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    case "$kind" in
      count) n="$(count_fixed "$src" "$needle")" ;;
      absent) n="$(count_fixed "$src" "$needle")" ;;
      wincount|winabsent)
        if [[ ! -f "$dir/main.window" ]]; then
          fail "$id" 'window-unavailable'
          continue
        fi
        n="$(count_fixed "$dir/main.window" "$needle")"
        ;;
      *) fail "$id" 'unknown-kind'; continue ;;
    esac
    case "$id" in
      C5_RETAIN_ABSENT) c5_retain_n="$n" ;;
      G_C5_SEAMS_ABSENT) c8_seams_n="$n" ;;
    esac
    case "$kind" in
      count|wincount)
        if [[ "$n" == "$expected" ]]; then
          pass "$id" "count=$n expected=$expected"
        else
          fail "$id" "count=$n expected=$expected"
        fi
        ;;
      absent|winabsent)
        if [[ "$n" == 0 && "$expected" == 0 ]]; then
          pass "$id" 'count=0 expected=0'
        else
          fail "$id" "count=$n expected=0"
        fi
        ;;
    esac
  done < "$expect_file"

  # Pinned ancestry and the shared-file integration hazard.
  local mb
  mb="$(git -C "$repo" merge-base "$c5" "$c8" 2>/dev/null)"
  if [[ "$mb" == "$base" ]] &&
     git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c5^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c8^{commit}" 2>/dev/null; then
    pass D_COMMON_BASE "merge-base=${base:0:8} commits=3"
    merge_ok=1
  else
    fail D_COMMON_BASE 'merge-base-or-commits-invalid'
  fi

  local d5 d8
  d5="$(git -C "$repo" diff --name-only "$base" "$c5" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  d8="$(git -C "$repo" diff --name-only "$base" "$c8" 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  if [[ ";$d5;" == *";$store_rel;"* ]]; then
    pass D_C5_TOUCHES_STORE 'shared-file=1'
    d5_has=1
  else
    fail D_C5_TOUCHES_STORE 'shared-file-missing'
  fi
  if [[ ";$d8;" == *";$store_rel;"* ]]; then
    pass D_C8_TOUCHES_STORE 'shared-file=1'
    d8_has=1
  else
    fail D_C8_TOUCHES_STORE 'shared-file-missing'
  fi

  # Bounded onSuccess window; source came from the pinned C8 MainActivity object.
  if [[ -f "$dir/main.window" ]]; then
    local wl opens closes
    wl="$(wc -l < "$dir/main.window" | tr -d '[:space:]')"
    opens="$(count_fixed "$dir/main.window" 'result.onSuccess {')"
    closes="$(count_fixed "$dir/main.window" '}.onFailure {')"
    if (( wl >= 5 && wl <= window_max_lines && opens == 1 && closes == 1 )); then
      pass M_WINDOW_BOUNDED "lines=$wl open=1 close=1 cap=$window_max_lines"
    else
      fail M_WINDOW_BOUNDED "lines=$wl open=$opens close=$closes cap=$window_max_lines"
    fi
  else
    fail M_WINDOW_BOUNDED 'anchor-not-unique'
  fi

  # Re-evaluate window rows now that the window exists; rows that ran before it
  # were recorded as FAIL and still count, so a missing window is never a PASS.
  local order_ok=0
  if [[ -f "$dir/main.window" ]]; then
    local l_channels l_nonempty l_livekeys l_retain l_save l_assign
    l_channels="$(LC_ALL=C grep -n -F -m1 'channels = it.channels' "$dir/main.window" | cut -d: -f1)"
    l_nonempty="$(LC_ALL=C grep -n -F -m1 'if (guideMappings.isNotEmpty()) {' "$dir/main.window" | cut -d: -f1)"
    l_livekeys="$(LC_ALL=C grep -n -F -m1 'val liveKeys = channels.mapTo(HashSet(channels.size))' "$dir/main.window" | cut -d: -f1)"
    l_retain="$(LC_ALL=C grep -n -F -m1 'GuideMappingStore.retain(guideMappings, liveKeys)' "$dir/main.window" | cut -d: -f1)"
    l_save="$(LC_ALL=C grep -n -F -m1 'if (pruned !== guideMappings && guideMappingStore.save(pruned)) {' "$dir/main.window" | cut -d: -f1)"
    l_assign="$(LC_ALL=C grep -n -F -m1 'guideMappings = pruned' "$dir/main.window" | cut -d: -f1)"
    if [[ -n "$l_channels" && -n "$l_nonempty" && -n "$l_livekeys" &&
          -n "$l_retain" && -n "$l_save" && -n "$l_assign" ]] &&
       (( l_channels < l_nonempty && l_nonempty < l_livekeys &&
          l_livekeys < l_retain && l_retain < l_save && l_save < l_assign )); then
      pass O_ONSUCCESS_ORDER "lines=$l_channels,$l_nonempty,$l_livekeys,$l_retain,$l_save,$l_assign"
      order_ok=1
    else
      fail O_ONSUCCESS_ORDER 'marker-order-invalid'
    fi
  else
    fail O_ONSUCCESS_ORDER 'window-unavailable'
  fi

  if [[ "$merge_ok" == 1 && "$d5_has" == 1 && "$d8_has" == 1 &&
        "$c5_retain_n" == 0 && "$c8_seams_n" == 0 ]]; then
    pass H_MERGE_REVIEW 'shared-file-hazard-detected'
    hazard=1
  else
    fail H_MERGE_REVIEW 'hazard-conditions-unmet'
  fi

  # Exactly one bounded synthetic marker line, derived from the pinned marker fixture.
  local marker_text marker_line marker_count marker_bytes
  marker_text="$(tr -d '\r\n' < "$marker_file")"
  marker_line="$(printf 'HAZARD\t%s\tshared=%s\tbase=%s\tc5=%s\tc8=%s' \
    "$marker_text" "$store_rel" "${base:0:8}" "${c5:0:8}" "${c8:0:8}")"
  printf '%s\n' "$marker_line" >> "$report"
  marker_count="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$report")"
  marker_bytes="$(printf '%s' "$marker_line" | wc -c | tr -d '[:space:]')"
  if [[ "$marker_count" == 1 && "$marker_bytes" -le 200 && "$marker_text" == 'MERGE-REVIEW-NEEDED' ]]; then
    pass H_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  else
    fail H_MARKER_BOUNDED "markers=$marker_count bytes=$marker_bytes"
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src/main core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then
    pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi

  if LC_ALL=C grep -E -q -i \
      '(https?://|[[:alnum:].-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
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
    if [[ "$hazard" == 1 ]]; then
      verdict=MERGE-REVIEW-NEEDED; rc=3
    else
      verdict=PASS; rc=0
    fi
  else
    verdict=FAIL; rc=1
  fi
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tbase=%s\tc5=%s\tc8=%s\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" \
    "${base:0:8}" "${c5:0:8}" "${c8:0:8}")"
  printf '%s\n' "$summary"
  printf '%s\n' "$summary" > "$dir/summary.txt"
  return "$rc"
}

mutation_probe() {
  local c5 c8 py="$script_dir/mutate.py" rc=0
  c5="$(ref_sha C5)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c8="$(ref_sha C8)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  local d="$work/mutation"
  mkdir -p "$d"
  python3 "$py" --repo "$repo" --c5 "$c5" --c8 "$c8" --dir "$d" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }
  python3 "$py" --repo "$repo" --c5 "$c5" --c8 "$c8" --dir "$d" mutate || { printf 'MUTATION\tFAIL\tmutate\n'; return 1; }

  printf '%s\n' '--- RED (mutated scratch overlay) ---'
  QA33_OVERLAY_DIR="$d/overlay" QA33_OVERLAY_REF=C8 QA33_OVERLAY_FILE=GuideMappingStore.kt gate red
  local red_rc=$?
  local red="$work/gate.red/report.tsv"
  local red_hits red_first red_marker
  red_hits="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 == "G_FAIL_CLOSED_EMPTY" { n++ } END { print n + 0 }' "$red")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"
  red_marker="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$red")"

  python3 "$py" --repo "$repo" --c5 "$c5" --c8 "$c8" --dir "$d" restore || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }

  printf '%s\n' '--- RESTORED (same scratch overlay, original bytes) ---'
  QA33_OVERLAY_DIR="$d/overlay" QA33_OVERLAY_REF=C8 QA33_OVERLAY_FILE=GuideMappingStore.kt gate restored
  local restored_rc=$?
  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?

  local restored_marker green_marker restored_verdict green_verdict
  restored_marker="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$work/gate.restored/report.tsv")"
  green_marker="$(awk -F '\t' '$1 == "HAZARD" { n++ } END { print n + 0 }' "$work/gate.green/report.tsv")"
  restored_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.restored/summary.txt" 2>/dev/null || true)"
  green_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.green/summary.txt" 2>/dev/null || true)"

  printf 'MUTATION\tRED_RC\t%d\tRED_HITS\t%s\tRED_FIRST\t%s\tRESTORED_RC\t%d\tGREEN_RC\t%d\tRESTORE_MARKER\t%s\tGREEN_MARKER\t%s\n' \
    "$red_rc" "$red_hits" "${red_first:-none}" "$restored_rc" "$green_rc" "$restored_marker" "$green_marker"

  if (( red_rc != 1 || red_hits != 1 || restored_rc != 3 || green_rc != 3 )) ||
     [[ "$red_first" != 'first=G_FAIL_CLOSED_EMPTY' ||
        "$red_marker" != 1 || "$restored_marker" != 1 || "$green_marker" != 1 ||
        "$restored_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ||
        "$green_verdict" != 'verdict=MERGE-REVIEW-NEEDED' ]]; then
    printf 'MUTATION\tFAIL\tsensitivity-not-proven\tred-first=%s\n' "${red_first:-none}"
    rc=1
  else
    printf 'MUTATION\tPASS\tsingle-scratch-mutation-detected-and-restored\tred-first=%s\tverdict=MERGE-REVIEW-NEEDED\n' "$red_first"
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only) gate green ;;
  verify) mutation_probe ;;
  *) printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2 ;;
esac
