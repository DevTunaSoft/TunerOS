#!/usr/bin/env python3
"""QA-52 scratch-only mutators. Never writes into the committed fixture directory."""
import hashlib
import os
import shutil
import sys

FILES = ("cases.tsv", "corrupt.tsv", "dimensions.tsv", "sha256.txt", "README.md")
DATA = ("cases.tsv", "corrupt.tsv", "dimensions.tsv")


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def replace_once(data, old, new, label):
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATOR_TARGET %s count=%d" % (label, count))
    return data.replace(old, new, 1)


def refresh_manifest(target):
    lines = []
    for name in DATA:
        digest = hashlib.sha256(read(os.path.join(target, name))).hexdigest()
        lines.append((digest + "\t" + name).encode("ascii"))
    write(os.path.join(target, "sha256.txt"), b"\n".join(lines) + b"\n")


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: mutate.py FIXTURE_DIR OUT_DIR MUTANT PROD_SOURCE")
    source, target, mutant, prod = sys.argv[1:]
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.makedirs(target)
    for name in FILES:
        write(os.path.join(target, name), read(os.path.join(source, name)))

    cases = os.path.join(target, "cases.tsv")
    corrupt = os.path.join(target, "corrupt.tsv")
    dims = os.path.join(target, "dimensions.tsv")
    readme = os.path.join(target, "README.md")

    if mutant == "sha-drift":
        data = read(os.path.join(target, "sha256.txt"))
        head, *rest = data.split(b"\n")
        first = rest[0].split(b"\t")[0]
        flipped = (b"0" if first[:1] != b"0" else b"1") + first[1:]
        rest[0] = flipped + rest[0][len(first):]
        write(os.path.join(target, "sha256.txt"), b"\n".join([head] + rest))
    elif mutant == "byte-drift":
        write(readme, replace_once(read(readme), b"Matrix:", b"Matrx:", "readme byte"))
    elif mutant == "header-drop":
        data = read(cases)
        write(cases, replace_once(data, b"id\tcurrent\tnext\trecflag\texpected\tnote",
                                  b"ix\tcurrent\tnext\trecflag\texpected\tnote", "case header"))
    elif mutant == "field-extra":
        old = b"c001\tNONE\tEMPTY\t0\tREFRESH\tno-current"
        write(cases, replace_once(read(cases), old, old + b"\tX", "case row"))
    elif mutant == "corrupt-relax":
        data = read(corrupt)
        write(corrupt, replace_once(data, b"c903\tROUTE_A\tROUTE_B\t0\tMAYBE\tinvalid",
                                    b"c903\tROUTE_A\tROUTE_B\t0\tREUSE\tinvalid", "corrupt verdict"))
    elif mutant == "privacy-leak":
        values = {}
        for line in read(dims).decode("utf-8").splitlines()[1:]:
            key, value = line.split("\t", 1)
            values[key] = value
        token = "QA52M-" + hashlib.sha256(values["markerSeed"].encode("ascii")).hexdigest()[:8]
        data = read(readme)
        if token.encode("ascii") in data:
            raise SystemExit("MUTATOR_TARGET token already present")
        write(readme, data + b"scratch tag " + token.encode("ascii") + b"\n")
    elif mutant == "verdict-flip":
        old = b"c001\tNONE\tEMPTY\t0\tREFRESH\tno-current"
        new = b"c001\tNONE\tEMPTY\t0\tREUSE\tno-current"
        write(cases, replace_once(read(cases), old, new, "case verdict"))
    elif mutant == "count-drift":
        write(dims, replace_once(read(dims), b"caseCount\t112", b"caseCount\t111", "case count"))
        refresh_manifest(target)
    elif mutant == "digest-drift":
        data = read(dims)
        old = data.split(b"casesSha256\t")[1].split(b"\n")[0]
        flipped = (b"0" if old[:1] != b"0" else b"1") + old[1:]
        write(dims, replace_once(data, b"casesSha256\t" + old,
                                 b"casesSha256\t" + flipped, "pinned digest"))
        refresh_manifest(target)
    elif mutant == "prod-guard":
        data = read(prod)
        mutated = replace_once(data, b"!playingRecording && ", b"true && ", "production guard")
        write(os.path.join(target, "PlayerReusePolicy.kt"), mutated)
    else:
        raise SystemExit("MUTATOR_UNKNOWN %s" % mutant)

    print("MUTATED\t%s" % mutant)


if __name__ == "__main__":
    main()
