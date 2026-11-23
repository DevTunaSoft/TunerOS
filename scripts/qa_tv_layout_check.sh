#!/usr/bin/env bash
# QA-01 bounded runner: compile the instrumented APK, then run the compiled
# TV-guide layout / D-pad / OK acceptance on one scratch Android device.
# No provider URL, credential, real media or manual remote operation is used.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Native Windows tools (python3, adb) do not translate MSYS paths on this host.
ROOT_NATIVE="$ROOT"
if pwd -W >/dev/null 2>&1; then ROOT_NATIVE="$(cd "$ROOT" && pwd -W)"; fi
PACKAGE="com.devtunasoft.tuneros"
TEST_PACKAGE="${QA_TV_TEST_PACKAGE:-${PACKAGE}.test}"
RUNNER="com.devtunasoft.tuneros.TvGuideLayoutInstrumentation"
OUT="${ROOT}/build/qa-tv-layout"
FIXTURES_REL="qa/fixtures/tv-guide-layout"
ANCHOR="$(date -u +%s)"
ANCHOR=$((ANCHOR - ANCHOR % 1800))
PORT=8791

log() { printf '%s QA-01 %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
not_run() { log "NOT RUN $*"; exit 2; }

cd "$ROOT"
mkdir -p "$OUT"

log "static source gate"
python3 "$ROOT_NATIVE/$FIXTURES_REL/verify_qa01_sources.py"

log "fixture determinism (fixed anchor, no network)"
python3 "$ROOT_NATIVE/$FIXTURES_REL/generate_fixture.py" --port "$PORT" --anchor "$ANCHOR" --out "$ROOT_NATIVE/${OUT#$ROOT/}/fixture-a"
python3 "$ROOT_NATIVE/$FIXTURES_REL/generate_fixture.py" --port "$PORT" --anchor "$ANCHOR" --out "$ROOT_NATIVE/${OUT#$ROOT/}/fixture-b"
for name in playlist.m3u guide.xml; do
  a="$(sha256sum "$OUT/fixture-a/$name" | cut -d' ' -f1)"
  b="$(sha256sum "$OUT/fixture-b/$name" | cut -d' ' -f1)"
  [ "$a" = "$b" ] || { log "FAIL fixture is not deterministic for $name"; exit 1; }
done
log "PASS qa01-fixture-determinism 3 channels, 9 programmes, identical SHA-256"

command -v adb >/dev/null 2>&1 || not_run "adb is not installed"
DEVICES="$(adb devices | awk 'NR>1 && $2 == "device" {print $1}')"
[ -n "$DEVICES" ] || not_run "no authorized Android device is attached"
[ "$(printf '%s\n' "$DEVICES" | wc -l)" -eq 1 ] || not_run "more than one device is attached; set a single target"
[ -x ./gradlew ] || not_run "Gradle wrapper is missing"
command -v java >/dev/null 2>&1 || not_run "JAVA_HOME/Java 17 is not available"
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || not_run "Android SDK path is not set"

log "compile debug APK and instrumented test APK"
timeout 900 ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1 || {
  awk 'NR<=120' "$OUT/build.log"
  log "FAIL instrumented APK did not compile"
  exit 1
}
log "PASS qa01-instrumented-compile"

log "install and run on the scratch device"
adb install -r -t "$ROOT_NATIVE/app/build/outputs/apk/debug/app-debug.apk" >"$OUT/install-app.log" 2>&1
adb install -r -t "$ROOT_NATIVE/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >"$OUT/install-test.log" 2>&1
timeout 180 adb shell am instrument -w -r "$TEST_PACKAGE/$RUNNER" >"$OUT/instrumentation.txt" 2>&1 || true
awk 'NR<=120' "$OUT/instrumentation.txt"

adb shell run-as "$PACKAGE" cat files/qa-tv-layout-report.txt >"$OUT/report.txt" 2>/dev/null || true
[ -s "$OUT/report.txt" ] || not_run "the app wrote no QA-01 report"
awk 'NR<=40' "$OUT/report.txt"

for check in guide-loaded-vs-unloaded guide-row-bounds-no-clipping dpad-focus-transitions ok-select-to-play; do
  printf -v needle 'PASS	%s	' "$check"
  grep -Fq "$needle" "$OUT/report.txt" || { log "FAIL $check"; exit 1; }
done
printf -v needle 'FAIL	'
if grep -Fq "$needle" "$OUT/report.txt"; then
  log "FAIL report contains a failing check"
  exit 1
fi
printf -v needle '4 PASS	0 FAIL'
grep -Fq "$needle" "$OUT/report.txt" || { log "FAIL report summary is not 4 PASS / 0 FAIL"; exit 1; }
grep -Fq 'INSTRUMENTATION_RESULT: qa_tv_failures=0' "$OUT/instrumentation.txt" || {
  log "FAIL instrumentation result is not successful"
  exit 1
}
log "PASS qa01-device-acceptance 4 compiled checks passed on one scratch Android device"
log "report: $OUT/report.txt"
