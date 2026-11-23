#!/usr/bin/env bash
# QA-28 device status probe. This worker never claims a device PASS.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03}}"
ADB="$SDK/platform-tools/adb.exe"
if [ ! -x "$ADB" ]; then
  say 'DEVICE\tBLOCKED\tno adb binary available'
  say 'DEVICE_EXECUTION\tUNVERIFIED'
  exit 0
fi
if timeout 20 "$ADB" devices 2>/dev/null \
    | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' >/dev/null; then
  say 'DEVICE\tNOT RUN\tauthorized target present but instrumentation was not executed'
else
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target'
fi
say 'DEVICE_EXECUTION\tUNVERIFIED'
exit 0
