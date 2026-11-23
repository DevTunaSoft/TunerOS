#!/usr/bin/env python3
"""QA-23 fixture mutators. Copies a fixture tree to scratch and changes exactly one thing."""
import hashlib
import pathlib
import shutil
import sys

DATA_FILES = ["contract.txt", "cases.tsv", "large.idx"]


def replace_once(path, old, new):
    data = path.read_bytes()
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATE_TARGET_COUNT %s %d" % (path.name, count))
    path.write_bytes(data.replace(old, new, 1))


def rewrite_manifest(dst):
    lines = []
    for name in DATA_FILES:
        lines.append(hashlib.sha256((dst / name).read_bytes()).hexdigest() + "  " + name)
    (dst / "sha256.txt").write_bytes(("\n".join(lines) + "\n").encode("ascii"))


def flip_case_hex(dst, case_id):
    path = dst / "cases.tsv"
    lines = path.read_bytes().split(b"\n")
    hits = [i for i, line in enumerate(lines) if line.startswith(case_id + b"\t")]
    if len(hits) != 1:
        raise SystemExit("MUTATE_ROW_COUNT %d" % len(hits))
    cols = lines[hits[0]].split(b"\t")
    value = cols[1]
    if len(value) < 16:
        raise SystemExit("MUTATE_HEX_SHORT")
    pos = len(value) - 6
    old = value[pos:pos + 1]
    new = b"1" if old != b"1" else b"2"
    cols[1] = value[:pos] + new + value[pos + 1:]
    lines[hits[0]] = b"\t".join(cols)
    path.write_bytes(b"\n".join(lines))


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py SRC DST NAME")
    src = pathlib.Path(sys.argv[1])
    dst = pathlib.Path(sys.argv[2])
    name = sys.argv[3]
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    if name in ("byte-drift", "sha-drift"):
        flip_case_hex(dst, b"V0_VALID")
        if name == "sha-drift":
            rewrite_manifest(dst)
    elif name == "expect-drift":
        replace_once(
            dst / "cases.tsv",
            b"\tREJECT\tDASH SegmentBase index is truncated",
            b"\tACCEPT\tDASH SegmentBase index is truncated",
        )
        rewrite_manifest(dst)
    elif name == "cap-shrink":
        replace_once(dst / "dimensions.tsv", b"large_refs\t10000", b"large_refs\t9998")
    else:
        raise SystemExit("MUTATE_UNKNOWN " + name)
    print("MUTATE\tOK\t" + name)


if __name__ == "__main__":
    main()
