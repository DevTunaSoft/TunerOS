"""QA-30 fixture mutator: copy the pinned tree to scratch, then introduce one defect."""
import hashlib
import os
import shutil
import sys

DATA_FILES = ["contract.txt", "cases.tsv", "corrupt.tsv", "dimensions.tsv"]
HEADER = "id\theader\toffset\tlength\texpected\tgroup"


def marker():
    return "tok" + hashlib.sha256(b"20261123-qa30-scratch-marker").hexdigest()[:8]


def rewrite(path, fn):
    with open(path, encoding="utf-8", newline="") as handle:
        text = handle.read()
    text = fn(text)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def replace_once(text, old, new):
    count = text.count(old)
    if count != 1:
        raise SystemExit("target not unique: count=%d needle=%r" % (count, old[:48]))
    return text.replace(old, new, 1)


def remanifest(root):
    lines = []
    for name in DATA_FILES:
        with open(os.path.join(root, name), "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        lines.append(digest + "\t" + name + "\n")
    with open(os.path.join(root, "sha256.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("".join(lines))


def flip_expected(text, group, value):
    lines = text.split("\n")
    for index in range(1, len(lines)):
        fields = lines[index].split("\t")
        if len(fields) == 6 and fields[5] == group:
            fields[4] = value
            lines[index] = "\t".join(fields)
            return "\n".join(lines)
    raise SystemExit("no row for group " + group)


def first_row_edit(text, column, value):
    lines = text.split("\n")
    fields = lines[1].split("\t")
    if len(fields) != 6:
        raise SystemExit("first case row is malformed")
    fields[column] = value
    lines[1] = "\t".join(fields)
    return "\n".join(lines)


def mutate(root, name):
    cases = os.path.join(root, "cases.tsv")
    if name == "leak-inject":
        rewrite(os.path.join(root, "contract.txt"),
                lambda t: t + "scratch marker " + marker() + "\n")
        remanifest(root)
    elif name == "header-drift":
        rewrite(cases, lambda t: replace_once(t, HEADER, HEADER.replace("\texpected\t", "\tverdict\t")))
        remanifest(root)
    elif name == "field-drift":
        rewrite(cases, lambda t: first_row_edit(t, 2, "N"))
        remanifest(root)
    elif name == "cap-drift":
        def drop_last(text):
            lines = text.split("\n")
            if lines[-1] == "":
                lines.pop()
            lines.pop()
            lines.append("")
            return "\n".join(lines)
        rewrite(cases, drop_last)
        remanifest(root)
    elif name == "dimension-drift":
        rewrite(os.path.join(root, "dimensions.tsv"),
                lambda t: replace_once(t, "ACCEPT_N\t12", "ACCEPT_N\t13"))
        remanifest(root)
    elif name == "manifest-drift":
        def drift(text):
            lines = text.split("\n")
            first = lines[0]
            head = "0" if first[0] != "0" else "f"
            lines[0] = head + first[1:]
            return "\n".join(lines)
        rewrite(os.path.join(root, "sha256.txt"), drift)
    elif name == "byte-drift":
        rewrite(cases, lambda t: first_row_edit(t, 1, edit_last_digit(t.split("\n")[1].split("\t")[1])))
        remanifest(root)
    elif name == "expect-accept":
        rewrite(cases, lambda t: flip_expected(t, "ACCEPT", "REJECT"))
        remanifest(root)
    elif name == "expect-offset":
        rewrite(cases, lambda t: flip_expected(t, "REJECT_OFFSET", "ACCEPT"))
        remanifest(root)
    elif name == "expect-malformed":
        rewrite(cases, lambda t: flip_expected(t, "REJECT_MALFORMED", "ACCEPT"))
        remanifest(root)
    elif name == "corrupt-accept":
        rewrite(os.path.join(root, "corrupt.tsv"),
                lambda t: replace_once(t, "\tOTHER", "\tACCEPT"))
        remanifest(root)
    else:
        raise SystemExit("unknown mutant: " + name)


def edit_last_digit(header):
    return header[:-1] + ("6" if header[-1] != "6" else "7")


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixtures> <scratch> <name>")
    source, scratch, name = sys.argv[1:4]
    if os.path.isdir(scratch):
        shutil.rmtree(scratch)
    shutil.copytree(source, scratch)
    mutate(scratch, name)


if __name__ == "__main__":
    main()
