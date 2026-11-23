#!/usr/bin/env bash
# MV-04 bounded runner for the deterministic multiview lifecycle churn acceptance.
# Owned paths only: app/src/androidTest/**, qa/fixtures/mv04/**, scripts/**.
# No Gradle/manifest/app-source edit, no external URL, credential or real media.
# Device absence is reported BLOCKED, never PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_ROOT="$ROOT"
if pwd -W >/dev/null 2>&1; then NATIVE_ROOT="$(cd "$ROOT" && pwd -W)"; fi
PACKAGE="com.devtunasoft.tuneros"
TEST_PACKAGE="${MV04_TEST_PACKAGE:-${PACKAGE}.test}"
RUNNER="com.devtunasoft.tuneros.Mv04LifecycleChurnInstrumentation"
OUT="$ROOT/build/mv04"
CHECKS=(mv04-loopback-fixture mv04-bounded-churn mv04-exactly-one-audible \
        mv04-capacity-refusal-fallback mv04-clean-teardown \
        mv04-no-leaked-players mv04-no-leaked-handler-threads)

log() { printf '%s MV-04 %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
blocked() { log "BLOCKED $*"; exit 2; }
fail() { log "FAIL $*"; exit 1; }

cd "$ROOT"
mkdir -p "$OUT"

# JDK 17+: JAVA_HOME may be a native C:/ path on MSYS hosts.
if [ -z "${JAVA_HOME:-}" ]; then blocked "JAVA_HOME is not set (JDK 17+ required)"; fi
case "$JAVA_HOME" in
  [A-Za-z]:/*) JH="/$(printf '%s' "${JAVA_HOME:0:1}" | tr 'A-Z' 'a-z')${JAVA_HOME:2}" ;;
  *) JH="$JAVA_HOME" ;;
esac
[ -x "$JH/bin/javac" ] || blocked "javac not found under JAVA_HOME"
[ -x "$JH/bin/java" ] || blocked "java not found under JAVA_HOME"

log "JVM generator, verifier and negative-mutation gate"
mkdir -p "$OUT/jvm"
JVM_OUT="$(cd "$OUT/jvm" && pwd -W 2>/dev/null || printf '%s' "$OUT/jvm")"
"$JH/bin/javac" -encoding UTF-8 -d "$JVM_OUT" \
  "$NATIVE_ROOT/qa/fixtures/mv04/Mv04Scenario.java" || fail "Mv04Scenario.java did not compile"
"$JH/bin/java" -cp "$JVM_OUT" \
  Mv04Scenario --all "$NATIVE_ROOT/qa/fixtures/mv04/generated/churn.trace" \
  >"$OUT/jvm-gate.txt" 2>&1 || { cat "$OUT/jvm-gate.txt"; fail "JVM contract gate failed"; }
cat "$OUT/jvm-gate.txt"
grep -Fq 'PASS	mv04-jvm-gate' "$OUT/jvm-gate.txt" || fail "JVM gate summary missing"
for mutation in two-audible-owners surviving-player-at-teardown third-tile-on-refusal; do
  grep -Fq "PASS	mv04-negative-mutation	$mutation" "$OUT/jvm-gate.txt" \
    || fail "negative control not rejected: $mutation"
done

log "source contract gate"
SRC="$NATIVE_ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/Mv04LifecycleChurnInstrumentation.kt"
MANIFEST="$NATIVE_ROOT/app/src/androidTest/AndroidManifest.xml"
for check in "${CHECKS[@]}"; do
  grep -Fq "\"$check\"" "$SRC" || fail "instrumentation source lacks check: $check"
done
grep -Fq "$RUNNER" "$MANIFEST" || fail "test manifest does not register the MV-04 runner"
if grep -Eo 'https?://[^"[:space:]]+' "$SRC" | grep -qv '^http://127\.0\.0\.1:'; then
  fail "instrumentation source contains a non-loopback URL"
fi
cmp -s "$NATIVE_ROOT/qa/fixtures/mv04/generated/churn.trace" \
  "$NATIVE_ROOT/app/src/androidTest/assets/mv04/churn.trace" \
  || fail "loopback asset differs from the generated fixture"
log "PASS mv04-source-gate seven checks, registered runner, loopback-only fixture"

log "compiled-source check (unmodified Gradle build)"
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || blocked "Android SDK path is not set"
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
timeout 900 ./gradlew --no-daemon --offline \
  :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest >"$OUT/compile.log" 2>&1 \
  || { tail -80 "$OUT/compile.log"; fail "instrumented test APK did not compile"; }
log "PASS mv04-instrumented-compile 7 checks, zero test dependencies"

log "device probe"
ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"; fi
[ -n "$ADB" ] || blocked "adb is not installed; device execution UNVERIFIED"
DEVICES="$("$ADB" devices | awk 'NR>1 && $2 == "device" {print $1}')"
[ -n "$DEVICES" ] || blocked "no authorized Android device or emulator is attached; device execution UNVERIFIED"
[ "$(printf '%s\n' "$DEVICES" | wc -l)" -eq 1 ] || blocked "more than one device is attached; set a single target"

log "install and run seven bounded checks on one scratch device"
timeout 600 ./gradlew --no-daemon --offline :app:assembleDebug >>"$OUT/compile.log" 2>&1 \
  || { tail -80 "$OUT/compile.log"; fail "debug APK did not compile"; }
"$ADB" install -r -t "$NATIVE_ROOT/app/build/outputs/apk/debug/app-debug.apk" >"$OUT/install-app.log" 2>&1 \
  || { tail -40 "$OUT/install-app.log"; fail "app install failed"; }
"$ADB" install -r -t "$NATIVE_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  >"$OUT/install-test.log" 2>&1 || { tail -40 "$OUT/install-test.log"; fail "test APK install failed"; }
timeout 300 "$ADB" shell am instrument -w -r "$TEST_PACKAGE/$RUNNER" >"$OUT/instrumentation.txt" 2>&1 || true
cat "$OUT/instrumentation.txt"
"$ADB" shell run-as "$PACKAGE" cat files/mv04-lifecycle-report.txt >"$OUT/report.txt" 2>/dev/null || true
[ -s "$OUT/report.txt" ] || blocked "the app wrote no MV-04 report"
cat "$OUT/report.txt"
for check in "${CHECKS[@]}"; do
  printf -v needle 'PASS\t%s\t' "$check"
  grep -Fq "$needle" "$OUT/report.txt" || fail "$check"
done
if grep -Fq 'FAIL	' "$OUT/report.txt"; then fail "report contains a failing check"; fi
grep -Fq '7 PASS	0 FAIL' "$OUT/report.txt" || fail "report summary is not 7 PASS / 0 FAIL"
grep -Fq 'INSTRUMENTATION_RESULT: mv04_failures=0' "$OUT/instrumentation.txt" \
  || fail "instrumentation result is not successful"
log "PASS mv04-device-acceptance seven compiled lifecycle churn checks passed"
log "report: $OUT/report.txt"
