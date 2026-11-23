#!/usr/bin/env bash
# QA-27 bounded host gate runner. Compiles one Java source, runs RED controls,
# then the GREEN gate. Writes only to an isolated scratch directory.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH_DEFAULT="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
JH="${JAVA_HOME:-$JH_DEFAULT}/bin"
FIXTURE="$ROOT/qa/fixtures/qa27"
SCRATCH_BASE="$(cygpath -m "$HOME/AppData/Local/hermes/cache/scratch" 2>/dev/null || printf '%s' "$HOME/AppData/Local/hermes/cache/scratch")"
SCRATCH="$SCRATCH_BASE/qa27-run"
BUILD="$SCRATCH/build"
PROD="$ROOT/app/src/main/java/com/devtunasoft/tuneros/ProgramTimelineView.kt"
EXPECTED_CHECKS=15

MUTANTS="byteflip:BYTE_IDENTITY header:HEADER_STRICT rowbounds:ROW_BOUNDS argb:TOKEN_ARGB roleset:ROLE_SET alpha:ALPHA_SEMANTICS contrast:CONTRAST_BOUND truncated:TRUNCATED_INPUT marker:MARKER_DISTINCT leak:PRIVACY_SCAN"

compile_gate() {
  mkdir -p "$BUILD" || return 1
  timeout 180 "$JH/javac" -d "$BUILD" "$ROOT/scripts/qa27/Qa27Gate.java"
}

