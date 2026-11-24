#!/usr/bin/env bash
# QA-65 bounded host gate runner: RED mutants, RESTORE proof, GREEN under -Xmx96m.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
REPO="$(cd "$HERE/../.." && native_pwd)"
SRC="$REPO/core/src/main/kotlin/com/devtunasoft/tuneros/core"
FIX="$REPO/qa/fixtures/qa65"
GATE_SRC="$HERE/Qa65Gate.java"
MUTATE="$HERE/mutate.py"

SCRATCH="$(cygpath -m "${TMPDIR:-/tmp}")/qa65_run"
rm -rf "$SCRATCH"
mkdir -p "$SCRATCH/gate" "$SCRATCH/baseline"

JDK_BIN="${QA65_JDK_BIN:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin}"
JAVA="$JDK_BIN/java.exe"
JAVAC="$JDK_BIN/javac.exe"
GM2=C:/Users/X1/.gradle/caches/modules-2/files-2.1
KS="$GM2/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar"
KR="$GM2/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/c9b044380ad41f89aa89aa896c2d32a8c0b2129d/kotlin-script-runtime-2.0.21.jar"
KT="$GM2/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar"
KO="$GM2/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/2c997cd1c0ef33f3e751d3831929aeff1390cb30/kotlinx-coroutines-core-jvm-1.6.4.jar"
KA="$GM2/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
KC="$GM2/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar"
KCP="$KC;$KS;$KR;$KT;$KO;$KA"

printf '%b\n' "TOOLCHAIN\tjava=$($JAVA -version 2>&1 | head -1 | tr -d '\r')"
printf '%b\n' "SCRATCH\t$SCRATCH"

k2_compile() {
    local outdir="$1"; local srcdir="$2"
    mkdir -p "$outdir"
    timeout 180 "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -jvm-target 17 -classpath "$KCP" -d "$outdir" \
        "$srcdir/SeriesRules.kt" "$srcdir/Models.kt" > "$SCRATCH/k2.log" 2>&1
    return $?
}

hash_prod() { (cd "$SRC" && sha256sum SeriesRules.kt Models.kt) | tr -d '\r'; }
hash_fix()  { (cd "$FIX" && sha256sum dimensions.tsv cases.tsv sha256.txt) | tr -d '\r'; }
hash_dir()  { (cd "$1" && sha256sum SeriesRules.kt Models.kt) | tr -d '\r'; }
hash_fixdir() { (cd "$1" && sha256sum dimensions.tsv cases.tsv sha256.txt) | tr -d '\r'; }

sum_field() {
    awk -F '\t' -v key="$2" '$1=="SUMMARY" {for(i=2;i<=NF;i++){split($i,a,"="); if(a[1]==key) print a[2]}}' "$1" | tr -d '\r'
}
first_code() { awk -F '\t' '$1=="FIRST" {print $2}' "$1" | tr -d '\r'; }
check_count() { grep -ac '^CHECK' "$1"; }

run_gate() {
    local classes="$1"; local fixdir="$2"; local log="$3"
    timeout 90 "$JAVA" -Xmx96m -cp "$classes;$KS;$GATE_DIR" Qa65Gate verify "$fixdir" > "$log" 2>&1
    return $?
}

GATE_DIR="$SCRATCH/gate"
PROD_BASE="$SCRATCH/baseline"

# Baseline compile of real production + gate.
k2_compile "$PROD_BASE" "$SRC" || { printf '%b\n' "BUILD\tFAIL\tk2-baseline"; exit 1; }
timeout 120 "$JAVAC" -encoding UTF-8 -cp "$PROD_BASE;$KS" -d "$GATE_DIR" "$GATE_SRC" \
    > "$SCRATCH/javac.log" 2>&1 || { printf '%b\n' "BUILD\tFAIL\tjavac"; exit 1; }
printf '%b\n' "BUILD\tPASS"

BEFORE_PROD="$(hash_prod)"
BEFORE_FIX="$(hash_fix)"

# ---------- RED: source mutants against scratch production copies ----------
# id | pinned FIRST code
MUTANTS="M1_CASE:CANON_CASE M2_LEAD:CANON_LEAD M3_SPACE:CANON_SPACE M4_NULL:CANON_NULL M5_CHANNEL:RULE_CHANNEL M6_ID:RULE_ID"
RED_FAIL=0
for entry in $MUTANTS; do
    mid="${entry%%:*}"
    pinned="${entry##*:}"
    mdir="$SCRATCH/mut_$mid"
    mkdir -p "$mdir/src"
    python3 "$MUTATE" copy "$SRC" "$mdir/src" > "$mdir/copy.log" 2>&1 || { printf '%b\n' "MUTANT\t$mid\tFAIL\tcopy"; RED_FAIL=1; continue; }
    python3 "$MUTATE" apply "$mdir/src" "$mid" > "$mdir/apply.log" 2>&1 || { printf '%b\n' "MUTANT\t$mid\tFAIL\t$(tr -d '\r' < "$mdir/apply.log")"; RED_FAIL=1; continue; }
    k2_compile "$mdir/classes" "$mdir/src" || { printf '%b\n' "MUTANT\t$mid\tFAIL\tcompile"; RED_FAIL=1; continue; }
    log="$mdir/verify.log"
    run_gate "$mdir/classes" "$FIX" "$log"
    rc=$?
    observed="$(first_code "$log")"
    if [[ "$rc" -ne 0 && "$observed" == "$pinned" ]]; then
        printf '%b\n' "MUTANT\t$mid\tPASS\t$pinned\t$observed\trc=$rc"
    else
        printf '%b\n' "MUTANT\t$mid\tFAIL\t$pinned\t$observed\trc=$rc"
        RED_FAIL=1
    fi
