#!/usr/bin/env bash
# QA-15 bounded, offline, zero-new-dependency host gate for repeated large EPG import.
#   run.sh generate     regenerate pinned manifest + scratch fixture, pin mirror digest
#   run.sh verify       RED source mutants, then GREEN host gate and JVM mirror (default)
#   run.sh red-green    alias for verify
# Gradle is not used here; the real pure-Kotlin core sources are compiled with the
# cached Kotlin 2.0.21 compiler. No network, device, credential or real media is used.
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] && [ "$MODE" != red-green ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
native_dir() { (cd "$1" && native_pwd); }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa15"
GATE="$HERE/Qa15Gate.kt"
FIXTURE_SRC="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa15/Qa15Fixture.kt"
ASSERTIONS="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa15/Qa15Assertions.kt"
INSTRUMENTATION="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa15/Qa15Instrumentation.kt"
STUBS="$HERE/stubs"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
for tool in "$JH/javac" "$JH/java" timeout grep sed awk find sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
    say "BLOCKED\tqa15-no-tool\t$tool"
    exit 2
  fi
done

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
  say 'BLOCKED\tqa15-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa15-build$([ "$MODE" = gate-only ] && printf -- '-gateonly')"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/mirror" "$BUILD/stubs" "$BUILD/scratch"
BUILD_N="$(native_dir "$BUILD")"
FIX_N="$(native_dir "$FIXTURES")"

mapfile -t CORE_SOURCES < <(find "$ROOT/core/src/main/kotlin" -type f -name '*.kt' | sort)
CORE_NATIVE=()
for source in "${CORE_SOURCES[@]}"; do CORE_NATIVE+=("$(native_path "$source")"); done
if [ "${#CORE_NATIVE[@]}" -lt 10 ]; then
  say 'BLOCKED\tqa15-core-sources\tno Kotlin core sources'
  exit 2
fi

compile_kotlin() {
  local out="$1"; shift
  local out_n; out_n="$(native_dir "$out")"
  mkdir -p "$out"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$out_n" -d "$out_n" "$@"
}

run_gate() {
  local classes="$1" scratch="$2" report="$3"
  local classes_n scratch_n
  classes_n="$(native_dir "$classes")"
  scratch_n="$(native_dir "$scratch")"
  if timeout 240 "$JH/java" -Xmx96m -cp "$classes_n;$STDLIB" \
      com.devtunasoft.tuneros.qa15.Qa15Gate verify "$FIX_N" "$scratch_n" > "$report" 2>&1; then
    return 0
  fi
  return 1
}

if ! compile_kotlin "$BUILD/classes" "${CORE_NATIVE[@]}" \
    "$(native_path "$FIXTURE_SRC")" "$(native_path "$GATE")" > "$BUILD/compile-host.log" 2>&1; then
  say 'FAIL\tqa15-compile-host\tsee scratch qa15-build/compile-host.log'
  exit 2
fi
say 'COMPILE\tPASS\tqa15-host-gate\tkotlin2.0.21+jvm17'

if [ "$MODE" = gate-only ]; then
  REPORT="$BUILD/gate-only-report.txt"
  if run_gate "$BUILD/classes" "$BUILD/scratch" "$REPORT"; then
    tail -n 3 "$REPORT"
    say 'GATE-ONLY\tPASS'
    exit 0
  fi
  tail -n 12 "$REPORT"
  say 'GATE-ONLY\tFAIL'
  exit 1
fi

if [ "$MODE" = generate ]; then
  GEN_N="$BUILD_N/generated-fixture.xmltv"
  timeout 120 "$JH/java" -Xmx96m -cp "$(native_dir "$BUILD/classes");$STDLIB" \
    com.devtunasoft.tuneros.qa15.Qa15Gate generate "$FIX_N" "$GEN_N"
  sed -i 's/\r$//' "$FIXTURES/manifest.txt"
  SIDE=$(awk -F= '$1 == "sidecar-sha256" { print $2; exit }' "$FIXTURES/manifest.txt")
  sed -i "s/private const val EXPECTED_SIDECAR = \"[^\"]*\"/private const val EXPECTED_SIDECAR = \"$SIDE\"/" "$ASSERTIONS"
  say "PIN\t$MODE\tmirror-digest=$SIDE"
  exit 0
fi

HOST_SHA=$(sha256sum "$GATE" "$FIXTURE_SRC" | awk '{ print $1 }' | sha256sum | awk '{ print $1 }')
MUTANTS='row-bound order guard privacy dimension'
EXPECTED='ROW_CELL_BOUND ORDER_SORTED GUARD_PROBE PRIVACY_FIXTURE MANIFEST_MATCH'
index=0
for kind in $MUTANTS; do
  index=$((index + 1))
  expected=$(printf '%s\n' $EXPECTED | sed -n "${index}p")
  work="$BUILD/mutants/$kind"
  mkdir -p "$work/src" "$work/classes" "$work/scratch"
  cp "$GATE" "$work/src/Qa15Gate.kt"
  cp "$FIXTURE_SRC" "$work/src/Qa15Fixture.kt"
  case "$kind" in
    row-bound) sed -i 's/timeline.size <= Qa15Fixture.MAX_ROW_CELLS/timeline.size <= 4/' "$work/src/Qa15Gate.kt" ;;
    order) sed -i 's/compareBy<GuideProgram> { it.startEpochSeconds }/compareBy<GuideProgram> { it.title }/' "$work/src/Qa15Gate.kt" ;;
    guard) sed -i 's/if (!rejected) throw AssertionError("reject-guard")/if (rejected) throw AssertionError("reject-guard")/' "$work/src/Qa15Gate.kt" ;;
    privacy) sed -i 's/TITLE_PREFIX = "Scratch Show"/TITLE_PREFIX = "secret-show"/' "$work/src/Qa15Fixture.kt" ;;
    dimension) sed -i 's/CHANNELS = 4096/CHANNELS = 4095/' "$work/src/Qa15Fixture.kt" ;;
  esac
  if cmp -s "$GATE" "$work/src/Qa15Gate.kt" && cmp -s "$FIXTURE_SRC" "$work/src/Qa15Fixture.kt"; then
    say "FAIL\tqa15-mutant-$kind\tno-source-change"
    exit 1
  fi
  if ! compile_kotlin "$work/classes" "${CORE_NATIVE[@]}" \
      "$(native_path "$work/src/Qa15Fixture.kt")" "$(native_path "$work/src/Qa15Gate.kt")" \
      > "$work/compile.log" 2>&1; then
    say "FAIL\tqa15-mutant-$kind\tcompile; see scratch qa15-build/mutants/$kind/compile.log"
    exit 1
  fi
  report="$work/report.txt"
  if run_gate "$work/classes" "$work/scratch" "$report"; then
    say "FAIL\tqa15-red-$kind\tmutant unexpectedly GREEN"
    exit 1
  fi
  first=$(awk -F '\t' '$1 == "FIRST" { print $2; exit }' "$report")
  if [ "$first" != "$expected" ]; then
    say "FAIL\tqa15-red-$kind\twrong-first=$first expected=$expected"
    exit 1
  fi
  say "RED\t$kind\t$first"
