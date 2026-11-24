#!/usr/bin/env bash
# QA-66 offline JVM check of the androidTest mirror (no emulator, no device).
set -uo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
REPO="$(cd "$HERE/../.." && native_pwd)"

JDK_BIN="${QA66_JDK_BIN:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin}"
JAVA="$JDK_BIN/java.exe"
GM2=C:/Users/X1/.gradle/caches/modules-2/files-2.1
KS="$GM2/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar"
KR="$GM2/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/c9b044380ad41f89aa89aa896c2d32a8c0b2129d/kotlin-script-runtime-2.0.21.jar"
KT="$GM2/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar"
KO="$GM2/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/2c997cd1c0ef33f3e751d3831929aeff1390cb30/kotlinx-coroutines-core-jvm-1.6.4.jar"
KA="$GM2/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
KC="$GM2/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar"
KCP="$KC;$KS;$KR;$KT;$KO;$KA"

OUT="$(cygpath -m "${TMPDIR:-/tmp}")/qa66_mirror_final"
rm -rf "$OUT"
mkdir -p "$OUT"
timeout 180 "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$KCP" -d "$OUT" \
    "$REPO/core/src/main/kotlin/com/devtunasoft/tuneros/core/ChannelVisibility.kt" \
    "$REPO/core/src/main/kotlin/com/devtunasoft/tuneros/core/Models.kt" \
    "$REPO/app/src/androidTest/java/com/devtunasoft/tuneros/qa66/Qa66Assertions.kt" \
    "$HERE/Qa66MirrorMain.kt" > "$OUT/k2.log" 2>&1 || {
    printf 'MIRROR\tFAIL\tcompile\n'
    cat "$OUT/k2.log"
    exit 1
}
LOG="$OUT/run.log"
timeout 90 "$JAVA" -Xmx96m -cp "$OUT;$KS" com.devtunasoft.tuneros.qa66.Qa66MirrorMainKt > "$LOG" 2>&1
rc=$?
tail -n 3 "$LOG"
if [[ "$rc" -eq 0 ]] && grep -aq $'MIRROR\tchecks=17\tfail=0' "$LOG"; then
    printf 'MIRROR\tPASS\tchecks=17\tfail=0\trc=0\n'
else
    printf 'MIRROR\tFAIL\trc=%s\n' "$rc"
    exit 1
fi
