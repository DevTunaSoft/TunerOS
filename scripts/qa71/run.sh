#!/usr/bin/env bash
# QA-71 bounded runner: regeneration -> RED mutants -> restore -> GREEN -> privacy -> mirror -> AGP.
set -uo pipefail

say() { printf '%b\n' "$1"; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA_HOME="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"
JAVA="$JAVA_HOME/bin/java"
SCRATCH="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa71_run"
BASE="$SCRATCH/base"
FIXTURE="$ROOT/qa/fixtures/qa71"
PROD="$ROOT/app/src/main/java/com/devtunasoft/tuneros/RecordingCatalog.kt"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa71/Qa71Contract.kt"
COMPILE="$ROOT/scripts/qa71/compile.sh"
MUTATOR="$ROOT/scripts/qa71/mutate.py"
PRIVACY="$ROOT/scripts/qa71/privacy.py"
STDLIB="$(find C:/Users/X1/.gradle/caches/modules-2/files-2.1 -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
MODE="${1:-full}"

if [ ! -x "$JAVA" ] || [ -z "$STDLIB" ]; then
  say 'RUNNER\tBLOCKED\tmissing-jdk-or-stdlib'
  exit 2
fi

aggregate() {
  (cd "$1" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
}

gate_run() {
  local gate_mode="$1"; local fixture="$2"; local report="$3"; local classpath="$4"
  gate_run_rc=0
  timeout 150 "$JAVA" -Xmx96m -cp "$classpath" \
    com.devtunasoft.tuneros.qa71.Qa71GateKt "$gate_mode" "$fixture" > "$report" 2>&1 || gate_run_rc=$?
  gate_first="$(awk -F '\t' '$1=="FIRST"{v=$2} END{print v}' "$report")"
  gate_checks="$(sed -nE 's/^SUMMARY\tchecks=([0-9]+).*/\1/p' "$report" | tail -1)"
  gate_pass="$(sed -nE 's/.*\tpass=([0-9]+).*/\1/p' "$report" | tail -1)"
  gate_fail="$(sed -nE 's/.*\tfail=([0-9]+).*/\1/p' "$report" | tail -1)"
}

expect_red() {
  local name="$1"; local expected="$2"; local gate_mode="$3"; local fixture="$4"; local classpath="$5"
  local report="$SCRATCH/red-$name.report"
  gate_run "$gate_mode" "$fixture" "$report" "$classpath"
  if [ "$gate_run_rc" -eq 0 ]; then
    say "RED\t$name\tWRONG\tstayed-green"
    return 1
  fi
  if [ "$gate_first" != "$expected" ]; then
    say "RED\t$name\tWRONG_CODE\tgot=$gate_first want=$expected"
    return 1
  fi
  say "RED\t$name\tPASS\tfirst=$gate_first\trc=$gate_run_rc"
  return 0
}

mkdir -p "$SCRATCH"
rm -rf "$BASE" "$SCRATCH/regen" "$SCRATCH/red-"* "$SCRATCH/mut-"* "$SCRATCH/fix-"*
if ! bash "$COMPILE" "$BASE" "$CONTRACT" "$ROOT/scripts/qa71/stubs/"*.kt \
  "$ROOT/scripts/qa71/Qa71Gate.kt" "$PROD" | grep -q 'COMPILE.*PASS'; then
  say 'COMPILE\tFAIL\tbaseline'
  exit 1
fi
say 'COMPILE\tPASS\tbaseline'
RUNTIME_CP="$BASE;$STDLIB"

PROD_SHA_BEFORE="$(sha256sum "$PROD" | awk '{print $1}')"
FIXTURE_SHA_BEFORE="$(aggregate "$FIXTURE")"
OWNED_BEFORE="$(find "$ROOT/scripts/qa71" "$ROOT/qa/fixtures/qa71" "$ROOT/app/src/androidTest" \
  -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"

# REGEN: byte-identical fixture regeneration into scratch.
gate_run generate "$SCRATCH/regen" "$SCRATCH/regen.report" "$RUNTIME_CP"
REGEN_AGG="$(aggregate "$SCRATCH/regen")"
if [ "$gate_run_rc" -eq 0 ] && [ "$REGEN_AGG" = "$FIXTURE_SHA_BEFORE" ]; then
  say "REGEN\tPASS\taggregate=$REGEN_AGG"
else
  say "REGEN\tFAIL\tscratch=$REGEN_AGG committed=$FIXTURE_SHA_BEFORE"
  exit 1
fi

verdict="PASS"
MUTATOR_NAMES="guard-limit conflict-replace order-asc interrupt-scope"
for kind in $MUTATOR_NAMES; do
  src="$SCRATCH/mut-$kind/RecordingCatalog.kt"
  mkdir -p "$SCRATCH/mut-$kind"
  if ! python3 "$MUTATOR" prod "$PROD" "$src" "$kind" > "$SCRATCH/mutator-$kind.log" 2>&1; then
    say "RED\t$kind\tFAIL\tmutator-did-not-apply"
    verdict="FAIL"
    continue
  fi
  if ! bash "$COMPILE" "$SCRATCH/mut-$kind/classes" "$CONTRACT" \
    "$ROOT/scripts/qa71/stubs/"*.kt "$ROOT/scripts/qa71/Qa71Gate.kt" "$src" \
    > "$SCRATCH/mut-$kind/compile.log" 2>&1; then
    say "RED\t$kind\tFAIL\tmutant-compile"
    verdict="FAIL"
    continue
  fi
  case "$kind" in
    guard-limit) expected_code="INVALID_LIMIT_REJECT" ;;
    conflict-replace) expected_code="INSERT_ADMISSION" ;;
    order-asc) expected_code="RECENT_ORDER" ;;
    interrupt-scope) expected_code="INTERRUPT_SCOPE" ;;
  esac
  expect_red "$kind" "$expected_code" verify "$FIXTURE" \
    "$SCRATCH/mut-$kind/classes;$BASE;$STDLIB" || verdict="FAIL"
