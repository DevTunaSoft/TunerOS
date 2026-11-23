#!/usr/bin/env bash
# QA-08 bounded host gate: DVR overlap handling and bounded series-rule expansion.
# Zero dependencies: JDK only for the host gate; optional offline AGP compile when an SDK is
# configured. A missing authorized device is BLOCKED / NOT RUN, never PASS.
set -euo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ]; }; then
  echo "usage: $0 [verify|generate]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/.." && native_pwd)"
ROOT_NATIVE="$ROOT"
SRC="$HERE/qa08/Qa08Gate.java"
FIXTURES="$ROOT/qa/fixtures/qa08"
OUT="$ROOT/build/qa08"
SCRATCH="$OUT/scratch"
CLASSES="$SCRATCH/classes"
ASSERTIONS="app/src/androidTest/java/com/devtunasoft/tuneros/qa08/Qa08Assertions.kt"
INSTRUMENTATION="app/src/androidTest/java/com/devtunasoft/tuneros/qa08/Qa08Instrumentation.kt"
ANDROID_MANIFEST="app/src/androidTest/AndroidManifest.xml"
RUNNER="com.devtunasoft.tuneros.qa08.Qa08Instrumentation"
NAMED_CHECKS=12
POSITIVE_CHECKS=18
NEGATIVE_CHECKS=6

log() { printf '%s QA-08 %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
fail() { log "FAIL $*"; exit 1; }
blocked() { log "BLOCKED $*"; exit 2; }

cd "$ROOT"
[ -f "$SRC" ] || fail "missing gate source $SRC"
command -v timeout >/dev/null 2>&1 || fail "timeout utility unavailable"
command -v cmp >/dev/null 2>&1 || fail "cmp utility unavailable"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}"
JH="${JH%/}"
[ -x "$JH/bin/javac" ] && [ -x "$JH/bin/java" ] || fail "set JAVA_HOME to a JDK 17 installation"
JAVAC="$(cygpath -m "$JH/bin/javac" 2>/dev/null || printf '%s' "$JH/bin/javac")"
JAVA="$(cygpath -m "$JH/bin/java" 2>/dev/null || printf '%s' "$JH/bin/java")"
log "JDK $($JAVA -version 2>&1 | head -1)"

mkdir -p "$CLASSES"
find "$CLASSES" -type f -name '*.class' -delete
timeout 120 "$JAVAC" -Xlint:all -d "$CLASSES" "$SRC" >"$OUT/javac.log" 2>&1 \
  || { cat "$OUT/javac.log"; fail "gate source did not compile"; }
FIX_NATIVE="$(cd "$FIXTURES" 2>/dev/null && native_pwd || printf '%s' "$FIXTURES")"

if [ "$MODE" = generate ]; then
  timeout 120 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate generate "$FIX_NATIVE"
  exit 0
fi

[ -d "$FIXTURES" ] || fail "missing committed fixture directory"
SCRATCH_NATIVE="$(mkdir -p "$SCRATCH" && cd "$SCRATCH" && native_pwd)"

log "byte-identical generator"
mkdir -p "$SCRATCH/gen-a" "$SCRATCH/gen-b"
timeout 120 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate generate "$(cd "$SCRATCH/gen-a" && native_pwd)" \
  >"$OUT/generate-a.txt" || fail "first fixture generation failed"
timeout 120 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate generate "$(cd "$SCRATCH/gen-b" && native_pwd)" \
  >"$OUT/generate-b.txt" || fail "second fixture generation failed"
cmp -s "$SCRATCH/gen-a/manifest.txt" "$SCRATCH/gen-b/manifest.txt" || fail "generator is not byte-identical"
for name in schedules.tsv rules.tsv programs.tsv manifest.txt sha256.txt; do
  cmp -s "$SCRATCH/gen-a/$name" "$SCRATCH/gen-b/$name" || fail "non-byte-identical generation: $name"
  cmp -s "$SCRATCH/gen-a/$name" "$FIXTURES/$name" || fail "committed fixture differs from generator: $name"
done
log "PASS qa08-generator-byte-identical files=5 sha256=$(sha256sum "$FIXTURES/sha256.txt" | cut -d' ' -f1)"

