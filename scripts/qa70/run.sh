#!/usr/bin/env bash
# QA-70 bounded runner: regeneration -> RED mutants -> byte-identical RESTORE -> GREEN -> privacy -> mirror.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
STDLIB="$(find C:/Users/X1/.gradle/caches/modules-2/files-2.1 -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
FIXTURE="$ROOT/qa/fixtures/qa70"
APP="$ROOT/app/src/main/java/com/devtunasoft/tuneros"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
GATE="$ROOT/scripts/qa70"
SCRATCH="C:/Users/X1/AppData/Local/hermes/cache/scratch/qa70_run"
MODE="${1:-full}"
say() { printf '%b\n' "$1"; }

if [ "$MODE" = "generate" ]; then
  mkdir -p "$SCRATCH" || exit 1
  bash "$GATE/compile.sh" "$APP" "$SCRATCH/build/gen" >/dev/null || exit 1
  timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
    com.devtunasoft.tuneros.qa70.Qa70Gate generate "${2:-$FIXTURE}"
  exit $?
fi
if [ "$MODE" != "full" ]; then say "BAD_MODE\t$MODE"; exit 2; fi

OWNED="$ROOT/scripts/qa70 $ROOT/qa/fixtures/qa70 $ROOT/app/src/androidTest"
hash_tree() {
  find "$@" -type f 2>/dev/null | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}'
}
hash_pair() {
  (cd "$1" && find fixture prod -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}')
}
hash_dir() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}')
}

mkdir -p "$SCRATCH" || exit 1
say "SCRATCH\t$SCRATCH"
BEFORE="$(hash_tree $OWNED "$APP/AndroidDashCaptureSource.kt" "$APP/AndroidHlsCaptureSource.kt" "$APP/HttpStatusException.kt" "$CORE")"
say "TREE_HASH_BEFORE\t$BEFORE"

GOLD="$SCRATCH/golden"
rm -rf "$GOLD" "$SCRATCH/prod"
mkdir -p "$GOLD/prod" "$SCRATCH/prod/base"
cp -r "$FIXTURE" "$GOLD/fixture"
cp "$APP/AndroidDashCaptureSource.kt" "$APP/AndroidHlsCaptureSource.kt" "$APP/HttpStatusException.kt" "$GOLD/prod/"
cp "$APP/AndroidDashCaptureSource.kt" "$APP/AndroidHlsCaptureSource.kt" "$APP/HttpStatusException.kt" "$SCRATCH/prod/base/"
GOLD_HASH="$(hash_pair "$GOLD")"
say "GOLDEN_HASH\t$GOLD_HASH"

rm -rf "$SCRATCH/regen"
bash "$GATE/compile.sh" "$SCRATCH/prod/base" "$SCRATCH/build/gen" > "$SCRATCH/compile-gen.txt" 2>&1 || {
  say "REGEN\tFAIL\tcompile"; exit 1;
}
timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
  com.devtunasoft.tuneros.qa70.Qa70Gate generate "$SCRATCH/regen" > "$SCRATCH/regen.txt" 2>&1
regrc=$?
REGEN_SRC="$(hash_dir "$SCRATCH/regen")"
REGEN_COMMITTED="$(hash_dir "$FIXTURE")"
if [ "$regrc" -eq 0 ] && [ "$REGEN_SRC" = "$REGEN_COMMITTED" ]; then
  say "REGEN\tPASS\tdigest=$REGEN_SRC"
else
  say "REGEN\tFAIL\trc=$regrc"
fi

bash "$GATE/compile.sh" "$SCRATCH/prod/base" "$SCRATCH/build/base" > "$SCRATCH/compile-base.txt" 2>&1 || {
  say "COMPILE\tFAIL"; exit 1;
}
say "COMPILE\tPASS"

RED_LIST="byte-drift:BYTE_IDENTITY:verify sha-drift:MANIFEST_CONSISTENT:verify-manifest digest-drift:PINNED_DIGEST:verify-pinned range-bounds-relax:RANGE_BOUNDS:verify-semantic status-relax:RANGE_STATUS:verify-semantic short-relax:RANGE_SHORT:verify-semantic key-size-relax:KEY_SIZE:verify-semantic privacy-leak:PRIVACY_SCAN:verify-semantic"
red_pass=0
red_fail=0
restore_pass=0
restore_fail=0
report_args=""

