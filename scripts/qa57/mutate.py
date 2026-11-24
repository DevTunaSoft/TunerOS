#!/usr/bin/env python3
"""QA-57 scratch mutation driver: one named corruption per copy; committed fixtures are never touched."""
import hashlib
import os
import shutil
import sys

DATA_FILES = ["rows.tsv", "corrupt.txt", "cases.tsv", "dimensions.tsv", "report-copy.txt"]
SEED = 20261125
CANONICAL = "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\t-\t0"


def token():
    mix = (SEED * 6364136223846793005 + 1442695040888963407) & 0x7FFFFFFFFFFFFFFF
    return "qa57mk" + format(mix, "016x")


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def one(data, old, new, label):
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATOR_NEEDLE label=%s count=%d" % (label, count))
    return data.replace(old, new, 1)


def rewrite_manifest(dst):
    manifest = b""
    for name in DATA_FILES:
        digest = hashlib.sha256(read(os.path.join(dst, name))).hexdigest()
        manifest += digest.encode("ascii") + b"\t" + name.encode("ascii") + b"\n"
    write(os.path.join(dst, "sha256.txt"), manifest)


def mutate_fixture(name, dst):
    path = os.path.join(dst, "rows.tsv")
    data = read(path)
    if name == "byte-drift":
        write(path, data + b"x")
    elif name == "header-drop":
        write(path, one(data, b"id\ttitle\tstate\tstarted_epoch", b"ix\ttitle\tstate\tstarted_epoch", name))
    elif name == "field-extra":
        needle = b"qa57r-001\ttitle-007\tCOMPLETED\t1700000037\t524288\tloc-00017\t-\t0\n"
        write(path, one(data, needle, needle[:-1] + b"\t0\n", name))
    elif name == "row-overflow":
        write(path, data + b"qa57r-256\ttitle-000\tCOMPLETED\t1700009452\t2048\tloc-00042\tfail-\t0\n")
    elif name == "corrupt-relax":
        path = os.path.join(dst, "corrupt.txt")
        data = read(path)
        lines = data.split(b"\n")
        fields = lines[1].split(b"\t")
        fields[2] = CANONICAL.encode("ascii").hex().encode("ascii")
        lines[1] = b"\t".join(fields)
        write(path, b"\n".join(lines))
    elif name == "privacy-leak":
        path = os.path.join(dst, "report-copy.txt")
        write(path, read(path) + (token() + "\n").encode("ascii"))
    elif name == "digest-drift":
        path = os.path.join(dst, "dimensions.tsv")
        data = read(path)
        marker = b"digest\t"
        start = data.index(marker) + len(marker)
        first = data[start:start + 1]
        replacement = b"0" if first != b"0" else b"1"
        write(path, data[:start] + replacement + data[start + 1:])
    elif name == "sha-drift":
        path = os.path.join(dst, "sha256.txt")
        data = read(path)
        first = data[:1]
        replacement = b"0" if first != b"0" else b"1"
        write(path, replacement + data[1:])
    elif name.startswith("contract-"):
        return
    else:
        raise SystemExit("MUTATOR_UNKNOWN_FIXTURE name=%s" % name)
    if name != "sha-drift":
        rewrite_manifest(dst)


def mutate_contract(name, dst):
    path = os.path.join(dst, "Qa57Contract.kt")
    data = read(path)
    if name == "contract-playable":
        old = b'fun isPlayable(row: Row): Boolean = row.state == "COMPLETED" && row.location != "-"'
        new = b'fun isPlayable(row: Row): Boolean = row.state == "COMPLETED"'
    elif name == "contract-protected":
        old = b'if (row.protected == 1) append(MID).append("PROTECTED")'
        new = b'if (row.protected != 1) append(MID).append("PROTECTED")'
    elif name == "contract-unknown":
        old = b'if (started == "-") "Unknown time" else'
        new = b'if (started == "--") "Unknown time" else'
    elif name == "contract-size":
        old = b'val tenths = (bytes * 10L + 524288L) / 1048576L'
        new = b'val tenths = (bytes * 10L) / 1048576L'
    else:
        return
    write(path, one(data, old, new, name))


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixtures> <dst> <name>")
    src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
    if os.path.exists(dst):
        shutil.rmtree(dst)
    os.makedirs(os.path.join(dst, "fixture"))
    os.makedirs(os.path.join(dst, "contract"))
    for filename in DATA_FILES + ["sha256.txt"]:
        shutil.copyfile(os.path.join(src, filename), os.path.join(dst, "fixture", filename))
    contract_src = os.environ.get("QA57_CONTRACT_SRC")
    if not contract_src:
        raise SystemExit("MUTATOR_NO_CONTRACT_SRC")
    shutil.copyfile(contract_src, os.path.join(dst, "contract", "Qa57Contract.kt"))
    mutate_fixture(name, os.path.join(dst, "fixture"))
    mutate_contract(name, os.path.join(dst, "contract"))


if __name__ == "__main__":
    main()
