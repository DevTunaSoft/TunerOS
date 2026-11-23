#!/usr/bin/env bash
# Compile and run the Android-free QA-25 mirror on the JVM with the cached K2 compiler.
set -euo pipefail

native_pwd() { pwd -W 2>/dev/null || pwd; }
HERE="$(cd "$(dirname "$0")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
ASSERTIONS="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa25/Qa25Assertions.kt"
CATALOG="$ROOT/qa/fixtures/qa25/catalog.tsv"

SCRATCH="${QA25_SCRATCH:-C:/Users/X1/AppData/Local/hermes/cache/scratch/qa25}"
BUILD="$SCRATCH/build-mirror"
rm -rf "$BUILD"; mkdir -p "$BUILD"

GCACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
find_jar() { find "$GCACHE" -path "*/$1/*" -name '*.jar' 2>/dev/null | head -1; }
to_native() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }

K2="$(to_native "$(find_jar kotlin-compiler-embeddable)")"
STDLIB="$(to_native "$(find "$GCACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -name '*.jar' 2>/dev/null | head -1)")"
SCRIPT_RT="$(to_native "$(find_jar kotlin-script-runtime)")"
TROVE="$(to_native "$(find_jar trove4j)")"
ANNOT="$(to_native "$(find "$GCACHE/org.jetbrains/annotations/13.0" -name '*.jar' 2>/dev/null | head -1)")"
COROUTINES="$(to_native "$(find "$GCACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null | head -1)")"

if [ -z "$K2" ] || [ ! -f "$K2" ]; then
  printf 'MIRROR\tBLOCKED\treason=k2-not-cached\n'
  exit 3
fi

CP="$K2;$STDLIB;$SCRIPT_RT;$TROVE;$ANNOT;$COROUTINES"
OUT_NATIVE="$(to_native "$BUILD")"
SRC_NATIVE="$(to_native "$ASSERTIONS")"
timeout 180 java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$STDLIB" -d "$OUT_NATIVE" "$SRC_NATIVE" >/dev/null

OUT="$(timeout 120 java -cp "$OUT_NATIVE;$STDLIB" \
  com.devtunasoft.tuneros.qa25.Qa25Assertions)"
printf '%s\n' "$OUT"

MIRROR_SHA="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="MIRROR_SHA256" {print $2; exit}')"
COMMITTED_SHA="$(sha256sum "$CATALOG" | cut -d' ' -f1)"
PASSES="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="CHECK" && $2=="PASS" {n++} END{print n+0}')"
FAILS="$(printf '%s\n' "$OUT" | awk -F '\t' '$1=="CHECK" && $2=="FAIL" {n++} END{print n+0}')"

if [ "$MIRROR_SHA" = "$COMMITTED_SHA" ] && [ "$PASSES" -eq 6 ] && [ "$FAILS" -eq 0 ]; then
  printf 'MIRROR\tPASS\tchecks=6\tdigest_match=yes\n'
else
  printf 'MIRROR\tFAIL\tchecks=%s\tfails=%s\tdigest_match=%s\n' "$PASSES" "$FAILS" \
    "$([ "$MIRROR_SHA" = "$COMMITTED_SHA" ] && printf yes || printf no)"
  exit 1
fi
