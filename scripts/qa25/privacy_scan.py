#!/usr/bin/env python3
"""QA-25 independent privacy scan over the runner report and committed fixture bytes.

Derives the synthetic marker from the pinned seed at run time; never prints it.
No URL, credential, PIN, DRM marker, long key, private path or recording-payload
token may appear in diagnostics or fixtures.
"""
import re
import sys
from pathlib import Path

SEED_HEX = "51413235"
MASK = (1 << 64) - 1

PATTERNS = {
    "URL": re.compile(r"(?i)(https?|ftp|ws|wss)://"),
    "CREDENTIAL": re.compile(r"(?i)(password|passwd|api[_-]?key|access[_-]?token|bearer|authorization|secret|credential)[ \t]*[=:]"),
    "PIN": re.compile(r"(?i)\bpin[ \t]*[=:]\s*\d{4,}"),
    "DRM": re.compile(r"(?i)\b(drm|widevine|fairplay|playready|keyid|kid|license[_-]?key)[ \t]*[=:]"),
    "LONG_KEY": re.compile(r"\b[0-9a-fA-F]{32,}\b"),
    "PRIVATE_PATH": re.compile(r"(?i)([a-z]:\\users\\|/home/|/sdcard/|content://|file://)"),
    "PAYLOAD": re.compile(r"(?i)\b(payload|recording[_-]?bytes|media[_-]?chunk)\b[ \t]*[=:]"),
}


def derived_marker():
    v = (int(SEED_HEX, 16) ^ 0x9E3779B97F4A7C15) & MASK
    out = ["QA25-"]
    for _ in range(8):
        v = (v * 6364136223846793005 + 1442695040888963407) & MASK
        out.append(format((v >> 56) & 15, "x"))
    return "".join(out)


def main():
    if len(sys.argv) != 3:
        print("PRIVACY\tFAIL\tusage")
        return 2
    report = Path(sys.argv[1])
    fixture = Path(sys.argv[2])
    marker = derived_marker()
    hits = set()
    count = 0
    sources = [report] + sorted(p for p in fixture.iterdir() if p.is_file() and p.name != "manifest.sha256")
    for path in sources:
        count += 1
        text = path.read_bytes().decode("utf-8", errors="replace")
        if marker in text:
            hits.add("MARKER")
        lines = [line for line in text.splitlines() if "SHA256=" not in line]
        scan = "\n".join(lines)
        for name, pattern in PATTERNS.items():
            if pattern.search(scan):
                hits.add(name)
    if hits:
        print("PRIVACY\tFAIL\tfiles=%d\tcodes=%s" % (count, ",".join(sorted(hits))))
        return 1
    print("PRIVACY\tPASS\tfiles=%d\tcodes=NONE" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
