#!/usr/bin/env bash
set -euo pipefail
# Capture a bounded crash/exception log while the emulator is
# still running. Successful runs generate no logcat artifact.
on_failure() {
  status=$?
  if [ "$status" -ne 0 ]; then
    adb logcat -d -t 1200 -v brief > emulator-failure-logcat.txt 2>/dev/null || true
  fi
}
trap on_failure EXIT
adb install -r ci-apk/app-debug.apk
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.qa true
# The app runs the checks itself; CI only collects/asserts its private
# debug report. No human interaction, provider streams or credentials.
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-selftest-report.txt > on-device-report.txt 2>/dev/null || true
  if grep -Fq "$(printf 'PASS\tdebug-large-guide-stress\t')" on-device-report.txt && \
     grep -Fq "$(printf 'PASS\tdebug-ui-focus\t')" on-device-report.txt && \
     grep -Fq "$(printf 'PASS\tdebug-empty-guide-dpad\t')" on-device-report.txt && \
     grep -Fq "$(printf 'PASS\tdebug-now-marker\t')" on-device-report.txt && \
     ! adb shell run-as com.devtunasoft.tuneros ls files/tuneros-selftest-in-progress >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
for check in source-recovery-policy offline-m3u-parser offline-source-stall offline-xmltv-parser debug-xmltv-depth-limit offline-channel-filter offline-timeline offline-xmltv-dtd-block scratch-dvr-copy scratch-dvr-catalog android-keystore-roundtrip debug-large-guide-stress debug-hls-dvr-sequence debug-ui-focus debug-empty-guide-dpad debug-now-marker; do
  if ! grep -Fq "$(printf 'PASS\t%s\t' "$check")" on-device-report.txt; then
    echo "On-device QA assertion failed or never completed: $check"
    cat on-device-report.txt
    exit 1
  fi
done
if adb shell run-as com.devtunasoft.tuneros ls files/tuneros-selftest-in-progress >/dev/null 2>&1; then
  echo "The self-test has not committed its report and cleared its run marker"
  exit 1
fi
if grep -q '^FAIL[[:space:]]' on-device-report.txt; then
  echo "Unexpected failure in the app's own self-test report"
  cat on-device-report.txt
  exit 1
fi
cat on-device-report.txt
