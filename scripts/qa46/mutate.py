#!/usr/bin/env python3
"""QA-46 scratch mutator. Never writes to the committed production or fixture trees."""
import os
import shutil
import sys


def fail(message):
    print("MUTATE\tFAIL\t" + message)
    raise SystemExit(1)


def replace_once(data, needle, replacement, label):
    count = data.count(needle)
    if count != 1:
        fail(label + "-needle-count=" + str(count))
    return data.replace(needle, replacement, 1)


def mutate_production(kind, source, target):
    with open(source, "rb") as handle:
        data = handle.read()
    if kind == "prod-expect-length":
        data = replace_once(data, b'"identity"', b'"identityQA46X"', kind)
    elif kind == "prod-verdict":
        data = replace_once(data, b"expectedLength < 0L", b"expectedLength <= 0L", kind)
    else:
        fail("unknown-production-mutant=" + kind)
    with open(target, "wb") as handle:
        handle.write(data)


def mutate_fixture(kind, source, target):
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(source, target)

    if kind == "corrupt-accepted":
        path = os.path.join(target, "corrupt.tsv")
        with open(path, "rb") as handle:
            lines = handle.read().split(b"\n")
        row = b"C9999\t1\tidentity\t1\t1\tCOMPLETE\n"
        changed = 0
        for index, line in enumerate(lines):
            if line.startswith(b"C0003\t"):
                parts = line.split(b"\t")
                parts[2] = row.hex().upper().encode("ascii")
                lines[index] = b"\t".join(parts)
                changed += 1
        if changed != 1:
            fail(kind + "-row-count=" + str(changed))
        with open(path, "wb") as handle:
            handle.write(b"\n".join(lines))
    elif kind == "byte-drift":
        path = os.path.join(target, "notes.txt")
        with open(path, "rb") as handle:
            data = handle.read()
        data = replace_once(data, b"scratch fixture", b"scratchX fixture", kind)
        with open(path, "wb") as handle:
            handle.write(data)
    elif kind == "privacy-leak":
        # Assembled at run time so no service address literal exists in the source.
        scheme = "".join(chr(code) for code in (104, 116, 116, 112, 58, 47, 47))
        marker = scheme + "qa46" + chr(46) + "invalid" + "/scratch"
        path = os.path.join(target, "notes.txt")
        with open(path, "ab") as handle:
            handle.write(("synthetic diagnostic: " + marker + "\n").encode("ascii"))
    elif kind == "manifest-drift":
        path = os.path.join(target, "sha256.txt")
        with open(path, "rb") as handle:
            lines = handle.read().split(b"\n")
        if not lines[1] or not lines[1][0:1].isdigit():
            fail(kind + "-bad-manifest-row")
        lines[1] = b"4" + lines[1][1:]
        with open(path, "wb") as handle:
            handle.write(b"\n".join(lines))
    else:
        fail("unknown-fixture-mutant=" + kind)


def main():
    if len(sys.argv) != 4:
        fail("usage: mutate.py KIND SOURCE TARGET")
    kind, source, target = sys.argv[1:4]
    if kind.startswith("prod-"):
        mutate_production(kind, source, target)
    else:
        mutate_fixture(kind, source, target)
    print("MUTATE\tPASS\t" + kind)


if __name__ == "__main__":
    main()
