#!/usr/bin/env bash
# QA-64 bounded host gate: RED mutations -> byte-identical RESTORE -> GREEN under -Xmx96m.
set -uo pipefail

ROOT_RAW="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="$(cd "$ROOT_RAW" && (pwd -W 2>/dev/null || pwd))"
JDK="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
GRADLE_CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
SCRATCH_NATIVE="$(cygpath -m "${TMPDIR:-C:/Users/X1/AppData/Local/hermes/cache/scratch}")/qa64-run-$$"
SCRATCH_BASH="$(cygpath -u "$SCRATCH_NATIVE")"
PROD_DIR="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
FIXTURE_DIR="$ROOT/qa/fixtures/qa64"
GATE_SRC="$ROOT/scripts/qa64/Qa64HostMain.kt"
MIRROR_SRC="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa64/Qa64Assertions.kt"

nat() { cygpath -m "$1"; }
jar_one() { find "$1" -name '*.jar' -type f | sort | head -n 1; }

COMPILER="$(jar_one "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" || true)"
STDLIB="$(jar_one "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" || true)"
SCRIPT_RUNTIME="$(jar_one "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21" || true)"
TROVE="$(jar_one "$GRADLE_CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330" || true)"
COROUTINES="$(jar_one "$GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4" || true)"
ANNOTATIONS="$(jar_one "$GRADLE_CACHE/org.jetbrains/annotations/13.0" || true)"
K2_CP="$(nat "$COMPILER");$(nat "$STDLIB");$(nat "$SCRIPT_RUNTIME");$(nat "$TROVE");$(nat "$COROUTINES");$(nat "$ANNOTATIONS")"
RUN_CP="$(nat "$STDLIB");$(nat "$ANNOTATIONS")"
JAVA="$JDK/bin/java"
MUTATOR="$ROOT/scripts/qa64/mutate.py"

for needed in "$COMPILER" "$STDLIB" "$SCRIPT_RUNTIME" "$TROVE" "$COROUTINES" "$ANNOTATIONS"; do
  if [ -z "$needed" ] || [ ! -f "$needed" ]; then
    printf 'TOOLCHAIN\tBLOCKED\tkotlin-jars\n'
    exit 2
  fi
done
if [ ! -x "$JAVA" ]; then
  printf 'TOOLCHAIN\tBLOCKED\tjdk17\n'
  exit 2
fi
mkdir -p "$SCRATCH_BASH"

