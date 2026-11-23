#!/usr/bin/env bash
# QA-32: bounded host-only gate over pinned MAP-04 (PR 210) and CORE-49 (PR 208) fixes.
# Reads pinned git objects only. No emulator, no network, no product edit, no PR.
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
repo="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)"
if [[ -z "$repo" ]]; then
  printf 'SETUP\tFAIL\trepo-not-found\n'
  exit 2
fi
fixtures="${QA32_FIXTURES_DIR:-$repo/qa/fixtures/qa32}"
refs_file="$fixtures/refs.tsv"
expect_file="$fixtures/expectations.tsv"
sha_file="$fixtures/sha256.txt"
map_cases="$fixtures/map04_cases.tsv"
uri_cases="$fixtures/core49_cases.tsv"
golden="$fixtures/golden_save.txt"

java_bin="C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2/bin/java"
kotlinc_lib="${QA32_KOTLIN_LIB:-C:/Users/X1/AppData/Local/Temp/tunerostools/kotlin/kotlinc/lib}"
export JAVA_HOME="C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2"
[[ -x "$java_bin" && -f "$kotlinc_lib/kotlin-compiler.jar" ]] || {
  printf 'SETUP\tFAIL\ttoolchain-missing\n'; exit 2; }
std_cp="$kotlinc_lib/kotlin-stdlib.jar;$kotlinc_lib/annotations-13.0.jar;$kotlinc_lib/kotlin-script-runtime.jar;$kotlinc_lib/trove4j.jar"
k2_cp="$kotlinc_lib/kotlin-compiler.jar"

for required in "$refs_file" "$expect_file" "$sha_file" "$map_cases" "$uri_cases" "$golden" \
    "$script_dir/Qa32MapProbe.kt" "$script_dir/Qa32UriProbe.kt" \
    "$script_dir/stubs/AndroidStub.kt" "$script_dir/stubs/JsonStub.kt" "$script_dir/mutate.py"; do
  [[ -f "$required" ]] || { printf 'SETUP\tFAIL\tmissing-input\n'; exit 2; }
done

work="$(cygpath -m "${TMPDIR:-/tmp}")/qa32-work-$$"
mkdir -p "$work/src/deps" "$work/mut" || { printf 'SETUP\tFAIL\tscratch-unavailable\n'; exit 2; }
trap 'rm -rf "$work"' EXIT

