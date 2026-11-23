#!/usr/bin/env bash
# QA-38: deterministic host-only RESTORE-02 integration gate over pinned git objects.
# Reads immutable commit objects only. No emulator, network, product edit, PR or merge.
# Exit codes: 0 PASS (all checks green and RESTORE-02 integrated), 1 FAIL, 2 SETUP error.
# At the audited pin C13=c1d6f62b the truthful verdict is FAIL: the RESTORE-02
# production change and its regressions are not present at that commit.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa38"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
markers_file="$fixtures/markers.tsv"
sha_file="$fixtures/sha256.txt"

backup='app/src/main/java/com/devtunasoft/tuneros/BackupRestore.kt'
qa37_refs='qa/fixtures/qa37/refs.tsv'
qa37_exp='qa/fixtures/qa37/expectations.tsv'
qa37_runner='scripts/qa37/run.sh'
c13_branch='hermes/worker-c13-restore02'

max_bytes=65536
max_lines=2000
extra_checks=11

for required in "$refs_file" "$expect_file" "$markers_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

native_tmp="$(cygpath -m "${TMPDIR:-/tmp}" 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}")"
work="$native_tmp/qa38-work-$$"
mkdir -p "$work" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

ref_sha() {
  awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"
}

count_fixed() {
  LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'
}

declare -A QA38_SRC_CACHE=()
qa38_cache_dir=''

source_text() {
  # Explicit (ref, path) overlay scope: a declared but missing substitution fails closed.
  local ref="$1" path="$2" out="$3" sha key bytes lines cache_key
  sha="$(ref_sha "$ref")" || return 2
  key="${ref}_$(basename "$path")"
  cache_key="$ref|$path"
  if [[ -n "${QA38_SRC_CACHE[$cache_key]:-}" && -f "${QA38_SRC_CACHE[$cache_key]}" ]]; then
    cp "${QA38_SRC_CACHE[$cache_key]}" "$out" 2>/dev/null || return 2
  elif [[ -n "${QA38_OVERLAY_DIR:-}" &&
          "$ref" == "${QA38_OVERLAY_REF:-}" &&
          "$path" == "${QA38_OVERLAY_PATH:-}" ]]; then
    [[ -f "$QA38_OVERLAY_DIR/$key" ]] || return 2
    cp "$QA38_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
    mkdir -p "$qa38_cache_dir"
    cp "$out" "$qa38_cache_dir/$key" 2>/dev/null || return 2
    QA38_SRC_CACHE["$cache_key"]="$qa38_cache_dir/$key"
  else
    git -C "$repo" show "$sha:$path" > "$out" 2>/dev/null || return 2
    mkdir -p "$qa38_cache_dir"
    cp "$out" "$qa38_cache_dir/$key" 2>/dev/null || return 2
    QA38_SRC_CACHE["$cache_key"]="$qa38_cache_dir/$key"
  fi
  bytes="$(wc -c < "$out" | tr -d '[:space:]')"
  lines="$(wc -l < "$out" | tr -d '[:space:]')"
  if (( bytes == 0 || bytes > max_bytes || lines > max_lines )); then return 3; fi
  return 0
}

