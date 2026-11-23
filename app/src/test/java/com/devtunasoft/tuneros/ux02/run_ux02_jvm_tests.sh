#!/usr/bin/env bash
# UX-02 self-contained JVM harness. Synthetic Android stubs live only in this
# test directory and are materialised in scratch; no Android SDK, device,
# provider URL, credential or real recording is used. NOT an Android build.
set -uo pipefail

export JAVA_HOME="C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2"
JAVACMD="${JAVACMD:-$JAVA_HOME/bin/java.exe}"
KOTLIN_LIB="${KOTLIN_LIB:-C:/Users/X1/AppData/Local/Temp/tunerostools/kotlin/kotlinc/lib}"
JUNIT_DIR="${JUNIT_DIR:-C:/Users/X1/AppData/Local/Temp/tunerostools/junit}"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../../../../../../" && pwd)"
STUB_SRC="$HERE/jvm_stubs"
SCRATCH_N="${UX02_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/ux02_jvm_runner}"
SCRATCH="$(echo "$SCRATCH_N" | sed 's#^C:/#/c/#')"
native() { echo "$1" | sed 's#^/c/#C:/#'; }
REPO_N="$(native "$REPO")"
HERE_N="$(native "$HERE")"

rm -rf "$SCRATCH" && mkdir -p "$SCRATCH/classes-a" "$SCRATCH/classes-b" "$SCRATCH/stubs"
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
mapfile -t FAKES < <(find "$HERE/fakes" -name '*.kt' | sed 's#^/c/#C:/#')
APP="$REPO_N/app/src/main/java/com/devtunasoft/tuneros"

echo "[ux02] JVM harness: ${#CORE[@]} core + ${#STUBS[@]} stubs + ${#FAKES[@]} fakes"
echo "[ux02] compiling group A: parental, backup, timeshift"
kotlinc -jvm-target 17 -nowarn -cp "$JUNIT_DIR/junit-4.13.2.jar" -d "$SCRATCH_N/classes-a" \
  "${CORE[@]}" "${STUBS[@]}" \
  "$APP/ParentalControlsStore.kt" "$APP/BackupRestore.kt" \
  "$APP/TimeshiftRecorder.kt" "$APP/AndroidHlsCaptureSource.kt" \
  "$APP/SourceHttp.kt" "$APP/HttpStatusException.kt" \
  "$HERE_N/FakeSharedPreferences.kt" "$HERE_N/ParentalLockTransitionTest.kt" \
  "$HERE_N/BackupRestoreRegressionTest.kt" "$HERE_N/TimeshiftCancelTest.kt" \
  "${FAKES[@]}" || exit 2

echo "[ux02] compiling group B: series rule store"
kotlinc -jvm-target 17 -nowarn -cp "$JUNIT_DIR/junit-4.13.2.jar" -d "$SCRATCH_N/classes-b" \
  "${CORE[@]}" "${STUBS[@]}" "$APP/SeriesRuleStore.kt" \
  "$HERE_N/FakeSharedPreferences.kt" "$HERE_N/SeriesRuleCancelTest.kt" || exit 2

CP_A="$SCRATCH_N/classes-a;$KOTLIN_LIB/kotlin-stdlib.jar;$KOTLIN_LIB/kotlin-stdlib-jdk8.jar;$JUNIT_DIR/junit-4.13.2.jar;$JUNIT_DIR/hamcrest-core-1.3.jar"
CP_B="$SCRATCH_N/classes-b;$KOTLIN_LIB/kotlin-stdlib.jar;$KOTLIN_LIB/kotlin-stdlib-jdk8.jar;$JUNIT_DIR/junit-4.13.2.jar;$JUNIT_DIR/hamcrest-core-1.3.jar"

echo "[ux02] running group A"
"$JAVACMD" -cp "$CP_A" org.junit.runner.JUnitCore \
  com.devtunasoft.tuneros.ux02.ParentalLockTransitionTest \
  com.devtunasoft.tuneros.ux02.BackupRestoreRegressionTest \
  com.devtunasoft.tuneros.ux02.TimeshiftCancelTest
A=$?
echo "[ux02] running group B"
"$JAVACMD" -cp "$CP_B" org.junit.runner.JUnitCore \
  com.devtunasoft.tuneros.ux02.SeriesRuleCancelTest
B=$?
echo "[ux02] exit codes: A=$A B=$B"
exit $(( A != 0 || B != 0 ))
