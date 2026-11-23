#!/usr/bin/env python3
"""QA-21 deterministic fixture mutator. Writes only into a scratch fixture copy."""
import hashlib
import shutil
import sys
from pathlib import Path

DATA_FILES = ("contract.txt", "dimensions.tsv", "runs.tsv", "corrupt.hex")
RUN_HEADER = "case\tmarker\tstage\tlines\tbytes\trename_ok\tmarker_delete_ok\tverdict\tprevious\tcleared"


def rehash(fixture: Path) -> None:
    lines = []
    for name in DATA_FILES:
        digest = hashlib.sha256((fixture / name).read_bytes()).hexdigest()
        lines.append(f"{digest}  {name}\n")
    (fixture / "sha256.txt").write_text("".join(lines), encoding="ascii", newline="\n")


def replace_line(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text(encoding="ascii")
    if text.count(old) != expected:
        raise SystemExit(f"mutator target count {text.count(old)} != {expected} in {path.name}")
    path.write_text(text.replace(old, new), encoding="ascii", newline="\n")


def mutate(fixture: Path, name: str) -> None:
    runs = fixture / "runs.tsv"
    dims = fixture / "dimensions.tsv"
    corrupt = fixture / "corrupt.hex"

    if name == "byte-drift":
        data = bytearray(runs.read_bytes())
        row_start = data.index(b"\n", data.index(RUN_HEADER.encode())) + 1
        data[row_start + 4] = ord("X") if data[row_start + 4] != ord("X") else ord("Y")
        runs.write_bytes(data)
        return

    if name == "sha-drift":
        manifest = fixture / "sha256.txt"
        text = manifest.read_text(encoding="ascii")
        first = text.index("a") if "a" in text else 0
        text = text[:first] + ("b" if text[first] != "b" else "c") + text[first + 1:]
        manifest.write_text(text, encoding="ascii", newline="\n")
        return

    if name == "header-missing":
        replace_line(runs, RUN_HEADER, "case\tmarker\tstage\tlines")
        return

    if name == "field-count":
        lines = runs.read_text(encoding="ascii").splitlines(keepends=True)
        for i, line in enumerate(lines):
            if line.startswith("qa21-001\t"):
                fields = line.rstrip("\n").split("\t")
                lines[i] = "\t".join(fields[:-1]) + "\n"
                break
        runs.write_text("".join(lines), encoding="ascii", newline="\n")
        return

    if name == "int-bounds":
        lines = runs.read_text(encoding="ascii").splitlines(keepends=True)
        for i, line in enumerate(lines):
            if line.startswith("qa21-001\t"):
                fields = line.rstrip("\n").split("\t")
                fields[3] = "9999"
                lines[i] = "\t".join(fields) + "\n"
                break
        runs.write_text("".join(lines), encoding="ascii", newline="\n")
        return

    if name == "enum-unknown":
        lines = runs.read_text(encoding="ascii").splitlines(keepends=True)
        for i, line in enumerate(lines):
            if line.startswith("qa21-002\t"):
                fields = line.rstrip("\n").split("\t")
                fields[2] = "BOGUS_STAGE"
                lines[i] = "\t".join(fields) + "\n"
                break
        runs.write_text("".join(lines), encoding="ascii", newline="\n")
        return

    if name == "corrupt-accept":
        lines = corrupt.read_text(encoding="ascii").splitlines(keepends=True)
        for i, line in enumerate(lines):
            if line.startswith("bad-header\t"):
                hex_payload = line.split("\t", 1)[1].strip()
                fixed = bytes.fromhex(hex_payload)
                marker_len = len(b"QA21PENDING2\n")
                assert fixed[:marker_len] == b"QA21PENDING2\n"
                lines[i] = "bad-header\t" + (b"QA21PENDING1\n" + fixed[marker_len:]).hex() + "\n"
                break
        corrupt.write_text("".join(lines), encoding="ascii", newline="\n")
        return

    if name == "outcome-mismatch":
        lines = runs.read_text(encoding="ascii").splitlines(keepends=True)
        for i, line in enumerate(lines):
            fields = line.rstrip("\n").split("\t")
            if len(fields) == 10 and fields[7] == "COMPLETE":
                fields[7] = "REJECTED"
                lines[i] = "\t".join(fields) + "\n"
                break
        runs.write_text("".join(lines), encoding="ascii", newline="\n")
        return

    if name == "cap-shrink":
        replace_line(dims, "max_lines=32", "max_lines=33")
        return

    if name == "leak-inject":
        replace_line(dims, "leak_inject=0", "leak_inject=1")
        return

    raise SystemExit("unknown mutant: " + name)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <source-fixtures> <scratch-fixtures> <name>")
    source, scratch, name = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    if scratch.exists():
        shutil.rmtree(scratch)
    shutil.copytree(source, scratch)
    mutate(scratch, name)
    if name != "byte-drift" and name != "sha-drift":
        rehash(scratch)
    print(f"MUTATED\t{name}")


if __name__ == "__main__":
    main()