gate() {
  local label="$1"
  local dir="$work/gate.$label"
  rm -rf "$dir"
  mkdir -p "$dir"
  qa38_cache_dir="$dir/cache"
  QA38_SRC_CACHE=()
  mkdir -p "$qa38_cache_dir"
  local report="$dir/report.tsv"
  : > "$report"
  local checks=0 passes=0 fails=0 first=NONE
  local refs_ok=1 base c10 c13 qa37
  local qa37_runner_n=0 restore02_files=-1 backup_changed=0 marker_hits=0 marker_bytes=0
  local integration_ok=0 c13_no_remove=0 c13_no_add=0 c13_uses=0 c13_no_unguarded=0
  local marker_text=''

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
  want_exp="$(awk '$2 == "expectations.tsv" { print $1 }' "$sha_file")"
  want_refs="$(awk '$2 == "refs.tsv" { print $1 }' "$sha_file")"
  want_markers="$(awk '$2 == "markers.tsv" { print $1 }' "$sha_file")"
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

  base="$(ref_sha BASE)" || { fail REFS_RESOLVE 'BASE-missing'; refs_ok=0; }
  c10="$(ref_sha C10)" || { fail REFS_RESOLVE 'C10-missing'; refs_ok=0; }
  c13="$(ref_sha C13)" || { fail REFS_RESOLVE 'C13-missing'; refs_ok=0; }
  qa37="$(ref_sha QA37)" || { fail REFS_RESOLVE 'QA37-missing'; refs_ok=0; }
  if [[ -n "$base" && -n "$c10" && -n "$c13" && -n "$qa37" &&
        "$base" != "$c10" && "$base" != "$c13" && "$base" != "$qa37" &&
        "$c10" != "$c13" && "$c10" != "$qa37" && "$c13" != "$qa37" ]]; then
    for sha in "$base" "$c10" "$c13" "$qa37"; do
      git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null || refs_ok=0
    done
  else
    refs_ok=0
  fi
  if [[ "$refs_ok" == 1 ]]; then
    pass REFS_RESOLVE "refs=4 base=${base:0:8} c10=${c10:0:8} c13=${c13:0:8} qa37=${qa37:0:8}"
  else
    fail REFS_RESOLVE 'refs-invalid'
  fi

  # Pin freshness: the observed remote-tracking head must equal the pinned C13 SHA.
  # When C13 pushes a new final commit this check FAILs until refs.tsv is re-pinned.
  local live_c13
  live_c13="$(git -C "$repo" rev-parse "origin/$c13_branch" 2>/dev/null)"
  if [[ -n "$live_c13" && "$live_c13" == "$c13" ]]; then
    pass C13_PIN_FRESH "pinned=${c13:0:8} observed=${live_c13:0:8}"
  else
    fail C13_PIN_FRESH "pinned=${c13:0:8} observed=${live_c13:-none}"
  fi

  local mb10 mb13
  mb10="$(git -C "$repo" merge-base "$base" "$c10" 2>/dev/null)"
  mb13="$(git -C "$repo" merge-base "$base" "$c13" 2>/dev/null)"
  if [[ "$mb10" == "$base" && "$refs_ok" == 1 ]]; then
    pass C10_BASE_MERGE "merge-base=${base:0:8}"
  else
    fail C10_BASE_MERGE 'merge-base-mismatch'
  fi
  if [[ "$mb13" == "$base" && "$refs_ok" == 1 ]]; then
    pass C13_BASE_MERGE "merge-base=${base:0:8}"
  else
    fail C13_BASE_MERGE 'merge-base-mismatch'
  fi

  local id ref path kind needle expected n src scope
  local -a known=(BASE C10 C13 QA37)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    case "$path" in
      backup) scope="$backup" ;;
      qa37_refs) scope="$qa37_refs" ;;
      qa37_exp) scope="$qa37_exp" ;;
      *) fail "$id" 'unknown-path'; continue ;;
    esac
    src="$dir/src.$id"
    if ! source_text "$ref" "$scope" "$src"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    n="$(count_fixed "$src" "$needle")"
    case "$id" in
      C13_NO_LOOP_REMOVE) [[ "$n" == 0 ]] && c13_no_remove=1 ;;
      C13_NO_LOOP_ADD) [[ "$n" == 0 ]] && c13_no_add=1 ;;
      C13_USES_REPLACEALL) [[ "$n" == 1 ]] && c13_uses=1 ;;
      C13_NO_UNGUARDED_ADD) [[ "$n" == 0 ]] && c13_no_unguarded=1 ;;
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

  # QA-37 owns the detailed FAV-06 policy proof; QA-38 only confirms it is present
  # and pinned to the same C10 object. The policy needles are not re-verified here.
  qa37_runner_n="$(git -C "$repo" ls-tree -r --name-only "$qa37" 2>/dev/null | grep -c -F -- "$qa37_runner")"
  if [[ "$qa37_runner_n" == 1 ]]; then
    pass QA37_GATE_PRESENT 'qa37-runner=1'
  else
    fail QA37_GATE_PRESENT "qa37-runner=${qa37_runner_n:-0}"
  fi

  restore02_files="$(git -C "$repo" ls-tree -r --name-only "$c13" 2>/dev/null | grep -c -i 'restore02')"
  if [[ "$restore02_files" -ge 1 ]]; then
    pass C13_RESTORE02_TEST_PRESENT "restore02-files=$restore02_files"
  else
    fail C13_RESTORE02_TEST_PRESENT "restore02-files=${restore02_files:-0}"
  fi

  # C13 must actually change the restore path relative to the common base.
  if ! cmp -s "$dir/src.C13_NO_LOOP_REMOVE" "$dir/src.BASE_LOOP_REMOVE" 2>/dev/null; then
    pass C13_BACKUP_CHANGED 'backup-blob-changed'
    backup_changed=1
  else
    fail C13_BACKUP_CHANGED 'backup-blob-identical-to-base'
  fi

  # One bounded synthetic marker, emitted only when RESTORE-02 is fully integrated.
  if [[ "$c13_no_remove" == 1 && "$c13_no_add" == 1 && "$c13_uses" == 1 &&
        "$c13_no_unguarded" == 1 && "$restore02_files" -ge 1 && "$backup_changed" == 1 ]]; then
    integration_ok=1
  fi
  marker_text="$(awk -F '\t' '$1 == "RESTORE02_INTEGRATION" { print $2 }' "$markers_file")"
  local marker_line
  marker_line="$(printf 'RESTORE02\t%s\tsha=%s\ttests=%d' "$marker_text" "${c13:0:8}" "$restore02_files")"
  marker_bytes="$(printf '%s' "$marker_line" | wc -c | tr -d '[:space:]')"
  if [[ "$integration_ok" == 1 ]]; then
    printf '%s\n' "$marker_line" >> "$report"
  fi
  marker_hits="$(awk -F '\t' '$1 == "RESTORE02" { n++ } END { print n + 0 }' "$report")"
  if [[ "$marker_hits" == 1 && "$marker_bytes" -le 200 && "$integration_ok" == 1 ]]; then
    pass RESTORE02_MARKER_EMITTED "markers=1 bytes=$marker_bytes"
  else
    fail RESTORE02_MARKER_EMITTED "markers=$marker_hits bytes=$marker_bytes"
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- app/src/main core/src 2>/dev/null)"
  if [[ -z "$drift" ]]; then pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else fail P_NO_PRODUCTION_DRIFT 'production-path-modified'; fi

  if LC_ALL=C grep -E -q \
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
  if (( fails == 0 && checks == total && integration_ok == 1 && marker_hits == 1 )); then
    verdict=PASS; rc=0
  else
    verdict=FAIL; rc=1
  fi
  summary="$(printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tpinned-c13=%s\tmarker=%d\n' \
    "$checks" "$passes" "$fails" "$first" "$verdict" "$total" "${c13:0:8}" "$marker_hits")"
  printf '%s\n' "$summary"
  printf '%s\n' "$summary" > "$dir/summary.txt"
  return "$rc"
}

