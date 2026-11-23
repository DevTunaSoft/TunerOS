#!/usr/bin/env python3
"""QA-20 scratch-only fixture mutator. Never touches the committed fixture."""

import hashlib
import os
import sys

FILES = ["contract.txt", "dimensions.tsv", "events.tsv", "policy.tsv", "corrupt.hex"]


def fail(message):
    print("MUTATE\tFAIL\t" + message)
    raise SystemExit(2)


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def replace_once(data, old, new, label):
    if data.count(old) != 1:
        fail(label + "-occurrences=" + str(data.count(old)))
    return data.replace(old, new, 1)


def replace_first(data, old, new, label):
    count = data.count(old)
    if count < 1:
        fail(label + "-occurrences=" + str(count))
    return data.replace(old, new, 1)


def first_line(data):
    return data.split(b"\n", 1)[0]


def rewrite_first_line(data, transform, label):
    head, sep, tail = data.partition(b"\n")
    if not sep:
        fail(label + "-no-newline")
    return transform(head) + sep + tail


def recompute_manual(directory):
    lines = []
    for name in FILES:
        digest = hashlib.sha256(read(os.path.join(directory, name))).hexdigest()
        lines.append(digest + "  " + name + "\n")
    write(os.path.join(directory, "sha256.txt"), "".join(lines).encode("ascii"))


def main():
    if len(sys.argv) != 3:
        fail("usage")
    directory, name = sys.argv[1], sys.argv[2]
    paths = {item: os.path.join(directory, item) for item in FILES}
    paths["sha256.txt"] = os.path.join(directory, "sha256.txt")

    if name == "bytes-drift":
        data = read(paths["events.tsv"])
        index = data.find(b"qa20e-00000")
        if index < 0:
            fail("target-row-missing")
        index += 8
        bit = 0x01 if data[index] & 0x01 == 0 else 0x00
        changed = bytearray(data)
        changed[index] ^= bit
        write(paths["events.tsv"], bytes(changed))
    elif name == "sha-drift":
        data = read(paths["sha256.txt"])
        if len(data) < 4 or data[0] not in b"0123456789abcdef":
            fail("manifest-hex-missing")
        replacement = b"0" if data[0:1] != b"0" else b"1"
        write(paths["sha256.txt"], replacement + data[1:])
    elif name == "header-token":
        data = read(paths["events.tsv"])
        write(paths["events.tsv"], replace_first(data, b"qa20v1", b"qa20v0", "header"))
        recompute_manual(directory)
    elif name == "field-count":
        data = read(paths["events.tsv"])

        def drop_last(line):
            fields = line.split(b"\t")
            if len(fields) != 8:
                fail("field-count-shape=" + str(len(fields)))
            return b"\t".join(fields[:-1])

        write(paths["events.tsv"], rewrite_first_line(data, drop_last, "field-count"))
        recompute_manual(directory)
    elif name == "int-bounds":
        data = read(paths["events.tsv"])

        def bad_int(line):
            fields = line.split(b"\t")
            if len(fields) != 8 or fields[3] != b"0":
                fail("int-bounds-target")
            fields[3] = b"9"
            return b"\t".join(fields)

        write(paths["events.tsv"], rewrite_first_line(data, bad_int, "int-bounds"))
        recompute_manual(directory)
    elif name == "family-code":
        data = read(paths["events.tsv"])

        def bad_family(line):
            fields = line.split(b"\t")
            if len(fields) != 8 or fields[2] != b"REJECT_STREAK":
                fail("family-target")
            fields[2] = b"UNKNOWN_FAMILY"
            return b"\t".join(fields)

        write(paths["events.tsv"], rewrite_first_line(data, bad_family, "family-code"))
        recompute_manual(directory)
    elif name == "outcome-mismatch":
        data = read(paths["events.tsv"])

        def bad_outcome(line):
            fields = line.split(b"\t")
            if len(fields) != 8 or fields[7] != b"R4":
                fail("outcome-target=" + repr(fields[7]))
            fields[7] = b"A"
            return b"\t".join(fields)

        write(paths["events.tsv"], rewrite_first_line(data, bad_outcome, "outcome-mismatch"))
        recompute_manual(directory)
    elif name == "corrupt-relaxed":
        data = read(paths["corrupt.hex"])
        write(paths["corrupt.hex"], replace_once(data, b"\tHEADER_STRICT\n", b"\tACCEPT\n", "corrupt-reason"))
        recompute_manual(directory)
    elif name == "leak":
        marker = hashlib.sha256(b"qa20-runtime-marker-20261123").hexdigest()[:12]
        data = read(paths["contract.txt"])
        write(paths["contract.txt"], data + ("\nsynthetic marker " + marker + "\n").encode("ascii"))
        recompute_manual(directory)
    else:
        fail("unknown-mutant=" + name)

    print("MUTATE\tPASS\t" + name)


if __name__ == "__main__":
    main()
