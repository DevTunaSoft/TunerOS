"""QA-26 independent privacy scan of the emitted report and committed fixture bytes."""
import os
import re
import sys

SEED = 20261123
MIXED = (SEED * 2654435761) & 0x7FFFFFFFFFFFFFFF
MARKER = "tok" + format(MIXED, "x")[-8:]
FILES = ["contract.txt", "cases.tsv", "corrupt.tsv", "dimensions.tsv", "sha256.txt"]
PATTERNS = [
    ("SCHEME", re.compile(r"(?i)(?:https?|ftp|ftps|rtmp|udp)://")),
    ("SENSITIVE", re.compile(r"(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential)[\s:=]")),
    ("PIN", re.compile(r"(?i)\bpin[\s:=]?\d{4,}")),
    ("PAYLOAD", re.compile(r"(?i)(?:recording|media)[_-]?(?:payload|bytes)[\s:=]")),
]


def scan(label, text):
    if MARKER in text:
        return "MARKER"
    for code, pattern in PATTERNS:
        if pattern.search(text):
            return code
    return None


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: privacy_scan.py <report> <fixture-dir>")
    report, fixtures = sys.argv[1:3]
    texts = [("report", open(report, encoding="utf-8", errors="replace").read())]
    for name in FILES:
        path = os.path.join(fixtures, name)
        texts.append((name, open(path, encoding="utf-8", errors="replace").read()))
    offenders = []
    for label, text in texts:
        code = scan(label, text)
        if code:
            offenders.append("%s:%s" % (label, code))
    if offenders:
        print("PRIVACY\tFAIL\t%s" % ",".join(offenders))
        return 1
    print("PRIVACY\tPASS\tfiles=%d" % len(texts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
