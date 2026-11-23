"""QA-26 fixture mutants. Each mutation is applied once to a scratch copy."""
import hashlib
import os
import shutil
import sys

SEED = 20261123
MIXED = (SEED * 2654435761) & 0x7FFFFFFFFFFFFFFF
MARKER = "tok" + format(MIXED, "x")[-8:]


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit("MUTATE_NEEDLE %s count=%d" % (label, count))
    return text.replace(old, new, 1)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixture-dir> <scratch-dir> <name>")
    src, dst, name = sys.argv[1:4]
    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    cases = os.path.join(dst, "cases.tsv")
    corrupt = os.path.join(dst, "corrupt.tsv")
    dims = os.path.join(dst, "dimensions.tsv")
    contract = os.path.join(dst, "contract.txt")
    manifest = os.path.join(dst, "sha256.txt")

    if name in ("byte-drift", "sha-drift"):
        with open(cases, "rb") as handle:
            body = handle.read()
        body = replace_once(body, b"c0001\ttp_num\t", b"c0009\ttp_num\t", "case-id")
        with open(cases, "wb") as handle:
            handle.write(body)
    elif name == "header-drift":
        with open(cases, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        if not lines[0].startswith("id\tfamily\t"):
            raise SystemExit("MUTATE_HEADER")
        lines[0] = "id\twrong\theader"
        with open(cases, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif name == "field-count":
        with open(cases, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        fields = lines[1].split("\t")
        if len(fields) != 13:
            raise SystemExit("MUTATE_FIELDS")
        lines[1] = "\t".join(fields[:-1])
        with open(cases, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif name == "fingerprint-drift":
        with open(cases, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        fields = lines[1].split("\t")
        if len(fields) != 13 or fields[11] != "P":
            raise SystemExit("MUTATE_EXPECT")
        fields[12] += "|X"
        lines[1] = "\t".join(fields)
        with open(cases, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif name == "corrupt-expect":
        with open(corrupt, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        fields = lines[1].split("\t")
        if len(fields) != 3:
            raise SystemExit("MUTATE_CORRUPT")
        fields[2] = "WRONG_REASON"
        lines[1] = "\t".join(fields)
        with open(corrupt, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif name == "cap-shrink":
        with open(dims, encoding="utf-8") as handle:
            body = handle.read()
        body = replace_once(body, "list_cap\t64", "list_cap\t63", "list-cap")
        with open(dims, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)
    elif name == "dimension-drift":
        with open(dims, encoding="utf-8") as handle:
            body = handle.read()
        body = replace_once(body, "seed\t20261123", "seed\t20261124", "seed")
        with open(dims, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)
        with open(dims, "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        with open(manifest, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        for index, line in enumerate(lines):
            if line.endswith("  dimensions.tsv"):
                lines[index] = digest + "  dimensions.tsv"
                break
        else:
            raise SystemExit("MUTATE_MANIFEST_LINE")
        with open(manifest, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif name == "leak-inject":
        if MARKER in open(contract, encoding="utf-8").read():
            raise SystemExit("MUTATE_MARKER_PRESENT")
        with open(contract, "a", encoding="utf-8", newline="\n") as handle:
            handle.write(MARKER + "\n")
    else:
        raise SystemExit("MUTATE_UNKNOWN " + name)

    print("MUTATE\tPASS\t%s" % name)


if __name__ == "__main__":
    main()
