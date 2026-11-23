#!/usr/bin/env bash
# QA-25 bounded host gate runner. Offline, zero dependencies, -Xmx96m.
set -euo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURE="$ROOT/qa/fixtures/qa25"

SCRATCH="${QA25_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa25}"
MODE="${1:-verify}"

if command -v javac >/dev/null 2>&1; then
  JDK_BIN="$(dirname "$(command -v javac)")"
else
  JDK_BIN=/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin
fi
export PATH="$JDK_BIN:$PATH"
export JAVA_HOME="$(cd "$JDK_BIN/.." && native_pwd)"

mkdir -p "$SCRATCH"
BUILD="$SCRATCH/build-$MODE"
mkdir -p "$BUILD"
timeout 120 javac -encoding UTF-8 -d "$BUILD" "$HERE/Qa25Gate.java" >/dev/null

run_gate() {
  timeout 120 java -Xmx96m -cp "$BUILD" Qa25Gate "$@"
}

aggregate() {
  local d="$1"
  (cd "$d" && sha256sum .gitattributes catalog.tsv contract.txt corrupt.tsv \
      dimensions.tsv manifest.sha256 pages.tsv) | sha256sum | cut -d' ' -f1
}

case "$MODE" in
  generate)
    run_gate generate "$FIXTURE"
    printf 'GENERATE\tPASS\tsha256=%s\n' "$(aggregate "$FIXTURE")"
    ;;
  verify)
    OUT="$(run_gate verify "$FIXTURE")" || RC=$?
    RC="${RC:-0}"
    printf '%s\n' "$OUT"
    printf 'HOST\t%s\trc=%s\n' "$([ "$RC" -eq 0 ] && printf PASS || printf FAIL)" "$RC"
    exit "$RC"
    ;;
  red-green)
    PRISTINE="$(aggregate "$FIXTURE")"
    printf 'PRISTINE\tsha256=%s\n' "$PRISTINE"
    REPORT="$SCRATCH/qa25-report.txt"
    : > "$REPORT"
    ALL_OK=1
    CASES="byte_flip:BYTE_IDENTITY header_typo:HEADER_STRICT truncated_row:ROW_BOUNDS \
limit_over_cap:PAGE_LIMIT_BOUNDS id_reuse:ID_IMMUTABLE protection_flip:PROTECTION_PRESERVED \
order_drift:RECENT_ORDER privacy_leak:PRIVACY_SCAN"
    for pair in $CASES; do
      case_name="${pair%%:*}"
      expected="${pair##*:}"
      gatedir="$SCRATCH/mutant-$case_name"
      rm -rf "$gatedir"
      mkdir -p "$gatedir"
      run_gate mutate "$FIXTURE" "$gatedir" "$case_name" >> "$REPORT" 2>&1
      gmode=verify
      [ "$case_name" != byte_flip ] && gmode=verify-semantic
      set +e
      OUT="$(run_gate "$gmode" "$gatedir" 2>&1)"
      RC=$?
      set -e
      printf '%s\n' "$OUT" >> "$REPORT"
      FIRST="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="FIRST" {print $2; exit}')"
      if [ "$RC" -ne 0 ] && [ "$FIRST" = "$expected" ]; then
        printf 'MUTANT\tPASS\t%s\tfirst=%s\n' "$case_name" "$FIRST"
      else
        printf 'MUTANT\tFAIL\t%s\trc=%s\tfirst=%s\twant=%s\n' "$case_name" "$RC" "$FIRST" "$expected"
        ALL_OK=0
      fi
    done

    RESTORE_DIR="$SCRATCH/restore"
    rm -rf "$RESTORE_DIR"
    mkdir -p "$RESTORE_DIR"
    (cd "$FIXTURE" && tar -cf - .) | (cd "$RESTORE_DIR" && tar -xf -)
    RESTORED="$(aggregate "$RESTORE_DIR")"
    if [ "$RESTORED" = "$PRISTINE" ]; then
      printf 'RESTORE\tPASS\tsha256=%s\n' "$RESTORED"
    else
      printf 'RESTORE\tFAIL\tsha256=%s\twant=%s\n' "$RESTORED" "$PRISTINE"
      ALL_OK=0
    fi

    set +e
    OUT="$(run_gate verify "$FIXTURE" 2>&1)"
    RC=$?
    set -e
    printf '%s\n' "$OUT"
    PASSES="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="CHECK" && $2=="PASS" {n++} END{print n+0}')"
    FAILS="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="CHECK" && $2=="FAIL" {n++} END{print n+0}')"
    SUMMARY="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="SUMMARY" {print $2"\t"$3"\t"$4"\t"$5; exit}')"
    printf 'GREEN\trc=%s\tpasses=%s\tfails=%s\t%s\n' "$RC" "$PASSES" "$FAILS" "$SUMMARY"
    if [ "$RC" -ne 0 ] || [ "$PASSES" -ne 15 ] || [ "$FAILS" -ne 0 ]; then ALL_OK=0; fi

    python3 "$HERE/privacy_scan.py" "$REPORT" "$FIXTURE"
    PRIV_RC=$?
    [ "$PRIV_RC" -eq 0 ] || ALL_OK=0

    printf 'RED_GREEN\t%s\tmutants=8\tchecks=15\n' "$([ "$ALL_OK" -eq 1 ] && printf PASS || printf FAIL)"
    [ "$ALL_OK" -eq 1 ]
    ;;
  restore)
    PRISTINE="$(aggregate "$FIXTURE")"
    RESTORE_DIR="$SCRATCH/restore-only"
    rm -rf "$RESTORE_DIR"
    mkdir -p "$RESTORE_DIR"
    (cd "$FIXTURE" && tar -cf - .) | (cd "$RESTORE_DIR" && tar -xf -)
    RESTORED="$(aggregate "$RESTORE_DIR")"
    printf 'RESTORE\t%s\tsha256=%s\n' "$([ "$RESTORED" = "$PRISTINE" ] && printf PASS || printf FAIL)" "$RESTORED"
    [ "$RESTORED" = "$PRISTINE" ]
    ;;
  *)
    printf 'USAGE\tgenerate|verify|red-green|restore\n' >&2
    exit 2
    ;;
esac
