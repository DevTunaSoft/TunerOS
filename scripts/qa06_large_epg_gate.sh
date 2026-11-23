#!/usr/bin/env bash
# QA-06 bounded host gate for the large Classic Cable EPG scratch fixture.
# Owned paths only: app/src/androidTest/**, qa/fixtures/qa06/**, scripts/**.
# No Gradle dependency, product-source, workflow or shared-doc change.
# No device claim: a missing device or emulator is BLOCKED, never PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_ROOT="$ROOT"
if pwd -W >/dev/null 2>&1; then NATIVE_ROOT="$(cd "$ROOT" && pwd -W)"; fi
OUT="$ROOT/build/qa06"
GEN="$OUT/generated"
mkdir -p "$OUT/jvm" "$GEN"
OUT_NATIVE="$(cd "$OUT" && pwd -W 2>/dev/null || printf '%s' "$OUT")"
GEN_NATIVE="$(cd "$GEN" && pwd -W 2>/dev/null || printf '%s' "$GEN")"
RUNNER="com.devtunasoft.tuneros.qa06.Qa06LargeEpgInstrumentation"
MANIFEST="qa/fixtures/qa06/manifest.txt"
GATE_SRC="scripts/qa06/Qa06LargeEpgGate.java"
ASSERTIONS="app/src/androidTest/java/com/devtunasoft/tuneros/qa06/Qa06LargeEpgAssertions.kt"
INSTRUMENTATION="app/src/androidTest/java/com/devtunasoft/tuneros/qa06/Qa06LargeEpgInstrumentation.kt"
ANDROID_MANIFEST="app/src/androidTest/AndroidManifest.xml"