done
AFTER_SHA=$(sha256sum "$GATE" "$FIXTURE_SRC" | awk '{ print $1 }' | sha256sum | awk '{ print $1 }')
if [ "$HOST_SHA" != "$AFTER_SHA" ]; then
  say 'FAIL\tqa15-source-restore\toriginal sources changed by mutation loop'
  exit 1
fi
say "RESTORE\tPASS\tbyte-identical-sources sha=$AFTER_SHA"

GREEN_REPORT="$BUILD/green-report.txt"
if ! run_gate "$BUILD/classes" "$BUILD/scratch" "$GREEN_REPORT"; then
  say 'FAIL\tqa15-green\tcanonical gate not GREEN'
  grep -E '^CHECK\tFAIL|^FIRST|^SUMMARY' "$GREEN_REPORT" | head -n 12
  exit 1
fi
POS=$(awk -F '\t' '$1 == "SUMMARY" { print $2; exit }' "$GREEN_REPORT" | sed 's/positive=//')
FAILED=$(awk -F '\t' '$1 == "SUMMARY" { print $3; exit }' "$GREEN_REPORT" | sed 's/failed=//')
TOTAL=$(awk -F '\t' '$1 == "SUMMARY" { print $4; exit }' "$GREEN_REPORT" | sed 's/total=//')
say "GREEN\tpositive=$POS\tfailed=$FAILED\ttotal=$TOTAL"

