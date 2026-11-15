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
# The system applies exact-alarm state asynchronously; re-apply and settle before the first launch.
adb shell cmd appops set --uid "$APP_UID" SCHEDULE_EXACT_ALARM allow || true
sleep 10
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
# Optional real-guide import: a public XMLTV guide supplied at dispatch time (never committed).
# The playlist stays a runner fixture (3 channels), so the guide is the variable. Programme count
# must be above zero; the exact count depends on the public feed.
if [ -n "${PUBLIC_GUIDE_URL:-}" ]; then
  (cd ci-import && python3 -m http.server 8766 --bind 0.0.0.0 >guide-server.log 2>&1) &
  GUIDE_HTTP_PID=$!
  sleep 2
  # Remove any earlier import report so the result can only come from this launch.
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-import-report.txt || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity \
    --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
    --es tuneros.debug.import_guide_url "$PUBLIC_GUIDE_URL"
  for attempt in $(seq 1 150); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-import-report.txt > public-guide-report.txt 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' public-guide-report.txt; then
      break
    fi
    # Sample memory while the guide imports (diagnostic only).
    if [ $((attempt % 5)) -eq 0 ]; then
      echo "guide-mem t=$((attempt*2))s $(adb shell dumpsys meminfo com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS|Java Heap|Native Heap' | tr -s ' ' | tr '\n' ' ' || echo none) | $(adb shell cat /proc/meminfo 2>/dev/null | tr -d '\r' | grep -E '^MemAvailable' || true)"
    fi
    sleep 2
  done
  kill "$GUIDE_HTTP_PID" 2>/dev/null || true
  if ! grep -Eq '^PASS[[:space:]]qa-loopback-import[[:space:]]channels=3 programs=[1-9][0-9]*' public-guide-report.txt; then
    echo "Public guide import failed or never completed"
    sed -E 's#https?://[^[:space:]]*#<url>#g' public-guide-report.txt 2>/dev/null || echo "(no report file)"
    echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null || echo none)"
    adb logcat -d -v brief 2>/dev/null | grep -E "FATAL|OutOfMemory|AndroidRuntime|GuideParser|ANR|lowmemory|Killing" | tail -60 || true
    exit 1
  fi
  echo "Public guide import result: $(grep -E '^PASS' public-guide-report.txt)"
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
  adb shell dumpsys deviceidle get deep || true
  adb shell dumpsys alarm 2>/dev/null | grep -iE "devtunasoft|qa-alarm" | head -20 || true
  adb shell dumpsys deviceidle unforce || true
  adb shell input keyevent 224 || true
  exit 1
fi
adb shell dumpsys deviceidle unforce || true
adb shell input keyevent 224 || true
adb shell wm dismiss-keyguard || true
sleep 2
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
adb shell input keyevent 224 || true
adb shell wm dismiss-keyguard || true
sleep 2
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.keys true
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
  if grep -Fq "ARMED" key-report.txt; then
    break
  fi
  sleep 2
done
# Keys must go to the app's window: wait until the app holds focus before injecting them.
FOCUSED=false
for attempt in $(seq 1 30); do
  adb shell dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus' | grep -q 'com.devtunasoft.tuneros' && { FOCUSED=true; break; }
  adb shell input keyevent 224 || true
  sleep 2
done
echo "Key test window focused: $FOCUSED"
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
  adb shell dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4 || true
  exit 1
fi
# Isolated launch for rotation: the activity must be recreated with its saved state after the
# emulator rotates. Auto-rotate is disabled so the user_rotation setting applies.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.rotation true
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-rotation-report.txt > rotation-report.txt 2>/dev/null || true
  if grep -Fq "CREATED" rotation-report.txt; then
    break
  fi
  sleep 2
done
adb shell settings put system user_rotation 1
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-rotation-report.txt > rotation-report.txt 2>/dev/null || true
  if grep -Fq "$(printf 'PASS\tqa-rotation-recreate\trecreated after orientation change')" rotation-report.txt; then
    break
  fi
  sleep 2
