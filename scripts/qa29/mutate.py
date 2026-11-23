"""QA-29 fixture mutator: copy the pinned fixture tree, then introduce one defect."""
import os
import shutil
import sys


def rewrite(path, fn):
    with open(path, encoding="utf-8", newline="") as handle:
        text = handle.read()
    text = fn(text)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def edit_row(text, index, values):
    lines = text.split("\n")
    fields = lines[index].split("\t")
    for column, value in values.items():
        fields[column] = value
    lines[index] = "\t".join(fields)
    return "\n".join(lines)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixtures> <scratch> <name>")
    source, scratch, name = sys.argv[1:4]
    if os.path.isdir(scratch):
        shutil.rmtree(scratch)
    shutil.copytree(source, scratch)
    cases = os.path.join(scratch, "cases.tsv")
    manifest = os.path.join(scratch, "sha256.txt")
    dimensions = os.path.join(scratch, "dimensions.tsv")

    if name == "byte-drift":
        rewrite(cases, lambda t: t.replace("qa29-F1-0001", "qa29-F1-000q", 1))
    elif name == "manifest-drift":
        def drift(text):
            lines = text.split("\n")
            fields = lines[0].split("\t")
            fields[1] = ("0" if fields[1][0] != "0" else "1") + fields[1][1:]
            lines[0] = "\t".join(fields)
            return "\n".join(lines)
        rewrite(manifest, drift)
    elif name == "header-drift":
        rewrite(cases, lambda t: t.replace("expected\tfamily\n", "expected\tfamilyX\n", 1))
    elif name == "field-drift":
        rewrite(cases, lambda t: edit_row(t, 1, {}).replace("qa29-F1-0001\tCOMPLETED", "qa29-F1-0001COMPLETED", 1))
    elif name == "state-drift":
        rewrite(cases, lambda t: edit_row(t, 1, {1: "BOGUS"}))
    elif name == "bytes-drift":
        rewrite(cases, lambda t: edit_row(t, 1, {2: "notnum"}))
    elif name == "expect-drift":
        rewrite(cases, lambda t: edit_row(t, 1, {3: "BOGUS"}))
    elif name == "count-drift":
        rewrite(cases, lambda t: "\n".join(t.split("\n")[:21]))
    elif name == "dimension-drift":
        rewrite(dimensions, lambda t: t.replace("seed\t20261123", "seed\t1", 1))
    elif name == "leak-inject":
        rewrite(cases, lambda t: edit_row(t, 1, {0: "http://scratch.invalid/leak?token=qa29"}))
    else:
        raise SystemExit("unknown mutant: " + name)
    print("MUTANT\tPASS\t%s" % name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