done

# Fixture-level controls, each with its own disjoint verifier mode.
if python3 "$MUTATOR" fixture "$FIXTURE" "$SCRATCH/fix-manifest-byte" manifest-byte \
    > "$SCRATCH/fix-manifest-byte.log" 2>&1; then
  expect_red manifest-byte MANIFEST_CONSISTENT verify-manifest \
    "$SCRATCH/fix-manifest-byte" "$RUNTIME_CP" || verdict="FAIL"
else
  say 'RED\tmanifest-byte\tFAIL\tmutator-did-not-apply'; verdict="FAIL"
fi
if python3 "$MUTATOR" fixture "$FIXTURE" "$SCRATCH/fix-pinned-digest" pinned-digest \
    > "$SCRATCH/fix-pinned-digest.log" 2>&1; then
  expect_red pinned-digest PINNED_DIGEST verify-pinned \
    "$SCRATCH/fix-pinned-digest" "$RUNTIME_CP" || verdict="FAIL"
else
  say 'RED\tpinned-digest\tFAIL\tmutator-did-not-apply'; verdict="FAIL"
fi
if python3 "$MUTATOR" fixture "$FIXTURE" "$SCRATCH/fix-expected-row" expected-row \
    > "$SCRATCH/fix-expected-row.log" 2>&1; then
  expect_red expected-row FIXTURE_ORACLE verify-semantic \
    "$SCRATCH/fix-expected-row" "$RUNTIME_CP" || verdict="FAIL"
else
  say 'RED\texpected-row\tFAIL\tmutator-did-not-apply'; verdict="FAIL"
fi

# RESTORE: recopy scratch production and fixtures, then verify every byte.
restore_ok=1
for kind in $MUTATOR_NAMES; do
  cp "$PROD" "$SCRATCH/mut-$kind/RecordingCatalog.kt"
  cmp -s "$PROD" "$SCRATCH/mut-$kind/RecordingCatalog.kt" || restore_ok=0
done
for name in manifest-byte pinned-digest expected-row; do
  for file in cases.tsv corrupt.tsv dimensions.txt expected.txt sha256.txt .gitattributes; do
    cp "$FIXTURE/$file" "$SCRATCH/fix-$name/$file"
    cmp -s "$FIXTURE/$file" "$SCRATCH/fix-$name/$file" || restore_ok=0
  done
done
PROD_SHA_AFTER="$(sha256sum "$PROD" | awk '{print $1}')"
FIXTURE_SHA_AFTER="$(aggregate "$FIXTURE")"
OWNED_AFTER="$(find "$ROOT/scripts/qa71" "$ROOT/qa/fixtures/qa71" "$ROOT/app/src/androidTest" \
  -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
if [ "$restore_ok" -eq 1 ] && [ "$PROD_SHA_BEFORE" = "$PROD_SHA_AFTER" ] &&
   [ "$FIXTURE_SHA_BEFORE" = "$FIXTURE_SHA_AFTER" ] && [ "$OWNED_BEFORE" = "$OWNED_AFTER" ]; then
  say "RESTORE\tALL\tBYTE_IDENTICAL\tprod=$PROD_SHA_AFTER"
