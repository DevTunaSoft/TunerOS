#!/usr/bin/env bash
# QA-69 bounded runner: byte-exact REGEN, RED mutants, RESTORE, GREEN under -Xmx96m, PRIVACY.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
STDLIB="$(find C:/Users/X1/.gradle/caches/modules-2/files-2.1 -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
FIXTURE="$ROOT/qa/fixtures/qa69"
PROD="$ROOT/app/src/main/java/com/devtunasoft/tuneros/RecordingScheduleStore.kt"
SCRATCH="$(cygpath -m "${TMPDIR:-/tmp}")/qa69_run"
OWNED="$ROOT/scripts/qa69 $ROOT/qa/fixtures/qa69 $ROOT/app/src/androidTest"
MODE="${1:-full}"

say() { printf '%b\n' "$1"; }

hash_tree() {
  find $1 -type f 2>/dev/null | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}'
}

hash_dir() {
  (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done \
    | sha256sum | awk '{print $1}')
}

mkdir -p "$SCRATCH" || exit 1
say "SCRATCH\t$SCRATCH"

if [ "$MODE" = "generate" ]; then
  bash "$ROOT/scripts/qa69/compile.sh" "$PROD" "$SCRATCH/build/gen" > "$SCRATCH/compile-gen.txt" 2>&1 || exit 1
  timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
    com.devtunasoft.tuneros.qa69.Qa69Gate generate "$FIXTURE"
  exit $?
fi

if [ "$MODE" = "regen" ] || [ "$MODE" = "full" ]; then
  rm -rf "$SCRATCH/regen"
  bash "$ROOT/scripts/qa69/compile.sh" "$PROD" "$SCRATCH/build/gen" > "$SCRATCH/compile-gen.txt" 2>&1 || exit 1
  timeout 120 "$JAVA" -Xmx96m -cp "$SCRATCH/build/gen;$STDLIB" \
    com.devtunasoft.tuneros.qa69.Qa69Gate generate "$SCRATCH/regen" > "$SCRATCH/regen.txt" 2>&1
  regrc=$?
  SOURCE_DIGEST="$(hash_dir "$SCRATCH/regen")"
  COMMITTED_DIGEST="$(hash_dir "$FIXTURE")"
  if [ "$regrc" -eq 0 ] && [ "$SOURCE_DIGEST" = "$COMMITTED_DIGEST" ]; then
    say "REGEN\tPASS\tdigest=$SOURCE_DIGEST"
  else
    say "REGEN\tFAIL\trc=$regrc\tdigest=$SOURCE_DIGEST"
  fi
  [ "$MODE" = "regen" ] && exit 0
fi

if [ "$MODE" != "full" ] && [ "$MODE" != "green" ]; then
  say "BAD_MODE\t$MODE"
  exit 2
fi

BEFORE="$(hash_tree "$OWNED $PROD")"
say "TREE_HASH_BEFORE\t$BEFORE"

bash "$ROOT/scripts/qa69/compile.sh" "$PROD" "$SCRATCH/build/base" > "$SCRATCH/compile-base.txt" 2>&1 || {
  say "COMPILE\tFAIL"
  exit 1
}
say "COMPILE\tPASS"

if [ "$MODE" = "green" ]; then
  timeout 180 "$JAVA" -Xmx96m -cp "$SCRATCH/build/base;$STDLIB" \
    com.devtunasoft.tuneros.qa69.Qa69Gate verify "$FIXTURE" > "$SCRATCH/report-green.txt" 2>&1
  grc=$?
  summary="$(grep '^SUMMARY' "$SCRATCH/report-green.txt" | tail -1 | tr -d '\r')"
  checks="$(grep -c '^CHECK.*PASS' "$SCRATCH/report-green.txt")"
  say "GREEN\t$summary\tchecks=$checks\trc=$grc"
  exit "$grc"
fi

python3 "$ROOT/scripts/qa69/mutate.py" golden "$FIXTURE" "$PROD" "$SCRATCH/golden" > "$SCRATCH/golden.txt" 2>&1 || exit 1
GOLD_HASH="$(awk '{print $2}' "$SCRATCH/golden.txt" | tr -d '\r')"
say "GOLDEN_HASH\t$GOLD_HASH"

RED_LIST="corrupt-relax:CORRUPT_REJECT prod-limit:LIMIT_BOUND prod-enum:ORPHAN_ENUM prod-after:ORPHAN_AFTER prod-keyfield:ORPHAN_KEY_FIELD prod-claim:CLAIM_KEY_GUARD prod-delete:DELETE_STATE_GUARD byte-drift:BYTE_IDENTITY sha-drift:MANIFEST_CONSISTENT digest-drift:PINNED_DIGEST privacy-leak:PRIVACY_SCAN"
red_pass=0
red_fail=0
restore_pass=0
restore_fail=0

