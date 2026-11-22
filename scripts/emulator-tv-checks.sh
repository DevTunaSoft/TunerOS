#!/usr/bin/env bash
set -euo pipefail
# Android TV device checks for TunerOS. CI runs this inside reactivecircus/android-emulator-runner on an Android 36 TV
# image (system-images;android-36;android-tv;x86_64, device tv_1080p). No Android 35 TV image exists on the runner.
# The debug APK is in ci-apk/. The script installs it, then launches the app's own debug checks and reads the app's
# private reports with run-as:
#   1. --ez tuneros.debug.ui_checks true: parental PIN dialogs, playback gate, D-pad focus and restore checks.
#   2. --ez tuneros.debug.keys true: remote key input (adb DPAD keys must reach the activity's dispatchKeyEvent).
# No touch input is sent. The app uses a scratch PIN store and its own test clip, so no user data is read or changed.
# Last, the script imports a loopback guide with current programmes and saves tv-guide.png, with a check that the
# guide rows are in the view hierarchy. The fixture is generated at run time and nothing from it is committed.
# Not run here: debug-ui-focus and debug-empty-guide-dpad (tuneros.debug.qa pass, the whole self-test suite) and
# external-recording-storage (dispatch-only, and the phone script taps the folder picker with input tap).

# Stop the emulator and the processes it started, with a bounded wait, on every exit path. The emulator runner sends
# "emu kill" and does not wait for the emulator to exit (same handling as scripts/emulator-self-test.sh).
stop_emulator() {
  timeout 20 adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    pgrep -f 'qemu-system|netsimd|crashpad_handler|emulator/emulator' >/dev/null 2>&1 || break
    sleep 1
  done
  if pgrep -f 'qemu-system|netsimd|crashpad_handler|emulator/emulator' >/dev/null 2>&1; then
    echo "Emulator processes were still running 30 seconds after the kill; stopping them"
    pkill -KILL -f 'qemu-system|netsimd|crashpad_handler|emulator/emulator' >/dev/null 2>&1 || true
  fi
  jobs -p | xargs -r kill >/dev/null 2>&1 || true
}
# Capture a bounded logcat tail on failure, then stop the emulator.
on_exit() {
  status=$?
  if [ "$status" -ne 0 ]; then
    adb logcat -d -t 1200 -v brief > tv-failure-logcat.txt 2>/dev/null || true
  fi
  stop_emulator
  exit "$status"
}
trap on_exit EXIT

PACKAGE=com.devtunasoft.tuneros
ACTIVITY=com.devtunasoft.tuneros/.MainActivity

# Each check below must print PASS in the app's report. Names match the phone script where it has them. Every check
# here sends key events or calls framework methods on the app's own screens; none needs a touchscreen.
UI_REQUIRED=(
  ui-pin-dialog-opens ui-pin-saved ui-restrict-dialog-opens ui-group-restricted
  ui-unrestricted-plays ui-restricted-asks-for-pin ui-wrong-pin-refused ui-right-pin-plays
  ui-channel-restricted ui-restricted-channel-asks-for-pin
  focus-reach-parental-pin focus-ring-on-focused-button focus-ok-opens-pin-dialog focus-returns-after-dialog
  ui-restore-dialog-counts ui-restore-cancel-changes-nothing focus-ring-on-channel-row
  guide-focus-channel-cell-takes-focus guide-focus-right-left-moves-programme guide-focus-left-edge-shifts-window
  guide-focus-up-down-leaves-timeline
)

# Start the activity with one debug extra. A refused start fails the run and prints the am output.
launch_check() {
  adb shell am start -n "$ACTIVITY" "$@" > am-start.txt 2>&1 || true
  tr -d '\r' < am-start.txt
  if grep -Eq 'Error|Warning: Activity not started' am-start.txt; then
    echo "The app did not start for the device checks"
    exit 1
  fi
}

# True when the key report holds the phone script's remote key PASS line (four key-up events, focus change either way).
key_passed() {
  grep -Eq "$(printf '^PASS\tqa-remote-key-input\tkeys=4 focusChanged=(true|false)$')" key-report.txt
}

adb install -r ci-apk/app-debug.apk

# An Android TV image declares the leanback feature. A phone image would install and then fail the checks in confusing
# ways, so the run stops here instead.
{
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r') api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  adb shell pm list features | tr -d '\r'
} > tv-features.txt
cat tv-features.txt
if ! grep -q '^feature:android.software.leanback$' tv-features.txt; then
  echo "The emulator does not declare android.software.leanback, so it is not an Android TV image"
  exit 1
