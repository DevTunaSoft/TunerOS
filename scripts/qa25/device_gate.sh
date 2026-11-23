#!/usr/bin/env bash
# QA-25 device gate. Reports a truthful status; never claims a device PASS.
set -euo pipefail
SDK="${ANDROID_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03}"
ADB="$SDK/platform-tools/adb.exe"
if [ ! -x "$ADB" ] && [ ! -f "$ADB" ]; then
  printf 'DEVICE\tBLOCKED\treason=adb-missing\n'
  exit 3
fi
OUT="$(timeout 30 "$ADB" devices 2>&1 || true)"
COUNT="$(printf '%s\n' "$OUT" | awk '/^List of devices attached/ {next} NF>1 && $2=="device" {n++} END{print n+0}')"
if [ "$COUNT" -eq 0 ]; then
  printf 'DEVICE\tBLOCKED\treason=no-authorized-device\tstate=UNVERIFIED\n'
  exit 3
fi
printf 'DEVICE\tPRESENT\tcount=%s\tstate=NOT_RUN\n' "$COUNT"
exit 0
