#!/usr/bin/env python3
"""QA-35 mutation probe: one bounded scratch overlay, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
MAX_LINES = 2000
ADAPTER = "app/src/main/java/com/devtunasoft/tuneros/CableGuideAdapter.kt"
VIEW = "app/src/main/java/com/devtunasoft/tuneros/ProgramTimelineView.kt"
TEST = "app/src/test/java/com/devtunasoft/tuneros/guide07/GuideRecycledRowFocusTest.kt"
POLICY = "app/src/main/java/com/devtunasoft/tuneros/guide/GuideCellLabelPolicy.kt"
TARGET = "CableGuideAdapter.kt"
OLD = b"windowShiftDirection = shiftDirection,"
NEW = b"windowShiftDirection = 0,"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_show(repo, sha, path):
    data = subprocess.check_output(["git", "-C", repo, "show", "{}:{}".format(sha, path)])
    if not data or len(data) > MAX_BYTES or data.count(b"\n") > MAX_LINES:
        raise SystemExit("MUTATION\tFAIL\tsource-empty-or-unbounded")
    return data


def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["prepare", "mutate", "restore"])
    p.add_argument("--repo", required=True)
    p.add_argument("--fix", required=True)
    p.add_argument("--dir", required=True)
    args = p.parse_args()

    root = pathlib.Path(args.dir)
    overlay = root / "overlay"
    backup = root / "backup"
    sources = {
        "PRE_CableGuideAdapter.kt": ("PRE", ADAPTER),
        TARGET: (args.fix, ADAPTER),
        "ProgramTimelineView.kt": (args.fix, VIEW),
        "GuideRecycledRowFocusTest.kt": (args.fix, TEST),
        "GuideCellLabelPolicy.kt": ("UX01", POLICY),
    }
    refs = {}
    for name, (tag, path) in sources.items():
        sha = tag if tag.startswith(("a523", "0029", "6230", "70cd")) else args.fix
        # Resolve symbolic PRE/UX01 through the pinned refs fixture.
        if tag == "PRE":
            sha = _ref(args.repo, "PRE")
        elif tag == "UX01":
            sha = _ref(args.repo, "UX01")
        refs[name] = sha

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        for name, (_, path) in sources.items():
            data = git_show(args.repo, refs[name], path)
            (backup / (name + ".orig")).write_bytes(data)
            (overlay / name).write_bytes(data)
            print("PREPARE\t{}\tsha256={}\tbytes={}".format(name, digest(data), len(data)))
        return

    if args.mode == "mutate":
        target = overlay / TARGET
        before = target.read_bytes()
        if before.count(OLD) != 1:
            raise SystemExit("MUTATION\tFAIL\tneedle-count-not-one")
        after = before.replace(OLD, NEW)
        target.write_bytes(after)
        print("MUTATE\t{}\tneedle=1\tbefore={}\tafter={}".format(TARGET, digest(before), digest(after)))
        return

    ok = True
    for name, (_, path) in sources.items():
        original = (backup / (name + ".orig")).read_bytes()
        target = overlay / name
        target.write_bytes(original)
        current = target.read_bytes()
        from_git = git_show(args.repo, refs[name], path)
        identical = current == original and digest(current) == digest(original) and current == from_git
        ok = ok and identical
        print("RESTORE\t{}\t{}\tsha256={}".format(
            name, "BYTE_IDENTICAL" if identical else "MISMATCH", digest(current)))
    if not ok:
        raise SystemExit("MUTATION\tFAIL\trestore-mismatch")
    print("RESTORE\tALL\tBYTE_IDENTICAL")


def _ref(repo, key):
    fixture = pathlib.Path(repo, "qa", "fixtures", "qa35", "refs.tsv")
    for line in fixture.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and parts[0] == key:
            return parts[1]
    raise SystemExit("MUTATION\tFAIL\tref-missing\t" + key)


if __name__ == "__main__":
    main()