fi
if grep -q '^feature:android.hardware.touchscreen$' tv-features.txt; then
  echo "Touchscreen feature: declared (the checks send no touch input)"
else
  echo "Touchscreen feature: not declared"
fi
adb shell svc power stayon true || true

# Device checks. The app drives its own parental PIN dialogs, playback gate, D-pad focus and restore screens.
adb shell run-as "$PACKAGE" rm -f files/tuneros-ui-report.txt 2>/dev/null || true
adb shell am force-stop "$PACKAGE"
launch_check --ez tuneros.debug.ui_checks true
REPORT_READY=false
for attempt in $(seq 1 240); do
  adb shell run-as "$PACKAGE" cat files/tuneros-ui-report.txt > ui-report.txt 2>/dev/null || true
  # The app writes the whole report at the end, so a complete report is non-empty and ends with a newline.
  if [ -s ui-report.txt ] && [ -z "$(tail -c 1 ui-report.txt)" ]; then
    REPORT_READY=true
    break
  fi
  sleep 2
done
cat ui-report.txt 2>/dev/null || echo "(no report file)"
if [ "$REPORT_READY" != true ]; then
  echo "The device checks did not finish writing tuneros-ui-report.txt within 8 minutes"
  exit 1
fi
if grep -q '^FAIL' ui-report.txt; then
  echo "A device check failed"
  exit 1
fi
for check in "${UI_REQUIRED[@]}"; do
  if ! grep -Fq "$(printf 'PASS\t%s\t' "$check")" ui-report.txt; then
    echo "Device check $check did not pass or did not finish"
    exit 1
  fi
done
# The OK and BACK checks must pass by the remote key itself. Their details say when the framework's click or cancel
# fallback was used instead, and such a PASS does not prove the D-pad path on this device, so it fails the run.
ok_line="$(grep -F "$(printf 'PASS\tfocus-ok-opens-pin-dialog\t')" ui-report.txt || true)"
back_line="$(grep -F "$(printf 'PASS\tfocus-returns-after-dialog\t')" ui-report.txt || true)"
case "$ok_line" in
  *'(by the OK key)'*) ;;
  *) echo "focus-ok-opens-pin-dialog did not open its dialog with the OK key: $ok_line"; exit 1 ;;
esac
case "$back_line" in
  *'(by the BACK key)'*) ;;
  *) echo "focus-returns-after-dialog did not close its dialog with the BACK key: $back_line"; exit 1 ;;
esac

# Remote key input. The app arms its key report on launch and records four key-up events. Keys go to the app's
# window only once it has focus, so wait for focus first (bounded). The key report below still decides the result.
adb shell run-as "$PACKAGE" rm -f files/tuneros-key-report.txt 2>/dev/null || true
adb shell am force-stop "$PACKAGE"
launch_check --ez tuneros.debug.keys true
for attempt in $(seq 1 60); do
  adb shell run-as "$PACKAGE" cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
  if grep -Fq "ARMED" key-report.txt; then break; fi
  sleep 2
done
if ! grep -Fq "ARMED" key-report.txt; then
  echo "The remote key report was never armed"
  exit 1
fi
for attempt in $(seq 1 30); do
  adb shell dumpsys window > focus-window.txt 2>/dev/null || true
  if grep -q 'mCurrentFocus=.*com.devtunasoft.tuneros' focus-window.txt; then break; fi
  sleep 2
done
grep -E 'mCurrentFocus=' focus-window.txt | head -1 | cut -c1-200 || true
# Up to three rounds. A round is repeated only when the app recorded no key at all: a partly delivered round is already
# counted, and repeating it would miscount the four keys.
for round in 1 2 3; do
  adb shell input keyevent 22
  sleep 1
  adb shell input keyevent 22
  sleep 1
  adb shell input keyevent 20
  sleep 1
  adb shell input keyevent 20
  for attempt in $(seq 1 15); do
    adb shell run-as "$PACKAGE" cat files/tuneros-key-report.txt > key-report.txt 2>/dev/null || true
    if key_passed; then break; fi
    sleep 2
  done
  if key_passed || grep -q '^KEY' key-report.txt; then break; fi
  echo "Key round $round: no key event reached the app"
  sleep 3
done
if ! key_passed; then
  echo "Remote key input check failed or never completed"
  cat key-report.txt 2>/dev/null || echo "(no report file)"
  exit 1
fi
cat key-report.txt

