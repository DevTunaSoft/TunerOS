#!/usr/bin/env python3
"""QA-22 fixture mutators. Copies a fixture tree to scratch and changes exactly one thing."""
import hashlib
import pathlib
import shutil
import sys

DATA_FILES = ["contract.txt", "iv-cases.tsv", "segments.tsv", "large.ct"]


def replace_once(path, old, new):
    data = path.read_bytes()
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATE_TARGET_COUNT %s %d" % (path.name, count))
    path.write_bytes(data.replace(old, new, 1))


def segment_line(path):
    data = path.read_bytes()
    lines = data.split(b"\n")
    hits = [i for i, line in enumerate(lines) if line.startswith(b"VALID_ONE_SHOT\t")]
    if len(hits) != 1:
        raise SystemExit("MUTATE_ROW_COUNT %d" % len(hits))
    return data, lines, hits[0]


def rewrite_manifest(dst):
    lines = []
    for name in DATA_FILES:
        lines.append(hashlib.sha256((dst / name).read_bytes()).hexdigest() + "  " + name)
    (dst / "sha256.txt").write_bytes(("\n".join(lines) + "\n").encode("ascii"))


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
        data, lines, index = segment_line(dst / "segments.tsv")
        cols = lines[index].split(b"\t")
        cipher = cols[2]
        cols[2] = (b"1" if cipher[:1] == b"0" else b"0") + cipher[1:]
        lines[index] = b"\t".join(cols)
        (dst / "segments.tsv").write_bytes(b"\n".join(lines))
        if name == "sha-drift":
            rewrite_manifest(dst)
    elif name == "expect-drift":
        data, lines, index = segment_line(dst / "segments.tsv")
        cols = lines[index].split(b"\t")
        if cols[4] != b"ACCEPT":
            raise SystemExit("MUTATE_EXPECT_BASE")
        cols[4] = b"REJECT"
        lines[index] = b"\t".join(cols)
        (dst / "segments.tsv").write_bytes(b"\n".join(lines))
        rewrite_manifest(dst)
    elif name == "cap-shrink":
        dims = dst / "dimensions.tsv"
        text = dims.read_bytes()
        lines = text.split(b"\n")
        hits = [i for i, line in enumerate(lines) if line.startswith(b"large_cipher_bytes\t")]
        if len(hits) != 1:
            raise SystemExit("MUTATE_CAP_COUNT %d" % len(hits))
        index = hits[0]
        cols = lines[index].split(b"\t")
        value = int(cols[1])
        cols[1] = str(value - 16).encode("ascii")
        lines[index] = b"\t".join(cols)
        dims.write_bytes(b"\n".join(lines))
    else:
        raise SystemExit("MUTATE_UNKNOWN " + name)
    print("MUTATE\tOK\t" + name)


if __name__ == "__main__":
    main()
