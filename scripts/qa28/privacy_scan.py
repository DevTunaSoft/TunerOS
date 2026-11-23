"""QA-28 independent privacy scan of emitted diagnostics and committed fixture bytes."""
import os
import re
import sys

MARKER = "tok9c4e7b21"
FILES = [
    "contract.txt", "dimensions.tsv", "capacity.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt",
    "corrupt/header.tsv", "corrupt/fields.tsv", "corrupt/numeric.tsv", "corrupt/op.tsv",
    "corrupt/token.tsv", "corrupt/result.tsv", "corrupt/steps.tsv", "corrupt/truncated.tsv",
    "corrupt/capacity.tsv",
]
PATTERNS = [
    ("SCHEME", re.compile(r"(?i)(?:https?|ftp|ftps|rtmp|udp)://")),
    ("SENSITIVE", re.compile(r"(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential)[\s:=]")),
    ("PIN", re.compile(r"(?i)\bpin[\s:=]?\d{4,}")),
    ("PAYLOAD", re.compile(r"(?i)(?:recording|media)[_-]?(?:payload|bytes)[\s:=]")),
]


def scan(text):
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
        code = scan(text)
        if code:
            offenders.append("%s:%s" % (label, code))
    if offenders:
        print("PRIVACY\tFAIL\t%s" % ",".join(offenders))
        return 1
    print("PRIVACY\tPASS\tfiles=%d" % len(texts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
