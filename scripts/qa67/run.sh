#!/usr/bin/env bash
# QA-67 bounded host gate runner: regeneration proof, RED mutants, RESTORE proof, GREEN under -Xmx96m.
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
REPO="$(cd "$HERE/../.." && native_pwd)"
SRC="$REPO/core/src/main/kotlin/com/devtunasoft/tuneros/core"
FIX="$REPO/qa/fixtures/qa67"
GATE_SRC="$HERE/Qa67Gate.java"
MUTATE="$HERE/mutate.py"

SCRATCH="${QA67_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa67_run}"
rm -rf "$SCRATCH"
mkdir -p "$SCRATCH/baseline" "$SCRATCH/gate" "$SCRATCH/regen"

JDK_BIN="${QA67_JDK_BIN:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin}"
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
        "$srcdir/TimeshiftWindow.kt" > "$SCRATCH/k2.log" 2>&1
    return $?
}

javac_gate() {
    local outdir="$1"; local classdir="$2"; local gatesrc="$3"
    mkdir -p "$outdir"
    timeout 120 "$JAVAC" -encoding UTF-8 -cp "$classdir;$KS" -d "$outdir" "$gatesrc" \
        > "$SCRATCH/javac.log" 2>&1
    return $?
}

run_gate() {
    local classes="$1"; local fixdir="$2"; local mode="$3"; local log="$4"
    timeout 90 "$JAVA" -Xmx96m -cp "$classes;$SCRATCH/baseline;$KS" \
        Qa67Gate verify "$fixdir" "$mode" > "$log" 2>&1
    return $?
}

first_code() { awk -F '\t' '$1=="FIRST" {print $2}' "$1" | tr -d '\r'; }
sum_field() {
    awk -F '\t' -v key="$2" '$1=="SUMMARY" {for(i=2;i<=NF;i++){split($i,a,"="); if(a[1]==key) print a[2]}}' "$1" | tr -d '\r'
}
hash_prod() { sha256sum "$SRC/TimeshiftWindow.kt" | cut -f1 -d' ' | tr -d '\r'; }
hash_fix() { (cd "$FIX" && sha256sum .gitattributes cases.tsv corrupt.hex dimensions.tsv sha256.txt) | tr -d '\r'; }

if ! k2_compile "$SCRATCH/baseline" "$SRC"; then
    printf '%b\n' "BUILD\tFAIL\tk2-baseline"
    exit 1
fi
if ! javac_gate "$SCRATCH/gate" "$SCRATCH/baseline" "$GATE_SRC"; then
    printf '%b\n' "BUILD\tFAIL\tjavac-gate"
    exit 1
fi
printf '%b\n' "BUILD\tPASS"

if [ "${1:-verify}" = "gate-only" ]; then
    timeout 90 "$JAVA" -Xmx96m -cp "$SCRATCH/gate;$SCRATCH/baseline;$KS" \
        Qa67Gate verify "$FIX" full > "$SCRATCH/green.log" 2>&1
    rc=$?
    cat "$SCRATCH/green.log"
    printf '%b\n' "GATE_ONLY\trc=$rc\tfirst=$(first_code "$SCRATCH/green.log")"
    exit "$rc"
fi

if [ "${1:-verify}" = "generate" ]; then
    timeout 60 "$JAVA" -Xmx96m -cp "$SCRATCH/gate;$SCRATCH/baseline;$KS" \
        Qa67Gate generate "$FIX"
    exit $?
fi

BEFORE_PROD="$(hash_prod)"
BEFORE_FIX="$(hash_fix)"
RED_FAIL=0

# Regeneration in a scratch directory must reproduce every committed fixture byte.
timeout 60 "$JAVA" -Xmx96m -cp "$SCRATCH/gate;$SCRATCH/baseline;$KS" \
    Qa67Gate generate "$SCRATCH/regen" > "$SCRATCH/regen.log" 2>&1
regen_rc=$?
REGEN_OK=1
for f in .gitattributes cases.tsv corrupt.hex dimensions.tsv sha256.txt; do
    if [ "$regen_rc" -ne 0 ] || ! cmp -s "$SCRATCH/regen/$f" "$FIX/$f"; then REGEN_OK=0; fi
done
if [ "$REGEN_OK" -eq 1 ]; then printf '%b\n' "REGEN\tPASS\tbytes=identical"; else printf '%b\n' "REGEN\tFAIL\trc=$regen_rc"; RED_FAIL=1; fi