for item in $RED_LIST; do
  name="${item%%:*}"
  rest="${item#*:}"
  code="${rest%%:*}"
  gmode="${rest#*:}"
  target="$SCRATCH/mut/$name"
  python3 "$GATE/mutate.py" "$FIXTURE" "$target" "$name" "$SCRATCH/prod/base" \
    > "$SCRATCH/mutate-$name.txt" 2>&1
  mrc=$?
  if [ "$mrc" -ne 0 ] || ! grep -q 'MUTATED' "$SCRATCH/mutate-$name.txt"; then
    say "RED\t$name\tFAIL\tmutator"
    red_fail=$((red_fail+1))
    continue
  fi
  bash "$GATE/compile.sh" "$target/prod" "$target/classes" > "$SCRATCH/compile-$name.txt" 2>&1
  cmprc=$?
  if [ "$cmprc" -ne 0 ]; then
    say "RED\t$name\tFAIL\tcompile"
    red_fail=$((red_fail+1))
    continue
  fi
  extra=""
  if [ "$name" = "privacy-leak" ]; then extra="$target/fixture/report-copy.txt"; fi
  timeout 180 "$JAVA" -Xmx96m -cp "$target/classes;$SCRATCH/build/base;$STDLIB" \
    com.devtunasoft.tuneros.qa70.Qa70Gate "$gmode" "$target/fixture" $extra \
    > "$SCRATCH/report-$name.txt" 2>&1
  grc=$?
  ok=1
  [ "$grc" -eq 1 ] || ok=0
  grep -F -q "$(printf 'FIRST\t%s' "$code")" "$SCRATCH/report-$name.txt" || ok=0
  if [ "$ok" -eq 1 ]; then
    say "RED\t$name\tPASS\tfirst=$code\trc=$grc"
    red_pass=$((red_pass+1))
  else
    say "RED\t$name\tFAIL\twant=$code\trc=$grc"
    red_fail=$((red_fail+1))
  fi
  if [ "$name" != "privacy-leak" ]; then
    report_args="$report_args $SCRATCH/report-$name.txt"
  fi

  rm -rf "$target/fixture" "$target/prod"
  cp -r "$GOLD/fixture" "$target/fixture"
  cp -r "$GOLD/prod" "$target/prod"
  A="$(hash_pair "$target")"
  if [ "$A" = "$GOLD_HASH" ]; then
    say "RESTORE\t$name\tPASS"
    restore_pass=$((restore_pass+1))
  else
    say "RESTORE\t$name\tFAIL"
    restore_fail=$((restore_fail+1))
  fi
done

AFTER="$(hash_tree $OWNED "$APP/AndroidDashCaptureSource.kt" "$APP/AndroidHlsCaptureSource.kt" "$APP/HttpStatusException.kt" "$CORE")"
if [ "$BEFORE" = "$AFTER" ]; then say "RESTORE\tALL\tPASS"; else say "RESTORE\tALL\tFAIL"; fi

timeout 180 "$JAVA" -Xmx96m -cp "$SCRATCH/build/base;$STDLIB" \
  com.devtunasoft.tuneros.qa70.Qa70Gate verify "$FIXTURE" > "$SCRATCH/report-green.txt" 2>&1
grc=$?
summary="$(grep '^SUMMARY' "$SCRATCH/report-green.txt" | tail -1)"
first="$(grep '^FIRST' "$SCRATCH/report-green.txt" | tail -1)"
checks="$(awk -F '\t' '$1=="CHECK" && $3=="PASS" {n++} END{print n+0}' "$SCRATCH/report-green.txt")"
if [ "$grc" -eq 0 ] && printf '%s' "$first" | grep -F -q "$(printf 'FIRST\tNONE')"; then
  say "GREEN\tPASS\t$summary\tchecks=$checks"
else
  say "GREEN\tFAIL\trc=$grc\t$summary\t$first"
fi

timeout 180 "$JAVA" -Xmx96m -cp "$SCRATCH/build/base;$STDLIB" \
  com.devtunasoft.tuneros.qa70.Qa70MirrorMain verify "$FIXTURE" > "$SCRATCH/report-mirror.txt" 2>&1
mrc2=$?
msummary="$(grep '^SUMMARY' "$SCRATCH/report-mirror.txt" | tail -1)"
if [ "$mrc2" -eq 0 ]; then say "MIRROR\tPASS\t$msummary"; else say "MIRROR\tFAIL\trc=$mrc2"; fi

python3 "$GATE/privacy_scan.py" "$ROOT" "$SCRATCH/report-green.txt" $report_args \
  > "$SCRATCH/privacy.txt" 2>&1
prc=$?
if [ "$prc" -eq 0 ] && grep -q '^PRIVACY.PASS' "$SCRATCH/privacy.txt"; then
  say "PRIVACY\tPASS"
else
  say "PRIVACY\tFAIL"
fi

if [ "$red_fail" -eq 0 ] && [ "$restore_fail" -eq 0 ] && [ "$grc" -eq 0 ] && [ "$prc" -eq 0 ] \
   && [ "$mrc2" -eq 0 ] && [ "$BEFORE" = "$AFTER" ] && [ "$regrc" -eq 0 ] \
   && [ "$REGEN_SRC" = "$REGEN_COMMITTED" ]; then
  say "VERDICT\tPASS\tred=$red_pass\tgreen=1\tmirror=1\trestore=$restore_pass"
  exit 0
fi
say "VERDICT\tFAIL\tred_fail=$red_fail\trestore_fail=$restore_fail"
exit 1
