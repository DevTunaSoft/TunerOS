#!/usr/bin/env bash
set -euo pipefail
# QA-02 + PERF-01: deterministic 25,000-channel / 250,000-programme EPG import check.
#
# Uses ONLY existing entry points: PlaylistParser, GuideParser, ChannelVisibility and
# GuideSnapshot from :core. It does not edit core, does not boot an emulator, opens no
# network connection and uses no provider URL, credential or media. Output is generated
# under build/ (ignored by git); only the generator and its init script are checked in.
#
# Resource bounds: generator refuses >128 MiB on disk; harness runs with -Xmx768m and
# asserts sampled peak heap and, on Linux, peak RSS against manifest limits.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="build/qa-epg-fixture"
REPEAT="build/qa-epg-fixture-repeat"
rm -rf "$OUT" "$REPEAT"
mkdir -p "$OUT" "$REPEAT"

python3 scripts/generate_qa_epg_fixture.py --out "$OUT"
python3 scripts/generate_qa_epg_fixture.py --out "$REPEAT"
for name in playlist.m3u guide.xml manifest.properties; do
  cmp -s "$OUT/$name" "$REPEAT/$name" || {
    echo "FAIL qa-large-epg-determinism $name differs between two seeded runs"
    exit 1
  }
done
rm -rf "$REPEAT"
echo "PASS qa-large-epg-determinism identical output across two seeded runs"

# Resolve the core classpath through the project's own Gradle build. The init script is
# print-only, so no Gradle/settings/core file is changed.
./gradlew --no-daemon --offline -I qa/fixtures/core-classpath.init.gradle.kts \
  :core:classes :printQaClasspath > "$OUT/gradle.log" 2>&1 || {
  echo "FAIL qa-large-epg-classpath: Gradle could not resolve :core"
  tail -60 "$OUT/gradle.log"
  exit 1
}
CP="$(cat build/qa-core-classpath.txt)"
case "$CP" in
  *\;*) SEP=';' ;;
  *) SEP=':' ;;
esac

mkdir -p "$OUT/classes"
javac -encoding UTF-8 -cp "$CP" -d "$OUT/classes" scripts/QaFixtureCheck.java

java -Xms64m -Xmx768m -XX:+ExitOnOutOfMemoryError \
  -cp "$OUT/classes${SEP}${CP}" QaFixtureCheck \
  "$OUT/playlist.m3u" "$OUT/guide.xml" "$OUT/manifest.properties" | tee "$OUT/report.txt"

grep -q '^LARGE EPG FIXTURE CHECK PASS$' "$OUT/report.txt"
echo "QA-02/PERF-01 large EPG fixture check: PASS"