hash_tree() {
  (cd "$ROOT_RAW" && find scripts/qa64 qa/fixtures/qa64 app/src/androidTest -type f -print0 \
    | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}
hash_prod() {
  (cd "$ROOT_RAW" && sha256sum \
    core/src/main/kotlin/com/devtunasoft/tuneros/core/TimelineLayout.kt \
    core/src/main/kotlin/com/devtunasoft/tuneros/core/Models.kt \
    | sha256sum | cut -d' ' -f1)
}

compile_gate() {
  local gate="$1" mirror="$2" prod="$3" out="$4" log="$5"
  mkdir -p "$out"
  timeout 240s "$JAVA" -Xmx512m -cp "$K2_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$K2_CP" \
    -d "$(nat "$out")" "$(nat "$gate")" "$(nat "$mirror")" \
    "$(nat "$prod/TimelineLayout.kt")" "$(nat "$prod/Models.kt")" \
    > "$log" 2>&1
  return $?
}

run_gate() {
  local classes="$1" mode="$2" fixture="$3" out="$4"
  timeout 120s "$JAVA" -Xmx96m -cp "$(nat "$classes");$RUN_CP" \
    com.devtunasoft.tuneros.qa64.Qa64HostMain "$mode" "$(nat "$fixture")" > "$out" 2>&1
  return $?
}

first_code() { sed -n 's/^SUMMARY.*first=\([A-Z_]*\).*/\1/p' "$1" | tail -n 1; }
checks_count() { sed -n 's/^SUMMARY.*checks=\([0-9]*\).*/\1/p' "$1" | tail -n 1; }
fail_count() { sed -n 's/^SUMMARY.*fail=\([0-9]*\).*/\1/p' "$1" | tail -n 1; }
verdict() { sed -n 's/^VERDICT\t\([A-Z]*\).*/\1/p' "$1" | tail -n 1; }

echo "SCRATCH_NATIVE $SCRATCH_NATIVE"
MODE_ARG="${1:-verify}"
if [ "$MODE_ARG" != "verify" ] && [ "$MODE_ARG" != "generate" ] && [ "$MODE_ARG" != "gate-only" ]; then
  printf 'MODE\tBLOCKED\t%s\n' "$MODE_ARG"
  exit 2
fi
TREE_BEFORE="$(hash_tree)"
PROD_BEFORE="$(hash_prod)"
BASE="$SCRATCH_BASH/base"
mkdir -p "$BASE"
if ! compile_gate "$GATE_SRC" "$MIRROR_SRC" "$PROD_DIR" "$BASE/classes" "$BASE/compile.log"; then
  printf 'COMPILE\tFAIL\tbaseline\n'
  printf 'VERDICT\tFAIL\n'
  exit 1
fi
printf 'COMPILE\tPASS\tbaseline\n'

if [ "$MODE_ARG" = "generate" ]; then
  run_gate "$BASE/classes" "generate" "$FIXTURE_DIR" "$BASE/generate.log"
  rc=$?
  cat "$BASE/generate.log"
  exit "$rc"
fi
if [ "$MODE_ARG" = "gate-only" ]; then
  GREEN_ONLY="$BASE/green-only.log"
  run_gate "$BASE/classes" "verify" "$FIXTURE_DIR" "$GREEN_ONLY"
  rc=$?
  cat "$GREEN_ONLY"
  exit "$rc"
fi

RED_CASES="byte-flip:BYTE_IDENTITY manifest-flip:MANIFEST_CONSISTENT golden-value:CONTRACT_WIDTHS prod-floor:FLOOR_CASE prod-cap:SATURATION_CASE prod-require:CORRUPT_REJECT gate-privacy:PRIVACY_SCAN"
red_ok=0
restore_ok=0
red_total=0
for entry in $RED_CASES; do
  case_name="${entry%%:*}"
  expected="${entry##*:}"
  red_total=$((red_total + 1))
  if ! python3 "$MUTATOR" build "$ROOT" "$SCRATCH_NATIVE" "$case_name" > "$SCRATCH_BASH/$case_name-mutate.log" 2>&1; then
    printf 'RED\t%s\tFAIL\tmutator\n' "$case_name"
    continue
  fi
  case_dir="$SCRATCH_BASH/$case_name"
  fixture="$FIXTURE_DIR"
  mode="verify"
  classes="$BASE/classes"
  case "$case_name" in
    byte-flip|manifest-flip|golden-value)
      fixture="$case_dir/fixture"
      if [ "$case_name" != "byte-flip" ]; then mode="golden"; fi
      ;;
    prod-floor|prod-cap|prod-require|gate-privacy)
      if ! compile_gate "$case_dir/src/Qa64HostMain.kt" "$case_dir/src/Qa64Assertions.kt" \
        "$case_dir/prod" "$case_dir/classes" "$case_dir/compile.log"; then
        printf 'RED\t%s\tFAIL\tcompile\n' "$case_name"
        continue
      fi
      classes="$case_dir/classes"
      ;;
  esac
  run_gate "$classes" "$mode" "$fixture" "$case_dir/out.log"
  rc=$?
  actual="$(first_code "$case_dir/out.log")"
  if [ "$rc" -ne 0 ] && [ "$actual" = "$expected" ]; then
    printf 'RED\t%s\tPASS\tfirst=%s\trc=%s\n' "$case_name" "$actual" "$rc"
    red_ok=$((red_ok + 1))
  else
    printf 'RED\t%s\tFAIL\tfirst=%s\trc=%s\twant=%s\n' "$case_name" "$actual" "$rc" "$expected"
  fi
  if python3 "$MUTATOR" restore "$ROOT" "$SCRATCH_NATIVE" "$case_name" > "$case_dir/restore.log" 2>&1; then
    printf '%s\n' "$(cat "$case_dir/restore.log")"
    restore_ok=$((restore_ok + 1))
  else
    printf 'RESTORE\t%s\tFAIL\tverify\n' "$case_name"
  fi
done

TREE_AFTER="$(hash_tree)"
PROD_AFTER="$(hash_prod)"
if [ "$TREE_BEFORE" = "$TREE_AFTER" ] && [ "$PROD_BEFORE" = "$PROD_AFTER" ]; then
  printf 'RESTORE\tALL\tPASS\towned=%s\tprod=%s\n' "$TREE_BEFORE" "$PROD_AFTER"
else
  printf 'RESTORE\tALL\tFAIL\towned_before=%s\towned_after=%s\n' "$TREE_BEFORE" "$TREE_AFTER"
fi

GREEN_OUT="$BASE/green.log"
run_gate "$BASE/classes" "verify" "$FIXTURE_DIR" "$GREEN_OUT"
green_rc=$?
green_verdict="$(verdict "$GREEN_OUT")"
green_checks="$(checks_count "$GREEN_OUT")"
green_fails="$(fail_count "$GREEN_OUT")"
if [ "$green_rc" -eq 0 ] && [ "$green_verdict" = "PASS" ] && [ "$green_fails" = "0" ] && [ "$green_checks" = "16" ]; then
  printf 'GREEN\tPASS\tchecks=%s\tfail=%s\tverdict=%s\n' "$green_checks" "$green_fails" "$green_verdict"
  grep -a '^BOUNDS' "$GREEN_OUT" || true
  grep -a '^PRIVACY_SCAN' "$GREEN_OUT" || true
  green_ok=1
else
  printf 'GREEN\tFAIL\trc=%s\tchecks=%s\tfail=%s\tverdict=%s\n' "$green_rc" "$green_checks" "$green_fails" "$green_verdict"
  green_ok=0
fi

if [ "$red_ok" -eq "$red_total" ] && [ "$restore_ok" -eq "$red_total" ] && \
   [ "$TREE_BEFORE" = "$TREE_AFTER" ] && [ "$PROD_BEFORE" = "$PROD_AFTER" ] && \
   [ "$green_ok" -eq 1 ]; then
  printf 'VERDICT\tPASS\tred=%s/%s\tgreen_checks=%s\n' "$red_ok" "$red_total" "$green_checks"
  exit 0
fi
printf 'VERDICT\tFAIL\tred=%s/%s\tgreen=%s\n' "$red_ok" "$red_total" "$green_ok"
exit 1