log "negative-mutation RED controls (corrupt fixtures must fail closed)"
MUTATIONS=(
  "row-overflow:ROW_BOUND"
  "interval-corrupt:INTERVAL"
  "privacy-ref:PRIVACY_REF"
  "rule-cap-tamper:RULE_CAP"
  "expansion-cap-tamper:EXPANSION_CAP"
  "terminal-state-tamper:TERMINAL_STATE"
  "padded-conflict-tamper:PAD_CONFLICT"
  "duplicate-rule-tamper:DUPLICATE_RULE"
)
for entry in "${MUTATIONS[@]}"; do
  name="${entry%%:*}"
  code="${entry#*:}"
  mutant="$SCRATCH/mut-$name"
  timeout 120 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate mutate "$name" "$FIX_NATIVE" \
    "$(cd "$SCRATCH" && native_pwd)/mut-$name" >"$OUT/mutate-$name.txt" 2>&1 \
    || { cat "$OUT/mutate-$name.txt"; fail "could not write mutation $name"; }
  if timeout 120 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate verify \
      "$(cd "$mutant" && native_pwd)" >"$OUT/red-$name.txt" 2>&1; then
    cat "$OUT/red-$name.txt"
    fail "corrupted fixture unexpectedly PASSED: $name"
  fi
  grep -Fq "first=$code" "$OUT/red-$name.txt" \
    || { cat "$OUT/red-$name.txt"; fail "mutation $name was not rejected by $code"; }
  log "PASS qa08-red-control $name first=$code"
done

log "GREEN host verification of committed fixtures"
timeout 180 "$JAVA" -Xmx96m -cp "$CLASSES" Qa08Gate verify "$FIX_NATIVE" \
  >"$OUT/host-report.txt" 2>&1 || { cat "$OUT/host-report.txt"; fail "host verifier rejected real fixtures"; }
cat "$OUT/host-report.txt"
grep -Fq $'SUMMARY\tpositive=18/18\tnegative-red=6/6\ttotal=24\tresult=PASS' "$OUT/host-report.txt" \
  || fail "host summary is not $POSITIVE_CHECKS/$POSITIVE_CHECKS positive and $NEGATIVE_CHECKS/$NEGATIVE_CHECKS red controls"
grep -c $'^PASS\tqa08-' "$OUT/host-report.txt" | grep -qx '24' || fail "expected 24 PASS lines in host report"
log "PASS qa08-host-gate positive=$POSITIVE_CHECKS negative-red=$NEGATIVE_CHECKS"

log "privacy scan of reports and diagnostics"
for report in "$OUT/host-report.txt" "$OUT"/red-*.txt "$OUT"/mutate-*.txt; do
  [ -f "$report" ] || continue
  if grep -Eiq 'https?://|password|passwd|secret|api[_-]?key|bearer|token=|drm|provider\.' "$report"; then
    fail "URL/credential-like token in diagnostic: $(basename "$report")"
  fi
done
log "PASS qa08-diagnostic-privacy no URL/credential/secret/PIN token emitted"

log "source contract gate"
[ -f "$ASSERTIONS" ] || fail "missing $ASSERTIONS"
[ -f "$INSTRUMENTATION" ] || fail "missing $INSTRUMENTATION"
[ -f "$ANDROID_MANIFEST" ] || fail "missing $ANDROID_MANIFEST"
for needle in qa08-fixture-digest qa08-row-bounds qa08-conflict-halfopen \
              qa08-padded-conflict qa08-overlap-selection qa08-terminal-not-blocking \
              qa08-duplicate-rules qa08-rule-cap qa08-expansion-cap \
              qa08-expansion-dedupe qa08-expansion-conflict qa08-privacy-diagnostics; do
  grep -Fq "\"$needle\"" "$ASSERTIONS" || fail "Kotlin assertions lack check: $needle"
done
grep -Fq "$RUNNER" "$ANDROID_MANIFEST" || fail "test manifest does not register the QA-08 runner"
for source in "$ASSERTIONS" "$INSTRUMENTATION" "$SRC"; do
  if grep -Eiq 'org\.junit|androidx\.test|kotlin\.test|junit\.framework' "$source"; then
    fail "test dependency reference in $source"
  fi
done
log "PASS qa08-source-gate checks=$NAMED_CHECKS registered-runner zero-test-dependencies"

log "same assertions on the JVM (cached Kotlin compiler when available)"
COMPILER="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin" \
  -type f -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1 || true)"
if [ -z "$COMPILER" ]; then
  log "BLOCKED qa08-kotlin-jvm-run no cached Kotlin compiler; AGP compile is the fallback"
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
    log "BLOCKED qa08-kotlin-jvm-run no kotlin-stdlib-$VERSION.jar"
  else
    STDLIB="$(cygpath -m "$STDLIB_RAW" 2>/dev/null || printf '%s' "$STDLIB_RAW")"
    KOTLIN_OUT="$SCRATCH_NATIVE/kotlin"
    mkdir -p "$KOTLIN_OUT"
    "$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$STDLIB" -d "$KOTLIN_OUT" \
      "$ROOT_NATIVE/$ASSERTIONS" >"$OUT/kotlin-compile.txt" 2>&1 \
      || { cat "$OUT/kotlin-compile.txt"; fail "Kotlin assertions did not compile"; }
    log "PASS qa08-kotlin-assertions-compile Kotlin $VERSION"
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
      *) SEP=':' ;;
    esac
    "$JAVA" -cp "$KOTLIN_OUT$SEP$STDLIB" com.devtunasoft.tuneros.qa08.Qa08Assertions \
      >"$OUT/kotlin-assertions.txt" 2>&1 \
      || { cat "$OUT/kotlin-assertions.txt"; fail "Kotlin assertions failed on JVM"; }
    cat "$OUT/kotlin-assertions.txt"
    grep -Fq $'SUMMARY\tpositive=12/12\tresult=PASS' "$OUT/kotlin-assertions.txt" \
      || fail "Kotlin assertion summary is not 12/12 PASS"
    log "PASS qa08-kotlin-assertions-jvm positive=12/12"
  fi