# Kotlin mirror: typecheck instrumentation against QA stubs, run assertions on the JVM.
STUB_N="$(native_dir "$BUILD/stubs")"
MIRROR_N="$(native_dir "$BUILD/mirror")"
STUB_SOURCES=()
while IFS= read -r -d '' stub; do STUB_SOURCES+=("$(native_path "$stub")"); done < <(find "$STUBS" -name '*.java' -print0)
if ! timeout 90 "$JH/javac" -encoding UTF-8 -d "$STUB_N" "${STUB_SOURCES[@]}" \
    > "$BUILD/stubs.log" 2>&1; then
  say 'FAIL\tqa15-mirror-stubs\tjavac'
  exit 1
fi
MIRROR_CP="$COMPILE_CP$STUB_N"
if ! timeout 180 "$JH/java" -Xmx768m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn -classpath "$MIRROR_CP" -d "$MIRROR_N" \
    "$(native_path "$FIXTURE_SRC")" "$(native_path "$ASSERTIONS")" \
    "$(native_path "$INSTRUMENTATION")" > "$BUILD/mirror-compile.log" 2>&1; then
  say 'FAIL\tqa15-mirror-compile\tsee scratch qa15-build/mirror-compile.log'
  exit 1
fi
if ! timeout 90 "$JH/java" -Xmx96m -cp "$MIRROR_N;$STDLIB" \
    com.devtunasoft.tuneros.qa15.Qa15Assertions > "$BUILD/mirror-run.txt" 2>&1; then
  say 'FAIL\tqa15-mirror-run\tsee scratch qa15-build/mirror-run.txt'
  exit 1
fi
if grep -E '://|Bearer |token=|pin=|secret=' "$BUILD/mirror-run.txt" >/dev/null 2>&1; then
  say 'FAIL\tqa15-mirror-privacy\tsensitive marker in mirror report'
  exit 1
fi
MIRROR_PASS=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
MIRROR_FAIL=$(awk -F '\t' '$1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$BUILD/mirror-run.txt")
SIDE=$(awk -F= '$1 == "sidecar-sha256" { print $2; exit }' "$FIXTURES/manifest.txt")
PIN=$(sed -n 's/.*EXPECTED_SIDECAR = "\([^"]*\)".*/\1/p' "$ASSERTIONS")
if [ "$MIRROR_FAIL" -ne 0 ] || [ "$MIRROR_PASS" -lt 12 ] || [ "$PIN" != "$SIDE" ]; then
  say "FAIL\tqa15-mirror-checks\tpass=$MIRROR_PASS fail=$MIRROR_FAIL pin-match=$([ "$PIN" = "$SIDE" ] && echo yes || echo no)"
  exit 1
fi
say "MIRROR\tPASS\tchecks=$MIRROR_PASS\tpin=$PIN"
DEVICE_ADB="$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03/platform-tools/adb.exe"
if [ -x "$DEVICE_ADB" ] && timeout 20 "$DEVICE_ADB" devices 2>/dev/null | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' >/dev/null; then
  say 'DEVICE\tAVAILABLE\tconnected authorized target; application is handled by agp_compile.sh only'
else
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target probed'
fi
exit 0