exp() { awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$expect_file"; }
ref() { awk -F '\t' -v key="$1" '$1 == key { print $2; found = 1 } END { exit(found ? 0 : 1) }' "$refs_file"; }
count_fixed() { LC_ALL=C grep -o -F -- "$2" "$1" 2>/dev/null | wc -l | tr -d '[:space:]'; }

map_sha="$(ref MAP04)" || { printf 'SETUP\tFAIL\trefs\n'; exit 2; }
map_base_sha="$(ref MAP04_BASE)" || { printf 'SETUP\tFAIL\trefs\n'; exit 2; }
uri_sha="$(ref CORE49)" || { printf 'SETUP\tFAIL\trefs\n'; exit 2; }
uri_base_sha="$(ref CORE49_BASE)" || { printf 'SETUP\tFAIL\trefs\n'; exit 2; }
map_path="$(exp map.source.path)" || { printf 'SETUP\tFAIL\texpect\n'; exit 2; }
uri_path="$(exp core.source.path)" || { printf 'SETUP\tFAIL\texpect\n'; exit 2; }
max_bytes="$(exp max.bytes)"
max_lines="$(exp max.lines)"

# Runtime-derived canary: never written as a literal in source, fixture or report.
seed="qa32-fixed-seed-v1"
token="$(printf '%s' "$seed" | sha256sum | awk '{ print substr($1, 1, 12) }')"
canary="QA32${token^^}"

report="$work/report.tsv"
: > "$report"
checks=0; passes=0; fails=0; first=NONE
pass() { checks=$((checks + 1)); passes=$((passes + 1)); printf 'CHECK\tPASS\t%s\t%s\n' "$1" "$2" >> "$report"; }
fail() { checks=$((checks + 1)); fails=$((fails + 1)); [[ "$first" == NONE ]] && first="$1"; printf 'CHECK\tFAIL\t%s\t%s\n' "$1" "$2" >> "$report"; }

# Pinned fixture bytes, before any use.
fixture_ok=1
while read -r want name; do
  [[ -z "$want" || -z "$name" ]] && continue
  name="${name#\*}"
  [[ -f "$fixtures/$name" ]] || { fixture_ok=0; continue; }
  got="$(sha256sum "$fixtures/$name" | awk '{ print $1 }')"
  [[ "$got" == "$want" ]] || fixture_ok=0
done < "$sha_file"
piemanifest="$(awk 'NF && $1 !~ /^#/ { n++ } END { print n + 0 }' "$sha_file")"
if [[ "$fixture_ok" == 1 && "$piemanifest" -eq 5 ]]; then
  pass FIXTURE_SHA256 "files=5"
else
  fail FIXTURE_SHA256 "files=$piemanifest fixture-bytes-changed"
fi

if [[ "$map_sha" != "$map_base_sha" && "$uri_sha" != "$uri_base_sha" && "$map_sha" != "$uri_sha" ]] &&
   git -C "$repo" cat-file -e "$map_sha^{commit}" 2>/dev/null &&
   git -C "$repo" cat-file -e "$map_base_sha^{commit}" 2>/dev/null &&
   git -C "$repo" cat-file -e "$uri_sha^{commit}" 2>/dev/null &&
   git -C "$repo" cat-file -e "$uri_base_sha^{commit}" 2>/dev/null; then
  pass REFS_VALID 'commits=4 distinct'
else
  fail REFS_VALID 'pinned-commits-invalid'
fi

extract() { git -C "$repo" show "$1:$2" > "$3" 2>/dev/null; }
extract "$map_sha" "$map_path" "$work/src/map.fixed.kt"
extract "$map_base_sha" "$map_path" "$work/src/map.base.kt"
extract "$uri_sha" "$uri_path" "$work/src/core.fixed.kt"
extract "$uri_base_sha" "$uri_path" "$work/src/core.base.kt"
IFS=';' read -r -a deps <<< "$(exp core.deps.paths)"
for dep in "${deps[@]}"; do
  [[ -n "$dep" ]] || continue
  extract "$uri_sha" "$dep" "$work/src/deps/$(basename "$dep")"
done

bounds_ok=1
for f in "$work"/src/*.kt "$work"/src/deps/*.kt; do
  [[ -f "$f" ]] || { bounds_ok=0; continue; }
  bytes="$(wc -c < "$f" | tr -d '[:space:]')"
  lines="$(wc -l < "$f" | tr -d '[:space:]')"
  if (( bytes > max_bytes || lines > max_lines )); then bounds_ok=0; fi
done
if [[ "$bounds_ok" == 1 ]]; then
  pass SOURCE_BOUNDS "files=$(( ${#deps[@]} + 4 )) max-bytes=$max_bytes"
else
  fail SOURCE_BOUNDS 'source-unavailable-or-unbounded'
fi

drift="$(git -C "$repo" status --porcelain -- app/src/main core/src/main app/src/test core/src/test)"
if [[ -z "$drift" ]]; then
  pass PRODUCT_DRIFT_CLEAN 'production-paths-unmodified'
else
  fail PRODUCT_DRIFT_CLEAN 'production-path-modified'
fi

k2() {
  local out="$1"; shift
  rm -rf "$out"
  mkdir -p "$out"
  timeout 240 "$java_bin" -cp "$k2_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 "$@" -d "$out" > "$work/k2.log" 2>&1
  local rc=$?
  if (( rc != 0 )); then
    printf 'COMPILE\tFAIL\trc=%d\n' "$rc"
    grep -E 'error:|Exception' "$work/k2.log" | head -8
    return 1
  fi
  return 0
}

PYTHONPYCACHEPREFIX="$work/pycache" python3 -m py_compile "$script_dir/mutate.py" > "$work/pycompile.log" 2>&1 || {
  printf 'SETUP\tFAIL\tmutate-py\n'; exit 2; }

compiled_map=0; compiled_core=0
if k2 "$work/classes.map.green" -classpath "$std_cp" \
    "$script_dir/stubs/AndroidStub.kt" "$script_dir/stubs/JsonStub.kt" \
    "$work/src/map.fixed.kt" "$script_dir/Qa32MapProbe.kt"; then compiled_map=1; fi
if k2 "$work/core_deps" -classpath "$std_cp" "${work}/src/deps/"*.kt; then
  core_fast_cp="$std_cp;$work/core_deps"
  if k2 "$work/classes.core.green" -classpath "$core_fast_cp" \
      -Xfriend-paths="$work/core_deps" "$work/src/core.fixed.kt" "$script_dir/Qa32UriProbe.kt"; then
    compiled_core=1
  fi
fi
printf 'COMPILE\tMAP04\tgreen=%d\tCORE49\tgreen=%d\n' "$compiled_map" "$compiled_core"
if [[ "$compiled_map" != 1 || "$compiled_core" != 1 ]]; then
  printf 'SETUP\tFAIL\tcompile\n'; exit 2
fi

run_probe() {
  local label="$1" cls="$2" cp="$3" outreport="$4"; shift 4
  local out="$work/probe.$label.out"
  timeout 120 "$java_bin" -Xmx256m -cp "$cp" "$cls" "$@" > "$out" 2>&1
  local rc=$?
  grep '^CHECK' "$out" >> "$outreport" 2>/dev/null
  local summary
  summary="$(grep '^PROBE_SUMMARY' "$out" | head -1)"
  if (( rc != 0 )) || [[ -z "$summary" ]]; then
    printf 'PROBE\tFAIL\t%s\trc=%d\n' "$label" "$rc"
    return 1
  fi
  printf '%s\n' "$summary" | tr '\t' ' '
  return 0
}

map_args=("$expect_file" "$map_cases" "$golden" "$work/src/map.fixed.kt" "$work/src/map.base.kt" "$canary")
uri_args=("$expect_file" "$uri_cases" "$work/src/core.fixed.kt" "$work/src/core.base.kt" "$canary")
green="$work/report.green"
: > "$green"
run_probe MAP04_GREEN com.devtunasoft.tuneros.Qa32MapProbe \
  "$work/classes.map.green;$std_cp" "$green" "${map_args[@]}" || exit 2
run_probe CORE49_GREEN com.devtunasoft.tuneros.core.Qa32UriProbe \
  "$work/classes.core.green;$work/core_deps;$std_cp" "$green" "${uri_args[@]}" || exit 2

green_checks="$(awk -F '\t' '$1 == "CHECK" { n++ } END { print n + 0 }' "$green")"
green_fails="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n + 0 }' "$green")"
printf 'PROBE\tBASELINE\tchecks=%s\tfail=%s\n' "$green_checks" "$green_fails"
cat "$green" >> "$report"

# Privacy: specific sensitive patterns plus the runtime-derived marker.
if LC_ALL=C grep -E -q -i \
    '(https?://|(password|passwd|secret|api[_-]?key|credential|token)[[:space:]]*[=:]|Bearer[[:space:]]+[[:alnum:]])' \
    "$report" "$expect_file" "$refs_file" "$map_cases" "$uri_cases" "$golden" ||
   LC_ALL=C grep -F -q -- "$canary" "$report" "$expect_file" "$refs_file" "$map_cases" "$uri_cases" "$golden"; then
  fail P_OUTPUT_PRIVACY 'sensitive-pattern-or-marker-found'
else
  pass P_OUTPUT_PRIVACY 'report-and-fixtures-clean'
fi

mode="${1:-gate-only}"
expect_gate="$(( $(exp expected.checks.gate) ))"
expect_verify="$(( $(exp expected.checks.verify) ))"

if [[ "$mode" == verify ]]; then
  # One scratch mutation per gate, each scoped to exactly one declared source file.
  for gate in map core; do
    python3 "$script_dir/mutate.py" --repo "$repo" --refs "$refs_file" --expect "$expect_file" \
      --dir "$work/mut/$gate" --gate "$gate" --mode prepare || { printf 'MUTATION\tFAIL\tprepare-%s\n' "$gate"; exit 2; }
    python3 "$script_dir/mutate.py" --repo "$repo" --refs "$refs_file" --expect "$expect_file" \
      --dir "$work/mut/$gate" --gate "$gate" --mode mutate || { printf 'MUTATION\tFAIL\tmutate-%s\n' "$gate"; exit 2; }
  done

  if k2 "$work/classes.map.red" -classpath "$std_cp" \
      "$script_dir/stubs/AndroidStub.kt" "$script_dir/stubs/JsonStub.kt" \
      "$work/mut/map/scratch.map.kt" "$script_dir/Qa32MapProbe.kt" && \
     k2 "$work/classes.core.red" -classpath "$core_fast_cp" -Xfriend-paths="$work/core_deps" \
      "$work/mut/core/scratch.core.kt" "$script_dir/Qa32UriProbe.kt"; then
    red="$work/report.red"
    : > "$red"
    map_red_args=("$expect_file" "$map_cases" "$golden" "$work/mut/map/scratch.map.kt" "$work/src/map.base.kt" "$canary")
    uri_red_args=("$expect_file" "$uri_cases" "$work/mut/core/scratch.core.kt" "$work/src/core.base.kt" "$canary")
    run_probe MAP04_RED com.devtunasoft.tuneros.Qa32MapProbe \
      "$work/classes.map.red;$std_cp" "$red" "${map_red_args[@]}" || true
    run_probe CORE49_RED com.devtunasoft.tuneros.core.Qa32UriProbe \
      "$work/classes.core.red;$work/core_deps;$std_cp" "$red" "${uri_red_args[@]}" || true
    map_first="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 ~ /^M_/ { print $3; exit }' "$red")"
    core_first="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 ~ /^C_/ { print $3; exit }' "$red")"
    map_red_fails="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 ~ /^M_/ { n++ } END { print n + 0 }' "$red")"
    core_red_fails="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" && $3 ~ /^C_/ { n++ } END { print n + 0 }' "$red")"
    printf 'MUTATION\tMAP04\tfirst=%s\tfail=%s\nMUTATION\tCORE49\tfirst=%s\tfail=%s\n' \
      "$map_first" "$map_red_fails" "$core_first" "$core_red_fails"
    if [[ "$map_first" == "$(exp map.expected.first)" && "$map_red_fails" -ge 1 &&
          "$core_first" == "$(exp core.expected.first)" && "$core_red_fails" -ge 1 ]]; then
      pass MUTATION_RED_CONTROLS 'gates=2 pinned-first-codes-matched'
    else
      fail MUTATION_RED_CONTROLS 'sensitivity-not-proven'
    fi
  else
    fail MUTATION_RED_CONTROLS 'mutant-compile-failed'
  fi

  # Byte-identical restoration, then rerun the cached GREEN classes on the restored tree.
  restore_ok=1
  for gate in map core; do
    python3 "$script_dir/mutate.py" --repo "$repo" --refs "$refs_file" --expect "$expect_file" \
      --dir "$work/mut/$gate" --gate "$gate" --mode restore || restore_ok=0
    a="$(sha256sum "$work/mut/$gate/original.$gate.kt" | awk '{ print $1 }')"
    b="$(sha256sum "$work/mut/$gate/scratch.$gate.kt" | awk '{ print $1 }')"
    [[ "$a" == "$b" ]] || restore_ok=0
    printf 'RESTORED\t%s\tsha256=%s\n' "${gate^^}" "$a"
  done
  restored="$work/report.restored"
  : > "$restored"
  run_probe MAP04_RESTORED com.devtunasoft.tuneros.Qa32MapProbe \
    "$work/classes.map.green;$std_cp" "$restored" "${map_args[@]}" || true
  run_probe CORE49_RESTORED com.devtunasoft.tuneros.core.Qa32UriProbe \
    "$work/classes.core.green;$work/core_deps;$std_cp" "$restored" "${uri_args[@]}" || true
  restored_fails="$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n + 0 }' "$restored")"
  printf 'RESTORED\tGREEN_RERUN\tfail=%s\n' "$restored_fails"
  [[ "$restore_ok" == 1 && "$restored_fails" -eq 0 ]] || printf 'RESTORED\tFAIL\tnot-byte-identical\n'
else
  [[ "$mode" == gate-only ]] || { printf 'USAGE\t%s\tgate-only|verify\n' "$0"; exit 2; }
fi

# Recompute counters after controls are appended.
checks=0; passes=0; fails=0; first=NONE
while IFS=$'\t' read -r a b c d; do
  [[ "$a" == CHECK ]] || continue
  checks=$((checks + 1))
  if [[ "$b" == PASS ]]; then
    passes=$((passes + 1))
  else
    fails=$((fails + 1))
    [[ "$first" == NONE ]] && first="$c"
  fi
done < "$report"
if [[ "$mode" == verify ]]; then want_total="$expect_verify"; else want_total="$expect_gate"; fi
if (( fails == 0 && checks == want_total )); then verdict=PASS; rc=0; else verdict=FAIL; rc=1; fi

# Bounded report: only ids, counts and hashes.
head -40 "$report"
printf 'SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\texpected-total=%d\tmode=%s\n' \
  "$checks" "$passes" "$fails" "$first" "$verdict" "$want_total" "$mode"
exit "$rc"
