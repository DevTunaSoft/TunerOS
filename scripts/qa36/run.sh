#!/usr/bin/env bash
# QA-36: bounded, host-only deterministic gate for HTTP-07 malformed-URL redaction.
# Reads pinned git objects only. No emulator, no network, no product edit.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="$repo/qa/fixtures/qa36"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
sha_file="$fixtures/sha256.txt"
src='app/src/main/java/com/devtunasoft/tuneros/SourceHttp.kt'
test='app/src/test/java/com/devtunasoft/tuneros/http07/SourceHttpHygieneTest.kt'
core='core/src/main/kotlin/com/devtunasoft/tuneros/core/HlsCaptureEngine.kt'
ctest='core/src/test/kotlin/com/devtunasoft/tuneros/core/HlsResourceUriAdversarialTest.kt'
max_bytes=131072
max_lines=4000
extra_checks=10

for required in "$refs_file" "$expect_file" "$sha_file"; do
  if [[ ! -f "$required" ]]; then
    printf 'SETUP\tFAIL\tmissing-fixture\n'
    exit 2
  fi
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa36-work-$$"
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

  pass() {
    checks=$((checks + 1)); passes=$((passes + 1))
    printf 'CHECK\tPASS\t%s\t%s\n' "$1" "$2" >> "$report"
  }
  fail() {
    checks=$((checks + 1)); fails=$((fails + 1))
    if [[ "$first" == NONE ]]; then first="$1"; fi
    printf 'CHECK\tFAIL\t%s\t%s\n' "$1" "$2" >> "$report"
  }

  # Fixture bytes are pinned: a tampered expectation/ref file cannot redefine the gate.
  local want_exp want_refs got_exp got_refs
  want_exp="$(awk '{ name = $2; if (name == "expectations.tsv") print $1 }' "$sha_file")"
  want_refs="$(awk '{ name = $2; if (name == "refs.tsv") print $1 }' "$sha_file")"
  got_exp="$(sha256sum "$expect_file" | awk '{ print $1 }')"
  got_refs="$(sha256sum "$refs_file" | awk '{ print $1 }')"
  if [[ -n "$want_exp" && -n "$want_refs" && "$want_exp" == "$got_exp" && "$want_refs" == "$got_refs" ]]; then
    pass FIXTURE_SHA256 'digests=2'
  else
    fail FIXTURE_SHA256 'fixture-bytes-changed'
  fi

  local http07 http07_base c49 c49_base
  http07="$(ref_sha HTTP07)" || fail REFS_RESOLVE 'HTTP07-missing'
  http07_base="$(ref_sha HTTP07_BASE)" || fail REFS_RESOLVE 'HTTP07_BASE-missing'
  c49="$(ref_sha CORE49)" || fail REFS_RESOLVE 'CORE49-missing'
  c49_base="$(ref_sha CORE49_BASE)" || fail REFS_RESOLVE 'CORE49_BASE-missing'

  source_text() {
    # Fixed input set. One declared (ref, basename) overlay is used only by the
    # scratch mutation probe and is capped exactly like the git input.
    local ref="$1" path="$2" out="$3" sha key bytes lines
    sha="$(ref_sha "$ref")" || return 2
    key="$(basename "$path")"
    if [[ -n "${QA36_OVERLAY_DIR:-}" &&
          "$ref" == "${QA36_OVERLAY_REF:-}" &&
          "$key" == "${QA36_OVERLAY_FILE:-}" ]]; then
      [[ -f "$QA36_OVERLAY_DIR/$key" ]] || return 2
      cp "$QA36_OVERLAY_DIR/$key" "$out" 2>/dev/null || return 2
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

  local id ref path kind needle expected n s
  local -a known=(HTTP07 HTTP07_BASE CORE49 CORE49_BASE)
  while IFS=$'\t' read -r id ref path kind needle expected; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    s="$dir/src.$id"
    if [[ " ${known[*]} " != *" $ref "* ]]; then
      fail "$id" 'unknown-ref'
      continue
    fi
    if ! source_text "$ref" "$path" "$s"; then
      fail "$id" 'source-unavailable-or-unbounded'
      continue
    fi
    n="$(count_fixed "$s" "$needle")"
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

  # Structural: both URL construction sites sit inside a try/catch, in order.
  local first_url first_catch second_url second_catch ok=1
  if source_text HTTP07 "$src" "$dir/src.http07"; then
    first_url="$(LC_ALL=C grep -n -F -m1 'URL(value)' "$dir/src.http07" | cut -d: -f1)"
    first_catch="$(LC_ALL=C grep -n -F -m1 'catch (_: Exception)' "$dir/src.http07" | cut -d: -f1)"
    second_url="$(LC_ALL=C grep -n -F -m1 'URL(current, location)' "$dir/src.http07" | cut -d: -f1)"
    second_catch="$(LC_ALL=C grep -n -F 'catch (_: Exception)' "$dir/src.http07" | sed -n '2p' | cut -d: -f1)"
  else
    ok=0
  fi
  if (( ok )) && [[ -n "$first_url" && -n "$first_catch" && -n "$second_url" && -n "$second_catch" ]] &&
     (( first_url < first_catch && first_catch < second_url && second_url < second_catch )); then
    pass S_BOTH_SITES_WRAPPED "lines=$first_url/$first_catch/$second_url/$second_catch"
  else
    fail S_BOTH_SITES_WRAPPED 'site-or-guard-order-invalid'
  fi

  # Structural: neither catch block binds the exception nor interpolates raw text.
  local blocks="$dir/catch.blocks" fixed_throws bound msg raw
  : > "$blocks"
  if [[ -f "$dir/src.http07" ]]; then
    awk '/catch \(_: Exception\) \{/ { inside = 1 }
         inside { print }
         inside && /^[[:space:]]*\}[[:space:]]*$/ { inside = 0 }' "$dir/src.http07" > "$blocks"
  fi
  fixed_throws="$(count_fixed "$blocks" 'throw IllegalArgumentException(MALFORMED_SOURCE)')"
  bound="$(count_fixed "$blocks" 'catch (e:')"
  msg="$(count_fixed "$blocks" '.message')"
  raw="$(count_fixed "$blocks" '$location')"
  raw=$(( raw + $(count_fixed "$blocks" '$value') ))
  if [[ "$fixed_throws" == 2 && "$bound" == 0 && "$msg" == 0 && "$raw" == 0 ]]; then
    pass S_CATCH_BLOCKS_FIXED "fixed=2 bound=0 echo=0"
  else
    fail S_CATCH_BLOCKS_FIXED "fixed=$fixed_throws bound=$bound echo=$((msg + raw))"
  fi

  # Structural: the redirect refusal disconnects and releases in that order.
  local dis rel thr dis_n rel_n
  dis_n="$(count_fixed "$blocks" 'connection.disconnect()')"
  rel_n="$(count_fixed "$blocks" 'connections.release(connection)')"
  dis="$(LC_ALL=C grep -n -F -m1 'connection.disconnect()' "$blocks" | cut -d: -f1)"
  rel="$(LC_ALL=C grep -n -F -m1 'connections.release(connection)' "$blocks" | cut -d: -f1)"
  thr="$(LC_ALL=C grep -n -F 'throw IllegalArgumentException(MALFORMED_SOURCE)' "$blocks" | tail -1 | cut -d: -f1)"
  if [[ "$dis_n" == 1 && "$rel_n" == 1 && -n "$dis" && -n "$rel" && -n "$thr" ]] &&
     (( dis < rel && rel < thr )); then
    pass S_REDIRECT_RELEASE_ORDER "disconnect=$dis release=$rel throw=$thr"
  else
    fail S_REDIRECT_RELEASE_ORDER "disconnect=${dis:-none} release=${rel:-none} throw=${thr:-none}"
  fi

  # Structural: each fix owns only its own production area (app vs core).
  local app_paths core_paths want_app want_core
  app_paths="$(git -C "$repo" diff --name-only "$http07_base" "$http07" -- app/ 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  core_paths="$(git -C "$repo" diff --name-only "$c49_base" "$c49" -- core/ 2>/dev/null | LC_ALL=C sort | paste -sd ';' -)"
  want_app="$(printf '%s\n%s\n' "$src" "$test" | LC_ALL=C sort | paste -sd ';' -)"
  want_core="$(printf '%s\n%s\n' "$core" "$ctest" | LC_ALL=C sort | paste -sd ';' -)"
  if [[ "$app_paths" == "$want_app" ]]; then
    pass S_HTTP07_APP_ONLY 'changed-paths=2'
  else
    fail S_HTTP07_APP_ONLY 'changed-path-set-mismatch'
  fi
  if [[ "$core_paths" == "$want_core" ]]; then
    pass S_C49_CORE_ONLY 'changed-paths=2'
  else
    fail S_C49_CORE_ONLY 'changed-path-set-mismatch'
  fi

  # The app/core pair stays disjoint: no shared production path between fixes.
  local common=0 p q
  local -a ap cp
  IFS=';' read -r -a ap <<< "$app_paths"
  IFS=';' read -r -a cp <<< "$core_paths"
  for p in "${ap[@]}"; do
    for q in "${cp[@]}"; do
      [[ -n "$p" && "$p" == "$q" ]] && common=$((common + 1))
    done
  done
  if (( common == 0 )); then
    pass S_APP_CORE_DISJOINT 'shared-paths=0'
  else
    fail S_APP_CORE_DISJOINT "shared-paths=$common"
  fi

  # Pins are four distinct, existing commits.
  if [[ "$http07_base" != "$http07" && "$c49_base" != "$c49" && "$http07" != "$c49" &&
        "$http07_base" != "$c49_base" ]] &&
     git -C "$repo" cat-file -e "$http07_base^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$http07^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c49_base^{commit}" 2>/dev/null &&
     git -C "$repo" cat-file -e "$c49^{commit}" 2>/dev/null; then
    pass S_PINS_DISTINCT 'commits=4 distinct-and-present'
  else
    fail S_PINS_DISTINCT 'pinned-commits-invalid'
  fi

  local drift
  drift="$(git -C "$repo" status --porcelain -- "$src" "$test" "$core" "$ctest")"
  if [[ -z "$drift" ]]; then
    pass P_NO_PRODUCTION_DRIFT 'tracked-production-paths-clean'
  else
    fail P_NO_PRODUCTION_DRIFT 'production-path-modified'
  fi

  if LC_ALL=C grep -E -q -i \
      '(https?://|[[:alnum:].-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}|(password|passwd|secret|api[_-]?key|credential)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
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
  local http07 py="$script_dir/mutate.py" rc=0
  http07="$(ref_sha HTTP07)" || { printf 'MUTATION\tFAIL\trefs-unavailable\n'; return 1; }
  local d="$work/mutation"
  mkdir -p "$d"
  python3 "$py" --repo "$repo" --http07 "$http07" --dir "$d" prepare || { printf 'MUTATION\tFAIL\tprepare\n'; return 1; }
  python3 "$py" --repo "$repo" --http07 "$http07" --dir "$d" mutate || { printf 'MUTATION\tFAIL\tmutate\n'; return 1; }

  printf '%s\n' '--- RED (mutated scratch overlay) ---'
  QA36_OVERLAY_DIR="$d/overlay" QA36_OVERLAY_REF=HTTP07 QA36_OVERLAY_FILE=SourceHttp.kt gate red
  local red_rc=$?
  local red="$work/gate.red/report.tsv"
  local red_hits red_first
  red_hits="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 == "H_FIXED_REFUSAL_BOTH_SITES" { n++ } END { print n + 0 }' "$red")"
  red_first="$(grep -o 'first=[A-Z0-9_]*' "$work/gate.red/summary.txt" 2>/dev/null || true)"

  python3 "$py" --repo "$repo" --http07 "$http07" --dir "$d" restore || { printf 'MUTATION\tFAIL\trestore\n'; return 1; }

  printf '%s\n' '--- RESTORED (same scratch overlay, original bytes) ---'
  QA36_OVERLAY_DIR="$d/overlay" QA36_OVERLAY_REF=HTTP07 QA36_OVERLAY_FILE=SourceHttp.kt gate restored
  local restored_rc=$?
  printf '%s\n' '--- GREEN (pinned git objects, no overlay) ---'
  gate green
  local green_rc=$?

  printf 'MUTATION\tRED_RC\t%d\tRED_HITS\t%s\tRESTORED_RC\t%d\tGREEN_RC\t%d\n' \
    "$red_rc" "$red_hits" "$restored_rc" "$green_rc"
  if (( red_rc == 0 || red_hits != 1 || restored_rc != 0 || green_rc != 0 )) ||
     [[ "$red_first" != 'first=H_FIXED_REFUSAL_BOTH_SITES' ]]; then
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