FIXTURE_MUTANTS="F1_BYTE:byte:BYTE_IDENTITY F2_MANIFEST:manifest:MANIFEST_CONSISTENT F3_DIGEST:digest:PINNED_DIGEST F4_HEADER:semantic:HEADER_STRICT F5_ROWS:semantic:ROW_BOUNDS F6_CAP:semantic:CAP_BINDING F7_CORRUPT:semantic:CORRUPT_FAIL_CLOSED F8_EXPECTED:semantic:DATE_EXACT"
for entry in $FIXTURE_MUTANTS; do
    mid="${entry%%:*}"; tail="${entry#*:}"; mode="${tail%%:*}"; pinned="${tail##*:}"
    mdir="$SCRATCH/mut_$mid"
    python3 "$MUTATE" fixture "$mid" "$FIX" "$mdir/fixture" > "$mdir.mutate.log" 2>&1
    mrc=$?
    if [ "$mrc" -ne 0 ]; then
        printf '%b\n' "MUTANT\t$mid\tFAIL\tmutator\trc=$mrc"
        RED_FAIL=1
        continue
    fi
    log="$mdir/verify.log"
    run_gate "$SCRATCH/gate" "$mdir/fixture" "$mode" "$log"
    rc=$?
    observed="$(first_code "$log")"
    if [ "$rc" -ne 0 ] && [ "$observed" = "$pinned" ]; then
        printf '%b\n' "MUTANT\t$mid\tPASS\t$pinned\t$observed\trc=$rc"
    else
        printf '%b\n' "MUTANT\t$mid\tFAIL\t$pinned\t$observed\trc=$rc"
        RED_FAIL=1
    fi
done

PROD_MUTANTS="P1_FORMAT:DATE_EXACT P2_NULL_TAG:NULL_OMITS P3_ORDER:ORDER_TAG P4_ZONE:DATE_EXACT"
for entry in $PROD_MUTANTS; do
    mid="${entry%%:*}"; pinned="${entry##*:}"
    mdir="$SCRATCH/mut_$mid"
    python3 "$MUTATE" source "$mid" "$SRC/TimeshiftWindow.kt" "$mdir/src/TimeshiftWindow.kt" \
        > "$mdir.mutate.log" 2>&1
    mrc=$?
    if [ "$mrc" -ne 0 ]; then
        printf '%b\n' "MUTANT\t$mid\tFAIL\tmutator\trc=$mrc"
        RED_FAIL=1
        continue
    fi
    if ! k2_compile "$mdir/classes" "$mdir/src"; then
        printf '%b\n' "MUTANT\t$mid\tFAIL\tcompile"
        RED_FAIL=1
        continue
    fi
    log="$mdir/verify.log"
    run_gate "$mdir/classes;$SCRATCH/gate" "$FIX" full "$log"
    rc=$?
    observed="$(first_code "$log")"
    if [ "$rc" -ne 0 ] && [ "$observed" = "$pinned" ]; then
        printf '%b\n' "MUTANT\t$mid\tPASS\t$pinned\t$observed\trc=$rc"
    else
        printf '%b\n' "MUTANT\t$mid\tFAIL\t$pinned\t$observed\trc=$rc"
        RED_FAIL=1
    fi
done

GDIR="$SCRATCH/mut_G1_PRIVACY"
python3 "$MUTATE" source G1_PRIVACY "$GATE_SRC" "$GDIR/src/Qa67Gate.java" > "$GDIR.mutate.log" 2>&1
mrc=$?
if [ "$mrc" -ne 0 ] || ! javac_gate "$GDIR/gate" "$SCRATCH/baseline" "$GDIR/src/Qa67Gate.java"; then
    printf '%b\n' "MUTANT\tG1_PRIVACY\tFAIL\tbuild\trc=$mrc"
    RED_FAIL=1
else
    log="$GDIR/verify.log"
    run_gate "$GDIR/gate" "$FIX" full "$log"
    rc=$?
    observed="$(first_code "$log")"
    if [ "$rc" -ne 0 ] && [ "$observed" = "PRIVACY_REPORT" ]; then
        printf '%b\n' "MUTANT\tG1_PRIVACY\tPASS\tPRIVACY_REPORT\t$observed\trc=$rc"
    else
        printf '%b\n' "MUTANT\tG1_PRIVACY\tFAIL\tPRIVACY_REPORT\t$observed\trc=$rc"
        RED_FAIL=1
    fi
fi

AFTER_PROD="$(hash_prod)"
AFTER_FIX="$(hash_fix)"
if [ "$AFTER_PROD" = "$BEFORE_PROD" ] && [ "$AFTER_FIX" = "$BEFORE_FIX" ]; then
    printf '%b\n' "RESTORE\tPASS\tproduction-and-fixtures-byte-identical"
else
    printf '%b\n' "RESTORE\tFAIL\thash-drift"
    RED_FAIL=1
fi

LOG="$SCRATCH/green.log"
run_gate "$SCRATCH/gate" "$FIX" full "$LOG"
green_rc=$?
checks="$(sum_field "$LOG" checks)"
pass="$(sum_field "$LOG" pass)"
fail="$(sum_field "$LOG" fail)"
verdict="$(awk -F '\t' '$1=="VERDICT" {print $2}' "$LOG" | tr -d '\r')"
printf '%b\n' "GREEN\trc=$green_rc\tchecks=$checks\tpass=$pass\tfail=$fail\tverdict=$verdict"
if [ "$green_rc" -eq 0 ] && [ "$checks" = "16" ] && [ "$pass" = "16" ] && [ "$fail" = "0" ] && [ "$verdict" = "PASS" ]; then
    printf '%b\n' "GREEN\tPASS"
else
    printf '%b\n' "GREEN\tFAIL"
    RED_FAIL=1
fi

if [ "$RED_FAIL" -eq 0 ]; then
    printf '%b\n' "QA67\tPASS\tmutants=13\tregeneration=identical\trestore=identical"
    exit 0
fi
printf '%b\n' "QA67\tFAIL\tmutants-or-restore"
exit 1
