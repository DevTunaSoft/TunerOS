#!/usr/bin/env bash
# QA-45 deterministic zero-dependency host gate runner.
# RED mutant controls first, byte-identical restore, then GREEN. No device use.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JAVA_HOME_NATIVE="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"
JH="$JAVA_HOME_NATIVE/bin"
if [ ! -x "$JH/java" ] || [ ! -x "$JH/javac" ]; then
  say 'BLOCKED\tqa45-host\tno JDK 17'
  exit 2
fi
export JAVA_HOME="$JAVA_HOME_NATIVE"
export PATH="$(cygpath -u "$JH" 2>/dev/null || printf '%s' "$JH"):$PATH"

GRADLE_CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
find_jar() {
  find "$GRADLE_CACHE" -name "$1" 2>/dev/null | head -n 1 | while read -r p; do cygpath -m "$p"; done
}
COMPILER="$(find_jar kotlin-compiler-embeddable-2.0.21.jar)"
STDLIB="$(find_jar kotlin-stdlib-2.0.21.jar)"
SCRIPTS="$(find_jar kotlin-script-runtime-2.0.21.jar)"
TROVE="$(find_jar trove4j-1.0.20200330.jar)"
COROUTINES="$(find_jar kotlinx-coroutines-core-jvm-1.6.4.jar)"
ANNOTATIONS="$(find_jar annotations-13.0.jar)"
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ] || [ -z "$SCRIPTS" ] || [ -z "$TROVE" ]; then
  say 'BLOCKED\tqa45-host\toffline Kotlin compiler jars unavailable'
  exit 2
fi
K2CP="$COMPILER;$STDLIB;$SCRIPTS;$TROVE;$COROUTINES;$ANNOTATIONS"

PROD="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/SourceRecoveryPolicy.kt"
FIX="$ROOT/qa/fixtures/qa45"
GATE="$ROOT/scripts/qa45/Qa45Gate.java"
SCRATCH="${QA45_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa45-host}"
RUN="$SCRATCH/run-$$"
mkdir -p "$RUN"
trap 'rm -rf "$RUN"' EXIT

FAILED=0
first_of() { printf '%s\n' "$1" | awk -F '\t' '$1=="FIRST"{print $2; exit}'; }
summary_field() {
  printf '%s\n' "$1" | awk -F '\t' -v key="$2" '$1=="SUMMARY"{for(i=2;i<=NF;i++){split($i,p,"="); if(p[1]==key){print p[2]; exit}}}'
}
expect_mutant() {
  local label="$1" expected="$2" output="$3" rc="$4" actual
  actual="$(first_of "$output")"
  if [ "$rc" -ne 0 ] && [ "$actual" = "$expected" ]; then
    say "MUTANT\\t$label\\texpected=$expected\\tactual=$actual\\tresult=PASS"
  else
    say "MUTANT\\t$label\\texpected=$expected\\tactual=$actual\\trc=$rc\\tresult=FAIL"
    FAILED=1
  fi
}
run_gate() {
  local cp="$1"; shift
  timeout 90 "$JH/java" -Xmx64m -XX:-UsePerfData -cp "$cp" Qa45Gate "$@" 2>&1
}

# --- compile the untouched production object and the host gate ------------
mkdir -p "$RUN/prod-orig" "$RUN/gate"
timeout 300 "$JH/java" -Xmx512m -cp "$K2CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STDLIB" -jvm-target 17 -d "$RUN/prod-orig" "$PROD" > "$RUN/k2.log" 2>&1
rc=$?
if [ "$rc" -ne 0 ] || [ ! -f "$RUN/prod-orig/com/devtunasoft/tuneros/core/SourceRecoveryPolicy.class" ]; then
  say "FAIL\\tqa45-compile\\tk2 rc=$rc"
  exit 1
