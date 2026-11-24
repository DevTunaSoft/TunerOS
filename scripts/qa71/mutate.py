"""QA-71 bounded negative-control mutator. Zero dependencies; scratch copies only."""
import hashlib
import os
import shutil
import sys

FIXTURE_FILES = ("cases.tsv", "corrupt.tsv", "dimensions.txt", "expected.txt",
                 "sha256.txt", ".gitattributes")

PROD_MUTATIONS = {
    "guard-limit": ("require(limit in 1..500)", "require(limit in 0..501)"),
    "conflict-replace": ("SQLiteDatabase.CONFLICT_IGNORE", "SQLiteDatabase.CONFLICT_REPLACE"),
    "order-asc": ('"COALESCE(started_epoch,0) DESC"', '"COALESCE(started_epoch,0) ASC"'),
    "interrupt-scope": ('arrayOf("RECORDING")', 'arrayOf("PARTIAL")'),
}


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def remanifest(directory):
    lines = []
    for name in sorted(FIXTURE_FILES):
        if name == "sha256.txt":
            continue
        lines.append(digest(os.path.join(directory, name)) + "\t" + name + "\n")
    with open(os.path.join(directory, "sha256.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("".join(lines))


def mutate_prod(source, destination, kind):
    needle, replacement = PROD_MUTATIONS[kind]
    with open(source, "rb") as handle:
        raw = handle.read().decode("utf-8").replace("\r\n", "\n")
    count = raw.count(needle)
    if count != 1:
        raise SystemExit("MUTATOR\tFAIL\tneedle count %d for %s" % (count, kind))
    with open(destination, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(raw.replace(needle, replacement))
    print("MUTATOR\tPROD\t%s\tOK" % kind)


def mutate_fixture(source, destination, kind):
    if os.path.isdir(destination):
        shutil.rmtree(destination)
    os.makedirs(destination)
    for name in FIXTURE_FILES:
        shutil.copyfile(os.path.join(source, name), os.path.join(destination, name))
    if kind == "manifest-byte":
        path = os.path.join(destination, "sha256.txt")
        with open(path, "r", encoding="utf-8", newline="") as handle:
            lines = handle.read().split("\n")
        first = lines[0]
        changed = ("1" + first[1:]) if first[0] == "0" else ("0" + first[1:])
        lines[0] = changed
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))
    elif kind == "pinned-digest":
        path = os.path.join(destination, "cases.tsv")
        with open(path, "r", encoding="utf-8", newline="") as handle:
            text = handle.read()
        needle = "qa71c001\tinsert\tqa71r000\tTitle-000\tch-00\t1760000000\t-\tADMITTED\n"
        if text.count(needle) != 1:
            raise SystemExit("MUTATOR\tFAIL\tpinned-row needle")
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(needle, needle.replace("\tADMITTED\n", "\tIGNORED\n")))
        remanifest(destination)
    elif kind == "expected-row":
        path = os.path.join(destination, "expected.txt")
        with open(path, "r", encoding="utf-8", newline="") as handle:
            text = handle.read()
        needle = "qa71c001\tADMITTED\n"
        if text.count(needle) != 1:
            raise SystemExit("MUTATOR\tFAIL\texpected-row needle")
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(needle, "qa71c001\tIGNORED\n"))
        remanifest(destination)
    else:
        raise SystemExit("MUTATOR\tFAIL\tunknown fixture mutant")
    print("MUTATOR\tFIXTURE\t%s\tOK" % kind)


def main():
    if len(sys.argv) < 2:
        raise SystemExit("MUTATOR\tFAIL\tusage")
    mode = sys.argv[1]
    if mode == "prod":
        mutate_prod(sys.argv[2], sys.argv[3], sys.argv[4])
    elif mode == "fixture":
        mutate_fixture(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit("MUTATOR\tFAIL\tunknown mode")


if __name__ == "__main__":
    main()
