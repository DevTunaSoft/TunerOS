#!/usr/bin/env bash
set -euo pipefail
# Android TV device checks for TunerOS. CI runs this inside reactivecircus/android-emulator-runner on an Android 35 TV
# image (system-images;android-35;android-tv;x86_64, device tv_1080p). The debug APK is in ci-apk/. The script installs
# it, then launches the app's own debug checks and reads the app's private reports with run-as:
#   1. --ez tuneros.debug.ui_checks true: parental PIN dialogs, playback gate, D-pad focus and restore checks.
#   2. --ez tuneros.debug.keys true: remote key input (adb DPAD keys must reach the activity's dispatchKeyEvent).
# No touch input is sent. The app uses a scratch PIN store and its own test clip, so no user data is read or changed.
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

echo "Android TV device checks: ${#UI_REQUIRED[@]} of ${#UI_REQUIRED[@]} required device checks PASS and remote key input PASS, 0 FAIL"
