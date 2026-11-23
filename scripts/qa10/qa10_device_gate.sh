#!/usr/bin/env bash
# QA-10 bounded runner for the device portion. Device execution is UNVERIFIED unless an
# authorized attached device is present; this runner never reports a device PASS on a host.
set -u
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
TARGET_PACKAGE="com.devtunasoft.tuneros"
RUNNER="com.devtunasoft.tuneros.Qa10EventLogInstrumentation"
TEST_PACKAGE="com.devtunasoft.tuneros.test"
REPORT_NAME="qa10-event-log-report.txt"
TIMEOUT_BIN=$(command -v timeout || true)
run_bounded() {
  local seconds=$1; shift
  if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" "$seconds" "$@"; else "$@"; fi
}

ADB=$(command -v adb || true)
if [ -z "$ADB" ]; then
  for candidate in \
    C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03/platform-tools/adb.exe \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
    if [ -x "$candidate" ]; then ADB="$candidate"; break; fi
  done
fi
if [ -z "$ADB" ]; then
  echo 'DEVICE\tBLOCKED\tno-adb\tno authorized device evidence'
  exit 2
fi

DEVICES=$(run_bounded 20 "$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if [ -z "$DEVICES" ]; then
  echo 'DEVICE\tBLOCKED\tno-authorized-device\tUNVERIFIED (no device PASS)'
  exit 2
fi
echo "DEVICE\tATTEMPT\tone device present"

OUT=$(run_bounded 120 "$ADB" shell am instrument -w -r "$TEST_PACKAGE/$RUNNER" 2>&1)
STATUS=$?
echo "INSTRUMENT\texit=$STATUS"
if [ "$STATUS" -ne 0 ] || ! printf '%s\n' "$OUT" | grep -q '^INSTRUMENTATION_STATUS_CODE'; then
  echo 'DEVICE\tUNVERIFIED\tinstrumentation did not finish cleanly'
  exit 3
fi

REPORT=$(run_bounded 30 "$ADB" shell run-as "$TARGET_PACKAGE" cat "files/$REPORT_NAME" 2>/dev/null)
REPORT_STATUS=$?
if [ "$REPORT_STATUS" -ne 0 ]; then
  echo 'DEVICE\tUNVERIFIED\treport not readable (run-as unavailable)'
  exit 3
fi
SUMMARY=$(printf '%s\n' "$REPORT" | awk -F '\t' '$1 == "SUMMARY" { print $2, $3; exit }')
if [ "$SUMMARY" = "0/0 PASS" ] || [ -z "$SUMMARY" ]; then
  echo 'DEVICE\tFAIL\tempty-or-invalid-report'
  exit 1
fi
echo "DEVICE\tSUMMARY=$SUMMARY"
printf '%s\n' "$REPORT" | awk -F '\t' '$1 == "PASS" || $1 == "FAIL" { print "CHECK\t" $2 "\t" $1 }'
if printf '%s\n' "$REPORT" | grep -q '^FAIL	'; then
  echo 'DEVICE\tFAIL\tsee report checks above'
  exit 1
fi
echo 'DEVICE\tPASS\tdevice report matched bounded check lines'
