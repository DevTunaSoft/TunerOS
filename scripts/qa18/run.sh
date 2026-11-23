#!/usr/bin/env bash
# QA-18 bounded, offline, zero-new-dependency host gate for corrupt evidence envelope ingestion.
#   run.sh generate     regenerate pinned scratch fixtures byte-for-byte
#   run.sh verify       RED mutants, RESTORE proof, GREEN host gate, JVM mirror (default)
#   run.sh red-green    alias for verify
#   run.sh gate-only    canonical GREEN gate without mutation controls
set -uo pipefail

MODE="${1:-verify}"
if [ "$#" -gt 1 ] || { [ "$MODE" != verify ] && [ "$MODE" != generate ] &&
   [ "$MODE" != red-green ] && [ "$MODE" != gate-only ]; }; then
  printf '%b\n' "usage: $0 [generate|verify|red-green|gate-only]"
  exit 2
fi

native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
FIXTURES="$ROOT/qa/fixtures/qa18"
GATE="$HERE/Qa18Gate.kt"
ASSERTIONS="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa18/Qa18Assertions.kt"

JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/javac" ] || [ ! -x "$JH/java" ]; then
  say 'BLOCKED\tqa18-no-jdk\tset JAVA_HOME to JDK 17'
  exit 2
fi
for tool in timeout grep sed awk find sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    say "BLOCKED\tqa18-no-tool\t$tool"
    exit 2
  fi
done

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' \
       -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
COMPILE_CP=''
for jar in $JARS; do COMPILE_CP="${COMPILE_CP}$(native_path "$jar");"; done
if [ -z "$COMPILE_CP" ] || [ -z "$STDLIB_RAW" ]; then
  say 'BLOCKED\tqa18-no-kotlin-compiler\toffline Kotlin 2.0.21 jars missing'
  exit 2
fi
STDLIB="$(native_path "$STDLIB_RAW")"

BUILD="${TMPDIR:-/tmp}/qa18-build-${MODE}"
rm -rf "$BUILD"
mkdir -p "$BUILD/core" "$BUILD/classes" "$BUILD/mutants" "$BUILD/reports" "$BUILD/scratch"
BUILD_N="$(native_dir "$BUILD")"
FIX_N="$(native_dir "$FIXTURES")"
CORE_N="$(native_dir "$BUILD/core")"
CLASSES_N="$(native_dir "$BUILD/classes")"

compile_kotlin() {
  local out="$1"; local extra="$2"; shift 2
  local out_n; out_n="$(native_dir "$out")"
  mkdir -p "$out"
  timeout 300 "$JH/java" -Xmx768m -cp "$COMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$COMPILE_CP$extra$out_n" -d "$out_n" "$@"
}

mapfile -t CORE_SOURCES < <(find "$ROOT/core/src/main/kotlin" -type f -name '*.kt' | sort)
CORE_NATIVE=()
for source in "${CORE_SOURCES[@]}"; do CORE_NATIVE+=("$(native_path "$source")"); done
if [ "${#CORE_NATIVE[@]}" -lt 10 ]; then
  say 'BLOCKED\tqa18-core-sources\tno Kotlin core sources'
  exit 2
fi

if ! compile_kotlin "$BUILD/core" '' "${CORE_NATIVE[@]}" > "$BUILD/compile-core.log" 2>&1; then
  say 'FAIL\tqa18-compile-core\tsee scratch qa18-build/compile-core.log'
  exit 2
fi
if ! compile_kotlin "$BUILD/classes" "$CORE_N;" \
    "$(native_path "$ASSERTIONS")" "$(native_path "$GATE")" > "$BUILD/compile-host.log" 2>&1; then
  say 'FAIL\tqa18-compile-host\tsee scratch qa18-build/compile-host.log'
  exit 2
fi
say "COMPILE\tPASS\tqa18-host-gate\tkotlin2.0.21+jvm17\tcore=${#CORE_NATIVE[@]}"

run_gate() {
  local mode="$1"; local fixture="$2"; local classes="$3"; local report="$4"
  local classes_n; classes_n="$(native_dir "$classes")"
  timeout 180 "$JH/java" -Xmx96m -cp "$classes_n;$CORE_N;$STDLIB" \
    com.devtunasoft.tuneros.qa18.Qa18Gate "$mode" "$fixture" > "$report" 2>&1
}

