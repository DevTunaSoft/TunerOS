#!/usr/bin/env bash
# MV-03 bounded runner for the compiled two-tile multiview UI acceptance.
# Owned paths only: app/src/androidTest/**, qa/fixtures/mv03/**, scripts/**.
# No Gradle/manifest/app-source edit, no provider URL, credential, real media or
# manual remote operation. Device absence is reported BLOCKED, never PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_NATIVE="$ROOT"
if pwd -W >/dev/null 2>&1; then ROOT_NATIVE="$(cd "$ROOT" && pwd -W)"; fi
PACKAGE="com.devtunasoft.tuneros"
TEST_PACKAGE="${MV03_TEST_PACKAGE:-${PACKAGE}.test}"
RUNNER="com.devtunasoft.tuneros.MultiviewUiAcceptanceInstrumentation"
OUT="$ROOT/build/mv03"
PORT=8793

log() { printf '%s MV-03 %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
blocked() { log "BLOCKED $*"; exit 2; }

cd "$ROOT"
mkdir -p "$OUT"

log "source gate"
python3 "$ROOT_NATIVE/qa/fixtures/mv03/verify_mv03_sources.py"

log "app prerequisite probe"
if ! python3 "$ROOT_NATIVE/qa/fixtures/mv03/mv03_app_contract_probe.py"; then
  blocked "the app under test has no two-tile UI; MV-02 must be integrated first"
fi

log "fixture determinism (fixed anchor, 127.0.0.1 only)"
ANCHOR="$(date -u +%s)"
ANCHOR=$((ANCHOR - ANCHOR % 1800))
for variant in a b; do
  python3 "$ROOT_NATIVE/qa/fixtures/mv03/generate_fixture.py" \
    --port "$PORT" --anchor "$ANCHOR" --out "$ROOT_NATIVE/${OUT#$ROOT/}/fixture-$variant" >/dev/null
done
for name in playlist.m3u guide.xml; do
  a="$(sha256sum "$OUT/fixture-a/$name" | cut -d' ' -f1)"
  b="$(sha256sum "$OUT/fixture-b/$name" | cut -d' ' -f1)"
  [ "$a" = "$b" ] || { log "FAIL fixture not deterministic: $name"; exit 1; }
done
log "PASS mv03-fixture-determinism 3 channels, 6 programmes, identical SHA-256"

command -v adb >/dev/null 2>&1 || blocked "adb is not installed"
DEVICES="$(adb devices | awk 'NR>1 && $2 == "device" {print $1}')"
[ -n "$DEVICES" ] || blocked "no authorized Android device or emulator is attached"
[ "$(printf '%s\n' "$DEVICES" | wc -l)" -eq 1 ] || blocked "more than one device is attached; set a single target"
[ -x ./gradlew ] || blocked "Gradle wrapper is missing"
command -v java >/dev/null 2>&1 || blocked "Java 17 is not available"
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || blocked "Android SDK path is not set"

log "compile debug APK and instrumented test APK"
timeout 900 ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1 || {
  awk 'NR<=120' "$OUT/build.log"
  log "FAIL instrumented APK did not compile"
  exit 1
}
log "PASS mv03-instrumented-compile"

log "install and run the six bounded checks on one scratch device"
adb install -r -t "$ROOT_NATIVE/app/build/outputs/apk/debug/app-debug.apk" >"$OUT/install-app.log" 2>&1
adb install -r -t "$ROOT_NATIVE/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >"$OUT/install-test.log" 2>&1
timeout 300 adb shell am instrument -w -r "$TEST_PACKAGE/$RUNNER" >"$OUT/instrumentation.txt" 2>&1 || true
awk 'NR<=120' "$OUT/instrumentation.txt"

adb shell run-as "$PACKAGE" cat files/mv03-ui-report.txt >"$OUT/report.txt" 2>/dev/null || true
[ -s "$OUT/report.txt" ] || blocked "the app wrote no MV-03 report"
awk 'NR<=40' "$OUT/report.txt"

for check in mv03-loopback-import mv03-two-bounded-tiles-shown mv03-third-tile-refused \
             mv03-single-audible-tile mv03-tiles-released-cleanly mv03-relaunch-has-no-tiles; do
  printf -v needle 'PASS\t%s\t' "$check"
  grep -Fq "$needle" "$OUT/report.txt" || { log "FAIL $check"; exit 1; }
done
printf -v fail_needle 'FAIL\t'
if grep -Fq "$fail_needle" "$OUT/report.txt"; then
  log "FAIL report contains a failing check"
  exit 1
fi
printf -v summary_needle '6 PASS\t0 FAIL'
grep -Fq "$summary_needle" "$OUT/report.txt" || { log "FAIL report summary is not 6 PASS / 0 FAIL"; exit 1; }
grep -Fq 'INSTRUMENTATION_RESULT: mv03_failures=0' "$OUT/instrumentation.txt" || {
  log "FAIL instrumentation result is not successful"
  exit 1
}
log "PASS mv03-device-acceptance six compiled UI checks passed"
log "report: $OUT/report.txt"