mutation_probe() {
  local base c10 c13 qa37 py="$script_dir/mutate.py" d="$work/mutation"
  base="$(ref_sha BASE)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c10="$(ref_sha C10)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  c13="$(ref_sha C13)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  qa37="$(ref_sha QA37)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  mkdir -p "$d"
  local common=(--repo "$repo" --base "$base" --c10 "$c10" --c13 "$c13" --qa37 "$qa37" --dir "$d")
  python3 "$py" "${common[@]}" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }

  local rc=0 overlay_ref='QA37' path="$qa37_exp" expect_id='X_QA37_COVERS_DUP'
  local restored_log="$d/restore.log" identical_n
  printf '%s\n' '--- RED (scratch mutation: QA37 duplicate-id coverage needle removed) ---'
  python3 "$py" "${common[@]}" mutate --target qa37_dup_coverage || { printf 'MUTATION\tFAIL\tmutate\n'; return 1; }
  QA38_OVERLAY_DIR="$d/overlay" QA38_OVERLAY_REF="$overlay_ref" QA38_OVERLAY_PATH="$path" gate "red"
  local red_rc=$?
  local red_hits red_first red_checks red_pass red_fail
  red_hits="$(awk -F '\t' -v id="$expect_id" '$1 == "CHECK" && $2 == "FAIL" && $3 == id { n++ } END { print n + 0 }' "$work/gate.red/report.tsv")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"
  red_checks="$(grep -o 'checks=[0-9]*' "$work/gate.red/summary.txt" 2>/dev/null | cut -d= -f2)"
  red_pass="$(grep -o 'pass=[0-9]*' "$work/gate.red/summary.txt" 2>/dev/null | cut -d= -f2)"
  red_fail="$(grep -o 'fail=[0-9]*' "$work/gate.red/summary.txt" 2>/dev/null | cut -d= -f2)"

  python3 "$py" "${common[@]}" restore > "$restored_log" || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }
  identical_n="$(awk -F '\t' '$1 == "RESTORE" && $2 != "ALL" && $3 == "BYTE_IDENTICAL" { n++ } END { print n + 0 }' "$restored_log")"

  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?
  local green_first green_verdict green_hits green_checks green_pass green_fail
  green_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.green/summary.txt" 2>/dev/null || true)"
  green_verdict="$(grep -o 'verdict=[A-Z-]*' "$work/gate.green/summary.txt" 2>/dev/null || true)"
  green_hits="$(awk -F '\t' '$1 == "RESTORE02" { n++ } END { print n + 0 }' "$work/gate.green/report.tsv")"
  green_checks="$(grep -o 'checks=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"
  green_pass="$(grep -o 'pass=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"
  green_fail="$(grep -o 'fail=[0-9]*' "$work/gate.green/summary.txt" 2>/dev/null | cut -d= -f2)"

  printf 'MUTATION\tRED_RC\t%d\tRED_HITS\t%d\tRED_FIRST\t%s\tRED_CHECKS\t%s\tRED_PASS\t%s\tRED_FAIL\t%s\n' \
    "$red_rc" "$red_hits" "${red_first:-none}" "$red_checks" "$red_pass" "$red_fail"
  printf 'MUTATION\tRESTORE_IDENTICAL\t%d\tGREEN_RC\t%d\tGREEN_FIRST\t%s\tGREEN_CHECKS\t%s\tGREEN_PASS\t%s\tGREEN_FAIL\t%s\tGREEN_VERDICT\t%s\tGREEN_MARKER\t%d\n' \
    "$identical_n" "$green_rc" "${green_first:-none}" "$green_checks" "$green_pass" "$green_fail" \
    "${green_verdict:-none}" "$green_hits"

  if (( red_rc != 1 || red_hits != 1 || identical_n != 4 || green_rc != 1 || green_hits != 0 )) || \
     [[ "$red_first" != "first=$expect_id" || "$green_first" == "first=$expect_id" || "$green_verdict" != 'verdict=FAIL' ]]; then
    printf 'MUTATION\tFAIL\tsensitivity-not-proven\n'
    rc=1
  fi
  if (( rc == 0 )); then
    printf 'MUTATION\tPASS\tsingle-scratch-mutation-detected-and-restored-byte-identically\n'
  fi
  return "$rc"
}

mode="${1:-gate-only}"
case "$mode" in
  gate-only) gate green ;;
  verify) mutation_probe ;;
  *) printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2 ;;
esac
