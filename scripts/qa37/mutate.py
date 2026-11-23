#!/usr/bin/env python3
"""QA-37 mutation probe: one bounded scratch overlay, never a repository write."""
import argparse
import hashlib
import pathlib
import subprocess

MAX_BYTES = 65536
MAX_LINES = 2000
STORE = "app/src/main/java/com/devtunasoft/tuneros/SeriesRuleStore.kt"
TARGET = "SeriesRuleStore.kt"
OLD = b'if (incoming.size > maxRules) return SeriesReplaceOutcome.Rejected("too many series rules")'
NEW = b'if (incoming.size > maxRules + 1000) return SeriesReplaceOutcome.Rejected("too many series rules")'


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

    if args.mode == "prepare":
        overlay.mkdir(parents=True, exist_ok=True)
        backup.mkdir(parents=True, exist_ok=True)
        data = git_show(args.repo, args.fix, STORE)
        (backup / (TARGET + ".orig")).write_bytes(data)
        (overlay / TARGET).write_bytes(data)
        print("PREPARE\t{}\tsha256={}\tbytes={}".format(TARGET, digest(data), len(data)))
        return

    if args.mode == "mutate":
        target = overlay / TARGET
        if not target.exists():
            raise SystemExit("MUTATION\tFAIL\ttarget-missing")
        before = target.read_bytes()
        if before.count(OLD) != 1:
            raise SystemExit("MUTATION\tFAIL\tneedle-count-not-one")
        after = before.replace(OLD, NEW)
        if after == before:
            raise SystemExit("MUTATION\tFAIL\tmutation-noop")
        target.write_bytes(after)
        print("MUTATE\t{}\tneedle=1\tbefore={}\tafter={}".format(
            TARGET, digest(before), digest(after)))
        return

    original = (backup / (TARGET + ".orig")).read_bytes()
    target = overlay / TARGET
    target.write_bytes(original)
    current = target.read_bytes()
    from_git = git_show(args.repo, args.fix, STORE)
    identical = current == original and digest(current) == digest(original) and current == from_git
    print("RESTORE\t{}\t{}\tsha256={}".format(
        TARGET, "BYTE_IDENTICAL" if identical else "MISMATCH", digest(current)))
    if not identical:
        raise SystemExit("MUTATION\tFAIL\trestore-mismatch")
    print("RESTORE\tALL\tBYTE_IDENTICAL")


if __name__ == "__main__":
    main()
