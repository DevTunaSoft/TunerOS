#!/usr/bin/env bash
# QA-24 bounded host gate runner: RED negative controls first, then unchanged GREEN.
set -uo pipefail

native_pwd() {
  pwd -W 2>/dev/null || pwd
}

SCRIPT_DIR=$(cd "$(dirname "$0")" && native_pwd)
REPO=$(cd "$SCRIPT_DIR/../.." && native_pwd)
GATE_SRC="$REPO/scripts/qa24/Qa24Gate.java"
FIXTURE="$REPO/qa/fixtures/qa24"

export JAVA_HOME='C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2'
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"

WORK_BASH=$(mktemp -d "${TMPDIR:-$HOME}/qa24-run.XXXXXX") || exit 1
WORK=$(cygpath -m "$WORK_BASH")
CLEAN_BUILD="$WORK/clean-build"
mkdir -p "$CLEAN_BUILD"

compile_gate() {
  local src=$1
  local out=$2
  mkdir -p "$out"
  timeout 120 javac -encoding UTF-8 -d "$out" "$src" > "$out/compile.log" 2>&1
  return $?
}

gate_run() {
  local cp=$1
  local dir=$2
  local mode=$3
  local log=$4
  timeout 180 java -Xmx96m -cp "$cp" Qa24Gate verify "$dir" "$mode" > "$log" 2>&1
  return $?
}

first_code() {
  awk -F'\t' '$1 == "FIRST" { code = $2 } END { print (code == "" ? "NONE" : code) }' "$1"
}

expect_code() {
  local id=$1
  local expected=$2
  local dir=$3
  local mode=$4
  local cp=$5
  local log="$WORK/$id.log"
  gate_run "$cp" "$dir" "$mode" "$log"
  local status=$?
  local code
  code=$(first_code "$log")
  if [ "$status" -eq 0 ] || [ "$code" != "$expected" ]; then
    printf '%b\n' "MUTANT\t$id\tFAIL\texit=$status\tfirst=$code\texpected=$expected"
    return 1
  fi
  printf '%b\n' "MUTANT\t$id\tPASS\texit=$status\tfirst=$code\texpected=$expected"
  return 0
}

remnant() {
  cp -R "$FIXTURE/." "$1/" || return 1
}

mutate_source() {
  local from=$1
  local to=$2
  local out=$3
  local count
  count=$(grep -F -c "$from" "$GATE_SRC")
  if [ "$count" -ne 1 ]; then
    printf '%b\n' "MUTATE\tFAIL\toccurrences=$count\tneedle=$from"
    return 1
  fi
  mkdir -p "$WORK/$out-src"
  sed "s#$from#$to#" "$GATE_SRC" > "$WORK/$out-src/Qa24Gate.java" || return 1
  compile_gate "$WORK/$out-src/Qa24Gate.java" "$WORK/$out-build"
}

