#!/usr/bin/env bash
# QA-27 JVM mirror check: compiles and runs the Android-free mirror assertions.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH_DEFAULT="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
JH="${JAVA_HOME:-$JH_DEFAULT}/bin"
B="$(cygpath -m "$HOME/AppData/Local/hermes/cache/scratch" 2>/dev/null || printf '%s' "$HOME/AppData/Local/hermes/cache/scratch")/qa27-run/mirror"
rm -rf "$B"; mkdir -p "$B"
timeout 180 "$JH/javac" -d "$B" "$ROOT/scripts/qa27/Qa27Mirror.java" || { say 'FAIL\tqa27-mirror\tcompile'; exit 1; }
LOG="${TMPDIR:-/tmp}/qa27-mirror.log"
if ! timeout 90 "$JH/java" -Xmx64m -cp "$B" Qa27Mirror > "$LOG" 2>&1; then
  say 'FAIL\tqa27-mirror\texit-nonzero'
  cat "$LOG"
  exit 1
fi
N="$(awk -F '\t' '$1=="CHECK" && $3=="PASS"{n++} END{print n+0}' "$LOG")"
if [ "$N" -ne 7 ]; then
  say "FAIL\tqa27-mirror\tchecks=$N expected=7"
  cat "$LOG"
  exit 1
fi
say "PASS\tqa27-mirror\tchecks=$N"
