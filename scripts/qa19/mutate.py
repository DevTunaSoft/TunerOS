#!/usr/bin/env python3
"""QA-19 on-disk mutant applicator. Python standard library only.

Mutates a *scratch copy* of the fixture. The committed fixture is never touched.
Names map to one pinned first-failure code in scripts/qa19/run.sh.
"""
import hashlib
import os
import sys

DATA_FILES = ["contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.hex"]


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def remanifest(root):
    lines = []
    for name in DATA_FILES:
        digest = hashlib.sha256(read(os.path.join(root, name))).hexdigest()
        lines.append(f"{digest}  {name}\n")
    write(os.path.join(root, "sha256.txt"), "".join(lines).encode("ascii"))


def case_lines(root):
    raw = read(os.path.join(root, "cases.tsv")).decode("utf-8")
    lines = raw.split("\n")
    return lines


def replace_field(line, index, value):
    fields = line.split("\t")
    fields[index] = value
    return "\t".join(fields)


def main():
    if len(sys.argv) != 3:
        print("usage: mutate.py <fixture-copy> <mutant-name>", file=sys.stderr)
        return 2
    root, name = sys.argv[1], sys.argv[2]
    cases_path = os.path.join(root, "cases.tsv")

    if name == "bytes-drift":
        # No re-manifest: byte identity must fire before any semantic check.
        with open(cases_path, "ab") as handle:
            handle.write(b"X")
        return 0

    if name == "sha-drift":
        path = os.path.join(root, "sha256.txt")
        raw = read(path).decode("ascii")
        raw = ("0" if raw[0] != "0" else "1") + raw[1:]
        write(path, raw.encode("ascii"))
        return 0

    lines = case_lines(root)
    manifest = True
    if name == "header-missing":
        lines = lines[1:]
    elif name == "field-count":
        lines[1] = lines[1] + "\tEXTRA"
    elif name == "int-bounds":
        lines[1] = replace_field(lines[1], 2, "9999")
    elif name == "family-unknown":
        lines[1] = replace_field(lines[1], 1, "UNKNOWN_FAMILY")
    elif name == "cap-unbound":
        target = next(i for i, line in enumerate(lines) if "\tREJECT_CAP_READ\t" in line)
        lines[target] = replace_field(lines[target], 10, "4095")
    elif name == "downgrade-drift":
        target = next(i for i, line in enumerate(lines) if "\tREJECT_DOWNGRADE\t" in line)
        lines[target] = replace_field(lines[target], 4, "REL_HTTPS")
    elif name == "corrupt-relaxed":
        valid_row = lines[1]
        path = os.path.join(root, "corrupt.hex")
        raw = read(path).decode("utf-8")
        rows = raw.split("\n")
        fields = rows[1].split("\t")
        fields[2] = valid_row.encode("utf-8").hex()
        rows[1] = "\t".join(fields)
        write(path, "\n".join(rows).encode("utf-8"))
    elif name == "leak":
        # In-memory mutation inside the gate; nothing changes on disk.
        manifest = False
    else:
        print("unknown mutant: " + name, file=sys.stderr)
        return 2

    if lines is not None and name != "corrupt-relaxed" and name != "leak":
        write(cases_path, "\n".join(lines).encode("utf-8"))
    if manifest:
        remanifest(root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