fixture_digest() {
  local d="$1"
  (cd "$d" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}

count_checks() {
  awk -F '\t' '$1=="CHECK" && $3=="PASS" {n++} END {print n+0}' "$1"
}

first_code() {
  awk -F '\t' '$1=="FIRST" {print $2; exit}' "$1"
}

do_generate() {
  compile_gate || { say 'FAIL\tqa27\tcompile'; return 1; }
  rm -rf "$FIXTURE"
  mkdir -p "$FIXTURE/corrupt"
  printf '%s\n' '* -text' '*.tsv -text' '*.txt -text' > "$FIXTURE/.gitattributes"
  timeout 90 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate generate "$FIXTURE"
}

do_verify() {
  compile_gate || { say 'FAIL\tqa27\tcompile'; return 1; }
  local log="$SCRATCH/green.log"
  mkdir -p "$SCRATCH"
  if ! timeout 120 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate verify "$FIXTURE" full > "$log" 2>&1; then
    say 'FAIL\tqa27-green\texit-nonzero'
    awk -F '\t' '$1=="FIRST" || $1=="CHECK"' "$log" | head -n 8
    return 1
  fi
  local n; n="$(count_checks "$log")"
  if [ "$n" -ne "$EXPECTED_CHECKS" ]; then
    say "FAIL\tqa27-green\tcheck-count=$n expected=$EXPECTED_CHECKS"
    return 1
  fi
  if awk -F '\t' '$1=="FIRST"{found=1} END{exit !found}' "$log"; then
    say 'FAIL\tqa27-green\tFIRST-present'
    return 1
  fi
  if ! grep -qF 'SUMMARY	PASS='"$EXPECTED_CHECKS"'' "$log"; then
    say 'FAIL\tqa27-green\tsummary-mismatch'
    return 1
  fi
  say "PASS\tqa27-green\tchecks=$n\theap=-Xmx96m\tlog=$log"
  return 0
}

do_red_green() {
  compile_gate || { say 'FAIL\tqa27\tcompile'; return 1; }
  mkdir -p "$SCRATCH"
  local prod_before prod_after
  prod_before="$(sha256sum "$PROD" | cut -d' ' -f1)"
  local fixture_before fixture_after
  fixture_before="$(fixture_digest "$FIXTURE")"

  local red_ok=0 red_bad=0
  local pair name code mode m log rc first
  for pair in $MUTANTS; do
    name="${pair%%:*}"; code="${pair#*:}"
    m="$SCRATCH/m-$name"
    rm -rf "$m"; mkdir -p "$m"
    if ! timeout 60 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate mutate "$m" "$name" > "$SCRATCH/mutate-$name.log" 2>&1; then
      say "FAIL\tqa27-red\t$name\tmutator-error"
      red_bad=$((red_bad + 1))
      continue
    fi
    mode="semantic"
    if [ "$name" = "byteflip" ]; then mode="full"; fi
    log="$SCRATCH/red-$name.log"
    timeout 120 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate verify "$m" "$mode" > "$log" 2>&1
    rc=$?
    first="$(first_code "$log")"
    if [ "$rc" -ne 0 ] && [ "$first" = "$code" ]; then
      say "PASS\tqa27-red\t$name\t$code"
      red_ok=$((red_ok + 1))
    else
      say "FAIL\tqa27-red\t$name\twant=$code got=$first rc=$rc"
      red_bad=$((red_bad + 1))
    fi
  done

  prod_after="$(sha256sum "$PROD" | cut -d' ' -f1)"
  fixture_after="$(fixture_digest "$FIXTURE")"
  if [ "$prod_before" = "$prod_after" ] && [ "$fixture_before" = "$fixture_after" ]; then
    say "PASS\tqa27-restore\tproduction-byte-identical\tfixture-byte-identical"
  else
    say 'FAIL\tqa27-restore\tdigest-drift'
    return 1
  fi

  local restored="$SCRATCH/restored"
  rm -rf "$restored"; mkdir -p "$restored"
  cp -R "$FIXTURE/." "$restored/"
  if [ "$(fixture_digest "$restored")" = "$fixture_before" ]; then
    say 'PASS\tqa27-restore\tstandalone-copy-match'
  else
    say 'FAIL\tqa27-restore\tstandalone-copy-mismatch'
    return 1
  fi

  if [ "$red_bad" -ne 0 ]; then
    say "FAIL\tqa27-red-green\tred_ok=$red_ok red_bad=$red_bad"
    return 1
  fi

  do_verify || return 1
  say "PASS\tqa27-red-green\tmutants=$red_ok rejected=$red_ok green_checks=$EXPECTED_CHECKS"
  return 0
}

do_corrupt() {
  compile_gate || { say 'FAIL\tqa27\tcompile'; return 1; }
  local log="$SCRATCH/corrupt.log"
  timeout 90 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate corrupt "$FIXTURE" > "$log" 2>&1 || {
    say 'FAIL\tqa27-corrupt\texit-nonzero'; cat "$log"; return 1; }
  local n; n="$(awk -F '\t' '$1=="CORRUPT" && $5=="PASS"{c++} END{print c+0}' "$log")"
  if [ "$n" -ne 8 ]; then
    say "FAIL\tqa27-corrupt\tcases=$n expected=8"
    cat "$log"
    return 1
  fi
  say "PASS\tqa27-corrupt\tcases=$n fail-closed"
  awk -F '\t' '$1=="CORRUPT"{print}' "$log"
  return 0
}

do_regen() {
  compile_gate || { say 'FAIL\tqa27\tcompile'; return 1; }
  local regen="$SCRATCH/regen"
  rm -rf "$regen"; mkdir -p "$regen"
  printf '%s\n' '* -text' '*.tsv -text' '*.txt -text' > "$regen/.gitattributes"
  timeout 90 "$JH/java" -Xmx96m -cp "$BUILD" Qa27Gate generate "$regen" > "$SCRATCH/regen.log" 2>&1 || {
    say 'FAIL\tqa27-regen\texit-nonzero'; return 1; }
  local a b
  a="$(fixture_digest "$FIXTURE")"
  b="$(fixture_digest "$regen")"
  if [ "$a" = "$b" ]; then
    say "PASS\tqa27-regen\tdigest=$a"
  else
    say 'FAIL\tqa27-regen\tdigest-mismatch'
    return 1
  fi
  return 0
}

do_privacy() {
  local report="$SCRATCH/green.log"
  if [ ! -f "$report" ]; then
    say 'FAIL\tqa27-privacy\tno-green-report'
    return 1
  fi
  python3 "$ROOT/scripts/qa27/privacy_scan.py" "$FIXTURE" "$report" 2>/dev/null
}

case "${1:-all}" in
  generate) do_generate ;;
  verify) do_verify ;;
  red-green) do_red_green ;;
  corrupt) do_corrupt ;;
  regen) do_regen ;;
  privacy) do_privacy ;;
  restore) fixture_digest "$FIXTURE" ;;
  all) do_regen && do_verify && do_corrupt && do_red_green && do_privacy ;;
  *) say 'USAGE\trun.sh\tgenerate|verify|red-green|corrupt|regen|privacy|restore|all'; exit 2 ;;
esac
