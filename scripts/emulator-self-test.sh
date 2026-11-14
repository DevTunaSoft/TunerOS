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
# Autonomous test setup: grant exact-alarm access to the debug build so the alarm
# check runs for real instead of reporting BLOCKED. No human interaction.
echo "appops set (cmd): $(adb shell cmd appops set com.devtunasoft.tuneros SCHEDULE_EXACT_ALARM allow 2>&1)"
echo "appops set (appops): $(adb shell appops set com.devtunasoft.tuneros SCHEDULE_EXACT_ALARM allow 2>&1)"
echo "exact-alarm appop: $(adb shell appops get com.devtunasoft.tuneros SCHEDULE_EXACT_ALARM 2>&1)"
echo "app uid: $(adb shell dumpsys package com.devtunasoft.tuneros 2>/dev/null | grep -m1 userId= || true)"
adb shell dumpsys appops com.devtunasoft.tuneros 2>&1 | grep -iE "EXACT|uid|mode" | head -20 || true
adb shell dumpsys package com.devtunasoft.tuneros 2>&1 | grep -iE "userId|appId|SCHEDULE_EXACT" | head -10 || true
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.qa true
# The app runs the checks itself; CI only collects/asserts its private
# debug report. No human interaction, provider streams or credentials.
for attempt in $(seq 1 120); do
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
for check in source-recovery-policy offline-m3u-parser offline-source-stall offline-xmltv-parser debug-xmltv-depth-limit offline-channel-filter offline-timeline offline-xmltv-dtd-block scratch-dvr-copy scratch-dvr-catalog scratch-dvr-schedule-reservation scratch-dvr-preparation-recovery android-keystore-roundtrip debug-large-guide-stress debug-hls-dvr-sequence debug-hls-response-length debug-loopback-hls-transport debug-connection-abort debug-internal-dvr-finalize debug-saf-storage-guard debug-ui-focus debug-empty-guide-dpad debug-now-marker; do
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
# Isolated launch for the foreground-service stop check. It runs separately so it
# cannot disturb the main QA pass, and its own result file is required.
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.service_stop true
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-service-stop-report.txt > service-stop-report.txt 2>/dev/null || true
  if grep -Fq "$(printf 'PASS\tdebug-service-stop-cancels\t')" service-stop-report.txt && \
     grep -Fq "$(printf 'PASS\tdebug-service-destroy-cancels\t')" service-stop-report.txt; then
    break
  fi
  sleep 2
done
if ! grep -Fq "$(printf 'PASS\tdebug-service-stop-cancels\t')" service-stop-report.txt || \
   ! grep -Fq "$(printf 'PASS\tdebug-service-destroy-cancels\t')" service-stop-report.txt; then
  echo "Foreground service stop check failed or never completed"
  cat service-stop-report.txt 2>/dev/null || echo "(no report file)"
  echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null || echo none)"
  adb logcat -d -v brief 2>/dev/null | grep -E "FATAL|AndroidRuntime|DvrRecording|ActivityManager.*(died|crash|ANR)|ForegroundService|tuneros" | tail -120 || true
  exit 1
fi
if grep -q '^FAIL[[:space:]]' on-device-report.txt; then
  echo "Unexpected failure in the app's own self-test report"
  cat on-device-report.txt
  exit 1
fi
cat on-device-report.txt