fi
timeout 180 "$JH/javac" -encoding UTF-8 -cp "$RUN/prod-orig;$STDLIB" \
  -d "$RUN/gate" "$GATE" > "$RUN/javac.log" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  say "FAIL\\tqa45-compile\\tjavac rc=$rc"
  exit 1
fi
CP_ORIG="$RUN/prod-orig;$RUN/gate;$STDLIB"
say "COMPILE\\tPASS\\tproduction-object-and-host-gate"

PROD_BEFORE="$(sha256sum "$PROD" | cut -d' ' -f1)"
FIX_BEFORE="$(cd "$FIX" && sha256sum * .gitattributes | sha256sum | cut -d' ' -f1)"

# --- RED control 1: invert the production restoration guard ---------------
MUTSRC="$RUN/mut-prod-src/com/devtunasoft/tuneros/core"
mkdir -p "$MUTSRC" "$RUN/prod-mut"
cp "$PROD" "$MUTSRC/SourceRecoveryPolicy.kt"
MATCHES="$(grep -c '!hasLoadedData' "$MUTSRC/SourceRecoveryPolicy.kt")"
if [ "$MATCHES" != "1" ]; then
  say "FAIL\\tqa45-mut-prod\\tneedle-count=$MATCHES"
  FAILED=1
else
  sed 's/!hasLoadedData/hasLoadedData/' "$MUTSRC/SourceRecoveryPolicy.kt" > "$RUN/mutated.kt"
  timeout 300 "$JH/java" -Xmx512m -cp "$K2CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -classpath "$STDLIB" -jvm-target 17 -d "$RUN/prod-mut" "$RUN/mutated.kt" > "$RUN/k2-mut.log" 2>&1
  if [ $? -ne 0 ]; then
    say 'FAIL\tqa45-mut-prod\tmutant-compile'
    FAILED=1
  else
    OUT="$(run_gate "$RUN/prod-mut;$RUN/gate;$STDLIB" verify "$FIX")"; RC=$?
    expect_mutant 'prod-restore-guard' RESTORE_TRUTH_TABLE "$OUT" "$RC"
  fi
fi

# --- RED control 2: corrupt a pinned commit expectation -------------------
FXM="$RUN/fx-commit"
cp -R "$FIX" "$FXM"
CID="$(awk -F '\t' '$5=="0"{print $1; exit}' "$FXM/commit.tsv")"
if [ -z "$CID" ]; then
  say 'FAIL\tqa45-mut-commit\tno-target-row'
  FAILED=1
else
  awk -F '\t' -v id="$CID" 'BEGIN{OFS="\t"} $1==id{$5="1"} {print}' "$FXM/commit.tsv" > "$RUN/commit.new"
  mv "$RUN/commit.new" "$FXM/commit.tsv"
  OUT="$(run_gate "$CP_ORIG" remanifest "$FXM")"; RC=$?
  if [ "$RC" -ne 0 ]; then
    say 'FAIL\tqa45-mut-commit\tremanifest'
    FAILED=1
  else
    OUT="$(run_gate "$CP_ORIG" verify "$FXM" --mutant)"; RC=$?
    expect_mutant 'commit-expectation' COMMIT_EPOCH "$OUT" "$RC"
  fi
fi

# --- RED control 3: make a corrupt record parseable -----------------------
FXC="$RUN/fx-corrupt"
cp -R "$FIX" "$FXC"
awk -F '\t' -v name='bad-flag' 'BEGIN{OFS="\t"} $1==name{$4="R00001~N~S00001~0~0~0"} {print}' \
  "$FXC/corrupt.tsv" > "$RUN/corrupt.new"
mv "$RUN/corrupt.new" "$FXC/corrupt.tsv"
OUT="$(run_gate "$CP_ORIG" remanifest "$FXC")"; RC=$?
if [ "$RC" -ne 0 ]; then
  say 'FAIL\tqa45-mut-corrupt\tremanifest'
  FAILED=1
