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
# Wait until the app process is gone and ActivityManager no longer holds a record of it. A process can disappear
# while its activity record remains for a moment, and a launch in that window is delivered to the dead record,
# so no self-test pass starts. Returns 1 when either is still present after 60 seconds.
wait_for_app_records_gone() {
  local tick
  for tick in $(seq 1 60); do
    adb shell dumpsys activity activities > app-records.txt 2>/dev/null || true
    if ! grep -q 'com.devtunasoft.tuneros/' app-records.txt && \
       [ -z "$(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r ')" ]; then
      echo "app process and activity records gone after $tick s"
      return 0
    fi
    sleep 1
  done
  echo "app process or activity records still present after 60 s:"
  grep -E 'com.devtunasoft.tuneros/' app-records.txt | head -5 | cut -c1-200 || true
  return 1
}

# Start the self-test pass. -S stops any remaining instance first (only set for the external-storage run).
# A launch that ActivityManager still delivers to an existing instance starts nothing, so it is detected and
# retried after another stop and wait. A launch that starts no process within 30 seconds is retried the same way.
MAIN_START_FLAGS=""
launch_self_test_pass() {
  local try pid_tick
  for try in 1 2 3; do
    adb shell am start $MAIN_START_FLAGS -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.qa true > main-start.txt 2>&1 || true
    tr -d '\r' < main-start.txt
    if ! grep -q 'Warning: Activity not started' main-start.txt; then
      for pid_tick in $(seq 1 30); do
        if [ -n "$(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r ')" ]; then
          echo "self-test process started (launch $try)"
          return 0
        fi
        sleep 1
      done
    fi
    echo "self-test process did not start (launch $try); stopping any remaining instance and retrying"
    adb shell am force-stop com.devtunasoft.tuneros || true
    wait_for_app_records_gone || true
  done
  echo "self-test process never started after 3 launches"
  return 1
}

