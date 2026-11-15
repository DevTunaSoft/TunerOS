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
APP_UID=$(adb shell dumpsys package com.devtunasoft.tuneros 2>/dev/null > uid-dump.txt; tr -d '\r' < uid-dump.txt | grep -o 'appId=[0-9]*' | head -1 | cut -d= -f2 || true)
echo "app uid: ${APP_UID:-unknown}"
adb shell cmd appops set --uid "$APP_UID" SCHEDULE_EXACT_ALARM allow; echo "set-by-uid rc=$?"
adb shell cmd appops set com.devtunasoft.tuneros SCHEDULE_EXACT_ALARM allow; echo "set-by-pkg rc=$?"
echo "get: $(adb shell cmd appops get com.devtunasoft.tuneros SCHEDULE_EXACT_ALARM 2>&1 | tr -d '\r')"
echo "uid-get: $(adb shell cmd appops get --uid "$APP_UID" SCHEDULE_EXACT_ALARM 2>&1 | tr -d '\r')"
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
# Isolated launch for exact-alarm delivery. The alarm is scheduled first; only after it is
# armed does this script force the device into deep Doze and sleep the screen. The alarm
# must still be delivered while idle.
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.alarm true
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-alarm-report.txt > alarm-report.txt 2>/dev/null || true
  if grep -Fq "SCHEDULED" alarm-report.txt; then
    break
  fi
  sleep 2
done
adb shell dumpsys deviceidle force-idle || true
adb shell input keyevent 223 || true
for attempt in $(seq 1 330); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-alarm-report.txt > alarm-report.txt 2>/dev/null || true
  if grep -Eq "$(printf '^(PASS|FAIL)\tqa-alarm-delivery\t')" alarm-report.txt; then
    break
  fi
  sleep 2
done
if ! grep -Fq "$(printf 'PASS\tqa-alarm-delivery\tExact alarm delivered, idle=true')" alarm-report.txt; then
  echo "Exact alarm delivery during Doze failed or never completed"
  cat alarm-report.txt 2>/dev/null || echo "(no report file)"
  adb shell dumpsys deviceidle unforce || true
  exit 1
fi
adb shell dumpsys deviceidle unforce || true
# Isolated launch for first decoded video frame. The runner generates a short test stream,
# serves it on the host, and the emulator reaches it through the host alias 10.0.2.2.
command -v ffmpeg >/dev/null || sudo apt-get install -y -qq ffmpeg
mkdir -p ci-playback
ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=10:size=320x240:rate=25 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart ci-playback/test.mp4
(cd ci-playback && python3 -m http.server 8765 --bind 0.0.0.0 >http-server.log 2>&1) &
HTTP_PID=$!
sleep 2
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.playback_url http://10.0.2.2:8765/test.mp4
for attempt in $(seq 1 90); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-playback-report.txt > playback-report.txt 2>/dev/null || true
  if grep -Fq "$(printf 'PASS\tqa-playback-first-frame\tfirst video frame rendered')" playback-report.txt; then
    break
  fi
  sleep 2
done
kill "$HTTP_PID" 2>/dev/null || true
if ! grep -Fq "$(printf 'PASS\tqa-playback-first-frame\tfirst video frame rendered')" playback-report.txt; then
  echo "First video frame check failed or never completed"
  cat playback-report.txt 2>/dev/null || echo "(no report file)"
  cat ci-playback/http-server.log 2>/dev/null | tail -20 || true
  echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null || echo none)"
  adb logcat -d -v brief 2>/dev/null | grep -E "FATAL|AndroidRuntime|ExoPlayer|MediaCodec|ImageReader|tuneros|Playback|DecoderInit" | tail -120 || true
  exit 1
fi
# Isolated launch for the real import path. Fixtures are generated on the runner and served to
# the emulator; the app imports them without saving the URLs. Counts must match exactly.
mkdir -p ci-import
cat > ci-import/playlist.m3u <<'M3U'
#EXTM3U
#EXTINF:-1 tvg-id="qa.one" group-title="QA",QA One
http://10.0.2.2:8766/stream-one.m3u8
#EXTINF:-1 tvg-id="qa.two" group-title="QA",QA Two
http://10.0.2.2:8766/stream-two.m3u8
#EXTINF:-1 tvg-id="qa.three" group-title="QA",QA Three
http://10.0.2.2:8766/stream-three.m3u8
M3U
cat > ci-import/guide.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<tv>
<channel id="qa.one"><display-name>QA One</display-name></channel>
<channel id="qa.two"><display-name>QA Two</display-name></channel>
<channel id="qa.three"><display-name>QA Three</display-name></channel>
<programme start="20991115000000 +0000" stop="20991115010000 +0000" channel="qa.one"><title>Show One</title></programme>
<programme start="20991115010000 +0000" stop="20991115020000 +0000" channel="qa.one"><title>Show Two</title></programme>
<programme start="20991115000000 +0000" stop="20991115010000 +0000" channel="qa.two"><title>Show Three</title></programme>
<programme start="20991115010000 +0000" stop="20991115020000 +0000" channel="qa.two"><title>Show Four</title></programme>
<programme start="20991115000000 +0000" stop="20991115010000 +0000" channel="qa.three"><title>Show Five</title></programme>
<programme start="20991115010000 +0000" stop="20991115020000 +0000" channel="qa.three"><title>Show Six</title></programme>
</tv>
XML
(cd ci-import && python3 -m http.server 8766 --bind 0.0.0.0 >http-server.log 2>&1) &
IMPORT_HTTP_PID=$!
sleep 2
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity \
  --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
  --es tuneros.debug.import_guide_url http://10.0.2.2:8766/guide.xml
for attempt in $(seq 1 90); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-import-report.txt > import-report.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' import-report.txt; then
    break
  fi
  sleep 2
done
kill "$IMPORT_HTTP_PID" 2>/dev/null || true
if ! grep -Fq "$(printf 'PASS\tqa-loopback-import\tchannels=3 programs=6')" import-report.txt; then
  echo "Loopback import check failed or never completed"
  cat import-report.txt 2>/dev/null || echo "(no report file)"
  cat ci-import/http-server.log 2>/dev/null | tail -20 || true
  exit 1
fi
# Isolated launch for remote key input: keys are injected with adb after the app reports it
# is armed. Each key must reach the activity's key dispatch, not a synthetic call.
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.keys true
# The Doze step above sleeps the screen; wake it and dismiss the keyguard so input reaches the app.
adb shell input keyevent 224 || true
adb shell wm dismiss-keyguard || true
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
  if grep -Fq "ARMED" key-report.txt; then
    break
  fi
  sleep 2
done
adb shell input keyevent 22
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 20
for attempt in $(seq 1 30); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
  if grep -Fq "$(printf 'PASS\tqa-remote-key-input\tkeys=4 focusChanged=')" key-report.txt; then
    break
  fi
  sleep 2
done
if ! grep -Eq "$(printf '^PASS\tqa-remote-key-input\tkeys=4 focusChanged=(true|false)$')" key-report.txt; then
  echo "Remote key input check failed or never completed"
  cat key-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi
if grep -q '^FAIL[[:space:]]' on-device-report.txt; then
  echo "Unexpected failure in the app's own self-test report"
  cat on-device-report.txt
  exit 1
fi
cat on-device-report.txt
