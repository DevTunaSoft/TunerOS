#!/usr/bin/env python3
"""QA-22 independent privacy scan of emitted diagnostics and fixture bytes.

Prints counts and verdict only; never prints a matched marker or any fixture content.
"""
import pathlib
import re
import sys

SEED = 20261123
DERIVED_KEY = bytes(
    ((SEED >> (8 * (i % 4))) ^ (i * 37 + 11)) & 0xFF for i in range(16)
).hex()
SENTINEL = "qa22payload" + format((SEED ^ 0x5A5A5A5A) & 0xFFFFFFFF, "x")
URL = re.compile(rb"(?i)(https?|ftp|file)://")
SECRET = re.compile(
    rb"(?i)(password|passwd|secret|credential|personal[_-]?code|access[_-]?marker|aes[_-]?key|drm[_-]?key)\s*[:=]"
)


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: privacy_scan.py REPORT FIXTURE_DIR")
    report = pathlib.Path(sys.argv[1])
    fixture_dir = pathlib.Path(sys.argv[2])
    files = [report] + sorted(p for p in fixture_dir.iterdir() if p.is_file())
    total = 0
    hits = 0
    for path in files:
        data = path.read_bytes()
        total += len(data)
        if URL.search(data) or SECRET.search(data):
            hits += 1
        if DERIVED_KEY.encode("ascii") in data or SENTINEL.encode("ascii") in data:
            hits += 1
    if hits:
        print("PRIVACY\tFAIL\tscan_hits=%d" % hits)
        raise SystemExit(1)
    print("PRIVACY\tPASS\tfiles=%d\tbytes=%d\tpatterns=4" % (len(files), total))


if __name__ == "__main__":
    main()
