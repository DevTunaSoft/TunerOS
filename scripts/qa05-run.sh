#!/usr/bin/env bash
# QA-05 deterministic JVM generator/verifier. No device, no network, no dependencies.
# Set JAVA_HOME to a JDK 11+ when java is not on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  echo "QA05 BLOCKED: no JDK found; set JAVA_HOME to a JDK 11+ and rerun"
  exit 3
fi

FIXTURE="qa/fixtures/qa05/guide-search-fixture.txt"
TMP="scripts/qa05/.qa05-generated-fixture.txt"
trap 'rm -f "$TMP"' EXIT

echo "QA05 JDK: $($JAVA_BIN -version 2>&1 | head -1)"
$JAVA_BIN scripts/qa05/Qa05FixtureGenerator.java "$TMP"
# core.autocrlf may give the checked-out fixture CRLF while the generator always writes LF.
# Compare line content so the check is about the data, not the checkout's line endings.
if ! diff -q <(tr -d '\r' < "$TMP") <(tr -d '\r' < "$FIXTURE") >/dev/null; then
  echo "QA05 FAIL: committed fixture differs from deterministic generator output"
  diff -u "$FIXTURE" "$TMP" || true
  exit 1
fi
echo "QA05 PASS: committed fixture matches regenerated output (line endings normalised)"
$JAVA_BIN scripts/qa05/Qa05FixtureVerifier.java "$FIXTURE" \
  --embedded app/src/androidTest/java/com/devtunasoft/tuneros/qa05/Qa05GuideAssertions.kt
