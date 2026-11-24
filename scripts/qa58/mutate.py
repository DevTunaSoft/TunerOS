#!/usr/bin/env python3
"""QA-58 bounded negative-mutation writer. All writes go to a scratch output directory."""
import os
import re
import shutil
import sys

NAMES = ["rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt"]
SEED = 20261124
MASK = (1 << 64) - 1

def secret_token():
    mixed = (SEED * 6364136223846793005 + 1442695040888963407) & MASK
    return str(mixed)[-8:].zfill(8)

def read(path):
    with open(path, "r", encoding="utf-8", newline="") as handle:
        return handle.read()

def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

def replace_once(text, needle, replacement, label):
    count = text.count(needle)
    if count != 1:
        raise SystemExit("needle %s count=%d" % (label, count))
    return text.replace(needle, replacement, 1)

def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mutate.py <fixtures> <out> <mutant>")
    fixtures, out, mutant = sys.argv[1:]
    contract_src = os.environ.get("QA58_CONTRACT_SRC")
    if not contract_src or not os.path.isfile(contract_src):
        raise SystemExit("QA58_CONTRACT_SRC is required")
    fixture_out = os.path.join(out, "fixture")
    contract_out = os.path.join(out, "contract")
    os.makedirs(fixture_out, exist_ok=True)
    os.makedirs(contract_out, exist_ok=True)
    for name in NAMES:
        shutil.copy2(os.path.join(fixtures, name), os.path.join(fixture_out, name))
    contract = read(contract_src)
    rows_path = os.path.join(fixture_out, "rows.tsv")
    corrupt_path = os.path.join(fixture_out, "corrupt.txt")
    report_path = os.path.join(fixture_out, "report-copy.txt")
    manifest_path = os.path.join(fixture_out, "sha256.txt")

    if mutant == "byte-drift":
        lines = read(rows_path).split("\n")
        target = lines[101]
        fields = target.split("\t")
        if len(fields) != 4 or not fields[1].isdigit():
            raise SystemExit("byte-drift target row unexpected")
        fields[1] = fields[1][:-1] + ("9" if fields[1][-1] != "9" else "8")
        lines[101] = "\t".join(fields)
        write(rows_path, "\n".join(lines))
    elif mutant == "sha-drift":
        lines = read(manifest_path).split("\n")
        old = lines[0].split("\t")[0]
        if len(old) != 64:
            raise SystemExit("manifest hash unexpected")
        lines[0] = ("0" if old[0] != "0" else "1") + old[1:]
        write(manifest_path, "\n".join(lines))
    elif mutant == "digest-drift":
        contract = re.sub(
            r'(const val PINNED_FIXTURE_DIGEST = ")([0-9a-f]{64})(")',
            lambda m: m.group(1) + ("1" * 64) + m.group(3),
            contract,
            count=1,
        )
    elif mutant == "header-drop":
        lines = read(rows_path).split("\n")
        if not lines[0].startswith("QA58RESUME"):
            raise SystemExit("header not found")
        write(rows_path, "\n".join(lines[1:]))
    elif mutant == "row-overflow":
        text = read(rows_path)
        extra = "ch999999\t1000\t100000\t1700200000000\n"
        write(rows_path, text + extra)
    elif mutant == "corrupt-relax":
        text = read(corrupt_path)
        needle = "ROW\tch000001\t99000\t100000\t1700000000000"
        text = replace_once(text, needle, "ROW\tch000001\t90000\t100000\t1700000000000", "corrupt-relax")
        write(corrupt_path, text)
    elif mutant == "privacy-leak":
        text = read(report_path)
        write(report_path, text + "token=" + secret_token() + "\n")
    elif mutant == "cap-drift":
        contract = replace_once(contract, "const val MAX_ENTRIES = 64", "const val MAX_ENTRIES = 8", "cap-drift")
    elif mutant == "encode-order":
        contract = replace_once(contract, ".values.sortedBy { it.key }", ".values.sortedByDescending { it.key }", "encode-order")
    elif mutant == "rank-tiebreak":
        contract = replace_once(contract, ".thenBy { it.key }", ".thenByDescending { it.key }", "rank-tiebreak")
    elif mutant == "stale-write":
        contract = replace_once(contract, "updated > previous.updatedMs", "updated >= previous.updatedMs", "stale-write")
    else:
        raise SystemExit("unknown mutant " + mutant)

    write(os.path.join(contract_out, "Qa58Contract.kt"), contract)
    print("MUTATE\tPASS\t" + mutant)

if __name__ == "__main__":
    main()
