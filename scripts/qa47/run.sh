#!/usr/bin/env bash
# QA-47 bounded host gate: RED controls, byte-identical restore, GREEN proof.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa47-build"
JDK_BIN="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin"
JAVA="$JDK_BIN/java.exe"
JAVAC="$JDK_BIN/javac.exe"
M2="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
FIXTURE="qa/fixtures/qa47"
GATE_SRC="scripts/qa47/Qa47Gate.java"
MUTATOR="scripts/qa47/mutate.py"
PROD_SRC="core/src/main/kotlin/com/devtunasoft/tuneros/core/RecordingWorkerLifecycle.kt"

if [ ! -f "$JAVA" ] || [ ! -f "$JAVAC" ]; then
  printf 'FAIL TOOLCHAIN missing JDK executable\n'
  exit 1
fi
COMP=$(find "$M2/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" -name 'kotlin-compiler-embeddable-2.0.21.jar' | head -1)
STD=$(find "$M2/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -name 'kotlin-stdlib-2.0.21.jar' | head -1)
SCR=$(find "$M2/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21" -name 'kotlin-script-runtime-2.0.21.jar' | head -1)
TRV=$(find "$M2/org.jetbrains.intellij.deps/trove4j" -name 'trove4j-1.0.20200330.jar' | head -1)
COR=$(find "$M2/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4" -name 'kotlinx-coroutines-core-jvm-1.6.4.jar' | head -1)
ANN=$(find "$M2/org.jetbrains/annotations/13.0" -name 'annotations-13.0.jar' | head -1)
for jar in "$COMP" "$STD" "$SCR" "$TRV" "$COR" "$ANN"; do
  if [ -z "$jar" ]; then printf 'FAIL TOOLCHAIN missing cached jar\n'; exit 1; fi
done
CP_COMPILER="$COMP;$STD;$SCR;$TRV;$COR;$ANN"
CP_DEPS="$STD;$SCR;$TRV;$COR;$ANN"

hash_inputs() {
  sha256sum "$GATE_SRC" "scripts/qa47/run.sh" "$MUTATOR" \
    "$FIXTURE/.gitattributes" "$FIXTURE/corrupt.tsv" "$FIXTURE/dimensions.txt" \
    "$FIXTURE/expected.txt" "$FIXTURE/sha256.txt" "$FIXTURE/tasks.tsv" "$PROD_SRC"
}

compile_pair() {
  local prod="$1"; local gate="$2"; local out="$3"
  mkdir -p "$out"
  "$JAVA" -Xmx512m -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$CP_DEPS" -d "$out" "$prod" > "$out/compile.log" 2>&1
  "$JAVAC" -encoding UTF-8 -cp "$out;$CP_DEPS" -d "$out" "$gate" >> "$out/compile.log" 2>&1
}

verify_run() {
  local label="$1"; local classes="$2"; local fixture="$3"; local scratch="$4"; shift 4
  rm -rf "$scratch"; mkdir -p "$scratch"
  set +e
  timeout 150 "$JAVA" -Xmx64m -cp "$classes;$CP_DEPS" Qa47Gate verify "$fixture" \
    --scratch "$scratch" "$@" > "$scratch/verify.log" 2>&1
  VERIFY_RC=$?
  set -e
  VERIFY_LOG="$scratch/verify.log"
  printf 'RUN\t%s\t%s\trc=%s\n' "$label" "$(basename "$scratch")" "$VERIFY_RC"
}

expect_red() {
  local label="$1"; local code="$2"
  if [ "$VERIFY_RC" -eq 0 ]; then
    printf 'MUTANT\tFAIL\t%s\texpected_nonzero\n' "$label"
    return 1
  fi
  if ! grep -q "^FIRST $code$" "$VERIFY_LOG"; then
    printf 'MUTANT\tFAIL\t%s\texpected_first=%s\n' "$label" "$code"
    return 1
  fi
  printf 'MUTANT\tPASS\t%s\tfirst=%s\n' "$label" "$code"
}

expect_green() {
  local label="$1"
  if [ "$VERIFY_RC" -ne 0 ]; then
    printf 'GREEN\tFAIL\t%s\trc=%s\n' "$label" "$VERIFY_RC"
    return 1
  fi
  local lines
  lines=$(awk -F '\t' '$1 == "PASS" { n++ } END { print n + 0 }' "$VERIFY_LOG")
  local total
  total=$(sed -n 's/^SUMMARY total=\([0-9][0-9]*\).*/\1/p' "$VERIFY_LOG")
  printf 'GREEN\tPASS\t%s\tchecks=%s\ttotal=%s\n' "$label" "$lines" "$total"
}

rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
hash_inputs > "$BUILD/before.sha"

printf 'CHECK\tINFO\tbase=a523c2e43d198ee752f7c4f03f9f7853356ffb30\n'
compile_pair "$PROD_SRC" "$GATE_SRC" "$BUILD/classes"

# Deterministic regeneration: same bytes and same pinned aggregate digest.
"$JAVA" -Xmx64m -cp "$BUILD/classes;$CP_DEPS" Qa47Gate generate "$BUILD/regen" > "$BUILD/generate.log"
GEN_DIGEST=$(sed -n 's/^DIGEST //p' "$BUILD/generate.log")
PINNED=$(sed -n 's/.*PINNED_FIXTURE_DIGEST = "\([^"]*\)".*/\1/p' "$GATE_SRC")
if [ "$GEN_DIGEST" != "$PINNED" ]; then
  printf 'CHECK\tFAIL\tPINNED_DIGEST\tgenerated=%s\n' "$GEN_DIGEST"
  exit 1
fi
for name in corrupt.tsv dimensions.txt expected.txt sha256.txt tasks.tsv; do
  cmp -s "$BUILD/regen/$name" "$FIXTURE/$name" || { printf 'CHECK\tFAIL\tREGEN_BYTES\t%s\n' "$name"; exit 1; }
done
printf 'CHECK\tPASS\tREGEN_BYTE_IDENTITY\tfiles=5\tdigest=%s\n' "$GEN_DIGEST"

# RED controls. Each mutant lives only in scratch.
python3 "$MUTATOR" byte "$FIXTURE" "$BUILD/mut/byte/fixture"
verify_run byte "$BUILD/classes" "$BUILD/mut/byte/fixture" "$BUILD/mut/byte/scratch"
expect_red byte FIXTURE_BYTE_IDENTITY

for kind in corrupt privacy thread; do
  python3 "$MUTATOR" source "$kind" "$GATE_SRC" "$BUILD/mut/$kind/Qa47Gate.java"
  compile_pair "$PROD_SRC" "$BUILD/mut/$kind/Qa47Gate.java" "$BUILD/mut/$kind/classes"
  verify_run "$kind" "$BUILD/mut/$kind/classes" "$FIXTURE" "$BUILD/mut/$kind/scratch" --semantic
  case "$kind" in
    corrupt) expect_red "$kind" CORRUPT_FAIL_CLOSED ;;
    privacy) expect_red "$kind" PRIVACY_SCAN ;;
    thread) expect_red "$kind" THREAD_BOUND ;;
  esac
