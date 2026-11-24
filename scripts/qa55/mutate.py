#!/usr/bin/env python3
"""QA-55 scratch-only mutation driver.

Copies the pinned fixture and the three compiled production sources into a scratch
target, applies exactly one byte-level mutation, and rewrites the hash manifest only
for mutants that need to reach a semantic guard. Nothing in the repository is written.
"""

import hashlib
import os
import shutil
import sys

SEED = 20261124
MIX = (SEED * 6364136223846793005 + 1442695040888963407) & 0x7FFFFFFFFFFFFFFF
TOKEN = ("qa55mk" + format(MIX, "016x")).encode("ascii")
DATA_FILES = ("dimensions.tsv", "cases.tsv", "corrupt.txt", "guide.xmltv", "report-copy.txt")


def replace_once(path, old, new):
    with open(path, "rb") as handle:
        data = handle.read()
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATOR_NEEDLE_COUNT path=%s count=%d" % (os.path.basename(path), count))
    with open(path, "wb") as handle:
        handle.write(data.replace(old, new, 1))


def remanifest(fixture_dir):
    lines = []
    for name in DATA_FILES:
        with open(os.path.join(fixture_dir, name), "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        lines.append(digest.encode("ascii") + b"\t" + name.encode("ascii") + b"\n")
    with open(os.path.join(fixture_dir, "sha256.txt"), "wb") as handle:
        handle.write(b"".join(lines))


def flip_hex(value):
    return ("0" if value[:1] != "0" else "1") + value[1:]


def mutate(fixture_src, target, name, prod_src):
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    fixture_dir = os.path.join(target, "fixture")
    prod_dir = os.path.join(target, "prod")
    shutil.copytree(fixture_src, fixture_dir)
    shutil.copytree(prod_src, prod_dir)
    path = lambda n: os.path.join(fixture_dir, n)
    ppath = lambda n: os.path.join(prod_dir, n)

    if name == "byte-drift":
        replace_once(path("guide.xmltv"), b"Title-00001", b"Title-X0001")
    elif name == "sha-drift":
        with open(path("sha256.txt"), "rb") as handle:
            first = handle.readline().decode("ascii").strip()
        old = first[:64]
        if len(old) != 64:
            raise SystemExit("MUTATOR_MANIFEST_SHAPE")
        replace_once(path("sha256.txt"), old.encode("ascii"), flip_hex(old).encode("ascii"))
    elif name == "digest-drift":
        with open(path("dimensions.tsv"), "rb") as handle:
            lines = handle.read().decode("ascii").splitlines()
        old = next(line.split("\t", 1)[1] for line in lines if line.startswith("mirror_digest\t"))
        replace_once(path("dimensions.tsv"), old.encode("ascii"), flip_hex(old).encode("ascii"))
        remanifest(fixture_dir)
    elif name == "header-drop":
        replace_once(path("cases.tsv"), b"id\traw\tcanonical\tdecision\tcode",
                     b"id\traw\tdecision\tcanonical\tcode")
        remanifest(fixture_dir)
    elif name == "field-extra":
        with open(path("cases.tsv"), "rb") as handle:
            lines = handle.read().split(b"\n")
        hits = [line for line in lines if line.startswith(b"C00\t")]
        if len(hits) != 1:
            raise SystemExit("MUTATOR_CASE_ROW_COUNT=%d" % len(hits))
        replace_once(path("cases.tsv"), hits[0], hits[0] + b"\textra")
        remanifest(fixture_dir)
    elif name == "case-count":
        replace_once(path("dimensions.tsv"), b"case_count\t18", b"case_count\t17")
        remanifest(fixture_dir)
    elif name == "norm-flip":
        replace_once(path("cases.tsv"), b"C01\tsports\tSPORTS\tADMIT\tADMIT",
                     b"C01\tsports\tNEWS\tADMIT\tADMIT")
        remanifest(fixture_dir)
    elif name == "corrupt-relax":
        target_row = None
        with open(path("corrupt.txt"), "rb") as handle:
            lines = handle.read().split(b"\n")
        for index, line in enumerate(lines):
            if line.startswith(b"X04\t"):
                target_row = index
                break
        if target_row is None:
            raise SystemExit("MUTATOR_CORRUPT_ROW_MISSING")
        fields = lines[target_row].split(b"\t", 2)
        if len(fields) != 3:
            raise SystemExit("MUTATOR_CORRUPT_SHAPE")
        repaired = bytes.fromhex(fields[2].decode("ascii")).replace(
            b"<category>NEWS\n", b"<category>NEWS</category>\n")
        if repaired == bytes.fromhex(fields[2].decode("ascii")):
            raise SystemExit("MUTATOR_CORRUPT_NEEDLE")
        lines[target_row] = fields[0] + b"\t" + fields[1] + b"\t" + repaired.hex().encode("ascii")
        with open(path("corrupt.txt"), "wb") as handle:
            handle.write(b"\n".join(lines))
        remanifest(fixture_dir)
    elif name == "privacy-leak":
        with open(path("report-copy.txt"), "ab") as handle:
            handle.write(TOKEN + b"\n")
        remanifest(fixture_dir)
    elif name == "prod-depth-guard":
        replace_once(ppath("GuideParser.kt"),
                     b"if (++depth > MAX_XML_DEPTH) {", b"if (false) {")
    elif name == "prod-blank-title":
        replace_once(ppath("GuideParser.kt"),
                     b"val programmeTitle = title?.takeIf { it.isNotBlank() }",
                     b"val programmeTitle = title")
    else:
        raise SystemExit("MUTATOR_UNKNOWN=" + name)
    print("MUTATED\t" + name)


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: mutate.py <fixture-src> <target> <mutant> <prod-src>")
    mutate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
