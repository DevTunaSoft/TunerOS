#!/usr/bin/env bash
# QA-16 bounded, offline, zero-new-dependency host gate for cancellable guide-import staging.
#   run.sh generate     regenerate the pinned scratch fixture in the repository fixture directory
#   run.sh verify       RED fixture mutants, RED source mutants, byte-identical restore, GREEN gate,
#                       JVM mirror (default)
#   run.sh red-green    alias for verify
#   run.sh gate-only    canonical GREEN gate without mutant controls
# No Gradle, network, Android device, credential or real media is used here.
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] \
    && [ "$MODE" != red-green ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_path() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
GATE="$HERE/Qa16Gate.java"
STUBS="$HERE/stubs"
FIX="$ROOT/qa/fixtures/qa16"
ASSERT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa16/Qa16Assertions.kt"
INSTR="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa16/Qa16Instrumentation.kt"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
for tool in "$JH/javac" "$JH/java" grep awk find sed sha256sum cmp timeout cygpath; do
  if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
    say "BLOCKED\tqa16-no-tool\t$tool"
    exit 2
  fi
done
export JAVA_HOME="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"

BUILD="$(native_path "$HOME/AppData/Local/hermes/cache/scratch/qa16-build")"
if [ "$MODE" = gate-only ]; then BUILD="${BUILD}-gateonly"; fi
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/scratch" "$BUILD/mirror" "$BUILD/stubs"

if ! timeout 180 "$JH/javac" -encoding UTF-8 -d "$BUILD/classes" "$GATE" > "$BUILD/compile-host.log" 2>&1; then
  say 'FAIL\tqa16-compile-host\tsee scratch qa16-build/compile-host.log'
  exit 2
fi
say 'COMPILE\tPASS\tqa16-host-gate\tjavac17'

if [ "$MODE" = generate ]; then
  timeout 120 "$JH/java" -Xmx64m -cp "$BUILD/classes" Qa16Gate generate "$FIX"
  say "PIN\tgenerate\t$(awk -F '\t' '$1 == "snapshot" { print $2; exit }' "$FIX/sha256.txt")"
  exit 0
fi

run_gate() {
  local classes="$1" fixture="$2" scratch="$3" report="$4"
  shift 4
  mkdir -p "$scratch"
  if timeout 240 "$JH/java" -Xmx64m -cp "$classes" Qa16Gate verify \
      "$fixture" "$scratch" "$@" > "$report" 2>&1; then
    return 0
  fi
  return 1
}

first_code() { awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$1"; }

if [ "$MODE" = gate-only ]; then
  REPORT="$BUILD/gate-only-report.txt"
  if run_gate "$BUILD/classes" "$FIX" "$BUILD/scratch" "$REPORT"; then
    tail -n 3 "$REPORT"
    say 'GATE-ONLY\tPASS'
    exit 0
  fi
  tail -n 12 "$REPORT"
  say 'GATE-ONLY\tFAIL'
  exit 1
fi

ORIGINAL_SHA=$(sha256sum "$GATE" "$ASSERT" "$INSTR" "$FIX"/* | sha256sum | awk '{ print $1 }')

# --- RED controls 1: on-disk fixture mutants. Each mutated fixture is written to scratch only.
KINDS=(byte-flip bad-header row-count seq-gap leak snapshot-drift cancel-publish corrupt-accept dimensions-drift)
CODES=(BYTE_IDENTITY HEADER_STRICT ROW_BOUNDS SEQ_CONTIGUOUS PRIVACY_FIXTURE SNAPSHOT_DIGEST CANCEL_NO_PUBLISH CORRUPT_FAIL_CLOSED DIMENSIONS)
for index in "${!KINDS[@]}"; do
  kind="${KINDS[$index]}"
  expected="${CODES[$index]}"
  work="$BUILD/red-fixture/$kind"
  mkdir -p "$work/fixture" "$work/scratch"
  if ! timeout 120 "$JH/java" -Xmx64m -cp "$BUILD/classes" Qa16Gate mutate \
      "$FIX" "$work/fixture" "$kind" > "$work/mutate.log" 2>&1; then
    say "FAIL\tqa16-mutate-$kind\tsee scratch qa16-build"
    exit 1
  fi
  extra=()
  if [ "$kind" != byte-flip ]; then extra=(skipIdentity); fi
  if run_gate "$BUILD/classes" "$work/fixture" "$work/scratch" "$work/report.txt" "${extra[@]}"; then
    say "FAIL\tqa16-red-fixture-$kind\tmutant unexpectedly GREEN"
    exit 1
  fi
  actual=$(first_code "$work/report.txt")
  if [ "$actual" != "$expected" ]; then
    say "FAIL\tqa16-red-fixture-$kind\tfirst=$actual expected=$expected"
    exit 1
  fi
  say "RED\tfixture-$kind\t$actual"
done

# --- RED controls 2: source mutants on scratch copies; the committed gate is never edited.
S_KINDS=(staging journal snapshot cancel privacy)
S_CODES=(STAGING_BOUND JOURNAL_BOUND SNAPSHOT_DIGEST CANCEL_NO_PUBLISH PRIVACY_REPORT)
S_FROM=(
  'require(metrics.stagingPeak <= STAGING_ROWS, "STAGING_BOUND");'
  'require(metrics.journalPeak <= MAX_JOURNAL_BYTES, "JOURNAL_BOUND");'
  'require(manifest.get("snapshot").equals(snapshotDigest), "SNAPSHOT_DIGEST");'
  'if (attempt.cancelAt == chunk) {'
  'require(!containsForbidden(rendered), "PRIVACY_REPORT");'
)
S_TO=(
  'require(metrics.stagingPeak <= -1, "STAGING_BOUND");'
  'require(metrics.journalPeak <= -1, "JOURNAL_BOUND");'
  'require("wrong".equals(snapshotDigest), "SNAPSHOT_DIGEST");'
  'if (false) {'
  'require(false, "PRIVACY_REPORT");'
)
for index in "${!S_KINDS[@]}"; do
  kind="${S_KINDS[$index]}"
  expected="${S_CODES[$index]}"
  from="${S_FROM[$index]}"
  to="${S_TO[$index]}"
  hits=$(grep -F -c "$from" "$GATE")
  if [ "$hits" -ne 1 ]; then
    say "FAIL\tqa16-source-pattern-$kind\thits=$hits"
    exit 1
  fi
  work="$BUILD/red-source/$kind"
  mkdir -p "$work/src" "$work/classes" "$work/scratch"
  cp "$GATE" "$work/src/Qa16Gate.java"
  sed -i "s|$from|$to|" "$work/src/Qa16Gate.java"
  if cmp -s "$GATE" "$work/src/Qa16Gate.java"; then
    say "FAIL\tqa16-source-mutant-$kind\tno-source-change"
    exit 1
  fi
  if ! timeout 180 "$JH/javac" -encoding UTF-8 -d "$work/classes" "$work/src/Qa16Gate.java" \
      > "$work/compile.log" 2>&1; then
    say "FAIL\tqa16-source-compile-$kind\tsee scratch qa16-build"
    exit 1
  fi
  if run_gate "$work/classes" "$FIX" "$work/scratch" "$work/report.txt"; then
    say "FAIL\tqa16-red-source-$kind\tmutant unexpectedly GREEN"
    exit 1
  fi
  actual=$(first_code "$work/report.txt")
  if [ "$actual" != "$expected" ]; then
    say "FAIL\tqa16-red-source-$kind\tfirst=$actual expected=$expected"
    exit 1
  fi
  say "RED\tsource-$kind\t$actual"
done

AFTER_SHA=$(sha256sum "$GATE" "$ASSERT" "$INSTR" "$FIX"/* | sha256sum | awk '{ print $1 }')
if [ "$ORIGINAL_SHA" != "$AFTER_SHA" ]; then
  say 'FAIL\tqa16-restore\tcommitted fixtures or sources changed by mutants'
  exit 1
fi
say "RESTORE\tPASS\tbyte-identical sha=$AFTER_SHA"

# --- GREEN canonical gate.
GREEN="$BUILD/green-report.txt"
if ! run_gate "$BUILD/classes" "$FIX" "$BUILD/scratch" "$GREEN"; then
  say 'FAIL\tqa16-green\tcanonical gate not GREEN'
  grep -E '^CHECK\tFAIL|^FIRST|^SUMMARY' "$GREEN" | head -n 12
  exit 1
fi
if [ "$(first_code "$GREEN")" != NONE ] || grep -q '^CHECK.FAIL' "$GREEN"; then
  say 'FAIL\tqa16-green-report\tunexpected failure line'
  exit 1
fi
POS=$(awk -F '\t' '$1 == "SUMMARY" { print $2; exit }' "$GREEN" | sed 's/positive=//')
FAILED=$(awk -F '\t' '$1 == "SUMMARY" { print $3; exit }' "$GREEN" | sed 's/failed=//')
if [ "$POS" -lt 31 ] || [ "$FAILED" -ne 0 ]; then
  say "FAIL\tqa16-green-counts\tpositive=$POS failed=$FAILED"
  exit 1
fi
HEAP=$(awk -F '\t' '$1 == "CHECK" && $3 == "HEAP_BOUND" { print $4; exit }' "$GREEN")
THREADS=$(awk -F '\t' '$1 == "CHECK" && $3 == "THREAD_BOUND" { print $4; exit }' "$GREEN")
say "GREEN\tPASS\tpositive=$POS\tfailed=$FAILED\theap=$HEAP\tthreads=$THREADS"

# --- Kotlin mirror: stub typecheck, JVM compile and deterministic execution.
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' \
       -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB_RAW" ]; then
  say 'BLOCKED\tqa16-no-kotlin\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"
STUB_N=$(native_path "$BUILD/stubs")
MIRROR_N=$(native_path "$BUILD/mirror")
STUB_SOURCES=()
while IFS= read -r -d '' stub; do STUB_SOURCES+=("$(native_path "$stub")"); done < <(find "$STUBS" -name '*.java' -print0)
if ! timeout 90 "$JH/javac" -encoding UTF-8 -d "$STUB_N" "${STUB_SOURCES[@]}" > "$BUILD/stubs.log" 2>&1; then
  say 'FAIL\tqa16-mirror-stubs\tjavac'
  exit 1
fi
if ! timeout 180 "$JH/java" -Xmx768m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn -classpath "$COMPILE_CP$STUB_N" -d "$MIRROR_N" \
    "$(native_path "$ASSERT")" "$(native_path "$INSTR")" > "$BUILD/mirror-compile.log" 2>&1; then
  say 'FAIL\tqa16-mirror-compile\tsee scratch qa16-build/mirror-compile.log'
  exit 1
fi
if ! timeout 120 "$JH/java" -Xmx64m -cp "$MIRROR_N;$STDLIB" com.devtunasoft.tuneros.qa16.Qa16Assertions \
    > "$BUILD/mirror-run.txt" 2>&1; then
  say 'FAIL\tqa16-mirror-run\tsee scratch qa16-build/mirror-run.txt'
  exit 1
fi
if grep -E '://|Bearer |token=|pin=|secret=|password' "$BUILD/mirror-run.txt" >/dev/null 2>&1; then
  say 'FAIL\tqa16-mirror-privacy\tsensitive marker in mirror report'
  exit 1
fi
MIRROR_PASS=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
MIRROR_FAIL=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
MIRROR_DIGEST=$(awk -F '\t' '$1 == "DIGEST" { print $2; exit }' "$BUILD/mirror-run.txt")
PINNED_DIGEST=$(awk -F '\t' '$1 == "snapshot" { print $2; exit }' "$FIX/sha256.txt")
ASSERT_DIGEST=$(sed -n 's/.*EXPECTED_SNAPSHOT = "\([^"]*\)".*/\1/p' "$ASSERT")
if [ "$MIRROR_FAIL" -ne 0 ] || [ "$MIRROR_PASS" -lt 15 ] \
    || [ "$MIRROR_DIGEST" != "$PINNED_DIGEST" ] || [ "$ASSERT_DIGEST" != "$PINNED_DIGEST" ]; then
  say "FAIL\tqa16-mirror-checks\tpass=$MIRROR_PASS fail=$MIRROR_FAIL pin=$ASSERT_DIGEST"
  exit 1
fi
say "MIRROR\tPASS\tchecks=$MIRROR_PASS\tdigest=$PINNED_DIGEST"

ADB=""
for candidate in \
    "$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03/platform-tools/adb.exe" \
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
  if [ -x "$candidate" ]; then ADB="$candidate"; break; fi
done
if [ -n "$ADB" ] && timeout 20 "$ADB" devices 2>/dev/null \
    | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' >/dev/null; then
  say 'DEVICE\tNOT RUN\tno authorized device execution in QA-16; on-device verdict UNVERIFIED'
else
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target'
fi
say 'VERDICT\tPASS\tQA-16 host gate complete; device execution NOT RUN/UNVERIFIED'
exit 0
