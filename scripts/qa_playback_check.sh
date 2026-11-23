#!/usr/bin/env bash
set -euo pipefail
# QA-03 JVM harness runner for the SAME class AGP compiles into androidTest.
#
# This is the acceptance check for the on-device harness: it generates the bounded
# seeded fixture twice, checks byte-for-byte determinism, compiles the pure-Java
# shared harness with javac, runs switch/lifecycle/taxonomy/redaction checks and
# verifies that a corrupted expectation is rejected. It is deliberately not wired
# into CI: the repository's existing QA-03 JVM step owns the JVM gate, while CI
# gets the on-device half from app/src/androidTest via the existing emulator job.
# No network, SDK, emulator, provider URL, credential or media byte is used.
# Output lives under build/ and is disposable.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="build/qa-playback-fixture"
NEG="build/qa-playback-fixture-negative"
rm -rf "$OUT" "$NEG"
mkdir -p "$OUT/classes" "$NEG"

HARNESS="app/src/androidTest/java/com/devtunasoft/tuneros/qa/QaPlaybackSoak.java"
SOAK=com.devtunasoft.tuneros.qa.QaPlaybackSoak

javac -encoding UTF-8 -d "$OUT/classes" "$HARNESS"
echo "PASS qa-playback-harness-compiles harness=QaPlaybackSoak javac"

java -cp "$OUT/classes" "$SOAK" --generate "$OUT"
java -cp "$OUT/classes" "$SOAK" --generate "$NEG"
for name in playlist.m3u plan.tsv manifest.properties; do
  cmp -s "$OUT/$name" "$NEG/$name" || {
    echo "FAIL qa-playback-determinism $name differs between two seeded runs"
    exit 1
  }
done
echo "PASS qa-playback-determinism files=3 identical seeded outputs"

java -cp "$OUT/classes" "$SOAK" --check "$OUT" | tee "$OUT/report.txt"
grep -q '^PLAYBACK REGRESSION FIXTURE CHECK PASS$' "$OUT/report.txt"

# Negative control: an altered expected count must be detected, never silently accepted.
python3 - "$NEG/manifest.properties" <<'PY'
import sys
path = sys.argv[1]
with open(path, "r", encoding="utf-8", newline="\n") as source:
    lines = source.readlines()
with open(path, "w", encoding="utf-8", newline="\n") as target:
    for line in lines:
        if line.startswith("expected_first_frames="):
            target.write("expected_first_frames=" + str(int(line.split("=", 1)[1]) + 1) + "\n")
        else:
            target.write(line)
PY
if java -cp "$OUT/classes" "$SOAK" --check "$NEG" > "$NEG/negative.log" 2>&1; then
  echo "FAIL qa-playback-negative-control corrupted manifest was accepted"
  exit 1
fi
grep -q '^FAIL ' "$NEG/negative.log" || {
  echo "FAIL qa-playback-negative-control no explicit FAIL line"
  exit 1
}
echo "PASS qa-playback-negative-control corrupted expectation rejected"
rm -rf "$NEG"

echo "QA-03 on-device harness JVM check: PASS"