log() { printf '%s QA-06 %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
fail() { log "FAIL $*"; exit 1; }
blocked() { log "BLOCKED $*"; exit 2; }

cd "$ROOT"
mkdir -p "$OUT/jvm" "$GEN"

# Native java/javac reject MSYS /c/... arguments.
if [ -n "${JAVA_HOME:-}" ]; then
  case "$JAVA_HOME" in
    [A-Za-z]:/*) JH="/$(printf '%s' "${JAVA_HOME:0:1}" | tr 'A-Z' 'a-z')${JAVA_HOME:2}" ;;
    *) JH="$JAVA_HOME" ;;
  esac
else
  JH=""
fi
if [ -z "$JH" ]; then
  command -v java >/dev/null 2>&1 || blocked "no JDK found; set JAVA_HOME"
  JAVAC_RAW="$(command -v javac || true)"
  JAVA_RAW="$(command -v java)"
else
  [ -x "$JH/bin/javac" ] || blocked "javac not found under JAVA_HOME"
  [ -x "$JH/bin/java" ] || blocked "java not found under JAVA_HOME"
  JAVAC_RAW="$JH/bin/javac"
  JAVA_RAW="$JH/bin/java"
fi
JAVAC="$(cygpath -m "$JAVAC_RAW" 2>/dev/null || printf '%s' "$JAVAC_RAW")"
JAVA="$(cygpath -m "$JAVA_RAW" 2>/dev/null || printf '%s' "$JAVA_RAW")"
JVM_OUT="$(cd "$OUT/jvm" && pwd -W 2>/dev/null || printf '%s' "$OUT/jvm")"
log "JDK $($JAVA -version 2>&1 | head -1)"

log "JVM generator / verifier / negative controls"
[ -f "$GATE_SRC" ] || fail "missing $GATE_SRC"
[ -f "$MANIFEST" ] || fail "missing $MANIFEST"
"$JAVAC" -encoding UTF-8 -d "$JVM_OUT" "$NATIVE_ROOT/$GATE_SRC" || fail "QA-06 gate source did not compile"

FIX_A="$GEN_NATIVE/large-epg-a.fixture"
FIX_B="$GEN_NATIVE/large-epg-b.fixture"
"$JAVA" -cp "$JVM_OUT" Qa06LargeEpgGate --generate "$FIX_A" >"$OUT/generate-a.txt" 2>&1 \
  || { cat "$OUT/generate-a.txt"; fail "first generation failed"; }
"$JAVA" -cp "$JVM_OUT" Qa06LargeEpgGate --generate "$FIX_B" >"$OUT/generate-b.txt" 2>&1 \
  || { cat "$OUT/generate-b.txt"; fail "second generation failed"; }
cat "$OUT/generate-a.txt"
cmp -s "$GEN/large-epg-a.fixture" "$GEN/large-epg-b.fixture" \
  || fail "generator is not byte-identical across runs"
log "PASS qa06-generator-byte-identical $(sha256sum "$GEN/large-epg-a.fixture" | cut -d' ' -f1)"

"$JAVA" -cp "$JVM_OUT" Qa06LargeEpgGate --all "$FIX_A" "$NATIVE_ROOT/$MANIFEST" \
  "$OUT_NATIVE/report.txt" >"$OUT/jvm-gate.txt" 2>&1 \
  || { cat "$OUT/jvm-gate.txt"; fail "JVM verifier / negative-control gate failed"; }
cat "$OUT/jvm-gate.txt"
grep -Fq $'PASS	qa06-jvm-gate' "$OUT/jvm-gate.txt" || fail "JVM gate summary missing"
for mutation in ordering-altered clipped-visible-row page-count-overflow non-loopback-reference; do
  grep -Fq "$(printf 'PASS	qa06-negative-mutation	%s' "$mutation")" "$OUT/jvm-gate.txt" \
    || fail "negative control not rejected: $mutation"
done
[ -s "$OUT/report.txt" ] || fail "verifier wrote no report"
if grep -Eiq 'https?://|password|passwd|secret|api[_-]?key|bearer|token=|drm|provider\.' "$OUT/report.txt"; then
  log "report first lines:"
  sed -n '1,12p' "$OUT/report.txt" || true
  fail "generated report contains a URL/credential-like token"
fi
log "PASS qa06-report-privacy no URL/credential/secret token in generated report or diagnostics"

log "on-disk negative controls (mutated fixtures must FAIL the standalone verifier)"
MUTATIONS=(ordering-altered:CHANNEL_ORDER clipped-visible-row:ROW_NOT_CONTIGUOUS \
           page-count-overflow:ROW_COUNT_BOUND non-loopback-reference:PRIVACY_REF)
for entry in "${MUTATIONS[@]}"; do
  name="${entry%%:*}"
  code="${entry#*:}"
  mutant="$GEN_NATIVE/mutated-$name.fixture"
  "$JAVA" -cp "$JVM_OUT" Qa06LargeEpgGate --mutate "$name" "$mutant" \
    >"$OUT/mutate-$name.txt" 2>&1 || { cat "$OUT/mutate-$name.txt"; fail "could not write mutation $name"; }
  if "$JAVA" -cp "$JVM_OUT" Qa06LargeEpgGate --verify "$mutant" \
      >"$OUT/verify-$name.txt" 2>&1; then
    cat "$OUT/verify-$name.txt"
    fail "mutated fixture unexpectedly PASSED standalone verification: $name"
  fi
  grep -Fq $'FAIL	qa06-verify' "$OUT/verify-$name.txt" \
    || fail "mutation $name did not produce a FAIL summary"
  grep -Fq "first=$code" "$OUT/verify-$name.txt" \
    || fail "mutation $name was not rejected by $code"
  if grep -Eiq 'https?://|password|passwd|secret|api[_-]?key|bearer|token=|drm|provider\.' \
      "$OUT/verify-$name.txt"; then
    cat "$OUT/verify-$name.txt"
    fail "negative-control diagnostic leaked a URL/credential-like token: $name"
  fi
  log "PASS qa06-mutation-file $name rejected by $code"
done

log "source contract gate"
[ -f "$ASSERTIONS" ] || fail "missing $ASSERTIONS"
[ -f "$INSTRUMENTATION" ] || fail "missing $INSTRUMENTATION"
[ -f "$ANDROID_MANIFEST" ] || fail "missing $ANDROID_MANIFEST"
for needle in qa06-fixture-digest qa06-channel-order qa06-programme-order \
              qa06-page-bounds qa06-row-bounds qa06-no-clipping \
              qa06-report-privacy; do
  grep -Fq "\"$needle\"" "$ASSERTIONS" || fail "Kotlin assertions lack check: $needle"
done
grep -Fq "$RUNNER" "$ANDROID_MANIFEST" || fail "test manifest does not register the QA-06 runner"
grep -Fq "QUA06" "$MANIFEST" || fail "fixture manifest missing expected marker"
for source in "$ASSERTIONS" "$INSTRUMENTATION" "$GATE_SRC"; do
  if grep -Eiq 'org\.junit|androidx\.test|kotlin\.test|junit\.framework' "$source"; then
    fail "test dependency reference in $source"
  fi
done
log "PASS qa06-source-gate 7 named checks, registered runner, zero test dependencies"

log "same-assertions-on-JVM attempt (optional cached Kotlin compiler)"
COMPILER="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin" \
  -type f -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1 || true)"
if [ -z "$COMPILER" ]; then
  log "BLOCKED kotlin-assertion-JVM-run no cached Kotlin compiler; Gradle compile is the fallback"
else
  VERSION="$(basename "$COMPILER" | sed -E 's/kotlin-compiler-embeddable-(.*)\.jar/\1/')"
  CP=""
  for jar in $(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin" \
                    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx" \
                    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps" \
                    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations" \
                    -type f -name '*.jar' 2>/dev/null); do
    native="$(cygpath -m "$jar" 2>/dev/null || printf '%s' "$jar")"
    CP="$CP$native;"
  done
  STDLIB_RAW="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
    -type f -name "kotlin-stdlib-$VERSION.jar" 2>/dev/null | head -1 || true)"
  if [ -z "$STDLIB_RAW" ]; then
    log "BLOCKED kotlin-assertion-JVM-run no kotlin-stdlib-$VERSION.jar"
  else
    STDLIB="$(cygpath -m "$STDLIB_RAW" 2>/dev/null || printf '%s' "$STDLIB_RAW")"
    KOTLIN_OUT="$(cd "$OUT" && pwd -W 2>/dev/null || printf '%s' "$OUT")/kotlin"
    mkdir -p "$KOTLIN_OUT"
    "$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$STDLIB" -d "$KOTLIN_OUT" \
      "$NATIVE_ROOT/$ASSERTIONS" >"$OUT/kotlin-compile.txt" 2>&1 \
      || { cat "$OUT/kotlin-compile.txt"; fail "Kotlin assertions did not compile"; }
    log "PASS qa06-kotlin-assertions-compile Kotlin $VERSION, 0 errors"
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
      *) SEP=':' ;;
    esac
    "$JAVA" -cp "$KOTLIN_OUT$SEP$STDLIB" \
      com.devtunasoft.tuneros.qa06.Qa06LargeEpgAssertions >"$OUT/kotlin-assertions.txt" 2>&1 \
      || { cat "$OUT/kotlin-assertions.txt"; fail "Kotlin assertions failed on JVM"; }
    cat "$OUT/kotlin-assertions.txt"
    grep -Fq $'PASS	qa06-kotlin-assertions' "$OUT/kotlin-assertions.txt" \
      || fail "Kotlin assertion summary missing"
  fi
fi

log "compiled-source check (offline AGP when SDK is configured)"
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  log "BLOCKED gradle-compile ANDROID_HOME / ANDROID_SDK_ROOT is not set"
else
  export JAVA_HOME
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  timeout 900 ./gradlew --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest >"$OUT/compile.log" 2>&1 \
    || { tail -80 "$OUT/compile.log"; fail "instrumented test APK did not compile"; }
  APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [ -s "$APK" ] || fail "test APK missing after assemble"
  log "PASS qa06-instrumented-compile APK bytes=$(wc -c < "$APK")"
fi

log "device probe"
ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
fi
[ -n "$ADB" ] || blocked "adb is not installed; device execution NOT RUN"
DEVICES="$("$ADB" devices | awk 'NR>1 && $2 == "device" {print $1}')"
[ -n "$DEVICES" ] || blocked "no authorized Android device or emulator is attached; device execution NOT RUN"
[ "$(printf '%s\n' "$DEVICES" | wc -l)" -eq 1 ] || blocked "more than one device is attached; set a single target"

log "install and run the compiled gate on one scratch device"
timeout 600 ./gradlew --no-daemon --offline :app:assembleDebug >>"$OUT/compile.log" 2>&1 \
  || { tail -80 "$OUT/compile.log"; fail "debug APK did not compile"; }
"$ADB" install -r -t "$NATIVE_ROOT/app/build/outputs/apk/debug/app-debug.apk" \
  >"$OUT/install-app.log" 2>&1 || { tail -40 "$OUT/install-app.log"; fail "app install failed"; }
"$ADB" install -r -t "$NATIVE_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  >"$OUT/install-test.log" 2>&1 || { tail -40 "$OUT/install-test.log"; fail "test APK install failed"; }
timeout 600 "$ADB" shell am instrument -w -r "com.devtunasoft.tuneros.test/$RUNNER" \
  >"$OUT/instrumentation.txt" 2>&1 || true
cat "$OUT/instrumentation.txt"
"$ADB" shell run-as com.devtunasoft.tuneros cat files/qa06-large-epg-report.txt \
  >"$OUT/device-report.txt" 2>/dev/null || true
[ -s "$OUT/device-report.txt" ] || blocked "the app wrote no QA-06 report; device result NOT RUN"
cat "$OUT/device-report.txt"
for needle in qa06-fixture-digest qa06-channel-order qa06-programme-order \
              qa06-page-bounds qa06-row-bounds qa06-no-clipping qa06-report-privacy; do
  grep -Fq "$(printf 'PASS	%s	' "$needle")" "$OUT/device-report.txt" || fail "device check failed: $needle"
done
if grep -Fq $'FAIL	' "$OUT/device-report.txt"; then fail "device report contains a failing check"; fi
grep -Fq $'7 PASS	0 FAIL' "$OUT/device-report.txt" || fail "device report summary is not 7 PASS / 0 FAIL"
grep -Fq 'INSTRUMENTATION_RESULT: qa06_failures=0' "$OUT/instrumentation.txt" \
  || fail "instrumentation result is not successful"
log "PASS qa06-device-acceptance 7 compiled checks on one scratch device"
log "report: $OUT/device-report.txt"
