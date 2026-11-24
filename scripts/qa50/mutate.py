#!/usr/bin/env python3
"""QA-50: scratch-only fixture mutators for negative RED controls.

Copies the committed fixture into a scratch directory, then changes exactly one
byte-level target. Never writes into the repository fixture directory.
"""
import base64
import hashlib
import os
import shutil
import sys

FILES = (
    "cases.tsv", "guide.xmltv", "corrupt.tsv", "dimensions.tsv",
    "sha256.txt", "README.md",
)


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


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py FIXTURE_DIR OUT_DIR MUTANT")
    source, target, mutant = sys.argv[1:]
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.makedirs(target)
    for name in FILES:
        write(os.path.join(target, name), read(os.path.join(source, name)))

    cases_path = os.path.join(target, "cases.tsv")
    dimensions_path = os.path.join(target, "dimensions.tsv")
    guide_path = os.path.join(target, "guide.xmltv")
    corrupt_path = os.path.join(target, "corrupt.tsv")
    manifest_path = os.path.join(target, "sha256.txt")
    readme_path = os.path.join(target, "README.md")

    if mutant == "sha-drift":
        data = read(manifest_path)
        lines = data.split(b"\n")
        head = lines[0]
        body = lines[1]
        if not body or not all(character in b"0123456789abcdef" for character in body.split(b"\t")[0]):
            raise SystemExit("MUTATOR_TARGET manifest digest")
        old_hash = body.split(b"\t")[0]
        new_hash = (b"0" if old_hash[:1] != b"0" else b"1") + old_hash[1:]
        lines[1] = new_hash + body[len(old_hash):]
        write(manifest_path, b"\n".join([head] + lines[1:]))
    elif mutant == "byte-drift":
        data = read(guide_path)
        write(guide_path, replace_once(data, b"Alpha", b"Alpla", "guide display name"))
    elif mutant == "header-drop":
        data = read(cases_path)
        write(cases_path, replace_once(data, b"id\tchannel", b"ix\tchannel", "case header"))
    elif mutant == "field-extra":
        data = read(cases_path)
        write(cases_path, replace_once(
            data,
            b"c0003\tchA\tNORMAL\tNORMAL\t1767229200\t1767229800\tADMIT\tstarts at window start",
            b"c0003\tchA\tNORMAL\tNORMAL\t1767229200\t1767229800\tADMIT\tstarts at window start\tX",
            "case row",
        ))
    elif mutant == "edge-class":
        data = read(cases_path)
        write(cases_path, replace_once(
            data, b"EXCLUDE_WINDOW_EDGE_END", b"ADMIT", "edge class",
        ))
    elif mutant == "admit-flip":
        data = read(cases_path)
        write(cases_path, replace_once(
            data,
            b"c0013\tchB\tNORMAL\tNORMAL\t1767229500\t1767229560\tADMIT\tinside programme cap",
            b"c0013\tchB\tNORMAL\tNORMAL\t1767229500\t1767229560\tOVERFLOW\tinside programme cap",
            "admission class",
        ))
    elif mutant == "omit-count":
        data = read(dimensions_path)
        write(dimensions_path, replace_once(data, b"omittedExpected\t2", b"omittedExpected\t3", "omit count"))
    elif mutant == "channel-count":
        data = read(dimensions_path)
        write(dimensions_path, replace_once(
            data, b"channelOmittedExpected\t2", b"channelOmittedExpected\t3", "channel count",
        ))
    elif mutant == "title-cap":
        data = read(dimensions_path)
        write(dimensions_path, replace_once(data, b"maxTitleChars\t256", b"maxTitleChars\t255", "title cap"))
    elif mutant == "time-count":
        data = read(dimensions_path)
        write(dimensions_path, replace_once(
            data, b"invalidTimestampExpected\t1", b"invalidTimestampExpected\t2", "time count",
        ))
    elif mutant == "corrupt-relax":
        lines = read(corrupt_path).split(b"\n")
        good = None
        for index, line in enumerate(lines):
            if line.startswith(b"valid-control\t"):
                good = line.split(b"\t", 1)[1]
                break
        if good is None:
            raise SystemExit("MUTATOR_TARGET valid control row")
        changed = 0
        for index, line in enumerate(lines):
            if line.startswith(b"bad-doctype\t"):
                lines[index] = b"bad-doctype\t" + good
                changed += 1
        if changed != 1:
            raise SystemExit("MUTATOR_TARGET bad doctype row")
        write(corrupt_path, b"\n".join(lines))
    elif mutant == "privacy-leak":
        dims = {}
        for line in read(dimensions_path).decode("utf-8").splitlines()[1:]:
            key, value = line.split("\t", 1)
            dims[key] = value
        seed = dims["markerSeed"].encode("ascii")
        token = b"QA50M-" + hashlib.sha256(seed).hexdigest()[:8].encode("ascii")
        data = read(readme_path)
        if data.count(token):
            raise SystemExit("MUTATOR_TARGET token already present")
        write(readme_path, data + b"scratch tag " + token + b"\n")
    else:
        raise SystemExit("MUTATOR_UNKNOWN %s" % mutant)

    print("MUTATED\t%s" % mutant)


if __name__ == "__main__":
    main()