# Screenshot of the guide as a viewer sees it. The app is relaunched with the same debug import the phone script uses,
# but with programmes around the current time: the guide window starts at the current half hour, so a fixture dated in
# 2099 would show empty rows. The import report, the rendered channel row and the PNG header must all pass. The app's
# data is cleared first, so no restriction or PIN state from the checks above is on screen. Nothing here is committed.
TV_GUIDE_DIR=ci-tv-guide
rm -rf "$TV_GUIDE_DIR"
mkdir -p "$TV_GUIDE_DIR"
NOW=$(date -u +%s)
WINDOW=$(( NOW - NOW % 1800 ))
xmltime() { date -u -d "@$1" +%Y%m%d%H%M%S; }
{
  echo '#EXTM3U'
  for n in one two three; do
    echo "#EXTINF:-1 tvg-id=\"qa.$n\" group-title=\"QA\",QA ${n^}"
    echo "http://10.0.2.2:8766/stream-$n.m3u8"
  done
} > "$TV_GUIDE_DIR/playlist.m3u"
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<tv>'
  for n in one two three; do
    echo "<channel id=\"qa.$n\"><display-name>QA ${n^}</display-name></channel>"
  done
  for n in one two three; do
    echo "<programme start=\"$(xmltime $((WINDOW - 1800))) +0000\" stop=\"$(xmltime $((WINDOW + 1800))) +0000\" channel=\"qa.$n\"><title>Now on QA ${n^}</title></programme>"
    echo "<programme start=\"$(xmltime $((WINDOW + 1800))) +0000\" stop=\"$(xmltime $((WINDOW + 5400))) +0000\" channel=\"qa.$n\"><title>Next on QA ${n^}</title></programme>"
    echo "<programme start=\"$(xmltime $((WINDOW + 5400))) +0000\" stop=\"$(xmltime $((WINDOW + 9000))) +0000\" channel=\"qa.$n\"><title>Later on QA ${n^}</title></programme>"
  done
  echo '</tv>'
} > "$TV_GUIDE_DIR/guide.xml"
python3 -m http.server 8766 --bind 0.0.0.0 --directory "$TV_GUIDE_DIR" > tv-guide-server.log 2>&1 &
TV_GUIDE_HTTP_PID=$!
sleep 2
if ! curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8766/guide.xml; then
  echo "The loopback guide fixture is not being served"
  kill "$TV_GUIDE_HTTP_PID" 2>/dev/null || true
  exit 1
fi
adb shell pm clear "$PACKAGE" > /dev/null
launch_check --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
  --es tuneros.debug.import_guide_url http://10.0.2.2:8766/guide.xml
for attempt in $(seq 1 90); do
  adb shell run-as "$PACKAGE" cat files/tuneros-import-report.txt > tv-guide-import.txt 2>/dev/null || true
  if grep -Eq '^(PASS|FAIL)[[:space:]]qa-loopback-import' tv-guide-import.txt; then break; fi
  sleep 2
done
kill "$TV_GUIDE_HTTP_PID" 2>/dev/null || true
if ! grep -Eq '^PASS[[:space:]]qa-loopback-import[[:space:]]channels=3 programs=9$' tv-guide-import.txt; then
  echo "The loopback guide import did not complete for the screenshot"
  cat tv-guide-import.txt 2>/dev/null || echo "(no import report)"
  exit 1
fi
# Let the guide render, then wait for the app to hold focus before the capture.
sleep 3
for attempt in $(seq 1 15); do
  adb shell dumpsys window > tv-guide-focus.txt 2>/dev/null || true
  if grep -q 'mCurrentFocus=.*com.devtunasoft.tuneros' tv-guide-focus.txt; then break; fi
  sleep 2
