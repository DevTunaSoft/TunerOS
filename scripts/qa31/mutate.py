#!/usr/bin/env python3
"""QA-31 mutation probe: one bounded scratch copy, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
MANIFEST = "app/src/main/AndroidManifest.xml"
RECEIVER = "app/src/main/java/com/devtunasoft/tuneros/RecordingRearmReceiver.kt"
SCHEDULER = "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt"
TARGET = "RecordingRearmReceiver.kt"
OLD = b"RecordingScheduler.rearmPending("
NEW = b"RecordingScheduler.bypassedRearm("


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_show(repo, ref, path):
    data = subprocess.check_output(["git", "-C", repo, "show", "{}:{}".format(ref, path)])
    if len(data) > MAX_BYTES:
        raise SystemExit("MUTATION\tFAIL\tsource-too-large")
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "mutate", "restore"])
    parser.add_argument("--repo", required=True)
    parser.add_argument("--c2", required=True)
    parser.add_argument("--c3", required=True)
    parser.add_argument("--dir", required=True)
    args = parser.parse_args()

    root = pathlib.Path(args.dir)
    overlay = root / "overlay"
    backup = root / "backup"
    sources = {
        "AndroidManifest.xml": (args.c3, MANIFEST),
        TARGET: (args.c3, RECEIVER),
        "RecordingScheduler.kt": (args.c2, SCHEDULER),
    }

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        for name, (ref, path) in sources.items():
            data = git_show(args.repo, ref, path)
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
    for name in sources:
        original = (backup / (name + ".orig")).read_bytes()
        target = overlay / name
        target.write_bytes(original)
        current = target.read_bytes()
        identical = current == original and digest(current) == digest(original)
        ok = ok and identical
        label = "BYTE_IDENTICAL" if identical else "MISMATCH"
        print("RESTORE\t{}\t{}\tsha256={}".format(name, label, digest(current)))
    if not ok:
        raise SystemExit("MUTATION\tFAIL\trestore-mismatch")
    print("RESTORE\tALL\tBYTE_IDENTICAL")


if __name__ == "__main__":
    main()