for item in $RED_LIST; do
  name="${item%%:*}"
  code="${item#*:}"
  target="$SCRATCH/mut/$name"
  case "$name" in
    byte-drift) gmode="verify" ;;
    sha-drift) gmode="verify-manifest" ;;
    digest-drift) gmode="verify-pinned" ;;
    privacy-leak) gmode="verify-leak" ;;
    *) gmode="verify-semantic" ;;
  esac

  python3 "$ROOT/scripts/qa69/mutate.py" mutate "$name" "$FIXTURE" "$PROD" "$target" \
    > "$SCRATCH/mutate-$name.txt" 2>&1
  mrc=$?
  if [ "$mrc" -ne 0 ] || ! grep -q '^MUTATED' "$SCRATCH/mutate-$name.txt"; then
    say "RED\t$name\tFAIL\tmutator"
    red_fail=$((red_fail+1))
    continue
  fi

  case "$name" in
    corrupt-relax|prod-limit|prod-enum|prod-after|prod-keyfield|prod-claim|prod-delete)
      compiled="$target/prod/RecordingScheduleStore.kt" ;;
    *) compiled="$PROD" ;;
  esac
  bash "$ROOT/scripts/qa69/compile.sh" "$compiled" "$target/classes" \
    > "$SCRATCH/compile-$name.txt" 2>&1
  cmprc=$?
  if [ "$cmprc" -ne 0 ]; then
    say "RED\t$name\tFAIL\tcompile"
    red_fail=$((red_fail+1))
    continue
  fi

  extra=""
  if [ "$name" = "privacy-leak" ]; then extra="$target/fixture/leak.txt"; fi
  timeout 180 "$JAVA" -Xmx96m -cp "$target/classes;$STDLIB" \
    com.devtunasoft.tuneros.qa69.Qa69Gate "$gmode" "$target/fixture" $extra \
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

  python3 "$ROOT/scripts/qa69/mutate.py" restore "$target" "$SCRATCH/golden" \
    > "$SCRATCH/restore-$name.txt" 2>&1
  rrc=$?
  if [ "$rrc" -eq 0 ] && grep -q '^RESTORE_OK' "$SCRATCH/restore-$name.txt"; then
    say "RESTORE\t$name\tPASS"
    restore_pass=$((restore_pass+1))
  else
    say "RESTORE\t$name\tFAIL"
    restore_fail=$((restore_fail+1))
  fi
done

AFTER="$(hash_tree "$OWNED $PROD")"
if [ "$BEFORE" = "$AFTER" ]; then
  say "RESTORE\tALL\tPASS"
else
  say "RESTORE\tALL\tFAIL"
fi

timeout 180 "$JAVA" -Xmx96m -cp "$SCRATCH/build/base;$STDLIB" \
  com.devtunasoft.tuneros.qa69.Qa69Gate verify "$FIXTURE" > "$SCRATCH/report-green.txt" 2>&1
grc=$?
summary="$(grep '^SUMMARY' "$SCRATCH/report-green.txt" | tail -1 | tr -d '\r')"
checks="$(grep -c '^CHECK.*PASS' "$SCRATCH/report-green.txt")"
thread_line="$(grep '^THREADS' "$SCRATCH/report-green.txt" | tail -1 | tr -d '\r')"
thread_start="$(printf '%s' "$thread_line" | sed -E 's/.*start=([0-9]+).*/\1/')"
thread_end="$(printf '%s' "$thread_line" | sed -E 's/.*end=([0-9]+).*/\1/')"
if [ "$grc" -eq 0 ] && printf '%s' "$summary" | grep -q 'fail=0' && [ "$thread_start" = "$thread_end" ]; then
  say "GREEN\tPASS\t$summary\tchecks=$checks\tthreads=$thread_start"
else
  say "GREEN\tFAIL\trc=$grc\t$summary\tthreads=$thread_start/$thread_end"
fi

REPORTS="$(find "$SCRATCH" -type f -name 'report-*.txt' ! -name 'report-privacy-leak.txt' | LC_ALL=C sort | tr '\n' ' ')"
python3 "$ROOT/scripts/qa69/privacy_scan.py" "$FIXTURE" $REPORTS > "$SCRATCH/privacy.txt" 2>&1
prc=$?
privacy_line="$(cat "$SCRATCH/privacy.txt" | tr -d '\r')"
if [ "$prc" -eq 0 ] && printf '%s' "$privacy_line" | grep -q '^PRIVACY.PASS'; then
  say "PRIVACY\tPASS"
else
  say "PRIVACY\tFAIL"
fi

green_ok=0
if [ "$grc" -eq 0 ] && printf '%s' "$summary" | grep -q 'fail=0' && [ "$thread_start" = "$thread_end" ]; then
  green_ok=1
fi
regen_ok=0
if [ "$regrc" -eq 0 ] && [ "$SOURCE_DIGEST" = "$COMMITTED_DIGEST" ]; then regen_ok=1; fi
if [ "$red_fail" -eq 0 ] && [ "$restore_fail" -eq 0 ] && [ "$green_ok" -eq 1 ] \
   && [ "$prc" -eq 0 ] && [ "$BEFORE" = "$AFTER" ] && [ "$regen_ok" -eq 1 ]; then
  say "VERDICT\tPASS\tred=$red_pass\tgreen=1\trestore=$restore_pass\tchecks=$checks"
  exit 0
fi
say "VERDICT\tFAIL\tred_fail=$red_fail\trestore_fail=$restore_fail\tgreen=$green_ok\tregen=$regen_ok\tprivacy=$prc"
exit 1
