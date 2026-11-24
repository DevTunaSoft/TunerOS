#!/usr/bin/env python3
"""QA-60 bounded mutation driver. Every output lives in scratch; owned paths are never edited."""
import hashlib
import os
import shutil
import sys

FILES = [
    ".gitattributes", "rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt",
]
DATA = ["corrupt.txt", "dimensions.tsv", "report-copy.txt", "rows.tsv"]
MASK = (1 << 64) - 1

# Production-copy mutations: exact needle, replacement, and the expected FIRST code.
SOURCE_MUTANTS = {
    "late-register-escapes": (
        "abort(value)\n        return false",
        "/* late abort removed */\n        return false",
        "LATE_REGISTRATION",
    ),
    "reset-keeps-abort": (
        "synchronized(lock) {\n            aborted = false\n            current = null\n        }",
        "synchronized(lock) {\n            current = null\n        }",
        "RESET_CLEARS_ABORT",
    ),
    "stale-release-clears": (
        "if (current === value) current = null",
        "if (true) current = null",
        "RELEASE_STALE",
    ),
    "abort-twice": (
        "current.also { current = null }",
        "current",
        "ABORT_IDEMPOTENT",
    ),
    "reset-keeps-current": (
        "synchronized(lock) {\n            aborted = false\n            current = null\n        }",
        "synchronized(lock) {\n            aborted = false\n        }",
        "RESET_FORGETS_CURRENT",
    ),
}


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def rewrite_manifest(directory):
    lines = "".join(digest(os.path.join(directory, name)) + "\t" + name + "\n" for name in DATA)
    with open(os.path.join(directory, "sha256.txt"), "w", newline="\n", encoding="utf-8") as handle:
        handle.write(lines)


def hex_encode(text):
    return text.encode("utf-8").hex()


def derived_token():
    value = 0x51415936
    for _ in range(16):
        value = (value * 6364136223846793005 + 1442695040888963407) & MASK
        value = (value ^ (value >> 17)) & MASK
    return format(value & 0xFFFFFFFF, "08x")


def mutate_fixture(name, directory):
    rows_path = os.path.join(directory, "rows.tsv")
    with open(rows_path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    if name in ("byte-drift", "byte-flip-remanifested"):
        fields = lines[1].split("\t")
        fields[3] += "|0"
        lines[1] = "\t".join(fields)
        with open(rows_path, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(lines))
        if name == "byte-flip-remanifested":
            rewrite_manifest(directory)
        return
    if name == "digest-drift":
        dims = os.path.join(directory, "dimensions.tsv")
        with open(dims, encoding="utf-8") as handle:
            dlines = handle.read().split("\n")
        for index, line in enumerate(dlines):
            if line.startswith("MIRROR_DIGEST\t"):
                key, value = line.split("\t")
                dlines[index] = key + "\t" + ("0" if value[0] != "0" else "1") + value[1:]
        with open(dims, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(dlines))
        rewrite_manifest(directory)
        return
    if name == "cap-drift":
        dims = os.path.join(directory, "dimensions.tsv")
        with open(dims, encoding="utf-8") as handle:
            dlines = handle.read().split("\n")
        for index, line in enumerate(dlines):
            if line.startswith("ROWS\t"):
                dlines[index] = "ROWS\t23"
        with open(dims, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(dlines))
        return
    if name == "verdict-drift":
        fields = lines[2].split("\t")
        fields[3] = fields[3] + "|mutant"
        lines[2] = "\t".join(fields)
        with open(rows_path, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(lines))
        return
    if name == "corrupt-relax":
        corrupt = os.path.join(directory, "corrupt.txt")
        with open(corrupt, encoding="utf-8") as handle:
            blocks = handle.read().split("\n")
        valid = hex_encode(lines[1])
        for index, line in enumerate(blocks):
            if line == "BEGIN family-unknown" and index + 2 < len(blocks):
                blocks[index + 1] = valid
        with open(corrupt, "w", newline="\n", encoding="utf-8") as handle:
            handle.write("\n".join(blocks))
        return
    if name == "privacy-leak":
        report = os.path.join(directory, "report-copy.txt")
        with open(report, "a", newline="\n", encoding="utf-8") as handle:
            handle.write("scratch reference " + derived_token() + " at qa60.invalid\n")
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
    for filename in FILES:
        if not os.path.isfile(os.path.join(fixture, filename)):
            print("MUTATE missing-fixture-file\t" + filename)
            return 2
    if name in SOURCE_MUTANTS:
        source = os.environ.get("QA60_PROD_SRC")
        if not source or not os.path.isfile(source):
            print("MUTATE missing production source")
            return 2
        with open(source, encoding="utf-8", newline="") as handle:
            text = handle.read().replace("\r\n", "\n")
        old, new, _expected = SOURCE_MUTANTS[name]
        count = text.count(old)
        if count != 1:
            print("MUTATE needle-count\t" + str(count))
            return 2
        os.makedirs(os.path.join(target, "prod"))
        with open(os.path.join(target, "prod", "AbortableSlot.kt"), "w", newline="\n", encoding="utf-8") as handle:
            handle.write(text.replace(old, new))
    else:
        mutate_fixture(name, fixture)
    print("MUTATE\tPASS\t" + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
