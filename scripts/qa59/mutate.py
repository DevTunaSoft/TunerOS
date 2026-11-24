#!/usr/bin/env python3
"""QA-59 bounded mutation driver. All outputs live in scratch; no owned path is edited."""
import hashlib
import os
import shutil
import sys

FILES = ["rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt"]
DATA = FILES[:-1]
MASK = (1 << 64) - 1

SOURCE_MUTANTS = {
    "cap-drift": ("const val MAX_ROWS = 12", "const val MAX_ROWS = 11"),
    "verdict-drift": ("if (actual != row.expected) {", "if (actual == row.expected) {"),
    "no-gap-golden": ("val complete = byteArrayOf(9, 9, 1, 2, 3)", "val complete = byteArrayOf(9)"),
    "stale-write": (
        "if (stallRun.sleeps >= STALL_LIMIT / 10_000L) {",
        "if (stallRun.sleeps > STALL_LIMIT / 10_000L) {",
    ),
    "corrupt-relax": ('actual.second != "ACCEPTED"', 'actual.second == "ACCEPTED"'),
}


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def rewrite_manifest(directory):
    lines = "".join(digest(os.path.join(directory, name)) + "\t" + name + "\n" for name in DATA)
    with open(os.path.join(directory, "sha256.txt"), "w", newline="\n", encoding="utf-8") as handle:
        handle.write(lines)


def derived_token():
    value = 0x51415935
    for _ in range(16):
        value = (value * 6364136223846793005 + 1442695040888963407) & MASK
        value = (value ^ (value >> 17)) & MASK
    return format(value & 0xFFFFFFFF, "08x")


def mutate_fixture(name, directory):
    rows_path = os.path.join(directory, "rows.tsv")
    if name in ("byte-drift", "byte-flip-remanifested"):
        with open(rows_path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        fields = lines[1].split("\t")
        fields[14] = str(int(fields[14]) + 1)
        lines[1] = "\t".join(fields)
        with open(rows_path, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(lines))
        if name == "byte-flip-remanifested":
            rewrite_manifest(directory)
        return
    if name == "digest-drift":
        dims = os.path.join(directory, "dimensions.tsv")
        with open(dims, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        for index, line in enumerate(lines):
            if line.startswith("MIRROR_DIGEST\t"):
                key, value = line.split("\t")
                lines[index] = key + "\t" + ("0" if value[0] != "0" else "1") + value[1:]
        with open(dims, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(lines))
        rewrite_manifest(directory)
        return
    if name == "privacy-leak":
        report = os.path.join(directory, "report-copy.txt")
        with open(report, "a", newline="\n", encoding="utf-8") as handle:
            handle.write("probe " + derived_token() + "\n")
        rewrite_manifest(directory)
        return
    raise SystemExit("unknown fixture mutant " + name)


def main():
    if len(sys.argv) != 4:
        print("usage: mutate.py <fixture-dir> <mutant-dir> <name>")
        return 2
    source_fixture, target, name = sys.argv[1:]
    if os.path.exists(target):
        shutil.rmtree(target)
    os.makedirs(target)
    fixture = os.path.join(target, "fixture")
    shutil.copytree(source_fixture, fixture)
    if name in SOURCE_MUTANTS:
        contract = os.environ.get("QA59_CONTRACT_SRC")
        if not contract or not os.path.isfile(contract):
            print("MUTATE missing contract source")
            return 2
        with open(contract, encoding="utf-8") as handle:
            text = handle.read()
        old, new = SOURCE_MUTANTS[name]
        count = text.count(old)
        if count != 1:
            print("MUTATE needle count " + str(count))
            return 2
        os.makedirs(os.path.join(target, "contract"))
        with open(os.path.join(target, "contract", "Qa59Contract.kt"), "w", newline="\n", encoding="utf-8") as handle:
            handle.write(text.replace(old, new))
    else:
        mutate_fixture(name, fixture)
    print("MUTATE\tPASS\t" + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