else
  say 'RESTORE\tFAIL\tscratch-or-owned-tree-drift'
  verdict="FAIL"
fi

# GREEN: unchanged tree, bounded heap, zero thread drift.
gate_run verify "$FIXTURE" "$SCRATCH/green.report" "$RUNTIME_CP"
threads_before="$(awk -F '\t' '$1=="THREADS"{a=$2; b=$3} END{print a":"b}' "$SCRATCH/green.report")"
if [ "$gate_run_rc" -eq 0 ] && [ "$gate_checks" = "15" ] && [ "$gate_pass" = "15" ] &&
   [ "$gate_fail" = "0" ] && [ "$gate_first" = "NONE" ] &&
   [ "${threads_before%%:*}" = "${threads_before##*:}" ]; then
  say "GREEN\tPASS\tchecks=$gate_checks\tpass=$gate_pass\tfail=$gate_fail\tfirst=$gate_first\tthreads=$threads_before"
else
  say "GREEN\tFAIL\trc=$gate_run_rc\tchecks=$gate_checks\tpass=$gate_pass\tfail=$gate_fail\tfirst=$gate_first"
  verdict="FAIL"
fi

# PRIVACY: generic patterns plus the derived marker, with a live positive control.
python3 "$PRIVACY" "$SCRATCH/green.report" > "$SCRATCH/privacy.report" 2>&1
privacy_rc=$?
grep -E '^(PRIVACY|CONTROL|PRIVACY_HIT)' "$SCRATCH/privacy.report" || true
if [ "$privacy_rc" -ne 0 ]; then verdict="FAIL"; fi

# JVM mirror of the shared Android-free assertions.
gate_run mirror "$FIXTURE" "$SCRATCH/mirror.report" "$RUNTIME_CP"
mirror_pass="$(sed -nE 's/^SUMMARY\tchecks=8\tpass=([0-9]+).*/\1/p' "$SCRATCH/mirror.report" | tail -1)"
mirror_fail="$(sed -nE 's/^SUMMARY\tchecks=8\tpass=[0-9]+\tfail=([0-9]+).*/\1/p' "$SCRATCH/mirror.report" | tail -1)"
if [ "$gate_run_rc" -eq 0 ] && [ "$gate_first" = "NONE" ] &&
   [ "$mirror_pass" = "8" ] && [ "$mirror_fail" = "0" ]; then
  say "MIRROR\tPASS\tpass=$mirror_pass\tfail=$mirror_fail"
else
  say "MIRROR\tFAIL\trc=$gate_run_rc\tpass=$mirror_pass\tfail=$mirror_fail"
  verdict="FAIL"
fi

# Offline AGP compile/assembly. No device execution is attempted.
SDK=""
for candidate in C:/Android/Sdk "$SCRATCH/../android-sdk-mv03"; do
  if [ -f "$candidate/platforms/android-35/android.jar" ]; then SDK="$candidate"; break; fi
done
APK_BYTES="NA"
if [ -n "$SDK" ]; then
  export ANDROID_HOME="$(cygpath -m "$SDK")"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  (cd "$ROOT" && timeout 900 "$JAVA_HOME/bin/java" -Dorg.gradle.daemon=false \
    -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain \
    --no-daemon --offline :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest) \
    > "$SCRATCH/agp.log" 2>&1
  agp_rc=$?
  apk="$(find "$ROOT/app/build/outputs/apk/androidTest" -type f -name '*.apk' 2>/dev/null | head -1)"
  if [ -n "$apk" ]; then APK_BYTES="$(wc -c < "$apk" | tr -d ' ')"; fi
  if [ "$agp_rc" -eq 0 ] && [ -n "$apk" ]; then
    say "AGP\tPASS\tapk_bytes=$APK_BYTES"
  else
    say "AGP\tFAIL\trc=$agp_rc\tapk_bytes=$APK_BYTES"
  fi
else
  say 'AGP\tBLOCKED\tno-android-35-sdk'
fi

ADB=""
if [ -n "$SDK" ]; then
  for adb in "$SDK/platform-tools/adb.exe" "$SDK/platform-tools/adb"; do
    if [ -x "$adb" ]; then ADB="$adb"; break; fi
  done
fi
if [ -n "$ADB" ] && timeout 20 "$ADB" devices 2>/dev/null \
    | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' > /dev/null; then
  say 'DEVICE\tNOT RUN\ttarget-present-but-instrumentation-not-executed'
else
  say 'DEVICE\tBLOCKED\tno-authorized-adb-or-emulator-target'
fi

say "RUNNER\tVERDICT\t$verdict"
[ "$verdict" = "PASS" ]
