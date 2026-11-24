#!/usr/bin/env python3
"""QA-53 scratch-only mutators. Never writes into the committed fixture directory."""
import hashlib
import os
import shutil
import sys

FILES = ("contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.tsv", "headers.tsv", "sha256.txt")
DATA = FILES[:-1]

CASE_HEADER = ("id\tsource\tguide\tname\tdisplay\tlogoMode\tgroup\turlMode\theaderMode\texpect\tnote").encode("ascii")
C009_NEEDLE = b"c009\tqa53-src-a\tqa53-g009\tQA53 Channel 009\tQA53 Channel 009\tOK\t-\tFTP\tNONE\tREFUSE\tfamily=SCHEME_FTP rep=0"
C001_NEEDLE = b"c001\tqa53-src-a\tqa53-g001\tQA53 Channel 001\tQA53 Channel 001\tOK\t-\tOK\tNONE\tADMIT\tfamily=ADMIT rep=0"
PROD_CASE_NEEDLES = {
    "prod-header-cap": (b"private const val MAX_SHARED_HEADERS = 512",
                        b"private const val MAX_SHARED_HEADERS = 2048"),
    "prod-stable-id": (b"digest.update(sourcePrefix)", b"digest.update(ByteArray(0))"),
}


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


def marker_seed(target):
    for line in read(os.path.join(target, "dimensions.tsv")).decode("utf-8").splitlines():
        fields = line.split("\t")
        if len(fields) == 2 and fields[0] == "markerSeed":
            return fields[1]
    raise SystemExit("MUTATOR_TARGET markerSeed missing")


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: mutate.py FIXTURE_DIR OUT_DIR MUTANT PROD_DIR")
    source, target, mutant, prod_dir = sys.argv[1:]
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.makedirs(target)
    for name in FILES:
        write(os.path.join(target, name), read(os.path.join(source, name)))
    for name in ("PlaylistParser.kt", "BoundedLines.kt", "Models.kt"):
        write(os.path.join(target, name), read(os.path.join(prod_dir, name)))

    cases = os.path.join(target, "cases.tsv")
    corrupt = os.path.join(target, "corrupt.tsv")
    dims = os.path.join(target, "dimensions.tsv")
    contract = os.path.join(target, "contract.txt")

    if mutant == "sha-drift":
        data = read(os.path.join(target, "sha256.txt"))
        head, *rest = data.split(b"\n")
        first = rest[0].split(b"\t")[0]
        flipped = (b"0" if first[:1] != b"0" else b"1") + first[1:]
        rest[0] = flipped + rest[0][len(first):]
        write(os.path.join(target, "sha256.txt"), b"\n".join([head] + rest))
    elif mutant == "byte-drift":
        write(contract, replace_once(read(contract), b"synthetic scratch fixture",
                                     b"Synthetic scratch fixture", "contract byte"))
        refresh_manifest(target)
    elif mutant == "header-drop":
        write(cases, replace_once(read(cases), CASE_HEADER,
                                  CASE_HEADER.replace(b"id\t", b"ix\t", 1), "case header"))
    elif mutant == "field-extra":
        write(cases, replace_once(read(cases), C001_NEEDLE, C001_NEEDLE + b"\tX", "case row"))
    elif mutant == "case-count":
        write(dims, replace_once(read(dims), b"caseCount\t36", b"caseCount\t35", "case count"))
    elif mutant == "corrupt-relax":
        write(corrupt, replace_once(read(corrupt), b"c902\tFTP_URL\tunsupported scheme",
                                    b"c902\tADMIT_URL\tunsupported scheme", "corrupt kind"))
    elif mutant == "privacy-leak":
        token = "QA53M-" + hashlib.sha256(marker_seed(target).encode("ascii")).hexdigest()[:16]
        data = read(contract)
        if token.encode("ascii") in data:
            raise SystemExit("MUTATOR_TARGET token already present")
        write(contract, data + b"scratch tag " + token.encode("ascii") + b"\n")
    elif mutant == "verdict-flip":
        write(cases, replace_once(read(cases), C009_NEEDLE,
                                  C009_NEEDLE.replace(b"\tREFUSE\t", b"\tADMIT\t", 1), "case verdict"))
    elif mutant == "digest-drift":
        data = read(dims)
        old = data.split(b"aggregateSha256\t")[1].split(b"\n")[0]
        flipped = (b"0" if old[:1] != b"0" else b"1") + old[1:]
        write(dims, replace_once(data, b"aggregateSha256\t" + old,
                                 b"aggregateSha256\t" + flipped, "pinned digest"))
    elif mutant in PROD_CASE_NEEDLES:
        old, new = PROD_CASE_NEEDLES[mutant]
        prod_source = os.path.join(target, "PlaylistParser.kt")
        write(prod_source, replace_once(read(prod_source), old, new, "production guard"))
    else:
        raise SystemExit("MUTATOR_UNKNOWN %s" % mutant)

    print("MUTATED\t%s" % mutant)


if __name__ == "__main__":
    main()