done
adb shell settings put system user_rotation 0
if ! grep -Fq "$(printf 'PASS\tqa-rotation-recreate\trecreated after orientation change')" rotation-report.txt; then
  echo "Rotation recreate check failed or never completed"
  cat rotation-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi
# Isolated launch for a real recording: the recorder service copies a finite loopback stream
# into app-private storage (scratch catalog) and must finish COMPLETED with the exact byte count.
(cd ci-playback && python3 -m http.server 8767 --bind 0.0.0.0 >record-server.log 2>&1) &
RECORD_HTTP_PID=$!
sleep 2
RECORD_BYTES=$(stat -c %s ci-playback/test.mp4)
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.record_url http://10.0.2.2:8767/test.mp4
for attempt in $(seq 1 90); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recording-report.txt > recording-report.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-recording-partial-eof' recording-report.txt; then
    break
  fi
  sleep 2
done
kill "$RECORD_HTTP_PID" 2>/dev/null || true
if ! grep -Eq "^PASS[[:space:]]qa-recording-partial-eof.* bytes=${RECORD_BYTES}\$" recording-report.txt; then
  echo "Recording partial-EOF check failed or never completed (expected bytes=${RECORD_BYTES})"
  cat recording-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi

# Deadline mode: the runner serves the same file slowly, so the stream is still running when the
# scheduled stop passes. The recorder must complete with a partial byte count on disk.
cat > ci-playback/throttle.py <<'PY'
import http.server
import os
import time

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.mp4")
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1024)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    break
                time.sleep(0.25)

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer(("0.0.0.0", 8769), Handler).serve_forever()
PY
(cd ci-playback && python3 throttle.py >throttle.log 2>&1) &
THROTTLE_PID=$!
sleep 2
FULL_BYTES=$(stat -c %s ci-playback/test.mp4)
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.record_url http://10.0.2.2:8769/test.mp4 --es tuneros.debug.record_mode deadline
for attempt in $(seq 1 90); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recording-report.txt > deadline-report.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-recording-deadline-complete' deadline-report.txt; then
    break
  fi
  sleep 2
done
kill "$THROTTLE_PID" 2>/dev/null || true
DEADLINE_BYTES=$(sed -n 's/.* bytes=\([0-9]*\)$/\1/p' deadline-report.txt | tail -1)
if ! grep -Eq '^PASS[[:space:]]qa-recording-deadline-complete' deadline-report.txt \
   || [ -z "$DEADLINE_BYTES" ] || [ "$DEADLINE_BYTES" -le 0 ] || [ "$DEADLINE_BYTES" -ge "$FULL_BYTES" ]; then
  echo "Deadline recording check failed (full stream ${FULL_BYTES} bytes, recorded ${DEADLINE_BYTES:-none})"
  cat deadline-report.txt 2>/dev/null || echo "(no report file)"
  cat ci-playback/throttle.log 2>/dev/null | tail -20 || true
  exit 1
fi

# The deadline recording must also play back: its own file must render a first video frame.
for attempt in $(seq 1 60); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recording-report.txt > deadline-report.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-recording-playback-first-frame' deadline-report.txt; then
    break
  fi
  sleep 2
done
if ! grep -Fq "$(printf 'PASS\tqa-recording-playback-first-frame\tfirst video frame rendered')" deadline-report.txt; then
  echo "Recorded file playback check failed or never completed"
  cat deadline-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi

