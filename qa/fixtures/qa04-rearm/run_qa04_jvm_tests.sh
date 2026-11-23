#!/usr/bin/env bash
# QA-04 deterministic JVM coverage for scheduled-recording durability.
# Compiles the UNCHANGED production schedule store and scheduler against
# isolated stubs, runs real SQLite through a bounded Python bridge, then runs
# a fault-injected negative control to prove each regression assertion fails
# when the corresponding durability invariant is broken.
set -uo pipefail

FIXTURE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$FIXTURE/../../.." && pwd -P)"
native() { cygpath -m "$1" 2>/dev/null || printf '%s\n' "$1"; }

DEFAULT_JAVA_HOME="C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2"
DEFAULT_KOTLINC="C:/Users/X1/AppData/Local/Temp/tunerostools/kotlin/kotlinc/bin/kotlinc"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$DEFAULT_JAVA_HOME" ]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi
JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi

KOTLINC="${QA04_KOTLINC:-}"
if [ -z "$KOTLINC" ]; then
  if [ -x "$DEFAULT_KOTLINC" ]; then KOTLINC="$DEFAULT_KOTLINC"
  else KOTLINC="$(command -v kotlinc || true)"; fi
fi
if [ -z "$KOTLINC" ]; then echo "BLOCKED: no Kotlin compiler on PATH or at $DEFAULT_KOTLINC"; exit 2; fi

JUNIT_JAR="${QA04_JUNIT_JAR:-}"
if [ -z "$JUNIT_JAR" ]; then
  for candidate in \
    "C:/Users/X1/AppData/Local/Temp/tunerostools/junit/junit-4.13.2.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"; do
    [ -f "$candidate" ] && JUNIT_JAR="$candidate" && break
  done
fi
HAMCREST_JAR="${QA04_HAMCREST_JAR:-}"
if [ -z "$HAMCREST_JAR" ]; then
  for candidate in \
    "C:/Users/X1/AppData/Local/Temp/tunerostools/junit/hamcrest-core-1.3.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/1dc37250fbc78e23a65c25b337adbcb614188d57/hamcrest-core-1.3.jar"; do
    [ -f "$candidate" ] && HAMCREST_JAR="$candidate" && break
  done
fi
[ -f "$JUNIT_JAR" ] || { echo "BLOCKED: JUnit 4.13.2 jar not found"; exit 2; }
[ -f "$HAMCREST_JAR" ] || { echo "BLOCKED: Hamcrest core jar not found"; exit 2; }

KOTLINC_DIR="$(cd "$(dirname "$KOTLINC")" && pwd -P)"
KOTLIN_HOME="$(dirname "$KOTLINC_DIR")"
KOTLIN_STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
# MSYS-style paths in the stock kotlinc launcher confuse the native JVM. Invoke
# the compiler jars directly with native paths when the standard layout exists.
KOTLINC_INVOKE=()
if [ -f "$KOTLIN_HOME/lib/kotlin-preloader.jar" ] && \
   [ -f "$KOTLIN_HOME/lib/kotlin-compiler.jar" ] && \
   [ -f "$KOTLIN_STDLIB" ]; then
  KOTLINC_INVOKE=(
    "$JAVA_BIN" -Xmx768M
    -cp "$(native "$KOTLIN_HOME/lib/kotlin-preloader.jar")"
    org.jetbrains.kotlin.preloading.Preloader
    -cp "$(native "$KOTLIN_HOME/lib/kotlin-compiler.jar")"
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
  )
else
  KOTLINC_INVOKE=("$KOTLINC")
fi
if [ ! -f "$KOTLIN_STDLIB" ]; then
  KOTLIN_STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-*.jar' 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$KOTLIN_STDLIB" ] || { echo "BLOCKED: Kotlin stdlib jar not found"; exit 2; }

PYTHON_BIN="${QA04_PYTHON:-$(command -v python3 || true)}"
[ -n "$PYTHON_BIN" ] || { echo "BLOCKED: python3 not found"; exit 2; }