done

# Fixture mutant: byte flip in the scratch manifest only; expect BYTE_IDENTITY first.
FM="$SCRATCH/mut_fixture"
mkdir -p "$FM"
cp "$FIX/dimensions.tsv" "$FIX/cases.tsv" "$FIX/sha256.txt" "$FM/"
printf '\n' >> "$FM/sha256.txt"
run_gate "$PROD_BASE" "$FM" "$FM/verify.log"
rc=$?
observed="$(first_code "$FM/verify.log")"
if [[ "$rc" -ne 0 && "$observed" == "BYTE_IDENTITY" ]]; then
    printf '%b\n' "MUTANT\tF7_MANIFEST\tPASS\tBYTE_IDENTITY\t$observed\trc=$rc"
else
    printf '%b\n' "MUTANT\tF7_MANIFEST\tFAIL\tBYTE_IDENTITY\t$observed\trc=$rc"
    RED_FAIL=1
fi
printf '%b\n' "RED\t$([[ $RED_FAIL -eq 0 ]] && printf PASS || printf FAIL)\tmutants=7"

# ---------- RESTORE: all scratch production and fixtures byte-identical ----------
rm -rf "$SCRATCH/restored_src" "$SCRATCH/restored_fix"
python3 "$MUTATE" copy "$SRC" "$SCRATCH/restored_src" > "$SCRATCH/restore_src.log" 2>&1
mkdir -p "$SCRATCH/restored_fix"
cp "$FIX/dimensions.tsv" "$FIX/cases.tsv" "$FIX/sha256.txt" "$SCRATCH/restored_fix/"
AFTER_PROD="$(hash_prod)"
AFTER_FIX="$(hash_fix)"
SRC_RESTORED="$([[ "$(hash_dir "$SCRATCH/restored_src")" == "$(cd "$SRC" && sha256sum SeriesRules.kt Models.kt | tr -d '\r')" ]] && printf PASS || printf FAIL)"
FIX_RESTORED="$([[ "$(hash_fixdir "$SCRATCH/restored_fix")" == "$(cd "$FIX" && sha256sum dimensions.tsv cases.tsv sha256.txt | tr -d '\r')" ]] && printf PASS || printf FAIL)"
if [[ "$BEFORE_PROD" == "$AFTER_PROD" && "$BEFORE_FIX" == "$AFTER_FIX" && "$SRC_RESTORED" == "PASS" && "$FIX_RESTORED" == "PASS" ]]; then
    printf '%b\n' "RESTORE\tPASS\tsrc=$SRC_RESTORED\tfixture=$FIX_RESTORED"
else
    printf '%b\n' "RESTORE\tFAIL\tsrc=$SRC_RESTORED\tfixture=$FIX_RESTORED"
fi

# ---------- GREEN: byte-identical regeneration and unchanged run under -Xmx96m ----------
REGEN="$SCRATCH/regen"
mkdir -p "$REGEN"
timeout 90 "$JAVA" -Xmx96m -cp "$PROD_BASE;$KS;$GATE_DIR" Qa65Gate generate "$REGEN" > "$SCRATCH/generate.log" 2>&1
GEN_RC=$?
REGEN_OK=PASS
for f in dimensions.tsv cases.tsv sha256.txt; do
    cmp -s "$FIX/$f" "$REGEN/$f" || REGEN_OK=FAIL
done
printf '%b\n' "REGEN\t$REGEN_OK\trc=$GEN_RC\tfiles=3"

GREEN_LOG="$SCRATCH/green.log"
run_gate "$PROD_BASE" "$FIX" "$GREEN_LOG"
GREEN_RC=$?
CHECKS="$(check_count "$GREEN_LOG")"
PASS_N="$(sum_field "$GREEN_LOG" pass)"
FAIL_N="$(sum_field "$GREEN_LOG" fail)"
GREEN_FIRST="$(first_code "$GREEN_LOG")"
THREAD_LINE="$(awk -F '\t' '$1=="CHECK" && $2=="THREAD_DRIFT" {print $3}' "$GREEN_LOG" | tr -d '\r')"
HEAP_LINE="$(awk -F '\t' '$1=="HEAP" {print $2}' "$GREEN_LOG" | tr -d '\r')"
if [[ "$GREEN_RC" -eq 0 && "$FAIL_N" == "0" && "$GREEN_FIRST" == "NONE" && "$THREAD_LINE" == "PASS" && "$REGEN_OK" == "PASS" ]]; then
    printf '%b\n' "GREEN\tPASS\tchecks=$CHECKS\tpass=$PASS_N\tfail=$FAIL_N\tfirst=$GREEN_FIRST\tthread=$THREAD_LINE\theap=$HEAP_LINE"
else
    printf '%b\n' "GREEN\tFAIL\tchecks=$CHECKS\tpass=$PASS_N\tfail=$FAIL_N\tfirst=$GREEN_FIRST\tthread=$THREAD_LINE\theap=$HEAP_LINE"
fi

if [[ "$RED_FAIL" -eq 0 && "$BEFORE_PROD" == "$AFTER_PROD" && "$BEFORE_FIX" == "$AFTER_FIX" && "$GREEN_RC" -eq 0 && "$FAIL_N" == "0" && "$REGEN_OK" == "PASS" ]]; then
    printf '%b\n' "VERDICT\tPASS\tred=7\tgreen=$CHECKS"
else
    printf '%b\n' "VERDICT\tFAIL"
fi
