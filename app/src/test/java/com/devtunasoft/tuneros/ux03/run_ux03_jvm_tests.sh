#!/usr/bin/env bash
# UX-03 self-contained JVM harness. Synthetic Android stubs live only in this
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
SCRATCH_N="${UX03_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/ux03_jvm_runner}"
SCRATCH="$(echo "$SCRATCH_N" | sed 's#^C:/#/c/#')"
native() { echo "$1" | sed 's#^/c/#C:/#'; }
REPO_N="$(native "$REPO")"
HERE_N="$(native "$HERE")"
APP="$REPO_N/app/src/main/java/com/devtunasoft/tuneros"

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

echo "[ux03] JVM harness: ${#CORE[@]} core + ${#STUBS[@]} stubs"
kotlinc -jvm-target 17 -nowarn -cp "$JUNIT_DIR/junit-4.13.2.jar" -d "$SCRATCH_N/classes" \
  "${CORE[@]}" "${STUBS[@]}" \
  "$APP/ParentalQa.kt" "$APP/BackupQa.kt" "$APP/TimeshiftQa.kt" \
  "$APP/ParentalControlsStore.kt" "$APP/BackupRestore.kt" \
  "$APP/TimeshiftRecorder.kt" "$APP/AndroidHlsCaptureSource.kt" "$APP/AndroidTimeshiftRing.kt" \
  "$APP/SourceHttp.kt" "$APP/HttpStatusException.kt" \
  "$HERE_N/FakeQaContext.kt" "$HERE_N/QaReportPrivacyTest.kt" \
  "$HERE_N/fakes/QaStoreFakes.kt" || exit 2

"$JAVACMD" -cp "$SCRATCH_N/classes;$KOTLIN_LIB/kotlin-stdlib.jar;$KOTLIN_LIB/kotlin-stdlib-jdk8.jar;$JUNIT_DIR/junit-4.13.2.jar;$JUNIT_DIR/hamcrest-core-1.3.jar" \
  org.junit.runner.JUnitCore \
  com.devtunasoft.tuneros.ux03.QaReportPrivacyTest