file_hash() { sha256sum "$1" | awk '{print $1}'; }
tree_hash() {
  (
    sha256sum "$GATE" "$ASSERTIONS"
    for file in "$FIXTURES"/*; do [ -f "$file" ] && sha256sum "$file"; done
  ) | sort | sha256sum | awk '{print $1}'
}
dir_hash() {
  (cd "$1" && for file in *; do [ -f "$file" ] && sha256sum "$file"; done) | sort | sha256sum | awk '{print $1}'
}

if [ "$MODE" = generate ]; then
  timeout 120 "$JH/java" -Xmx96m -cp "$CLASSES_N;$CORE_N;$STDLIB" \
    com.devtunasoft.tuneros.qa18.Qa18Gate generate "$FIX_N"
  exit $?
fi

REPORT="$BUILD/reports/green.txt"
if ! run_gate verify "$FIX_N" "$BUILD/classes" "$REPORT"; then
  say 'FAIL\tqa18-green\tsee scratch qa18-build-*/reports/green.txt'
  tail -n 6 "$REPORT"
  exit 1
fi
SUMMARY=$(awk -F '\t' '$1 == "SUMMARY"{print $2 "\t" $3; exit}' "$REPORT")
COUNTS=$(printf '%s' "$SUMMARY" | awk -F '\t' '{print $1}')
if [ "$(printf '%s' "$SUMMARY" | awk -F '\t' '{print $2}')" != PASS ]; then
  say 'FAIL\tqa18-green-summary\tnot-pass'
  exit 1
fi
if [ "$(awk -F '\t' '$1 == "FIRST" || $1 == "CHECK" && $2 == "FAIL" { n++ } END { print n+0 }' "$REPORT")" != 0 ]; then
  say 'FAIL\tqa18-green-lines\tunexpected-failure-line'
  exit 1
fi
CHECKS=$(awk -F '\t' '$1 == "CHECK" && $2 == "PASS" { n++ } END { print n+0 }' "$REPORT")
say "GREEN\tPASS\tchecks=$CHECKS\tsummary=$COUNTS\tapk=not-applicable"

if [ "$MODE" = gate-only ]; then
  say 'GATE-ONLY\tPASS'
  exit 0
fi

# ---- RED: file mutants and source mutants, each on a scratch copy ----
BEFORE_SOURCES="$(tree_hash)"
say "RED\tBEGIN\tproduction-sha256=$BEFORE_SOURCES"

FILE_MUTANTS='byte-flip bad-header truncated record-count bad-digest'
FILE_CODES='MANIFEST_MATCH HDR_MAGIC TRUNCATED HDR_COUNT REC_DIGEST'
index=0
for kind in $FILE_MUTANTS; do
  index=$((index + 1))
  expected=$(printf '%s\n' $FILE_CODES | sed -n "${index}p")
  work="$BUILD/mutants/file-$kind"
  mkdir -p "$work/fixture"
  java_arg=("$FIX_N" "$(native_dir "$work/fixture")" "$kind")
  if ! timeout 90 "$JH/java" -Xmx96m -cp "$CLASSES_N;$CORE_N;$STDLIB" \
      com.devtunasoft.tuneros.qa18.Qa18Gate mutate "${java_arg[@]}" > "$BUILD/reports/mutate-$kind.txt" 2>&1; then
    say "FAIL\tqa18-mutant-$kind\tmutate-error"
    exit 1
  fi
  mutation_report="$BUILD/reports/red-file-$kind.txt"
  if run_gate verify-mutant "$(native_dir "$work/fixture")" "$BUILD/classes" "$mutation_report"; then
    say "FAIL\tqa18-mutant-$kind\tgate-accepted-mutant"
    exit 1
  fi
  first=$(awk -F '\t' '$1 == "FIRST"{print $2; exit}' "$mutation_report")
  if [ "$first" != "$expected" ]; then
    say "FAIL\tqa18-mutant-$kind\tfirst=$first expected=$expected"
    exit 1
  fi
  # Restore this scratch fixture byte-for-byte from the committed fixture.
  for name in contract.txt dimensions.tsv corrupt.hex envelopes.bin sha256.txt .gitattributes; do
    if [ -f "$FIXTURES/$name" ]; then cp "$FIXTURES/$name" "$work/fixture/$name"; fi
  done
  if [ "$(dir_hash "$work/fixture")" != "$(dir_hash "$FIXTURES")" ]; then
    say "FAIL\tqa18-mutant-$kind\tscratch-restore-mismatch"
    exit 1
  fi
  say "MUTANT\tPASS\tfile-$kind\tfirst=$first\tscratch=restored"
done

SOURCE_MUTANTS='privacy-detector heap-bound thread-bound'
SOURCE_CODES='NEG_LEAK_DETECTOR HEAP_DRIFT THREAD_DRIFT'
index=0
for kind in $SOURCE_MUTANTS; do
  index=$((index + 1))
  expected=$(printf '%s\n' $SOURCE_CODES | sed -n "${index}p")
  work="$BUILD/mutants/src-$kind"
  mkdir -p "$work/src" "$work/classes"
  cp "$ASSERTIONS" "$work/src/Qa18Assertions.kt"
  cp "$GATE" "$work/src/Qa18Gate.kt"
  case "$kind" in
    privacy-detector)
      sed -i 's/private fun leakDetector(text: String): Boolean = Qa18Assertions.clean(text)/private fun leakDetector(text: String): Boolean = true/' "$work/src/Qa18Gate.kt" ;;
    heap-bound)
      sed -i 's/private const val HEAP_DRIFT_LIMIT = 16L \* 1024 \* 1024/private const val HEAP_DRIFT_LIMIT = -1L/' "$work/src/Qa18Gate.kt" ;;
    thread-bound)
      sed -i 's/private const val THREAD_DRIFT_LIMIT = 2/private const val THREAD_DRIFT_LIMIT = -1/' "$work/src/Qa18Gate.kt" ;;
  esac
  if cmp -s "$GATE" "$work/src/Qa18Gate.kt"; then
    say "FAIL\tqa18-mutant-$kind\tno-source-change"
    exit 1
  fi
  if ! compile_kotlin "$work/classes" "$CORE_N;" \
      "$(native_path "$work/src/Qa18Assertions.kt")" "$(native_path "$work/src/Qa18Gate.kt")" \
      > "$BUILD/reports/compile-$kind.log" 2>&1; then
    say "FAIL\tqa18-mutant-$kind\tcompile-error"
    exit 1
  fi
  mutation_report="$BUILD/reports/red-src-$kind.txt"
  if run_gate verify-mutant "$FIX_N" "$work/classes" "$mutation_report"; then
    say "FAIL\tqa18-mutant-$kind\tgate-accepted-mutant"
    exit 1
  fi
  first=$(awk -F '\t' '$1 == "FIRST"{print $2; exit}' "$mutation_report")
  if [ "$first" != "$expected" ]; then
    say "FAIL\tqa18-mutant-$kind\tfirst=$first expected=$expected"
    exit 1
  fi
  say "MUTANT\tPASS\tsrc-$kind\tfirst=$first"