SCRATCH="${QA04_SCRATCH:-${TMPDIR:-/tmp}/qa04-jvm}"
CLASSES="$SCRATCH/classes"
DBDIR="$SCRATCH/db"
mkdir -p "$CLASSES" "$DBDIR"
export QA04_DB_DIR="$(native "$DBDIR")"
rm -f "$DBDIR"/*.db "$DBDIR"/*.db-journal "$DBDIR"/qa04-* 2>/dev/null || true

PROD="$ROOT/app/src/main/java/com/devtunasoft/tuneros"
SOURCES=(
  "$FIXTURE/stubs/AndroidStubs.kt"
  "$FIXTURE/stubs/AndroidCursorStub.kt"
  "$FIXTURE/stubs/AndroidNetStub.kt"
  "$FIXTURE/stubs/AndroidAlarmsStubs.kt"
  "$FIXTURE/stubs/AndroidReceiverStub.kt"
  "$FIXTURE/stubs/AndroidOsStubs.kt"
  "$FIXTURE/stubs/ProductionCompanionStubs.kt"
  "$FIXTURE/stubs/SqliteBridgeStubs.kt"
  "$PROD/RecordingScheduleStore.kt"
  "$PROD/RecordingScheduler.kt"
  "$FIXTURE/src/Qa04SchedulesTest.kt"
  "$FIXTURE/src/Qa04Runner.kt"
)
NATIVE_SOURCES=()
for source in "${SOURCES[@]}"; do NATIVE_SOURCES+=("$(native "$source")"); done

echo "QA-04 compile: unchanged production store + scheduler, isolated stubs"
if ! "${KOTLINC_INVOKE[@]}" -nowarn -jvm-target 17 -cp "$(native "$JUNIT_JAR")" \
    -d "$(native "$CLASSES")" "${NATIVE_SOURCES[@]}"; then
  echo "FAIL qa04-compile Kotlin compilation failed"
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac
CLASSPATH="$(native "$CLASSES")$CP_SEP$(native "$JUNIT_JAR")$CP_SEP$(native "$HAMCREST_JAR")$CP_SEP$(native "$KOTLIN_STDLIB")"
COMMON_PROPS=(
  "-Dqa04.python=$(native "$PYTHON_BIN")"
  "-Dqa04.bridge=$(native "$FIXTURE/sqlite_bridge.py")"
  "-Dqa04.scratch=$(native "$DBDIR")"
)

run_suite() {
  local mode="$1"; shift
  local report="$SCRATCH/report-$mode.txt"
  set +e
  "$JAVA_BIN" "${COMMON_PROPS[@]}" "$@" -cp "$CLASSPATH" com.devtunasoft.tuneros.Qa04RunnerKt >"$report" 2>&1
  local status=$?
  set -e
  echo "--- QA-04 $mode run (exit $status) ---"
  cat "$report"
  return "$status"
}

set -e
# RED first: inject the three regressions and require every new assertion to
# fail. No production source is modified or fixed by this harness.
if QA04_FAULT_QUERY_DROP=true run_suite mutation \
    -Dqa04.fault.queryDrop=true \
    -Dqa04.fault.dropAlarm=true \
    -Dqa04.fault.wipeOnOpen=true; then
  echo "FAIL qa04-negative-control fault-injected regressions were not detected"
  exit 1
fi
for test_name in storeRoundTrip450PlusNoSilentRowLoss \
    startupRearmRegistersExactAlarmsForStoredFutureSchedules \
    upgradePreservesDurableScheduleRows; do
  if ! grep -Fq "$(printf 'FAIL\t%s\t' "$test_name")" "$SCRATCH/report-mutation.txt"; then
    echo "FAIL qa04-negative-control missing FAIL line for $test_name"
    exit 1
  fi
done
echo "PASS\tqa04-negative-control\tall three invariants fail under injected regression faults"

# GREEN: unchanged production code against the same assertions.
run_suite normal
if [ "$(grep -c '^PASS[[:space:]]' "$SCRATCH/report-normal.txt")" -ne 3 ] || \
   grep -q '^FAIL[[:space:]]' "$SCRATCH/report-normal.txt"; then
  echo "FAIL qa04-jvm-normal expected three PASS lines and no FAIL"
  exit 1
fi
echo "PASS\tqa04-jvm-normal\t450+ round trip, exact-alarm rearm, durable upgrade rows"
