#!/usr/bin/env python3
"""QA-49 negative controls. Writes scratch mutant copies only; never edits the committed fixture."""
import hashlib
import os
import shutil
import sys

SEED = 20261124
DATA_FILES = ("contract.txt", "dimensions.tsv", "cases.tsv")
MANIFEST = "sha256.txt"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def marker() -> str:
    raw = ("%d|qa49-marker" % SEED).encode("utf-8")
    return "QA49M-" + hashlib.sha256(raw).hexdigest()[:12].upper()


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def replace_once(data: bytes, old: bytes, new: bytes, name: str) -> bytes:
    count = data.count(old)
    if count != 1:
        raise SystemExit("needle %s in %s occurs %d times" % (old.decode(), name, count))
    return data.replace(old, new, 1)


def republish(target: str, changed_cases: bool):
    """Recompute the dimensions pin first, then the manifest over the final bytes."""
    if changed_cases:
        dims_path = os.path.join(target, "dimensions.tsv")
        dims = read(dims_path).decode("utf-8")
        new_pin = digest(read(os.path.join(target, "cases.tsv")))
        out = []
        replaced = 0
        for line in dims.split("\n"):
            if line.startswith("casesSha256="):
                out.append("casesSha256=" + new_pin)
                replaced += 1
            else:
                out.append(line)
        if replaced != 1:
            raise SystemExit("casesSha256 pin not unique")
        write(dims_path, "\n".join(out).encode("utf-8"))

    lines = []
    for name in DATA_FILES:
        lines.append(digest(read(os.path.join(target, name))) + "  " + name + "\n")
    write(os.path.join(target, MANIFEST), "".join(lines).encode("ascii"))


def mutate(source: str, target: str, name: str) -> int:
    if os.path.exists(target):
        shutil.rmtree(target)
    shutil.copytree(source, target)
    cases_path = os.path.join(target, "cases.tsv")
    prose_path = os.path.join(target, "contract.txt")
    cases = read(cases_path).decode("utf-8")
    changed_cases = True

    if name == "sha-drift":
        write(cases_path, replace_once(cases.encode(), b"q49-001", b"q49-002", "cases.tsv"))
        return 0  # deliberately no republish: manifest must disagree
    if name == "byte-drift":
        write(cases_path, replace_once(cases.encode(), b"q49-001", b"q49-002", "cases.tsv"))
    elif name == "header-drop":
        write(cases_path, cases.replace("caseId\tfamily", "caseID\tfamily", 1).encode("utf-8"))
    elif name == "field-extra":
        lines = cases.split("\n")
        if not lines[1].endswith("\tADMIT"):
            raise SystemExit("unexpected first data row")
        lines[1] += "\textra"
        write(cases_path, "\n".join(lines).encode("utf-8"))
    elif name == "family-unknown":
        lines = cases.split("\n")
        fields = lines[1].split("\t")
        fields[1] = "BOGUS"
        lines[1] = "\t".join(fields)
        write(cases_path, "\n".join(lines).encode("utf-8"))
    elif name == "expect-flip":
        lines = cases.split("\n")
        fields = lines[1].split("\t")
        if fields[10] != "ADMIT":
            raise SystemExit("first row is not an admit row")
        fields[10] = "REFUSE"
        lines[1] = "\t".join(fields)
        write(cases_path, "\n".join(lines).encode("utf-8"))
    elif name == "admit-flip":
        lines = cases.split("\n")
        fields = lines[1].split("\t")
        if fields[1] != "ADMIT":
            raise SystemExit("first row is not family ADMIT")
        fields[3] = "com.android.externalstorage.documents.qa49"
        lines[1] = "\t".join(fields)
        write(cases_path, "\n".join(lines).encode("utf-8"))
    elif name == "trace-flip":
        lines = cases.split("\n")
        fields = lines[1].split("\t")
        if fields[11] != "ADMIT":
            raise SystemExit("first row trace is not ADMIT")
        fields[11] = "NONE"
        lines[1] = "\t".join(fields)
        write(cases_path, "\n".join(lines).encode("utf-8"))
    elif name == "privacy-leak":
        prose = read(prose_path).decode("utf-8")
        write(prose_path, (prose + marker() + "\n").encode("utf-8"))
        changed_cases = False
    else:
        raise SystemExit("unknown mutant " + name)

    republish(target, changed_cases)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("usage: mutate.py <fixtureDir> <scratchTarget> <mutantName>")
        sys.exit(2)
    sys.exit(mutate(sys.argv[1], sys.argv[2], sys.argv[3]))