done
adb exec-out screencap -p > tv-guide.png
if [ "$(head -c 8 tv-guide.png | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
  echo "The guide screenshot is not a PNG file"
  exit 1
fi
read -r SHOT_W SHOT_H < <(od -An -tu1 -j16 -N8 tv-guide.png | awk '{print $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8}')
if [ "$SHOT_W" -le 0 ] || [ "$SHOT_H" -le 0 ]; then
  echo "The guide screenshot has no size"
  exit 1
fi
# The rendered guide must be in the view hierarchy too: a channel name and its timeline description. This checks the
# rows themselves, not only the PNG. The dump is retried, since the UI may still be settling.
ROWS_OK=false
for attempt in 1 2 3; do
  adb shell uiautomator dump /data/local/tmp/tuneros-window.xml > /dev/null 2>&1 || true
  adb shell cat /data/local/tmp/tuneros-window.xml > tv-guide-window.xml 2>/dev/null || true
  if grep -Fq 'text="QA One"' tv-guide-window.xml && grep -Fq 'content-desc="QA One, ' tv-guide-window.xml; then
    ROWS_OK=true
    break
  fi
  sleep 3
done
if [ "$ROWS_OK" != true ]; then
  echo "The guide rows are not in the view hierarchy, so the screenshot cannot be trusted to show the guide"
  exit 1
fi
if grep -Fq 'text="Load guide"' tv-guide-window.xml; then
  echo "The source rows are still visible after the guide loaded"
  exit 1
fi
# Layout measurement from the device's own view tree (uiautomator bounds, not the image). It is printed to the log so
# it can be read without downloading the artifact. dp = px x 160 / density, and the density is read from the device.
DENSITY=$(adb shell wm density | tr -d '\r' | grep -oE '[0-9]+' | tail -1 || true)
DENSITY=${DENSITY:-320}
measure_layout() {
  python3 -I - "$1" "$DENSITY" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml_path, density = sys.argv[1], float(sys.argv[2])
scale = 160.0 / density
bounds = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
def walk(node, depth):
    m = bounds.match(node.get('bounds', ''))
    if m:
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        label = node.get('text') or node.get('content-desc') or node.get('resource-id') or ''
        label = label.encode('ascii', 'replace').decode('ascii')[:60]
        cls = (node.get('class') or '').rsplit('.', 1)[-1]
        print(f"{'  ' * depth}{cls:<24} top={y1 * scale:6.1f} bottom={y2 * scale:6.1f} "
              f"height={(y2 - y1) * scale:6.1f} width={(x2 - x1) * scale:6.1f}  {label}")
    for child in node:
        walk(child, depth + 1)
for top in ET.parse(xml_path).getroot():
    walk(top, 0)
PY
}
{
  echo "screen density=$DENSITY (dp = px x 160 / density); guide loaded, source rows hidden"
  measure_layout tv-guide-window.xml
} > tv-layout-guide-loaded.txt
cat tv-layout-guide-loaded.txt
{
  echo "screenshot=tv-guide.png ${SHOT_W}x${SHOT_H}"
  echo "import: $(grep -o 'PASS.*' tv-guide-import.txt | head -1)"
  echo "rendered: the QA One channel row and its timeline are in tv-guide-window.xml"
  echo "guide window starts at $(date -u -d "@$WINDOW" +%Y-%m-%dT%H:%M:%SZ) UTC; captured $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > tv-guide-info.txt
cat tv-guide-info.txt
# Second state: the same playlist, with a guide URL that the loopback server answers with 404. The channels load, the
# guide does not, and the source rows stay visible. Only the layout is read from this state; its import report is not
# asserted, because the guide is meant to fail here.
python3 -m http.server 8766 --bind 0.0.0.0 --directory "$TV_GUIDE_DIR" > tv-layout-server.log 2>&1 &
TV_LAYOUT_HTTP_PID=$!
sleep 2
adb shell pm clear "$PACKAGE" > /dev/null
launch_check --es tuneros.debug.import_playlist_url http://10.0.2.2:8766/playlist.m3u \
  --es tuneros.debug.import_guide_url http://10.0.2.2:8766/missing-guide.xml
LAYOUT_OK=false
for attempt in $(seq 1 30); do
  adb shell uiautomator dump /data/local/tmp/tuneros-window-2.xml > /dev/null 2>&1 || true
  adb shell cat /data/local/tmp/tuneros-window-2.xml 2>/dev/null | tr -d '\r' > tv-window-playlist-only.xml || true
  if grep -Fq 'text="Load guide"' tv-window-playlist-only.xml && grep -Fq 'text="QA One"' tv-window-playlist-only.xml; then
    LAYOUT_OK=true
    break
  fi
  sleep 3
done
kill "$TV_LAYOUT_HTTP_PID" 2>/dev/null || true
if [ "$LAYOUT_OK" != true ]; then
  echo "The playlist-only state did not show its source rows and channel rows"
  exit 1
fi
{
  echo "screen density=$DENSITY (dp = px x 160 / density); channels loaded, guide not loaded, source rows visible"
  measure_layout tv-window-playlist-only.xml
} > tv-layout-playlist-only.txt
cat tv-layout-playlist-only.txt
echo "Android TV device checks: ${#UI_REQUIRED[@]} of ${#UI_REQUIRED[@]} required device checks PASS and remote key input PASS, 0 FAIL"