# Optional real-feed import: a public playlist supplied at dispatch time (never committed). The
# guide stays a runner fixture so the expected programme count is fixed. Channel count must be > 0.
if [ -n "${PUBLIC_PLAYLIST_URL:-}" ]; then
  (cd ci-import && python3 -m http.server 8768 --bind 0.0.0.0 >public-guide-server.log 2>&1) &
  PUB_HTTP_PID=$!
  sleep 2
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity \
    --es tuneros.debug.import_playlist_url "$PUBLIC_PLAYLIST_URL" \
    --es tuneros.debug.import_guide_url http://10.0.2.2:8768/guide.xml
  for attempt in $(seq 1 120); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-import-report.txt > public-import-report.txt 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' public-import-report.txt; then
      break
    fi
    sleep 2
  done
  kill "$PUB_HTTP_PID" 2>/dev/null || true
  if ! grep -Eq '^PASS[[:space:]]qa-loopback-import[[:space:]]channels=[1-9][0-9]* programs=6' public-import-report.txt; then
    echo "Public playlist import failed or never completed"
    sed -E 's#https?://[^[:space:]]*#<url>#g' public-import-report.txt 2>/dev/null || echo "(no report file)"
    exit 1
  fi
  # Informational: play the first stream of the public playlist. Public streams go up and down,
  # so this result is reported but does not gate the merge.
  curl -fsSL --max-time 30 "$PUBLIC_PLAYLIST_URL" -o public-playlist.m3u8 || true
  PUBLIC_STREAM=$(grep -m1 -E '^https?://' public-playlist.m3u8 2>/dev/null || true)
  if [ -n "$PUBLIC_STREAM" ]; then
    adb shell am force-stop com.devtunasoft.tuneros
    adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.playback_url "$PUBLIC_STREAM"
    for attempt in $(seq 1 60); do
      adb shell run-as com.devtunasoft.tuneros cat files/tuneros-playback-report.txt > public-stream-report.txt 2>/dev/null || true
      if grep -Eq '^(PASS|FAIL)[[:space:]]qa-playback-first-frame' public-stream-report.txt; then
        break
      fi
      sleep 2
    done
    echo "INFORMATIONAL public stream first-frame result (not gated):"
    sed -E 's#https?://[^[:space:]]*#<url>#g' public-stream-report.txt 2>/dev/null || echo "(no report file)"
  else
    echo "INFORMATIONAL: no http(s) stream found in the public playlist"
  fi
fi
# Scheduled recording: a stored schedule is started by a real exact alarm through the receiver
# and must complete at its stop, end STARTED, and delete its credentials. Runner-served slow stream.
(cd ci-playback && python3 throttle.py >scheduled-throttle.log 2>&1) &
SCHED_HTTP_PID=$!
sleep 2
SCHED_FULL_BYTES=$(stat -c %s ci-playback/test.mp4)
adb shell am force-stop com.devtunasoft.tuneros
adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.scheduled_url http://10.0.2.2:8769/test.mp4
for attempt in $(seq 1 90); do
  adb shell run-as com.devtunasoft.tuneros cat files/tuneros-scheduled-report.txt > scheduled-report.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-scheduled-recording' scheduled-report.txt; then
    break
  fi
  sleep 2
done
kill "$SCHED_HTTP_PID" 2>/dev/null || true
SCHED_BYTES=$(sed -n 's/.* bytes=\([0-9]*\)$/\1/p' scheduled-report.txt | tail -1)
if ! grep -Eq '^PASS[[:space:]]qa-dvr-rearm' scheduled-report.txt \
   || ! grep -Eq '^PASS[[:space:]]qa-scheduled-recording' scheduled-report.txt \
   || [ -z "$SCHED_BYTES" ] || [ "$SCHED_BYTES" -le 0 ] || [ "$SCHED_BYTES" -ge "$SCHED_FULL_BYTES" ]; then
  echo "Scheduled recording check failed (full stream ${SCHED_FULL_BYTES} bytes, recorded ${SCHED_BYTES:-none})"
  cat scheduled-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi

if grep -q '^FAIL[[:space:]]' on-device-report.txt; then
  echo "Unexpected failure in the app's own self-test report"
  cat on-device-report.txt
  exit 1
fi
cat on-device-report.txt
