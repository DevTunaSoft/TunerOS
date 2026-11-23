#!/usr/bin/env python3
"""QA-27 independent privacy scan (second language).

Scans committed scratch fixture bytes and the captured host-gate report for
provider addresses, access markers, personal codes, protection material,
recording payload markers and the run-derived synthetic marker. Prints counts
and check ids only; never prints matched content.
"""
import pathlib
import re
import sys

SEED = 271827
MASK = 0xFFFFFFFFFFFFFFFF


def derived_marker(seed: int) -> str:
    x = (seed ^ 0x9E3779B97F4A7C15) & MASK
    x = ((x ^ (x >> 30)) * 0xBF58476D1CE4E5B9) & MASK
    x = ((x ^ (x >> 27)) * 0x94D049BB133111EB) & MASK
    x ^= x >> 31
    return "QA27M" + format(x & 0xFFFFFFFF, "08X")


PATTERNS = [
    ("SCHEME", re.compile(rb"[a-z][a-z0-9+.-]*://", re.I)),
    ("SECRET", re.compile(rb"\b(password|passwd|secret|credential|api[_-]?key)\b", re.I)),
    ("ASSIGN", re.compile(rb"\b(pin|key)\s*[:=]", re.I)),
    ("DRM", re.compile(rb"\bdrm\b", re.I)),
    ("FEED", re.compile(rb"\b(m3u|xmltv)\b", re.I)),
    ("RECORDING", re.compile(rb"\brec(ord)?[_-]?[0-9]{3,}\b", re.I)),
]


def main() -> int:
    if len(sys.argv) != 3:
        print("USAGE\tprivacy_scan.py\t<fixture-dir>\t<report-file>")
        return 2
    fixture = pathlib.Path(sys.argv[1])
    report = pathlib.Path(sys.argv[2])
    marker = derived_marker(SEED).encode("ascii")
    targets = sorted(p for p in fixture.rglob("*") if p.is_file())
    if report.is_file():
        targets.append(report)
    if not targets:
        print("CHECK\tPRIVACY_FILES\tFAIL")
        print("FIRST\tPRIVACY_FILES")
        return 1

    hits = 0
    scanned = 0
    for path in targets:
        data = path.read_bytes()
        scanned += len(data)
        if marker in data:
            hits += 1
        for _name, pattern in PATTERNS:
            if pattern.search(data):
                hits += 1
    if hits:
        print("CHECK\tPRIVACY_PY\tFAIL")
        print("FIRST\tPRIVACY_PY")
        print(f"SUMMARY\tfiles={len(targets)}\tbytes={scanned}\thits={hits}")
        return 1
    print(f"CHECK\tPRIVACY_PY\tPASS")
    print(f"SUMMARY\tfiles={len(targets)}\tbytes={scanned}\tpatterns={len(PATTERNS) + 1}\thits=0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
