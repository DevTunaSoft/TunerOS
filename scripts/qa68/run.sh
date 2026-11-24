#!/usr/bin/env bash
# QA-68 bounded runner: RED mutants -> byte-identical RESTORE -> GREEN -> PRIVACY.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
STDLIB="$(find C:/Users/X1/.gradle/caches/modules-2/files-2.1 -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
FIXTURE="$ROOT/qa/fixtures/qa68"
PROD="$ROOT/app/src/main/java/com/devtunasoft/tuneros/PlaybackEvidence.kt"
STREAMDOCTOR="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/StreamDoctor.kt"
SCRATCH="$(cygpath -m "${TMPDIR:-/tmp}")/qa68_run"
MODE="${1:-full}"

say() { printf '%b\n' "$1"; }

hash_tree() {
  local dirs="$1"
  find $dirs -type f 2>/dev/null | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}'
}

OWNED="$ROOT/scripts/qa68 $ROOT/qa/fixtures/qa68 $ROOT/app/src/androidTest"

if [ "$MODE" = "generate" ]; then
  mkdir -p "$SCRATCH"
  bash "$ROOT/scripts/qa68/compile.sh" "$PROD" "$SCRATCH/build/gen" >/dev/null || exit 1
  timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
    com.devtunasoft.tuneros.qa68.Qa68Gate generate "$FIXTURE"
  exit $?
fi

if [ "$MODE" != "full" ]; then
  say "BAD_MODE\t$MODE"
  exit 2
fi

mkdir -p "$SCRATCH" || exit 1
say "SCRATCH\t$SCRATCH"
BEFORE="$(hash_tree "$OWNED $PROD $STREAMDOCTOR")"
say "TREE_HASH_BEFORE\t$BEFORE"
GOLD="$SCRATCH/golden"
rm -rf "$GOLD"
mkdir -p "$GOLD/prod"
cp -r "$FIXTURE" "$GOLD/fixture"
cp "$PROD" "$GOLD/prod/PlaybackEvidence.kt"
hash_pair() { (cd "$1" && find fixture prod -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}'); }
GOLD_HASH="$(hash_pair "$GOLD")"
say "GOLDEN_HASH\t$GOLD_HASH"
hash_dir() { (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done | sha256sum | awk '{print $1}'); }
rm -rf "$SCRATCH/regen"
bash "$ROOT/scripts/qa68/compile.sh" "$PROD" "$SCRATCH/build/gen" >/dev/null || exit 1
timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
  com.devtunasoft.tuneros.qa68.Qa68Gate generate "$SCRATCH/regen" > "$SCRATCH/regen.txt" 2>&1
regrc=$?
REGEN_SRC="$(hash_dir "$SCRATCH/regen")"
REGEN_COMMITTED="$(hash_dir "$FIXTURE")"
if [ "$regrc" -eq 0 ] && [ "$REGEN_SRC" = "$REGEN_COMMITTED" ]; then
  say "REGEN\tPASS\tdigest=$REGEN_SRC"
else
  say "REGEN\tFAIL\trc=$regrc"
fi

bash "$ROOT/scripts/qa68/compile.sh" "$PROD" "$SCRATCH/build/base" >/dev/null || { say "COMPILE\tFAIL"; exit 1; }
say "COMPILE\tPASS"

RED_LIST="prod-status:EVIDENCE_STATUS prod-type:EVIDENCE_CONTENT_TYPE prod-transport:EVIDENCE_TRANSPORT prod-precedence:EVIDENCE_CONTENT_TYPE prod-depth:EVIDENCE_STATUS byte-drift:BYTE_IDENTITY sha-drift:MANIFEST_CONSISTENT digest-drift:PINNED_DIGEST corrupt-relax:CORRUPT_REJECT privacy-leak:PRIVACY_SCAN"
red_pass=0
red_fail=0
restore_pass=0
restore_fail=0

for item in $RED_LIST; do
  name="${item%%:*}"
  code="${item#*:}"
  target="$SCRATCH/mut/$name"
  case "$name" in
    byte-drift|sha-drift|digest-drift) gmode="verify" ;;
    corrupt-relax|privacy-leak) gmode="verify-semantic" ;;
    *) gmode="verify-semantic" ;;
  esac
  if [ "$name" = "digest-drift" ]; then gmode="verify-pinned"; fi
  if [ "$name" = "sha-drift" ]; then gmode="verify-manifest"; fi

  python3 "$ROOT/scripts/qa68/mutate.py" "$FIXTURE" "$target" "$name" "$PROD" \
    > "$SCRATCH/mutate-$name.txt" 2>&1
  mrc=$?
  if [ "$mrc" -ne 0 ] || ! grep -q "MUTATED" "$SCRATCH/mutate-$name.txt"; then
    say "RED\t$name\tFAIL\tmutator"
    red_fail=$((red_fail+1))
    continue
  fi

  bash "$ROOT/scripts/qa68/compile.sh" "$target/prod/PlaybackEvidence.kt" "$target/classes" > "$SCRATCH/compile-$name.txt" 2>&1
  cmprc=$?
  if [ "$cmprc" -ne 0 ]; then
    say "RED\t$name\tFAIL\tcompile"
    red_fail=$((red_fail+1))
    continue
  fi

  extra=""
  if [ "$name" = "privacy-leak" ]; then extra="$target/fixture/report-copy.txt"; fi
  timeout 180 "$JAVA" -Xmx96m -cp "$target/classes;$STDLIB" \
    com.devtunasoft.tuneros.qa68.Qa68Gate "$gmode" "$target/fixture" $extra \
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

  # Restore the scratch production and fixture copies byte-identically from golden copies.
  rm -rf "$target/fixture" "$target/prod"
  cp -r "$FIXTURE" "$target/fixture"
  mkdir -p "$target/prod"
  cp "$PROD" "$target/prod/PlaybackEvidence.kt"
  A="$(hash_pair "$target")"
  if [ "$A" = "$GOLD_HASH" ]; then
    say "RESTORE\t$name\tPASS"
    restore_pass=$((restore_pass+1))
  else
    say "RESTORE\t$name\tFAIL"
    restore_fail=$((restore_fail+1))
  fi