done

python3 "$MUTATOR" order "$PROD_SRC" "$BUILD/mut/order/RecordingWorkerLifecycle.kt"
compile_pair "$BUILD/mut/order/RecordingWorkerLifecycle.kt" "$GATE_SRC" "$BUILD/mut/order/classes"
verify_run order "$BUILD/mut/order/classes" "$FIXTURE" "$BUILD/mut/order/scratch" \
  --semantic --production "$BUILD/mut/order/RecordingWorkerLifecycle.kt"
expect_red order SUBMISSION_REJECTED

# Restore proof: committed inputs must be byte-identical after every probe.
hash_inputs > "$BUILD/after.sha"
if ! cmp -s "$BUILD/before.sha" "$BUILD/after.sha"; then
  printf 'RESTORE\tFAIL\tsha256_changed\n'
  exit 1
fi
printf 'RESTORE\tPASS\tsha256_inputs_identical\tfiles=10\n'

# GREEN at the restored tree, with a forced recompile.
compile_pair "$PROD_SRC" "$GATE_SRC" "$BUILD/green"
verify_run green "$BUILD/green" "$FIXTURE" "$BUILD/green-scratch"
expect_green green

# Diagnostics privacy: no provider URL, credential assignment, PIN, payload
# marker or derived marker token in the emitted GREEN log.
if grep -Eiq '(https?|ftp)://|(password|passwd|secret|api[-_]?key|credential|pin)[[:space:]]*[:=]|recording[-_]?payload' "$BUILD/green-scratch/verify.log"; then
  printf 'PRIVACY\tFAIL\tdiagnostic_pattern\n'
  exit 1
fi
SECRET_HEX=$(cat "$BUILD/green-scratch/qa47-secret.txt")
if grep -qF "$SECRET_HEX" "$BUILD/green-scratch/verify.log"; then
  printf 'PRIVACY\tFAIL\tderived_marker_in_diagnostics\n'
  exit 1
fi
printf 'PRIVACY\tPASS\tdiagnostics_clean\tpatterns=5\tderived_marker_absent=yes\n'

# Android-free mirror: same pinned bytes, compiled and run on the JVM here and
# also compiled into the instrumented APK by AGP.
MIRROR_SRC="app/src/androidTest/java/com/devtunasoft/tuneros/qa47/Qa47Mirror.kt"
mkdir -p "$BUILD/mirror"
"$JAVA" -Xmx512m -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$CP_DEPS" -d "$BUILD/mirror" "$MIRROR_SRC" \
  > "$BUILD/mirror/compile.log" 2>&1
set +e
timeout 90 "$JAVA" -Xmx64m -cp "$BUILD/mirror;$CP_DEPS" \
  com.devtunasoft.tuneros.qa47.Qa47Mirror > "$BUILD/mirror/run.log" 2>&1
MIRROR_RC=$?
set -e
if [ "$MIRROR_RC" -ne 0 ] || ! grep -q 'SUMMARY total=8 pass=8 fail=0' "$BUILD/mirror/run.log"; then
  printf 'MIRROR\tFAIL\trc=%s\n' "$MIRROR_RC"
  exit 1
fi
printf 'MIRROR\tPASS\tchecks=8\tpass=8\tfail=0\tdigest=%s\n' "$PINNED"
printf 'QA-47 HOST GATE: PASS\n'