fi

log "offline AGP compile (test APK only)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -d "C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03" ]; then
  SDK="C:/Users/X1/AppData/Local/hermes/cache/scratch/android-sdk-mv03"
fi
if [ -z "$SDK" ]; then
  log "BLOCKED qa08-instrumented-compile no Android SDK configured"
else
  export JAVA_HOME="$JH"
  export ANDROID_HOME="$SDK"
  export ANDROID_SDK_ROOT="$SDK"
  timeout 900 ./gradlew --no-daemon --offline \
    :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest >"$OUT/compile.log" 2>&1 \
    || { tail -80 "$OUT/compile.log"; fail "instrumented test APK did not compile"; }
  APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [ -s "$APK" ] || fail "test APK missing after assemble"
  log "PASS qa08-instrumented-compile APK-bytes=$(wc -c < "$APK")"
fi

log "device probe"
ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb.exe" ]; then
  ADB="$SDK/platform-tools/adb.exe"
fi
[ -n "$ADB" ] || { log "BLOCKED qa08-device NOT RUN adb unavailable"; exit 0; }
DEVICES="$($ADB devices | awk 'NR>1 && $2 == "device" {print $1}')"
[ -n "$DEVICES" ] || { log "BLOCKED qa08-device NOT RUN no authorized device or emulator attached"; exit 0; }
[ "$(printf '%s\n' "$DEVICES" | wc -l)" -eq 1 ] || blocked "more than one device attached; set a single target"

log "install and run compiled gate on one scratch device"
timeout 600 ./gradlew --no-daemon --offline :app:assembleDebug >>"$OUT/compile.log" 2>&1 \
  || { tail -80 "$OUT/compile.log"; fail "debug APK did not compile"; }
"$ADB" install -r -t "$(cd app/build/outputs/apk/debug && native_pwd)/app-debug.apk" \
  >"$OUT/install-app.log" 2>&1 || { tail -40 "$OUT/install-app.log"; fail "app install failed"; }
"$ADB" install -r -t "$(cd app/build/outputs/apk/androidTest/debug && native_pwd)/app-debug-androidTest.apk" \
  >"$OUT/install-test.log" 2>&1 || { tail -40 "$OUT/install-test.log"; fail "test APK install failed"; }
timeout 600 "$ADB" shell am instrument -w -r "com.devtunasoft.tuneros.test/$RUNNER" \
  >"$OUT/instrumentation.txt" 2>&1 || true
cat "$OUT/instrumentation.txt"
"$ADB" shell run-as com.devtunasoft.tuneros cat files/qa08-large-dvr-report.txt \
  >"$OUT/device-report.txt" 2>/dev/null || true
[ -s "$OUT/device-report.txt" ] || blocked "no QA-08 device report; device result NOT RUN"
cat "$OUT/device-report.txt"
for needle in qa08-fixture-digest qa08-row-bounds qa08-conflict-halfopen \
              qa08-padded-conflict qa08-overlap-selection qa08-terminal-not-blocking \
              qa08-duplicate-rules qa08-rule-cap qa08-expansion-cap \
              qa08-expansion-dedupe qa08-expansion-conflict qa08-privacy-diagnostics; do
  grep -Fq "$(printf 'PASS\t%s\t' "$needle")" "$OUT/device-report.txt" || fail "device check failed: $needle"
done
if grep -Fq $'FAIL\t' "$OUT/device-report.txt"; then fail "device report contains a failing check"; fi
grep -Fq "$NAMED_CHECKS PASS	0 FAIL" "$OUT/device-report.txt" || fail "device summary is not $NAMED_CHECKS PASS / 0 FAIL"
grep -Fq 'INSTRUMENTATION_RESULT: qa08_failures=0' "$OUT/instrumentation.txt" \
  || fail "instrumentation result is not successful"
log "PASS qa08-device-acceptance checks=$NAMED_CHECKS target=$DEVICES"
log "report: $OUT/device-report.txt"