run_red() {
  compile_gate "$GATE_SRC" "$CLEAN_BUILD" || return 1
  local red_fail=0
  local red_pass=0

  local m1="$WORK/m1"
  mkdir -p "$m1" && remnant "$m1" || return 1
  awk -F'\t' 'BEGIN { OFS = "\t" } NR == 5002 { $3 = "q" $3 } { print }' \
    "$m1/rows.tsv" > "$m1/rows.tmp" && mv "$m1/rows.tmp" "$m1/rows.tsv" || return 1
  bash "$REPO/scripts/qa24/remainifest.sh" "$m1" rows.tsv || return 1
  if expect_code M1_BYTE_FLIP FIXTURE_BYTE_IDENTICAL "$m1" normal "$CLEAN_BUILD"; then
    red_pass=$((red_pass + 1))
  else
    red_fail=$((red_fail + 1))
  fi

  local m2="$WORK/m2"
  mkdir -p "$m2" && remnant "$m2" || return 1
  awk -F'\t' 'BEGIN { OFS = "\t" } NR == 5002 { $2 = "1700000123" } { print }' \
    "$m2/rows.tsv" > "$m2/rows.tmp" && mv "$m2/rows.tmp" "$m2/rows.tsv" || return 1
  bash "$REPO/scripts/qa24/remainifest.sh" "$m2" rows.tsv || return 1
  if expect_code M2_SORT_ORDER SORT_ORDER "$m2" semantic "$CLEAN_BUILD"; then
    red_pass=$((red_pass + 1))
  else
    red_fail=$((red_fail + 1))
  fi

  local m3="$WORK/m3"
  mkdir -p "$m3" && remnant "$m3" || return 1
  if mutate_source 'if (value > CLIP_MAX) {' 'if (value > CLIP_MAX + 1) {' m3src; then
    if expect_code M3_CLIP_GUARD CASE_C06 "$m3" semantic "$WORK/m3src-build"; then
      red_pass=$((red_pass + 1))
    else
      red_fail=$((red_fail + 1))
    fi
  else
    red_fail=$((red_fail + 1))
  fi

  local m4="$WORK/m4"
  mkdir -p "$m4" && remnant "$m4" || return 1
  if mutate_source 'if (digits.length() > MAX_DIGITS) {' 'if (digits.length() > MAX_DIGITS + 1) {' m4src; then
    if expect_code M4_OVERFLOW_GUARD CASE_C07 "$m4" semantic "$WORK/m4src-build"; then
      red_pass=$((red_pass + 1))
    else
      red_fail=$((red_fail + 1))
    fi
  else
    red_fail=$((red_fail + 1))
  fi

  local m5="$WORK/m5"
  mkdir -p "$m5" && remnant "$m5" || return 1
  sed -n '2p' "$m5/single.tsv" >> "$m5/empty.tsv" || return 1
  bash "$REPO/scripts/qa24/remainifest.sh" "$m5" empty.tsv || return 1
  if expect_code M5_EMPTY_INPUT EMPTY_REJECT "$m5" semantic "$CLEAN_BUILD"; then
    red_pass=$((red_pass + 1))
  else
    red_fail=$((red_fail + 1))
  fi

  local m6="$WORK/m6"
  mkdir -p "$m6" && remnant "$m6" || return 1
  timeout 60 java -Xmx96m -cp "$CLEAN_BUILD" Qa24Gate marker > "$m6/leak.tsv" || return 1
  if expect_code M6_PRIVACY_SCAN PRIVACY_SCAN "$m6" semantic "$CLEAN_BUILD"; then
    red_pass=$((red_pass + 1))
  else
    red_fail=$((red_fail + 1))
  fi

  local m7="$WORK/m7"
  mkdir -p "$m7" && remnant "$m7" || return 1
  if mutate_source 'static final int MAX_THREADS = 8;' 'static final int MAX_THREADS = 0;' m7src; then
    if expect_code M7_THREAD_BOUND HEAP_THREADS_BOUNDED "$m7" semantic "$WORK/m7src-build"; then
      red_pass=$((red_pass + 1))
    else
      red_fail=$((red_fail + 1))
    fi
  else
    red_fail=$((red_fail + 1))
  fi

  printf '%b\n' "RED\tsuite=qa24-host\tpass=$red_pass\tfail=$red_fail\texpected_fail=7"
  [ "$red_fail" -eq 0 ]
}

run_green() {
  compile_gate "$GATE_SRC" "$CLEAN_BUILD" || return 1
  local log="$WORK/green.log"
  gate_run "$CLEAN_BUILD" "$FIXTURE" normal "$log"
  local status=$?
  local summary
  summary=$(awk -F'\t' '$1 == "SUMMARY" { print $2 "\t" $3 }' "$log")
  local first
  first=$(first_code "$log")
  if [ "$status" -eq 0 ] && [ "$first" = "NONE" ]; then
    printf '%b\n' "GREEN\tsuite=qa24-host\texit=$status\tfirst=$first\t$summary"
    return 0
  fi
  printf '%b\n' "GREEN\tsuite=qa24-host\texit=$status\tfirst=$first\t$summary"
  return 1
}

case "${1:-all}" in
  red) run_red; exit $? ;;
  green) run_green; exit $? ;;
  all)
    run_red || exit 1
    run_green || exit 1
    exit 0
    ;;
  *)
    printf '%b\n' 'USAGE\trun.sh\tred|green|all'
    exit 1
    ;;
esac
