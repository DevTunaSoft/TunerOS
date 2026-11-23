#!/usr/bin/env python3
"""QA-38 mutation probe: bounded scratch overlays only, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
BACKUP = "app/src/main/java/com/devtunasoft/tuneros/BackupRestore.kt"
QA37_REFS = "qa/fixtures/qa37/refs.tsv"
QA37_EXP = "qa/fixtures/qa37/expectations.tsv"

MUTANTS = {
    "c13_refusal_guard": {
        "key": "C13_BackupRestore.kt",
        "old": b"if (refusal != null) throw IllegalStateException(refusal)",
        "new": b"if (1 == 0) throw IllegalStateException(refusal)",
    },
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_show(repo, sha, path):
    data = subprocess.check_output(["git", "-C", repo, "show", "{}:{}".format(sha, path)])
    if not data or len(data) > MAX_BYTES:
        raise SystemExit("MUTATION\tFAIL\tsource-empty-or-too-large")
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "mutate", "restore"])
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--c10", required=True)
    parser.add_argument("--c13", required=True)
    parser.add_argument("--qa37", required=True)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--target", choices=sorted(MUTANTS), default=None)
    args = parser.parse_args()

    sources = {
        "C13_BackupRestore.kt": (args.c13, BACKUP),
        "BASE_BackupRestore.kt": (args.base, BACKUP),
        "QA37_refs.tsv": (args.qa37, QA37_REFS),
        "QA37_expectations.tsv": (args.qa37, QA37_EXP),
    }
    root = pathlib.Path(args.dir)
    overlay = root / "overlay"
    backup = root / "backup"

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        for name, (sha, path) in sources.items():
            data = git_show(args.repo, sha, path)
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
        from_git = git_show(args.repo, sha, path)
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
