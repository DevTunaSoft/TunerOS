"""QA-28 bounded fixture mutator: copy pinned scratch fixture and inject one defect."""
import os
import shutil
import sys

MARKER = "tok9c4e7b21"


def replace(path, old, new):
    with open(path, "r", encoding="utf-8", newline="") as handle:
        text = handle.read()
    if text.count(old) != 1:
        raise SystemExit("MUTATE_TARGET %s count=%d" % (os.path.basename(path), text.count(old)))
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text.replace(old, new, 1))


def replace_line(path, index, old, new):
    with open(path, "r", encoding="utf-8", newline="") as handle:
        lines = handle.read().splitlines(True)
    if index >= len(lines) or old not in lines[index]:
        raise SystemExit("MUTATE_TARGET_LINE %s line=%d" % (os.path.basename(path), index))
    lines[index] = lines[index].replace(old, new, 1)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write("".join(lines))


def append(path, text):
    with open(path, "a", encoding="utf-8", newline="") as handle:
        handle.write(text)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixtures> <scratch> <name>")
    source, scratch, name = sys.argv[1:4]
    shutil.rmtree(scratch, ignore_errors=True)
    shutil.copytree(source, scratch)
    cases = os.path.join(scratch, "cases.tsv")
    capacity = os.path.join(scratch, "capacity.tsv")
    corrupt = os.path.join(scratch, "corrupt.tsv")
    dims = os.path.join(scratch, "dimensions.tsv")
    digest = os.path.join(scratch, "sha256.txt")
    contract = os.path.join(scratch, "contract.txt")
    if name == "byte-drift":
        replace_line(cases, 1, "\tADMITTED\t", "\tADMITTEC\t")
    elif name == "digest-drift":
        with open(digest, "r", encoding="utf-8", newline="") as handle:
            first = handle.readline().rstrip("\r\n")
        if len(first) < 65:
            raise SystemExit("MUTATE_TARGET digest too short")
        flipped = ("1" if first[0] == "0" else "0") + first[1:]
        replace(digest, first, flipped)
    elif name == "header-drift":
        replace(cases, "case_id\tstep", "case\tstep")
    elif name == "field-drift":
        with open(cases, "r", encoding="utf-8", newline="") as handle:
            lines = handle.read().splitlines(True)
        if len(lines) < 2:
            raise SystemExit("MUTATE_TARGET cases too short")
        lines[1] = lines[1].rstrip("\r\n") + "\textra\n"
        with open(cases, "w", encoding="utf-8", newline="") as handle:
            handle.write("".join(lines))
    elif name == "numeric-drift":
        replace_line(cases, 1, "\t2\t4\t2\tOPEN", "\ttwo\t4\t2\tOPEN")
    elif name == "op-drift":
        replace_line(cases, 3, "\tOPEN\tt03\t", "\tJUMP\tt03\t")
    elif name == "token-drift":
        replace_line(cases, 4, "\tFOCUS\tt02\t", "\tFOCUS\tid:x9999\t")
    elif name == "result-drift":
        replace_line(cases, 1, "\tADMITTED\t", "\tADMITTEDX\t")
    elif name == "capacity-drift":
        replace(capacity, "2\t4\t2\t2", "2\t4\t2\t3")
    elif name == "corrupt-expect":
        replace(corrupt, "\tHEADER_STRICT", "\tFIELD_BOUNDS")
    elif name == "dimension-drift":
        replace(dims, "case_count\t37", "case_count\t38")
    elif name == "leak-inject":
        append(contract, "synthetic-probe password=%s\n" % MARKER)
    else:
        raise SystemExit("MUTATE_UNKNOWN %s" % name)
    print("MUTATE\tPASS\t%s" % name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
