#!/usr/bin/env python3
"""QA-44 zero-dependency negative-control mutator. Copies a fixture and changes exactly one thing."""

import os
import shutil
import sys


def replace_once(data, old, new):
    if old not in data:
        raise SystemExit("MUTATION_TARGET_MISSING")
    return data.replace(old, new, 1)


def mutate(dst, name):
    path = os.path.join(dst, name)

    def read():
        with open(path, "rb") as handle:
            return handle.read()

    def write(data):
        with open(path, "wb") as handle:
            handle.write(data)

    if name == "sha256.txt":
        data = read()
        pos = data.index(b"\t") + 1
        old = data[pos : pos + 1]
        new = b"1" if old != b"1" else b"2"
        write(data[:pos] + new + data[pos + 1 :])
        return

    if name == "cases.tsv":
        data = read()
        if kind == "byte-drift":
            write(replace_once(data, b"\tseg-000.bin\t", b"\tseg-007.bin\t"))
        elif kind == "header-drop":
            write(data.split(b"\n", 1)[1])
        elif kind == "field-extra":
            lines = data.split(b"\n")
            lines[1] += b"\tEXTRA"
            write(b"\n".join(lines))
        elif kind == "family-unknown":
            write(replace_once(data, b"\tSAFE_CREATE\t", b"\tUNKNOWN_FAMILY\t"))
        elif kind == "expect-flip":
            write(replace_once(data, b"\tCORRUPT_NAME\tPARENT\tREJECT\t", b"\tCORRUPT_NAME\tPARENT\tACCEPT\t"))
        else:
            raise SystemExit("MUTATION_UNSUPPORTED")
        return

    if name == "dimensions.tsv":
        data = read()
        if kind == "cap-loosen":
            write(replace_once(data, b"case_count=56\n", b"case_count=999\n"))
        elif kind == "privacy-leak":
            write(replace_once(data, b"leak_probe=0\n", b"leak_probe=1\n"))
        elif kind == "atomicity-marker":
            write(replace_once(data, b"atomic_rename=1\n", b"atomic_rename=0\n"))
        else:
            raise SystemExit("MUTATION_UNSUPPORTED")
        return

    raise SystemExit("MUTATION_UNSUPPORTED")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py SOURCE_DIR DEST_DIR KIND")
    source_dir, dest_dir, kind = sys.argv[1:]
    if os.path.exists(dest_dir):
        shutil.rmtree(dest_dir)
    shutil.copytree(source_dir, dest_dir)
    target = "sha256.txt" if kind == "sha-drift" else "cases.tsv" if kind in (
        "byte-drift", "header-drop", "field-extra", "family-unknown", "expect-flip"
    ) else "dimensions.tsv"
    mutate(dest_dir, target)
    print("MUTANT\t%s\t%s" % (kind, target))
