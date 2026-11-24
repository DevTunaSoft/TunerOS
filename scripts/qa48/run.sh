#!/usr/bin/env bash
# QA-48 bounded host gate: RED controls, byte-identical restore, GREEN proof.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa48-build"
JDK_BIN="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin"
JAVA="$JDK_BIN/java.exe"
JAVAC="$JDK_BIN/javac.exe"
M2="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
FIXTURE="qa/fixtures/qa48"
GATE_SRC="scripts/qa48/Qa48Gate.java"
MUTATOR="scripts/qa48/mutate.py"

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

CORE_PKG="core/src/main/kotlin/com/devtunasoft/tuneros/core"
PROD_MODELS="$CORE_PKG/Models.kt"
PROD_RECORDING="$CORE_PKG/RecordingModels.kt"
PROD_COPY="$CORE_PKG/StreamRecorder.kt"

INPUTS="$GATE_SRC scripts/qa48/run.sh $MUTATOR \
  $FIXTURE/.gitattributes $FIXTURE/dimensions.txt $FIXTURE/cases.tsv \
  $FIXTURE/corrupt.tsv $FIXTURE/expected.txt $FIXTURE/sha256.txt \
  app/src/androidTest/.gitattributes app/src/androidTest/AndroidManifest.xml \
  app/src/androidTest/java/com/devtunasoft/tuneros/qa48/Qa48Instrumentation.kt \
  app/src/androidTest/java/com/devtunasoft/tuneros/qa48/Qa48Mirror.kt \
  $PROD_MODELS $PROD_RECORDING $PROD_COPY"

hash_inputs() { sha256sum $INPUTS; }

compile_pair() {
  local models="$1"; local recording="$2"; local copy="$3"; local gate="$4"; local out="$5"
  mkdir -p "$out"
  "$JAVA" -Xmx512m -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$CP_DEPS" -d "$out" \
    "$models" "$recording" "$copy" > "$out/compile.log" 2>&1
  local k2rc=$?
  if [ "$k2rc" -ne 0 ]; then
    printf 'FAIL COMPILE_K2 rc=%s\n' "$k2rc"
    tail -20 "$out/compile.log"
    exit 1
  fi
  "$JAVAC" -encoding UTF-8 -cp "$out;$CP_DEPS" -d "$out" "$gate" >> "$out/compile.log" 2>&1
  local jrc=$?
  if [ "$jrc" -ne 0 ]; then
    printf 'FAIL COMPILE_JAVAC rc=%s\n' "$jrc"
    tail -20 "$out/compile.log"
    exit 1
  fi
}

verify_run() {
  local label="$1"; local classes="$2"; local fixture="$3"; local scratch="$4"; shift 4
  mkdir -p "$scratch"
  set +e
  timeout 120 "$JAVA" -Xmx64m -cp "$classes;$CP_DEPS" Qa48Gate verify "$fixture" \
    --scratch "$scratch" "$@" > "$scratch/verify.log" 2>&1
  VERIFY_RC=$?
  set -e
  VERIFY_LOG="$scratch/verify.log"
  printf 'RUN\t%s\trc=%s\n' "$label" "$VERIFY_RC"
}

expect_red() {
  local label="$1"; local code="$2"
  if [ "$VERIFY_RC" -eq 0 ]; then
    printf 'MUTANT\tFAIL\t%s\texpected_nonzero\n' "$label"
    return 1
  fi
  if ! grep -q "^FIRST $code$" "$VERIFY_LOG"; then
    printf 'MUTANT\tFAIL\t%s\texpected_first=%s\n' "$label" "$code"
    grep '^FIRST ' "$VERIFY_LOG" || true
    return 1
  fi
  printf 'MUTANT\tPASS\t%s\tfirst=%s\n' "$label" "$code"
}

expect_green() {
  local label="$1"
  if [ "$VERIFY_RC" -ne 0 ]; then
    printf 'GREEN\tFAIL\t%s\trc=%s\n' "$label" "$VERIFY_RC"
    grep '^FAIL' "$VERIFY_LOG" || true
    return 1
  fi
  local lines total first
  lines=$(awk -F '\t' '$1 == "PASS" { n++ } END { print n + 0 }' "$VERIFY_LOG")
  total=$(sed -n 's/^SUMMARY total=\([0-9][0-9]*\).*/\1/p' "$VERIFY_LOG")
  first=$(sed -n 's/^FIRST //p' "$VERIFY_LOG")
  if [ "$first" != "NONE" ]; then
    printf 'GREEN\tFAIL\t%s\tfirst=%s\n' "$label" "$first"
    return 1
  fi
  printf 'GREEN\tPASS\t%s\tchecks=%s\ttotal=%s\n' "$label" "$lines" "$total"
}

rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
hash_inputs > "$BUILD/before.sha"

printf 'CHECK\tINFO\tbase=a523c2e43d198ee752f7c4f03f9f7853356ffb30\n'
compile_pair "$PROD_MODELS" "$PROD_RECORDING" "$PROD_COPY" "$GATE_SRC" "$BUILD/classes"

# Deterministic regeneration and pinned fixture digest.
"$JAVA" -Xmx64m -cp "$BUILD/classes;$CP_DEPS" Qa48Gate generate "$BUILD/regen" > "$BUILD/generate.log"
GEN_DIGEST=$(sed -n 's/^DIGEST //p' "$BUILD/generate.log")
PINNED=$(sed -n 's/.*PINNED_FIXTURE_DIGEST = "\([^"]*\)".*/\1/p' "$GATE_SRC")
if [ "$GEN_DIGEST" != "$PINNED" ]; then
  printf 'CHECK\tFAIL\tPINNED_DIGEST\tgenerated=%s\n' "$GEN_DIGEST"
  exit 1
fi
for name in dimensions.txt cases.tsv corrupt.tsv expected.txt sha256.txt; do
  cmp -s "$BUILD/regen/$name" "$FIXTURE/$name" || { printf 'CHECK\tFAIL\tREGEN_BYTES\t%s\n' "$name"; exit 1; }
done
printf 'CHECK\tPASS\tREGEN_BYTE_IDENTITY\tfiles=5\tdigest=%s\n' "$GEN_DIGEST"

# RED: committed fixture byte flip.
python3 "$MUTATOR" byte "$FIXTURE" "$BUILD/mut/byte/fixture"
verify_run byte "$BUILD/classes" "$BUILD/mut/byte/fixture" "$BUILD/mut/byte/scratch"
expect_red byte FIXTURE_BYTE_IDENTITY

# RED: gate guard relaxations.
for kind in corrupt privacy heap thread; do
  python3 "$MUTATOR" source "$kind" "$GATE_SRC" "$BUILD/mut/$kind/Qa48Gate.java"
  compile_pair "$PROD_MODELS" "$PROD_RECORDING" "$PROD_COPY" \
    "$BUILD/mut/$kind/Qa48Gate.java" "$BUILD/mut/$kind/classes"
  verify_run "$kind" "$BUILD/mut/$kind/classes" "$FIXTURE" "$BUILD/mut/$kind/scratch" --semantic
  case "$kind" in
    corrupt) expect_red "$kind" CORRUPT_FAIL_CLOSED ;;
    privacy) expect_red "$kind" PRIVACY_SCAN ;;
    heap) expect_red "$kind" HEAP_BOUND ;;
    thread) expect_red "$kind" THREAD_BOUND ;;
  esac
done

# RED: real production guard inversions in scratch copies.
python3 "$MUTATOR" copy "$PROD_COPY" "$BUILD/mut/copy/StreamRecorder.kt"
compile_pair "$PROD_MODELS" "$PROD_RECORDING" "$BUILD/mut/copy/StreamRecorder.kt" \
  "$GATE_SRC" "$BUILD/mut/copy/classes"
verify_run copy "$BUILD/mut/copy/classes" "$FIXTURE" "$BUILD/mut/copy/scratch"
expect_red copy COPY_STORAGE_RECHECK

python3 "$MUTATOR" capacity "$PROD_RECORDING" "$BUILD/mut/capacity/RecordingModels.kt"
compile_pair "$PROD_MODELS" "$BUILD/mut/capacity/RecordingModels.kt" "$PROD_COPY" \
  "$GATE_SRC" "$BUILD/mut/capacity/classes"
verify_run capacity "$BUILD/mut/capacity/classes" "$FIXTURE" "$BUILD/mut/capacity/scratch"
expect_red capacity CAPACITY_FAIL_CLOSED

# Restore proof: every committed input must be byte-identical after all probes.
hash_inputs > "$BUILD/after.sha"
if ! cmp -s "$BUILD/before.sha" "$BUILD/after.sha"; then
  printf 'RESTORE\tFAIL\tsha256_changed\n'
  diff "$BUILD/before.sha" "$BUILD/after.sha" || true
  exit 1
fi
COUNT=$(wc -l < "$BUILD/before.sha")
printf 'RESTORE\tPASS\tsha256_inputs_identical\tfiles=%s\n' "$COUNT"

# GREEN at the restored tree, forced recompile.
compile_pair "$PROD_MODELS" "$PROD_RECORDING" "$PROD_COPY" "$GATE_SRC" "$BUILD/green"
verify_run green "$BUILD/green" "$FIXTURE" "$BUILD/green-scratch"
expect_green green

printf 'AGP\tNOT_RUN\tautomated_in_agp_compile.sh\n'