# External recording storage (dispatch-only, see the external_storage_check input). An isolated launch opens
# the document-tree picker. CI taps "Use this folder", then "Allow" if the system asks for it, and the persisted
# tree must then make external-recording-storage PASS in the main pass. Nothing is tapped when the input is off.
if [ "${EXTERNAL_STORAGE_CHECK:-false}" = "true" ]; then
  # Run 43752762409 showed the Download folder refused ("Can't use this folder"), so use a standard media folder.
  adb shell mkdir -p /sdcard/Movies || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.pick_folder true
  PICK_TAPPED=false
  ALLOW_TAPPED=false
  NAVIGATED=false
  NAV_NAME=""
  ALLOW_WAIT=0
  for attempt in $(seq 1 45); do
    sleep 2
    rm -f picker.xml
    adb shell uiautomator dump /sdcard/picker.xml >/dev/null 2>&1 || true
    adb pull /sdcard/picker.xml picker.xml >/dev/null 2>&1 || true
    # A failed dump leaves no file to read, so wait for the next attempt.
    if [ ! -s picker.xml ]; then continue; fi
    # The storage root and the Download folder are refused ("Can't use this folder"), so never confirm there. Open
    # Movies, Music or Pictures first, and keep waiting until the list shows one: the list can load after this dump.
    if [ "$NAVIGATED" = "false" ]; then
      NAV_LINE=$(tr '>' '\n' < picker.xml 2>/dev/null | grep -E 'text="(Movies|Music|Pictures)"' | head -1 || true)
      NAV_BOUNDS=$(echo "$NAV_LINE" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
      if [ -n "$NAV_BOUNDS" ]; then
        NAV_NAME=$(echo "$NAV_LINE" | grep -oE 'text="(Movies|Music|Pictures)"' | head -1 | sed -E 's/text="//; s/"$//' || true)
        X=$(echo "$NAV_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \3/' | awk '{print int(($1+$2)/2)}')
        Y=$(echo "$NAV_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2 \4/' | awk '{print int(($1+$2)/2)}')
        adb shell input tap "$X" "$Y"
        NAVIGATED=true
        echo "Opened the $NAV_NAME folder in the picker"
        sleep 2
      fi
      continue
    fi
    if [ "$PICK_TAPPED" = "false" ]; then
      # Confirm only inside the folder that was opened; its header reads "Files in <name>".
      PICK_BOUNDS=""
      if grep -Fq "text=\"Files in $NAV_NAME\"" picker.xml; then
        PICK_BOUNDS=$(tr '>' '\n' < picker.xml 2>/dev/null | grep -iE 'text="(Use this folder|USE THIS FOLDER|Select|SELECT)"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
      fi
      if [ -n "$PICK_BOUNDS" ]; then
        X=$(echo "$PICK_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \3/' | awk '{print int(($1+$2)/2)}')
        Y=$(echo "$PICK_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2 \4/' | awk '{print int(($1+$2)/2)}')
        adb shell input tap "$X" "$Y"
        PICK_TAPPED=true
      fi
    else
      ALLOW_WAIT=$((ALLOW_WAIT + 1))
      ALLOW_BOUNDS=$(tr '>' '\n' < picker.xml 2>/dev/null | grep -E 'text="(Allow|ALLOW)"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
      if [ -n "$ALLOW_BOUNDS" ]; then
        X=$(echo "$ALLOW_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \3/' | awk '{print int(($1+$2)/2)}')
        Y=$(echo "$ALLOW_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2 \4/' | awk '{print int(($1+$2)/2)}')
        adb shell input tap "$X" "$Y"
        ALLOW_TAPPED=true
        break
      fi
      # The Allow prompt is optional. Stop waiting for it after about ten seconds.
      if [ "$ALLOW_WAIT" -ge 5 ]; then break; fi
    fi
  done
  # Diagnostics for the next attempt: the foreground activity and the UI labels the picker showed (labels only).
  echo "picker top activity: $(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E 'topResumedActivity' | head -1 | cut -c1-160)"
  echo "picker labels seen: $(tr '>' '\n' < picker.xml 2>/dev/null | grep -oE ' text="[^"]+"' | sed -E 's/ text="//; s/"$//' | awk 'NF && !seen[$0]++' | head -40 | tr '\n' '|')"
  echo "Folder picker: confirm tapped=$PICK_TAPPED, allow tapped=$ALLOW_TAPPED"
  if [ "$NAVIGATED" != "true" ]; then
    echo "The picker never showed Movies, Music or Pictures to open; external-recording-storage cannot be checked"
    exit 1
  fi
  # The picker is closed only when this app's activity is top resumed again, which happens once the result has
  # been delivered. Use this folder can open an Allow confirmation, and a tap can miss while a dialog animates,
  # so read the screen again each round and tap the visible confirm button, up to four taps in total.
  PICKER_CLOSED=false
  CONFIRM_TAPS=0
  for close_check in $(seq 1 25); do
    adb shell dumpsys activity activities > picker-activities.txt 2>/dev/null || true
    if grep -q 'topResumedActivity=.*com.devtunasoft.tuneros/' picker-activities.txt; then PICKER_CLOSED=true; break; fi
    if [ "$CONFIRM_TAPS" -lt 4 ]; then
      adb shell uiautomator dump /sdcard/picker.xml >/dev/null 2>&1 || true
      adb pull /sdcard/picker.xml picker.xml >/dev/null 2>&1 || true
      if [ -s picker.xml ]; then
        CONFIRM_BOUNDS=$(tr '>' '\n' < picker.xml | grep -E 'text="(Allow|ALLOW)"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
        if [ -z "$CONFIRM_BOUNDS" ] && grep -Fq "text=\"Files in $NAV_NAME\"" picker.xml; then
          CONFIRM_BOUNDS=$(tr '>' '\n' < picker.xml | grep -iE 'text="(Use this folder|USE THIS FOLDER)"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 || true)
        fi
        if [ -n "$CONFIRM_BOUNDS" ]; then
          X=$(echo "$CONFIRM_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \3/' | awk '{print int(($1+$2)/2)}')
          Y=$(echo "$CONFIRM_BOUNDS" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2 \4/' | awk '{print int(($1+$2)/2)}')
          adb shell input tap "$X" "$Y"
          CONFIRM_TAPS=$((CONFIRM_TAPS + 1))
          echo "confirm tap $CONFIRM_TAPS sent"
        fi
      fi
    fi
    sleep 3
  done
  if [ "$PICKER_CLOSED" != "true" ]; then
    adb shell uiautomator dump /sdcard/picker.xml >/dev/null 2>&1 || true
    adb pull /sdcard/picker.xml picker.xml >/dev/null 2>&1 || true
    echo "picker labels after the wait: $(tr '>' '\n' < picker.xml 2>/dev/null | grep -oE ' text="[^"]+"' | sed -E 's/ text="//; s/"$//' | awk 'NF && !seen[$0]++' | head -40 | tr '\n' '|')"
    echo "picker top activity after the wait: $(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E 'topResumedActivity' | head -1 | cut -c1-160)"
    echo "Folder picker still open after $CONFIRM_TAPS confirm tap(s); external-recording-storage cannot be checked"
    exit 1
  fi
  echo "Folder picker closed after $CONFIRM_TAPS confirm tap(s) and $close_check check(s)"
  sleep 3
  # Record what the app shows and logs once the picker has returned. A rejected folder leaves its reason on screen.
  adb shell uiautomator dump /sdcard/after-picker.xml >/dev/null 2>&1 || true
  adb pull /sdcard/after-picker.xml after-picker.xml >/dev/null 2>&1 || true
  echo "app labels after the picker: $(tr '>' '\n' < after-picker.xml 2>/dev/null | grep -oE ' text="[^"]+"' | sed -E 's/ text="//; s/"$//' | awk 'NF && !seen[$0]++' | head -40 | tr '\n' '|')"
  echo "app debug log after the picker:"
  adb logcat -d -v brief 2>/dev/null | tr -d '\r' | grep -E 'TunerOSRecording|SafStorageInspector' | tail -20 | cut -c1-240 || true
  # Stop the picker-launched instance and wait until the process is really gone. Otherwise the next
  # launch is delivered to that instance as a new intent, and no self-test pass starts.
  for stop_tick in $(seq 1 15); do
    adb shell am force-stop com.devtunasoft.tuneros
    sleep 1
    if [ -z "$(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r ')" ]; then break; fi
  done
  echo "picker-launched app process gone after ${stop_tick:-0} stop attempts"
  wait_for_app_records_gone || true
  MAIN_START_FLAGS="-S"
fi
launch_self_test_pass
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
REQUIRED_EXTERNAL=""
if [ "${EXTERNAL_STORAGE_CHECK:-false}" = "true" ]; then REQUIRED_EXTERNAL="external-recording-storage"; fi
for check in $REQUIRED_EXTERNAL source-recovery-policy offline-m3u-parser offline-source-stall offline-xmltv-parser debug-xmltv-depth-limit offline-channel-filter offline-timeline offline-xmltv-dtd-block scratch-dvr-copy scratch-dvr-catalog scratch-dvr-schedule-reservation scratch-dvr-schedule-paging scratch-dvr-preparation-recovery android-keystore-roundtrip debug-large-guide-stress debug-hls-dvr-sequence debug-hls-response-length debug-loopback-hls-transport debug-connection-abort debug-internal-dvr-finalize debug-saf-storage-guard debug-ui-focus debug-empty-guide-dpad debug-now-marker debug-favorites debug-guide-doctor; do
  if ! grep -Fq "$(printf 'PASS\t%s\t' "$check")" on-device-report.txt; then
    echo "On-device QA assertion failed or never completed: $check"
    cat on-device-report.txt
    echo "app files: $(adb shell run-as com.devtunasoft.tuneros ls files 2>/dev/null | tr -d '\r' | tr '\n' ' ' || echo none)"
    echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' || echo none)"
    echo "package: $(adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -i tuneros || echo none)"
    adb logcat -d -v brief 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime|Process: com.devtunasoft|SafStorage|RecordingDestination|OnDeviceSelfTest|SecurityException|IllegalStateException|lowmemorykiller.*tuneros|Killing.*tuneros" | tail -120 || true
    exit 1
  fi
done
if adb shell run-as com.devtunasoft.tuneros ls files/tuneros-selftest-in-progress >/dev/null 2>&1; then
  echo "The self-test has not committed its report and cleared its run marker"
  exit 1
fi
# The runner generates the short test stream once. The picked-folder recording below, the first-frame check and
# the recording checks all read it.
command -v ffmpeg >/dev/null || sudo apt-get install -y -qq ffmpeg
mkdir -p ci-playback
ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=10:size=320x240:rate=25 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart ci-playback/test.mp4
# External storage: the same finite loopback stream is recorded into the picked folder while it is still saved.
# The report must say storage=saf, and the stored document must hold exactly the served bytes (sha256).
if [ "${EXTERNAL_STORAGE_CHECK:-false}" = "true" ]; then
  python3 -m http.server 8767 --bind 0.0.0.0 --directory ci-playback >saf-record-server.log 2>&1 &
  SAF_HTTP_PID=$!
  sleep 2
  SAF_BYTES=$(stat -c %s ci-playback/test.mp4)
  SAF_SHA=$(sha256sum ci-playback/test.mp4 | cut -d' ' -f1)
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-recording-report.txt || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.record_url http://10.0.2.2:8767/test.mp4
  for attempt in $(seq 1 90); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recording-report.txt > saf-recording-report.txt 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-recording-partial-eof' saf-recording-report.txt; then
      break
    fi
    sleep 2
  done
  kill "$SAF_HTTP_PID" 2>/dev/null || true
  wait "$SAF_HTTP_PID" 2>/dev/null || true
  if ! grep -Eq "^PASS[[:space:]]qa-recording-partial-eof.* bytes=${SAF_BYTES}\$" saf-recording-report.txt \
     || ! grep -Fq "storage=saf sha256=${SAF_SHA} disk=${SAF_BYTES}" saf-recording-report.txt; then
    echo "Recording into the picked folder failed the byte-exact check (expected ${SAF_BYTES} bytes, sha256=${SAF_SHA})"
    cat saf-recording-report.txt 2>/dev/null || echo "(no report file)"
    exit 1
  fi
  echo "Recording into the picked folder matched the served file: ${SAF_BYTES} bytes, sha256=${SAF_SHA}"
  # The internal recording check below reads the same report file. Clear this result so it cannot pass on it.
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-recording-report.txt || true
  if adb shell run-as com.devtunasoft.tuneros ls files/tuneros-recording-report.txt >/dev/null 2>&1; then
    echo "The recording report could not be cleared before the internal recording check"
    exit 1
  fi
fi
# The picked folder stays saved in the app. The external-storage check above has verified it, so reset the
# destination to internal storage before the checks that follow, which were written for internal storage.
if [ "${EXTERNAL_STORAGE_CHECK:-false}" = "true" ]; then
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -W -n com.devtunasoft.tuneros/.MainActivity --ez tuneros.debug.use_internal_storage true >/dev/null 2>&1 || true
  sleep 2
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell run-as com.devtunasoft.tuneros cat shared_prefs/tuneros_recording_storage.xml > recording-prefs.xml 2>/dev/null || true
  if grep -q 'recording_tree_uri' recording-prefs.xml 2>/dev/null; then
    echo "The saved recording folder was not reset to internal storage"
    exit 1
  fi
  echo "Recording destination reset to internal storage after the external-storage check"
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
# Isolated launch for first decoded video frame. The test stream generated earlier is served on the host,
# and the emulator reaches it through the host alias 10.0.2.2.
python3 -m http.server 8765 --bind 0.0.0.0 --directory ci-playback >http-server.log 2>&1 &
HTTP_PID=$!
sleep 2
# The server must answer on the runner before the emulator is asked to play from it.
if ! curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8765/test.mp4; then
  echo "Local test stream server did not answer on the runner"
  tail -20 http-server.log 2>/dev/null || true
  kill "$HTTP_PID" 2>/dev/null || true
  exit 1
fi
FRAME_PASS="$(printf 'PASS\tqa-playback-first-frame\tfirst video frame rendered')"
# A connection failure (Media3 codes 2000-2002) is reported at once. Launch again, up to three times, so one
# dropped connection between the emulator and the runner does not decide the check. The PASS line is still required.
for frame_try in 1 2 3; do
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-playback-report.txt 2>/dev/null || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.playback_url http://10.0.2.2:8765/test.mp4
  for attempt in $(seq 1 90); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-playback-report.txt > playback-report.txt 2>/dev/null || true
    if grep -Fq "$FRAME_PASS" playback-report.txt; then
      break
    fi
    if grep -Eq 'player error code 200[0-2]' playback-report.txt; then
      break
    fi
    sleep 2
  done
  if grep -Fq "$FRAME_PASS" playback-report.txt; then break; fi
  if ! grep -Eq 'player error code 200[0-2]' playback-report.txt; then break; fi
  echo "First-frame launch $frame_try lost its connection to the local stream; launching again"
  sleep 3
done
kill "$HTTP_PID" 2>/dev/null || true
if ! grep -Fq "$FRAME_PASS" playback-report.txt; then
  echo "First video frame check failed or never completed"
  cat playback-report.txt 2>/dev/null || echo "(no report file)"
  cat ci-playback/http-server.log 2>/dev/null | tail -20 || true
  echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null || echo none)"
  adb logcat -d -v brief 2>/dev/null | grep -E "FATAL|AndroidRuntime|ExoPlayer|MediaCodec|ImageReader|tuneros|Playback|DecoderInit" | tail -120 || true
  exit 1
fi
# Playback diagnostics. The runner answers a missing path with HTTP 404, and serves an HTML page as text/html.
# Neither is a stream, so each launch must fail and report its Stream Doctor evidence: a status code or a media
# type, never a URL.
printf '<!doctype html><title>not a stream</title>\n' > ci-playback/page.html
python3 -m http.server 8765 --bind 0.0.0.0 --directory ci-playback >http-diag-server.log 2>&1 &
DIAG_PID=$!
sleep 2
for diag_case in "missing.mp4|HTTP 404" "page.html|content type text/html"; do
  DIAG_PATH="${diag_case%%|*}"
  DIAG_EXPECT="${diag_case#*|}"
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-playback-report.txt 2>/dev/null || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.playback_url "http://10.0.2.2:8765/$DIAG_PATH"
  for attempt in $(seq 1 45); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-playback-report.txt > playback-diag-report.txt 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-playback-first-frame' playback-diag-report.txt; then
      break
    fi
    sleep 2
  done
  if ! grep -Eq "^FAIL[[:space:]]qa-playback-first-frame.*${DIAG_EXPECT}" playback-diag-report.txt; then
    echo "Playback diagnostic for ${DIAG_PATH} did not report: ${DIAG_EXPECT}"
    cat playback-diag-report.txt 2>/dev/null || echo "(no report file)"
    kill "$DIAG_PID" 2>/dev/null || true
    exit 1
  fi
  echo "Playback diagnostic ${DIAG_PATH}: $(grep -E '^FAIL[[:space:]]qa-playback-first-frame' playback-diag-report.txt | cut -c1-160)"
done
kill "$DIAG_PID" 2>/dev/null || true
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
python3 -m http.server 8766 --bind 0.0.0.0 --directory ci-import >http-server.log 2>&1 &
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
# Optional real-guide import: a public XMLTV guide supplied at dispatch time (never committed).
# It runs after the loopback fixtures in ci-import exist, so the 3-channel playlist fixture is
# always served. The app keeps every parsed programme, so a programme count above zero proves the
# public guide parsed; the exact count depends on the public feed and is not asserted.
if [ -n "${PUBLIC_GUIDE_URL:-}" ]; then
  python3 -m http.server 8766 --bind 0.0.0.0 --directory ci-import >guide-server.log 2>&1 &
  GUIDE_HTTP_PID=$!
  sleep 2
  if ! curl -fsS -o /dev/null --max-time 10 http://127.0.0.1:8766/playlist.m3u; then
    echo "Guide playlist fixture is not being served"
    kill "$GUIDE_HTTP_PID" 2>/dev/null || true
    exit 1
  fi
  # Remove any earlier import report, then prove it is gone, so the result can only come from this launch.
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-import-report.txt || true
  if adb shell run-as com.devtunasoft.tuneros ls files/tuneros-import-report.txt >/dev/null 2>&1; then
    echo "Could not clear the earlier import report; refusing a possibly stale result"
    kill "$GUIDE_HTTP_PID" 2>/dev/null || true
    exit 1
  fi
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity \
    --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
    --es tuneros.debug.import_guide_url "$PUBLIC_GUIDE_URL"
  # Sample total PSS about once a second while the guide imports, so the peak is measured, not one
  # snapshot. The samples are diagnostic only and never gate the result.
  rm -f guide-mem-samples.txt
  (
    while true; do
      adb shell dumpsys meminfo com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS' | tr -s ' ' >> guide-mem-samples.txt || true
      sleep 1
    done
  ) &
  GUIDE_MEM_PID=$!
  for attempt in $(seq 1 200); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-import-report.txt > public-guide-report.txt 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' public-guide-report.txt; then
      break
    fi
    sleep 2
  done
  kill "$GUIDE_HTTP_PID" 2>/dev/null || true
  kill "$GUIDE_MEM_PID" 2>/dev/null || true
  PEAK_PSS=$(sed -E 's/.*TOTAL PSS: ([0-9]+).*/\1/' guide-mem-samples.txt 2>/dev/null | sort -n | tail -1 || true)
  SAMPLES=0
  if [ -f guide-mem-samples.txt ]; then SAMPLES=$(wc -l < guide-mem-samples.txt); fi
  echo "guide-mem peak TOTAL PSS KiB: ${PEAK_PSS:-none} from ${SAMPLES} samples"
  if ! grep -Eq '^PASS[[:space:]]qa-loopback-import[[:space:]]channels=3 programs=[1-9][0-9]*' public-guide-report.txt; then
    echo "Public guide import failed or never completed"
    sed -E 's#https?://[^[:space:]]*#<url>#g' public-guide-report.txt 2>/dev/null || echo "(no report file)"
    echo "app pid: $(adb shell pidof com.devtunasoft.tuneros 2>/dev/null || echo none)"
    adb logcat -d -v brief 2>/dev/null | grep -E "FATAL|OutOfMemory|AndroidRuntime|GuideParser|ANR|lowmemory|Killing" | tail -60 || true
    exit 1
  fi
  echo "Public guide import result: $(grep -E '^PASS' public-guide-report.txt)"
  echo "guide-mem after pass: $(adb shell dumpsys meminfo com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS|Java Heap|Native Heap' | tr -s ' ' | tr '\n' ' ' || echo none)"
fi

# Soak (dispatch-only, same public guide, no login): three full imports in fresh launches, each with a
# memory snapshot after its pass, then five minutes idle with the last import loaded. The gate is that every
# import passes and the same app process survives the idle window. The snapshots are logged, not gated.
if [ -n "${PUBLIC_GUIDE_URL:-}" ]; then
  python3 -m http.server 8766 --bind 0.0.0.0 --directory ci-import >soak-server.log 2>&1 &
  SOAK_HTTP_PID=$!
  sleep 2
  for soak_run in 1 2 3; do
    adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-import-report.txt || true
    adb shell am force-stop com.devtunasoft.tuneros
    adb shell am start -n com.devtunasoft.tuneros/.MainActivity \
      --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
      --es tuneros.debug.import_guide_url "$PUBLIC_GUIDE_URL"
    for attempt in $(seq 1 200); do
      adb shell run-as com.devtunasoft.tuneros cat files/tuneros-import-report.txt > "soak-report-$soak_run.txt" 2>/dev/null || true
      if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' "soak-report-$soak_run.txt"; then
        break
      fi
      sleep 2
    done
    if ! grep -Eq '^PASS[[:space:]]qa-loopback-import[[:space:]]channels=3 programs=[1-9][0-9]*' "soak-report-$soak_run.txt"; then
      echo "Soak import $soak_run failed or never completed"
      sed -E 's#https?://[^[:space:]]*#<url>#g' "soak-report-$soak_run.txt" 2>/dev/null || echo "(no report file)"
      exit 1
    fi
    echo "soak import $soak_run: $(grep -E '^PASS' "soak-report-$soak_run.txt")"
    echo "soak snapshot $soak_run: $(adb shell dumpsys meminfo com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS' | tr -s ' ' || echo none)"
  done
  SOAK_PID=$(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r ' || true)
  if [ -z "$SOAK_PID" ]; then
    echo "Soak: the app process was not running after the last import"
    exit 1
  fi
  for tick in $(seq 1 30); do
    sleep 10
    CUR_PID=$(adb shell pidof com.devtunasoft.tuneros 2>/dev/null | tr -d '\r ' || true)
    if [ "$CUR_PID" != "$SOAK_PID" ]; then
      echo "Soak: the app process changed or died during the idle window (was $SOAK_PID, now ${CUR_PID:-none}) at tick $tick"
      exit 1
    fi
    if [ $((tick % 6)) -eq 0 ]; then
      echo "soak idle tick $tick (about $((tick * 10)) s): $(adb shell dumpsys meminfo com.devtunasoft.tuneros 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS' | tr -s ' ' || echo none)"
    fi
  done
  kill "$SOAK_HTTP_PID" 2>/dev/null || true
  echo "Soak result: three imports passed, and process $SOAK_PID stayed alive through five idle minutes"
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
# Keys must go to the app's window. The top resumed activity can match before a new window has focus, and keys
# sent in that gap are dropped, so the window's own focus is required. Top-activity is accepted only when the
# window dump has no mCurrentFocus line at all. A timed-out screen, a lock screen or the notification shade can
# take focus, so keep the device awake and unlocked and collapse the shade while waiting. The check stays strict.
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 600000 || true
FOCUSED=false
FOCUSED_BY=none
for attempt in $(seq 1 45); do
  adb shell dumpsys window 2>/dev/null > focus-window.txt || true
  adb shell dumpsys activity activities 2>/dev/null > focus-activity.txt || true
  if grep -q 'mCurrentFocus=.*com.devtunasoft.tuneros' focus-window.txt; then FOCUSED=true; FOCUSED_BY=window; break; fi
  if ! grep -q 'mCurrentFocus=' focus-window.txt && grep -Eq '(topResumedActivity|mResumedActivity).*com.devtunasoft.tuneros' focus-activity.txt; then FOCUSED=true; FOCUSED_BY=top-activity; break; fi
  adb shell input keyevent 224 || true
  adb shell wm dismiss-keyguard || true
  adb shell cmd statusbar collapse || true
  sleep 2
done
echo "Key test window focused: $FOCUSED ($FOCUSED_BY)"
grep -E 'mCurrentFocus=' focus-window.txt | head -1 | cut -c1-200 || true
# Let the newly focused window's input channel settle before the first key.
sleep 2
# Up to three rounds. A round is repeated only when the app recorded no key event at all: a key that arrived is
# already counted, so a partly delivered round is never repeated. The strict PASS line below still decides.
for round in 1 2 3; do
  adb shell input keyevent 22
  sleep 1
  adb shell input keyevent 22
  sleep 1
  adb shell input keyevent 20
  sleep 1
  adb shell input keyevent 20
  for attempt in $(seq 1 15); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
    if grep -Fq "$(printf 'PASS\tqa-remote-key-input\tkeys=4 focusChanged=')" key-report.txt; then
      break
    fi
    sleep 2
  done
  if grep -Fq "$(printf 'PASS\tqa-remote-key-input\tkeys=4 focusChanged=')" key-report.txt; then break; fi
  if grep -q '^KEY' key-report.txt; then break; fi
  echo "Key round $round: no key event reached the app; checking focus before the next round"
  adb shell input keyevent 224 || true
  adb shell wm dismiss-keyguard || true
  adb shell cmd statusbar collapse || true
  sleep 3
done
if ! grep -Eq "$(printf '^PASS\tqa-remote-key-input\tkeys=4 focusChanged=(true|false)$')" key-report.txt; then
  echo "Remote key input check failed or never completed"
  cat key-report.txt 2>/dev/null || echo "(no report file)"
  # Snapshot focus, keyguard and power state so a dropped-key failure can be diagnosed from the log.
  adb shell dumpsys window 2>/dev/null > key-window-dump.txt || true
  echo "window dump lines: $(wc -l < key-window-dump.txt 2>/dev/null || echo 0)"
  grep -E 'mCurrentFocus|mFocusedApp|isKeyguardShowing|mKeyguard' key-window-dump.txt | head -6 || true
  adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E 'topResumedActivity|mResumedActivity' | head -2 || true
  adb shell dumpsys power 2>/dev/null | tr -d '\r' | grep -E 'mWakefulness=|Display Power: state=' | head -2 || true
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
# into app-private storage (scratch catalog) and must finish PARTIAL with the exact bytes on disk (sha256 checked).
python3 -m http.server 8767 --bind 0.0.0.0 --directory ci-playback >record-server.log 2>&1 &
RECORD_HTTP_PID=$!
sleep 2
RECORD_BYTES=$(stat -c %s ci-playback/test.mp4)
RECORD_SHA=$(sha256sum ci-playback/test.mp4 | cut -d' ' -f1)
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
if ! grep -Eq "^PASS[[:space:]]qa-recording-partial-eof.* bytes=${RECORD_BYTES}\$" recording-report.txt \
   || ! grep -Fq "storage=file sha256=${RECORD_SHA} disk=${RECORD_BYTES}" recording-report.txt; then
  echo "Recording partial-EOF check failed or never completed (expected bytes=${RECORD_BYTES}, sha256=${RECORD_SHA})"
  cat recording-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi

# Encrypted, fMP4 and DASH recording on the device. One clip is cut into plain TS segments and encrypted with AES-128
# (the IV is the media sequence number, the default). A second clip is cut into fMP4 segments with an initialization
# section, and a third into a static DASH manifest. Each is recorded through the real service. The clip ends early,
# so the result is PARTIAL, and the stored bytes must equal the plaintext (fMP4 and DASH: the initialization segment,
# then the fragments) exactly.
rm -rf ci-hls && mkdir -p ci-hls/plain ci-hls/aes ci-hls/fmp4 ci-hls/dash
ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=9:size=160x120:rate=10 -c:v libx264 -pix_fmt yuv420p -g 10 \
  -f hls -hls_time 3 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename ci-hls/plain/seg%d.ts ci-hls/plain/index.m3u8
ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=9:size=160x120:rate=10 -c:v libx264 -pix_fmt yuv420p -g 10 \
  -f hls -hls_time 3 -hls_list_size 0 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_fmp4_init_filename init.mp4 -hls_segment_filename ci-hls/fmp4/seg%d.m4s ci-hls/fmp4/index.m3u8
ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=9:size=160x120:rate=10 -c:v libx264 -pix_fmt yuv420p -g 10 \
  -f dash -seg_duration 3 -use_template 1 -use_timeline 0 ci-hls/dash/manifest.mpd
HLS_KEY_HEX=00112233445566778899aabbccddeeff
python3 -c "import binascii,sys; sys.stdout.buffer.write(binascii.unhexlify('${HLS_KEY_HEX}'))" > ci-hls/aes/key.bin
HLS_SEGMENTS=$(grep -v '^#' ci-hls/plain/index.m3u8 | grep -v '^$')
HLS_INDEX=0
for HLS_SEGMENT in $HLS_SEGMENTS; do
  HLS_IV=$(printf '%032x' "$HLS_INDEX")
  openssl enc -aes-128-cbc -K "$HLS_KEY_HEX" -iv "$HLS_IV" -in "ci-hls/plain/$HLS_SEGMENT" -out "ci-hls/aes/$HLS_SEGMENT"
  HLS_INDEX=$((HLS_INDEX + 1))
done
{ head -n 1 ci-hls/plain/index.m3u8; echo '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"'; tail -n +2 ci-hls/plain/index.m3u8; } > ci-hls/aes/index.m3u8
python3 -m http.server 8770 --bind 0.0.0.0 --directory ci-hls >hls-server.log 2>&1 &
HLS_HTTP_PID=$!
sleep 2
for HLS_CASE in aes fmp4 dash; do
  case "$HLS_CASE" in
    aes)
      HLS_PATH=aes/index.m3u8
      HLS_LIST=$(grep -v '^#' ci-hls/plain/index.m3u8 | grep -v '^$' | sed 's#^#ci-hls/plain/#')
      HLS_EXPECT=$(for f in $HLS_LIST; do cat "$f"; done | tee ci-hls/expected-aes.bin | wc -c)
      ;;
    fmp4)
      HLS_PATH=fmp4/index.m3u8
      HLS_LIST=$(grep -v '^#' ci-hls/fmp4/index.m3u8 | grep -v '^$' | sed 's#^#ci-hls/fmp4/#')
      HLS_EXPECT=$({ cat ci-hls/fmp4/init.mp4; for f in $HLS_LIST; do cat "$f"; done; } | tee ci-hls/expected-fmp4.bin | wc -c)
      ;;
    dash)
      HLS_PATH=dash/manifest.mpd
      HLS_EXPECT=$({ cat ci-hls/dash/init-*.m4s; for f in $(ls ci-hls/dash/chunk-*.m4s | sort); do cat "$f"; done; } | tee ci-hls/expected-dash.bin | wc -c)
      ;;
  esac
  HLS_EXPECT_SHA=$(sha256sum "ci-hls/expected-${HLS_CASE}.bin" | cut -d' ' -f1)
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-recording-report.txt 2>/dev/null || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.record_url "http://10.0.2.2:8770/${HLS_PATH}"
  for attempt in $(seq 1 90); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recording-report.txt > "hls-${HLS_CASE}-report.txt" 2>/dev/null || true
    if grep -Eq '^(PASS|FAIL)[[:space:]]qa-recording-partial-eof' "hls-${HLS_CASE}-report.txt"; then
      break
    fi
    sleep 2
  done
  if ! grep -Eq "^PASS[[:space:]]qa-recording-partial-eof.* bytes=${HLS_EXPECT}\$" "hls-${HLS_CASE}-report.txt" \
     || ! grep -Fq "storage=file sha256=${HLS_EXPECT_SHA} disk=${HLS_EXPECT}" "hls-${HLS_CASE}-report.txt"; then
    echo "${HLS_CASE} recording did not match the plaintext (expected ${HLS_EXPECT} bytes, sha256 ${HLS_EXPECT_SHA})"
    cat "hls-${HLS_CASE}-report.txt" 2>/dev/null || echo "(no report file)"
    tail -20 hls-server.log 2>/dev/null || true
    kill "$HLS_HTTP_PID" 2>/dev/null || true
    exit 1
  fi
  echo "${HLS_CASE} recording matches the plaintext: ${HLS_EXPECT} bytes"
done
kill "$HLS_HTTP_PID" 2>/dev/null || true

# Deadline mode: the runner serves the same file slowly, so the stream is still running when the
# scheduled stop passes. The recorder must complete with a partial byte count on disk.
cat > ci-playback/throttle.py <<'PY'
import http.server
import os
import sys
import time

DELAY = float(sys.argv[1]) if len(sys.argv) > 1 else 0.25  # seconds per 1 KiB chunk

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
                time.sleep(DELAY)

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer(("0.0.0.0", 8769), Handler).serve_forever()
PY
python3 ci-playback/throttle.py >throttle.log 2>&1 &
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
  python3 -m http.server 8768 --bind 0.0.0.0 --directory ci-import >public-guide-server.log 2>&1 &
  PUB_HTTP_PID=$!
  sleep 2
  # Remove any earlier import report so the result can only come from this launch.
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-import-report.txt || true
  rm -f public-import-report.txt
  if adb shell run-as com.devtunasoft.tuneros ls files/tuneros-import-report.txt >/dev/null 2>&1; then
    echo "Could not clear the earlier import report; refusing a possibly stale result"
    kill "$PUB_HTTP_PID" 2>/dev/null || true
    exit 1
  fi
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
  # Real-stream check from the public playlist (dispatch-only, no login): play up to the first eight
  # http(s) streams in order. A stream counts only if it renders its own first video frame. At least one
  # must pass. Streams that are down are reported and skipped. Stream URLs are never printed.
  curl -fsSL --max-time 30 "$PUBLIC_PLAYLIST_URL" -o public-playlist.m3u8 || true
  grep -E '^https?://' public-playlist.m3u8 > public-streams.txt || true
  head -n 8 public-streams.txt > first-streams.txt || true
  STREAM_PASSES=0
  STREAM_INDEX=0
  while IFS= read -r PUBLIC_STREAM <&3; do
    STREAM_INDEX=$((STREAM_INDEX + 1))
    adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-playback-report.txt || true
    adb shell am force-stop com.devtunasoft.tuneros
    adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.playback_url "$PUBLIC_STREAM"
    for attempt in $(seq 1 60); do
      adb shell run-as com.devtunasoft.tuneros cat files/tuneros-playback-report.txt > public-stream-report.txt 2>/dev/null || true
      if grep -Eq '^(PASS|FAIL)[[:space:]]qa-playback-first-frame' public-stream-report.txt; then
        break
      fi
      sleep 2
    done
    RESULT=$(grep -E '^(PASS|FAIL)[[:space:]]qa-playback-first-frame' public-stream-report.txt 2>/dev/null || echo "NO RESULT")
    echo "public stream $STREAM_INDEX of up to 8: $RESULT"
    if grep -Eq '^PASS[[:space:]]qa-playback-first-frame' public-stream-report.txt 2>/dev/null; then
      STREAM_PASSES=$((STREAM_PASSES + 1))
    fi
  done 3< first-streams.txt
  if [ "$STREAM_PASSES" -lt 1 ]; then
    echo "Real stream check: no stream from the public playlist rendered a first frame"
    exit 1
  fi
  echo "Real stream check: $STREAM_PASSES of $STREAM_INDEX streams rendered a first frame"
fi
# Scheduled recording: a stored schedule is started by a real exact alarm through the receiver
# and must complete at its stop, end STARTED, and delete its credentials. The runner serves the stream
# at 0.5 s per KiB, so it is still running at the 20-second stop and the file stays partial.
python3 ci-playback/throttle.py 0.5 >scheduled-throttle.log 2>&1 &
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
  tail -n 20 scheduled-throttle.log 2>/dev/null || true
  adb logcat -d -v time 2>/dev/null | tr -d '\r' | grep -E 'RecordingAlarm|DvrRecording|AlarmManager|tuneros' | tail -60 | cut -c1-240 || true
  exit 1
fi

# Startup recovery: a user import saves the playlist and guide URLs encrypted, a cold start restores both with the
# fixture counts, and a failed import leaves the saved playlist unchanged. Loopback fixtures only. The app report
# holds PASS/FAIL, ids and counts, never a URL.
python3 -m http.server 8766 --bind 0.0.0.0 --directory ci-import >recovery-server.log 2>&1 &
RECOVERY_HTTP_PID=$!
sleep 2
REC_PLAYLIST="http://10.0.2.2:8766/playlist.m3u"
REC_GUIDE="http://10.0.2.2:8766/guide.xml"
REC_MISSING="http://10.0.2.2:8766/missing-playlist.m3u"
recovery_fail() {
  kill "$RECOVERY_HTTP_PID" 2>/dev/null || true
  echo "$1"
  cat recovery-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
}
recovery_launch() { # $1 = mode, then any extra arguments for am start
  local mode=$1
  shift
  adb shell run-as com.devtunasoft.tuneros rm -f files/tuneros-recovery-report.txt || true
  adb shell am force-stop com.devtunasoft.tuneros
  adb shell am start -n com.devtunasoft.tuneros/.MainActivity --es tuneros.debug.recovery_mode "$mode" "$@"
}
recovery_wait() { # $1 = check id; returns once that id has a PASS or FAIL line
  for attempt in $(seq 1 60); do
    adb shell run-as com.devtunasoft.tuneros cat files/tuneros-recovery-report.txt > recovery-report.txt 2>/dev/null || true
    if grep -Eq "^(PASS|FAIL)[[:space:]]$1[[:space:]]" recovery-report.txt; then
      return 0
    fi
    sleep 2
  done
  return 1
}
recovery_launch seed --es tuneros.debug.recovery_playlist_url "$REC_PLAYLIST" --es tuneros.debug.recovery_guide_url "$REC_GUIDE"
recovery_wait qa-startup-recovery-seed || recovery_fail "Startup recovery seed did not complete"
if ! grep -Fq "$(printf 'PASS\tqa-startup-recovery-seed\tchannels=3 programs=6 saved=true')" recovery-report.txt; then
  recovery_fail "Startup recovery seed failed"
fi
# The saved sources must be encrypted records in the app's preferences, never plaintext URLs.
adb shell run-as com.devtunasoft.tuneros cat shared_prefs/tuneros_alpha.xml > recovery-prefs.xml 2>/dev/null || true
if ! grep -q 'name="playlist.secure"' recovery-prefs.xml || ! grep -q 'name="guide.secure"' recovery-prefs.xml \
   || grep -q 'name="playlist"' recovery-prefs.xml || grep -q 'name="guide"' recovery-prefs.xml \
   || grep -Fq "10.0.2.2:8766/playlist.m3u" recovery-prefs.xml || grep -Fq "10.0.2.2:8766/guide.xml" recovery-prefs.xml; then
  recovery_fail "Saved sources are missing or stored as plaintext in the app's preferences"
fi
recovery_launch check
recovery_wait qa-startup-recovery || recovery_fail "Startup recovery after a cold start did not complete"
if ! grep -Fq "$(printf 'PASS\tqa-startup-recovery\tchannels=3 programs=6')" recovery-report.txt; then
  recovery_fail "Startup recovery did not restore the fixture counts after a cold start"
fi
recovery_launch bad --es tuneros.debug.recovery_playlist_url "$REC_MISSING"
recovery_wait qa-startup-recovery-bad-import || recovery_fail "Failed-import check did not complete"
if ! grep -Fq "$(printf 'PASS\tqa-startup-recovery-bad-import\t')" recovery-report.txt; then
  recovery_fail "A failed import changed the saved playlist"
fi
recovery_launch check
recovery_wait qa-startup-recovery || recovery_fail "Startup recovery after the failed import did not complete"
if ! grep -Fq "$(printf 'PASS\tqa-startup-recovery\tchannels=3 programs=6')" recovery-report.txt; then
  recovery_fail "The saved playlist did not survive the failed import"
fi
kill "$RECOVERY_HTTP_PID" 2>/dev/null || true
echo "Startup recovery: sources saved encrypted, restored after a cold start, unchanged by a failed import"

if grep -q '^FAIL[[:space:]]' on-device-report.txt; then
  echo "Unexpected failure in the app's own self-test report"
  cat on-device-report.txt
  exit 1
fi
cat on-device-report.txt
