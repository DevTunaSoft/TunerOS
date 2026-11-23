#!/usr/bin/env bash
# QA-22 bounded instrumented execution. Refuses to claim a PASS without an authorized device.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
      "$HOME/AppData/Local/hermes/cache/scratch/android-sdk-mv03" \
      "$HOME/AppData/Local/Android/Sdk" \
      "$HOME/Android/Sdk"; do
    if [ -f "$candidate/platform-tools/adb.exe" ] || [ -f "$candidate/platform-tools/adb" ]; then
      SDK="$candidate"
      break
    fi
  done
fi
ADB="$SDK/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"
if [ -z "$SDK" ] || [ ! -x "$ADB" ]; then
  say 'DEVICE\tBLOCKED\tno local adb binary'
  exit 0
fi

DEVICES=$(timeout 20 "$ADB" devices 2>/dev/null | grep -E '^[[:alnum:]_.:-]+[[:space:]]+device$' | wc -l)
if [ "$DEVICES" -eq 0 ]; then
  say 'DEVICE\tBLOCKED\tno authorized adb/emulator target'
  say 'DEVICE\tUNVERIFIED\tinstrumented execution not attempted'
  exit 0
fi

REPORT="${TMPDIR:-/tmp}/qa22-device.log"
if ! timeout 180 "$ADB" shell am instrument -w -r \
    com.devtunasoft.tuneros.test/com.devtunasoft.tuneros.qa22.Qa22Instrumentation \
    > "$REPORT" 2>&1; then
  say 'DEVICE\tFAIL\tam instrument returned nonzero; see scratch qa22-device.log'
  exit 1
fi
PASS_LINES=$(grep -c '^CHECK\tPASS' "$REPORT")
FAIL_LINES=$(grep -c '^CHECK\tFAIL' "$REPORT")
if [ "$FAIL_LINES" -ne 0 ] || [ "$PASS_LINES" -ne 8 ]; then
  say "DEVICE\tFAIL\tpass=$PASS_LINES\tfail=$FAIL_LINES"
  exit 1
fi
say "DEVICE\tPASS\tchecks=$PASS_LINES\tfail=$FAIL_LINES"
exit 0
