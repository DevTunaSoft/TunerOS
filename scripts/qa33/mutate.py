#!/usr/bin/env python3
"""QA-33 mutation probe: one bounded scratch overlay, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
MAIN_MAX_BYTES = 262144
STORE = "app/src/main/java/com/devtunasoft/tuneros/GuideMappingStore.kt"
MAIN = "app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt"
TEST = "app/src/test/java/com/devtunasoft/tuneros/map05/GuideMappingRetainTest.kt"
TARGET = "GuideMappingStore.kt"
TARGET_REF = "C8"
OLD = b"if (validKeys.isEmpty() || mappings.isEmpty()) return mappings"
NEW = b"if (mappings.isEmpty()) return mappings"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_show(repo, ref, path):
    data = subprocess.check_output(["git", "-C", repo, "show", "{}:{}".format(ref, path)])
    cap = MAIN_MAX_BYTES if path == MAIN else MAX_BYTES
    if not data or len(data) > cap:
        raise SystemExit("MUTATION\tFAIL\tsource-empty-or-too-large")
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "mutate", "restore"])
    parser.add_argument("--repo", required=True)
    parser.add_argument("--c5", required=True)
    parser.add_argument("--c8", required=True)
    parser.add_argument("--dir", required=True)
    args = parser.parse_args()

    root = pathlib.Path(args.dir)
    overlay = root / "overlay"
    backup = root / "backup"
    merge_base = subprocess.check_output(
        ["git", "-C", args.repo, "merge-base", args.c5, args.c8], text=True
    ).strip()
    sources = {
        "BASE_GuideMappingStore.kt": ("BASE", STORE, merge_base),
        "C5_GuideMappingStore.kt": (args.c5, STORE, args.c5),
        TARGET: (args.c8, STORE, args.c8),
        "MainActivity.kt": (args.c8, MAIN, args.c8),
        "GuideMappingRetainTest.kt": (args.c8, TEST, args.c8),
    }

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        for name, (_, path, sha) in sources.items():
            data = git_show(args.repo, sha, path)
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
    for name, (_, path, sha) in sources.items():
        original = (backup / (name + ".orig")).read_bytes()
        target = overlay / name
        target.write_bytes(original)
        current = target.read_bytes()
        from_git = git_show(args.repo, sha, path)
        identical = current == original and digest(current) == digest(original) and current == from_git
        ok = ok and identical
        label = "BYTE_IDENTICAL" if identical else "MISMATCH"
        print("RESTORE\t{}\t{}\tsha256={}".format(name, label, digest(current)))
    if not ok:
        raise SystemExit("MUTATION\tFAIL\trestore-mismatch")
    print("RESTORE\tALL\tBYTE_IDENTICAL")


if __name__ == "__main__":
    main()
