#!/usr/bin/env bash
# DVR-02 self-contained JVM fault-injection runner.
# Compiles the pure-Kotlin DVR decision/sink code with minimal android.* stubs that are
# materialised in a scratch directory and NEVER compiled into the app. No Android SDK,
# device or provider is involved. This is NOT an Android build or emulator verification.
set -uo pipefail

JAVACMD="${JAVACMD:-C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2/bin/java.exe}"
KOTLIN_LIB="${KOTLIN_LIB:-C:/Users/X1/AppData/Local/Temp/tunerostools/kotlin/kotlinc/lib}"
JUNIT_DIR="${JUNIT_DIR:-C:/Users/X1/AppData/Local/Temp/tunerostools/junit}"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../../../../.." && pwd)"
STUB_SRC="$HERE/jvm_stubs"
SCRATCH_N="${DVR02_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/dvr02_jvm_runner}"
SCRATCH="$(echo "$SCRATCH_N" | sed 's#^C:/#/c/#')"

native() { echo "$1" | sed 's#^/c/#C:/#'; }
REPO_N="$(native "$REPO")"

rm -rf "$SCRATCH" && mkdir -p "$SCRATCH/classes" "$SCRATCH/stubs"

while IFS= read -r stub; do
  rel="${stub#$STUB_SRC/}"
  dest="$SCRATCH/stubs/${rel%.txt}"
  mkdir -p "$(dirname "$dest")"
  cp "$stub" "$dest"
done < <(find "$STUB_SRC" -name '*.kt.txt')

kotlinc() {
  "$JAVACMD" -Xmx1g -cp "$KOTLIN_LIB/kotlin-preloader.jar" \
    org.jetbrains.kotlin.preloading.Preloader \
    -cp "$KOTLIN_LIB/kotlin-compiler.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"
}

mapfile -t CORE < <(find "$REPO/core/src/main/kotlin" -name '*.kt' | sed 's#^/c/#C:/#')
mapfile -t STUBS < <(find "$SCRATCH/stubs" -name '*.kt' | sed 's#^/c/#C:/#')
mapfile -t TESTS < <(find "$REPO/app/src/test/java" -name '*.kt' | sed 's#^/c/#C:/#')

echo "[dvr02] JVM fault-injection: ${#CORE[@]} core + ${#STUBS[@]} stubs + ${#TESTS[@]} tests"
kotlinc -jvm-target 17 -nowarn -cp "$JUNIT_DIR/junit-4.13.2.jar" -d "$SCRATCH_N/classes" \
  "${CORE[@]}" "${STUBS[@]}" \
  "$REPO_N/app/src/main/java/com/devtunasoft/tuneros/RecordingSink.kt" "${TESTS[@]}" || exit 2

"$JAVACMD" -cp "$SCRATCH_N/classes;$KOTLIN_LIB/kotlin-stdlib.jar;$KOTLIN_LIB/kotlin-stdlib-jdk8.jar;$JUNIT_DIR/junit-4.13.2.jar;$JUNIT_DIR/hamcrest-core-1.3.jar" \
  org.junit.runner.JUnitCore \
  com.devtunasoft.tuneros.DvrFinalizationRegressionTest \
  com.devtunasoft.tuneros.DvrOutcomePolicyTest \
  com.devtunasoft.tuneros.DvrStorageFaultPolicyTest \
  com.devtunasoft.tuneros.DvrStorageCatalogTest \
  com.devtunasoft.tuneros.DvrStorageCancellationTest
