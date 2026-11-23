#!/usr/bin/env python3
"""QA-34 mutation probe: bounded scratch overlays only, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
MAIN_MAX_BYTES = 262144

MANIFEST = "app/src/main/AndroidManifest.xml"
SCHEDULER = "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt"
RECEIVER = "app/src/main/java/com/devtunasoft/tuneros/RecordingRearmReceiver.kt"
STORE = "app/src/main/java/com/devtunasoft/tuneros/GuideMappingStore.kt"
MAIN = "app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt"
QA31_REFS = "qa/fixtures/qa31/refs.tsv"
QA33_REFS = "qa/fixtures/qa33/refs.tsv"
QA33_MARKER = "qa/fixtures/qa33/marker.txt"

MUTANTS = {
    "dvr": {
        "key": "C3_RecordingRearmReceiver.kt",
        "old": b"RecordingScheduler.rearmPending(",
        "new": b"RecordingScheduler.bypassedRearm(",
    },
    "map": {
        "key": "C8_GuideMappingStore.kt",
        "old": b"if (validKeys.isEmpty() || mappings.isEmpty()) return mappings",
        "new": b"if (mappings.isEmpty()) return mappings",
    },
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_show(repo, sha, path):
    data = subprocess.check_output(["git", "-C", repo, "show", "{}:{}".format(sha, path)])
    cap = MAIN_MAX_BYTES if path == MAIN else MAX_BYTES
    if not data or len(data) > cap:
        raise SystemExit("MUTATION\tFAIL\tsource-empty-or-too-large")
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "mutate", "restore"])
    parser.add_argument("--repo", required=True)
    parser.add_argument("--dbase", required=True)
    parser.add_argument("--c2", required=True)
    parser.add_argument("--c3", required=True)
    parser.add_argument("--gbase", required=True)
    parser.add_argument("--c5", required=True)
    parser.add_argument("--c8", required=True)
    parser.add_argument("--qa31", required=True)
    parser.add_argument("--qa33", required=True)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--target", choices=sorted(MUTANTS), default=None)
    args = parser.parse_args()

    repo = args.repo
    if subprocess.check_output(
        ["git", "-C", repo, "merge-base", args.c2, args.c3], text=True
    ).strip() != args.dbase:
        raise SystemExit("MUTATION\tFAIL\tdvr-base-mismatch")
    if subprocess.check_output(
        ["git", "-C", repo, "merge-base", args.c5, args.c8], text=True
    ).strip() != args.gbase:
        raise SystemExit("MUTATION\tFAIL\tmap-base-mismatch")

    sources = {
        "DBASE_RecordingScheduler.kt": (args.dbase, SCHEDULER),
        "C2_RecordingScheduler.kt": (args.c2, SCHEDULER),
        "C2_AndroidManifest.xml": (args.c2, MANIFEST),
        "C3_RecordingScheduler.kt": (args.c3, SCHEDULER),
        "C3_RecordingRearmReceiver.kt": (args.c3, RECEIVER),
        "C3_AndroidManifest.xml": (args.c3, MANIFEST),
        "C5_GuideMappingStore.kt": (args.c5, STORE),
        "C5_MainActivity.kt": (args.c5, MAIN),
        "C8_GuideMappingStore.kt": (args.c8, STORE),
        "C8_MainActivity.kt": (args.c8, MAIN),
        "QA31_refs.tsv": (args.qa31, QA31_REFS),
        "QA33_refs.tsv": (args.qa33, QA33_REFS),
        "QA33_marker.txt": (args.qa33, QA33_MARKER),
    }

    root = pathlib.Path(args.dir)
    overlay = root / "overlay"
    backup = root / "backup"

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        for name, (sha, path) in sources.items():
            data = git_show(repo, sha, path)
            (backup / (name + ".orig")).write_bytes(data)
            (overlay / name).write_bytes(data)
            print("PREPARE\t{}\tsha256={}\tbytes={}".format(name, digest(data), len(data)))
        return

    if args.mode == "mutate":
        if args.target is None:
            raise SystemExit("MUTATION\tFAIL\ttarget-missing")
        spec = MUTANTS[args.target]
        target = overlay / spec["key"]
        before = target.read_bytes()
        if before.count(spec["old"]) != 1:
            raise SystemExit("MUTATION\tFAIL\tneedle-count-not-one")
        after = before.replace(spec["old"], spec["new"])
        if after == before:
            raise SystemExit("MUTATION\tFAIL\tno-op-mutation")
        target.write_bytes(after)
        print("MUTATE\t{}\t{}\tneedle=1\tbefore={}\tafter={}".format(
            args.target, spec["key"], digest(before), digest(after)))
        return

    ok = True
    for name, (sha, path) in sources.items():
        original = (backup / (name + ".orig")).read_bytes()
        from_git = git_show(repo, sha, path)
        target = overlay / name
        target.write_bytes(original)
        current = target.read_bytes()
        identical = current == original and current == from_git and digest(current) == digest(original)
        ok = ok and identical
        label = "BYTE_IDENTICAL" if identical else "MISMATCH"
        print("RESTORE\t{}\t{}\tsha256={}".format(name, label, digest(current)))
    if not ok:
        raise SystemExit("MUTATION\tFAIL\trestore-mismatch")
    print("RESTORE\tALL\tBYTE_IDENTICAL\tfiles={}".format(len(sources)))


if __name__ == "__main__":
    main()