else
  OUT="$(run_gate "$CP_ORIG" verify "$FXC" --mutant)"; RC=$?
  expect_mutant 'corrupt-accepted' CORRUPT_REJECT "$OUT" "$RC"
fi

# --- RED control 4: byte-level fixture drift ------------------------------
FXB="$RUN/fx-byte"
cp -R "$FIX" "$FXB"
BEFORE="$(sha256sum "$FXB/notes.txt" | cut -d' ' -f1)"
sed -i 's/bounded/b0unded/' "$FXB/notes.txt"
AFTER="$(sha256sum "$FXB/notes.txt" | cut -d' ' -f1)"
if [ "$BEFORE" = "$AFTER" ]; then
  say 'FAIL\tqa45-mut-byte\tneedle-not-found'
  FAILED=1
else
  OUT="$(run_gate "$CP_ORIG" remanifest "$FXB")"; RC=$?
  if [ "$RC" -ne 0 ]; then
    say 'FAIL\tqa45-mut-byte\tremanifest'
    FAILED=1
  else
    OUT="$(run_gate "$CP_ORIG" verify "$FXB")"; RC=$?
    expect_mutant 'byte-drift' BYTE_IDENTITY "$OUT" "$RC"
  fi
fi

# --- RED control 5: privacy detector catches a synthetic leak -------------
OUT="$(run_gate "$CP_ORIG" selfleak "$RUN/leak.txt")"; RC=$?
if [ "$RC" -ne 0 ]; then
  say 'FAIL\tqa45-mut-privacy\tselfleak'
  FAILED=1
else
  OUT="$(run_gate "$CP_ORIG" scan "$RUN/leak.txt")"; RC=$?
  expect_mutant 'privacy-leak' PRIVACY_SCAN "$OUT" "$RC"
fi

# --- byte-identical restore of production source and committed fixture ----
PROD_AFTER="$(sha256sum "$PROD" | cut -d' ' -f1)"
FIX_AFTER="$(cd "$FIX" && sha256sum * .gitattributes | sha256sum | cut -d' ' -f1)"
if [ "$PROD_BEFORE" = "$PROD_AFTER" ] && [ "$FIX_BEFORE" = "$FIX_AFTER" ]; then
  say "RESTORE\\tPASS\\tproduction_sha256=$PROD_AFTER\\tfixture_aggregate_sha256=$FIX_AFTER"
else
  say 'RESTORE\tFAIL\tbyte-identity-broken'
  FAILED=1
fi

# --- GREEN on the committed fixture ---------------------------------------
OUT="$(run_gate "$CP_ORIG" verify "$FIX")"; RC=$?
printf '%s\n' "$OUT" > "$RUN/green-report.txt"
CHECKS="$(summary_field "$OUT" checks)"
PASSED="$(summary_field "$OUT" pass)"
if [ "$RC" -eq 0 ] && [ -n "$CHECKS" ] && [ "$PASSED" = "$CHECKS" ]; then
  say "GREEN\\tPASS\\tchecks=$CHECKS\\tpass=$PASSED\\tfail=0\\tfirst=NONE"
else
  say "GREEN\\tFAIL\\trc=$RC\\tchecks=$CHECKS\\tpass=$PASSED"
  printf '%s\n' "$OUT" | grep -E '^(CHECK|SUMMARY|FIRST)' | head -n 12
  FAILED=1
fi

OUT="$(run_gate "$CP_ORIG" scan "$RUN/green-report.txt" "$FIX"/* "$FIX/.gitattributes")"; RC=$?
if [ "$RC" -eq 0 ]; then
  say 'PRIVACY\tPASS\tdiagnostics-and-fixtures-scanned'
else
  say "PRIVACY\\tFAIL\\trc=$RC"
  FAILED=1
fi

if [ "$FAILED" -eq 0 ]; then
  say 'VERDICT\tPASS\tqa45-host'
  exit 0
fi
say 'VERDICT\tFAIL\tqa45-host'
exit 1
