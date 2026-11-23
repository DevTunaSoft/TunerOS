#!/usr/bin/env bash
# QA-27 truthful device-status probe. Never claims an unattached device.
set -uo pipefail
say() { printf '%b\n' "$1"; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03}}"
ADB="$SDK/platform-tools/adb.exe"
if [ ! -x "$ADB" ]; then
  say 'DEVICE\tBLOCKED\tno adb binary'
  exit 0
fi
OUT="$(timeout 20 "$ADB" devices 2>/dev/null)"
if printf '%s\n' "$OUT" | grep -Eq '^[[:alnum:]_.:-]+[[:space:]]+device$'; then
  say 'DEVICE\tNOT RUN\ttarget present; QA-27 does not execute instrumentation here'
else
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target'
fi
exit 0
