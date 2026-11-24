#!/usr/bin/env python3
"""QA-56 scratch mutation driver: one named corruption per copy; committed fixtures are never touched."""
import hashlib
import os
import shutil
import sys

DATA_FILES = ["channels.tsv", "corrupt.txt", "dimensions.tsv", "report-copy.txt"]
SEED = 20261124


def token():
    mix = (SEED * 6364136223846793005 + 1442695040888963407) & 0x7FFFFFFFFFFFFFFF
    return "qa56mk" + format(mix, "016x")


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
    path = os.path.join(dst, "channels.tsv")
    data = read(path)
    if name == "byte-drift":
        write(path, data + b"x")
    elif name == "header-drop":
        write(path, one(data, b"id\traw\tlabel\n", b"ix\traw\tlabel\n", name))
    elif name == "field-extra":
        needle = b"qa56c-0001\t38\tlabel-001\n"
        write(path, one(data, needle, b"qa56c-0001\t38\tlabel-001\t7\n", name))
    elif name == "row-overflow":
        write(path, data + b"qa56c-0512\t5\tlabel-005\n")
    elif name == "corrupt-relax":
        path = os.path.join(dst, "corrupt.txt")
        data = read(path)
        lines = data.split(b"\n")
        fields = lines[1].split(b"\t")
        canonical = b"qa56c-0007\t7\tlabel-007"
        fields[2] = canonical.hex().encode("ascii")
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
    path = os.path.join(dst, "Qa56Contract.kt")
    data = read(path)
    if name == "contract-duplicate":
        old = b"if (taken.putIfAbsent(number, row.id) == null) {"
        new = b"taken[number] = row.id; if (true) {"
    elif name == "contract-blank":
        old = b"if (trimmed.isEmpty()) return Decision(Kind.BLANK)"
        new = b"if (trimmed.isEmpty()) return Decision(Kind.VALID, 0)"
    elif name == "contract-range":
        old = b"if (number < 1 || number > MAX_NUMBER) return Decision(Kind.OUT_OF_RANGE)"
        new = b"if (number < 1 || number > MAX_NUMBER + 1000) return Decision(Kind.OUT_OF_RANGE)"
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
    contract_src = os.environ.get("QA56_CONTRACT_SRC")
    if not contract_src:
        raise SystemExit("MUTATOR_NO_CONTRACT_SRC")
    shutil.copyfile(contract_src, os.path.join(dst, "contract", "Qa56Contract.kt"))
    mutate_fixture(name, os.path.join(dst, "fixture"))
    mutate_contract(name, os.path.join(dst, "contract"))


if __name__ == "__main__":
    main()
