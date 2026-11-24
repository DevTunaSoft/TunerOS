"""QA-64 scratch mutant driver. All mutations stay under the scratch root."""
import hashlib
import shutil
import sys
from pathlib import Path

PROD_NAMES = ["TimelineLayout.kt", "Models.kt"]
FIXTURE_NAMES = ["dimensions.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt", ".gitattributes", "README.md"]
SRC_NAMES = ["Qa64HostMain.kt", "Qa64Assertions.kt"]
CASES = {"byte-flip", "manifest-flip", "golden-value", "prod-floor", "prod-cap",
         "prod-require", "gate-privacy"}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def replace_once(path, needle, replacement):
    text = Path(path).read_text(encoding="utf-8")
    count = text.count(needle)
    if count != 1:
        raise SystemExit("MUTATE_NEEDLE %s count=%d" % (path, count))
    Path(path).write_text(text.replace(needle, replacement), encoding="utf-8", newline="\n")


def rewrite_manifest(fixture_dir):
    names = ["dimensions.tsv", "cases.tsv", "corrupt.tsv"]
    lines = ["%s  %s" % (digest(Path(fixture_dir) / name), name) for name in names]
    Path(fixture_dir, "sha256.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def build(worktree, scratch, case):
    if case not in CASES:
        raise SystemExit("UNKNOWN_CASE " + case)
    root = Path(scratch) / case
    if root.exists():
        shutil.rmtree(root)
    for sub in ("backup/prod", "backup/fixture", "backup/src", "prod", "fixture", "src"):
        (root / sub).mkdir(parents=True, exist_ok=True)
    prod_src = Path(worktree) / "core/src/main/kotlin/com/devtunasoft/tuneros/core"
    fixture_src = Path(worktree) / "qa/fixtures/qa64"
    script_src = Path(worktree) / "scripts/qa64"
    test_src = Path(worktree) / "app/src/androidTest/java/com/devtunasoft/tuneros/qa64"

    for name in PROD_NAMES:
        shutil.copy2(prod_src / name, root / "backup/prod" / name)
        shutil.copy2(root / "backup/prod" / name, root / "prod" / name)
    for name in FIXTURE_NAMES:
        shutil.copy2(fixture_src / name, root / "backup/fixture" / name)
        shutil.copy2(root / "backup/fixture" / name, root / "fixture" / name)
    for name in SRC_NAMES:
        target = script_src / name if name == "Qa64HostMain.kt" else test_src / name
        shutil.copy2(target, root / "backup/src" / name)
        shutil.copy2(root / "backup/src" / name, root / "src" / name)

    if case == "byte-flip":
        replace_once(root / "fixture/cases.tsv",
                     "c0001\tfloor\t0\t1\t240\t1\tfloor-scratch-0",
                     "c0001\tfloor\t0\t1\t240\t2\tfloor-scratch-0")
    elif case == "manifest-flip":
        path = root / "fixture/sha256.txt"
        lines = path.read_text(encoding="utf-8").splitlines()
        old = lines[0][0]
        lines[0] = ("0" if old != "0" else "1") + lines[0][1:]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    elif case == "golden-value":
        replace_once(root / "fixture/cases.tsv",
                     "c0002\tfloor\t0\t59\t240\t3\tfloor-scratch-1",
                     "c0002\tfloor\t0\t59\t240\t999\tfloor-scratch-1")
        rewrite_manifest(root / "fixture")
    elif case == "prod-floor":
        replace_once(root / "prod/TimelineLayout.kt",
                     "return maxOf(1, ((entry.durationSeconds * unitsPerHour) / 3600L)"
                     ".coerceAtMost(Int.MAX_VALUE.toLong()).toInt())",
                     "return ((entry.durationSeconds * unitsPerHour) / 3600L)"
                     ".coerceAtMost(Int.MAX_VALUE.toLong()).toInt()")
    elif case == "prod-cap":
        replace_once(root / "prod/TimelineLayout.kt",
                     ".coerceAtMost(Int.MAX_VALUE.toLong())", "")
    elif case == "prod-require":
        replace_once(root / "prod/TimelineLayout.kt",
                     "require(unitsPerHour > 0)", "require(unitsPerHour >= 0)")
    elif case == "gate-privacy":
        replace_once(root / "src/Qa64HostMain.kt",
                     "val markerHit = text.contains(Qa64Assertions.marker())",
                     "val markerHit = false")
    print("MUTATE\t%s\tPASS\tcase=%s" % (case, case))


def restore(worktree, scratch, case):
    root = Path(scratch) / case
    pairs = []
    for name in PROD_NAMES:
        pairs.append((root / "backup/prod" / name, root / "prod" / name,
                      Path(worktree) / "core/src/main/kotlin/com/devtunasoft/tuneros/core" / name))
    for name in FIXTURE_NAMES:
        pairs.append((root / "backup/fixture" / name, root / "fixture" / name,
                      Path(worktree) / "qa/fixtures/qa64" / name))
    for name in SRC_NAMES:
        target = (Path(worktree) / "scripts/qa64" / name if name == "Qa64HostMain.kt"
                  else Path(worktree) / "app/src/androidTest/java/com/devtunasoft/tuneros/qa64" / name)
        pairs.append((root / "backup/src" / name, root / "src" / name, target))
    for backup, live, original in pairs:
        shutil.copy2(backup, live)
        if digest(backup) != digest(live) or digest(original) != digest(live):
            raise SystemExit("RESTORE_MISMATCH %s" % live)
    print("RESTORE\t%s\tPASS\tfiles=%d" % (case, len(pairs)))


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3], sys.argv[4])
    elif len(sys.argv) == 5 and sys.argv[1] == "restore":
        restore(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit("usage: mutate.py build|restore <worktree> <scratch> <case>")