done

AFTER_SOURCES="$(tree_hash)"
if [ "$BEFORE_SOURCES" != "$AFTER_SOURCES" ]; then
  say "FAIL\tqa18-restore\tproduction-sha256-changed"
  exit 1
fi
say "RESTORE\tPASS\tproduction-and-scratch-byte-identical\tsha256=$AFTER_SOURCES"

MIRROR_REPORT="$BUILD/reports/mirror.txt"
if ! timeout 90 "$JH/java" -Xmx96m -cp "$CLASSES_N;$CORE_N;$STDLIB" \
    com.devtunasoft.tuneros.qa18.Qa18Assertions > "$MIRROR_REPORT" 2>&1; then
  say 'FAIL\tqa18-mirror\tsee scratch qa18-build-*/reports/mirror.txt'
  tail -n 5 "$MIRROR_REPORT"
  exit 1
fi
MIRROR_CHECKS=$(awk -F '\t' '$1 == "PASS" { n++ } END { print n+0 }' "$MIRROR_REPORT")
MIRROR_SUMMARY=$(awk -F '\t' '$1 == "SUMMARY"{print $2 "\t" $3; exit}' "$MIRROR_REPORT")
if [ "$(printf '%s' "$MIRROR_SUMMARY" | awk -F '\t' '{print $2}')" != PASS ]; then
  say 'FAIL\tqa18-mirror-summary\tnot-pass'
  exit 1
fi
say "MIRROR\tPASS\tchecks=$MIRROR_CHECKS\tsummary=$MIRROR_SUMMARY"

say "FINAL\tPASS\tgreen=$COUNTS\tfileMutants=5\tsourceMutants=3\trestoreSha256=$AFTER_SOURCES"
exit 0