done

AFTER="$(hash_tree "$OWNED $PROD $STREAMDOCTOR")"
if [ "$BEFORE" = "$AFTER" ]; then
  say "RESTORE\tALL\tPASS"
else
  say "RESTORE\tALL\tFAIL"
fi

timeout 180 "$JAVA" -Xmx96m -cp "$SCRATCH/build/base;$STDLIB" \
  com.devtunasoft.tuneros.qa68.Qa68Gate verify "$FIXTURE" > "$SCRATCH/report-green.txt" 2>&1
grc=$?
summary="$(grep '^SUMMARY' "$SCRATCH/report-green.txt" | tail -1)"
first="$(grep '^FIRST' "$SCRATCH/report-green.txt" | tail -1)"
checks="$(grep -c '^CHECK.*PASS' "$SCRATCH/report-green.txt")"
if [ "$grc" -eq 0 ] && printf '%s' "$first" | grep -F -q "$(printf 'FIRST\tNONE')"; then
  say "GREEN\tPASS\t$summary\tchecks=$checks"
else
  say "GREEN\tFAIL\trc=$grc\t$summary\t$first"
fi

python3 "$ROOT/scripts/qa68/privacy_scan.py" "$ROOT" "$SCRATCH/report-green.txt" \
  "$SCRATCH/report-prod-status.txt" "$SCRATCH/report-privacy-leak.txt" > "$SCRATCH/privacy.txt" 2>&1
prc=$?
if [ "$prc" -eq 0 ] && grep -q '^PRIVACY.PASS' "$SCRATCH/privacy.txt"; then
  say "PRIVACY\tPASS"
else
  say "PRIVACY\tFAIL"
fi

if [ "$red_fail" -eq 0 ] && [ "$restore_fail" -eq 0 ] && [ "$grc" -eq 0 ] && [ "$prc" -eq 0 ] \
   && [ "$BEFORE" = "$AFTER" ] && [ "$regrc" -eq 0 ] && [ "$REGEN_SRC" = "$REGEN_COMMITTED" ]; then
  say "VERDICT\tPASS\tred=$red_pass\tgreen=1\trestore=$restore_pass"
  exit 0
fi
say "VERDICT\tFAIL\tred_fail=$red_fail\trestore_fail=$restore_fail"
exit 1
