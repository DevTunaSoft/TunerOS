"""QA-29 independent privacy scan of emitted diagnostics and committed fixture bytes."""
import os
import re
import sys

MARKER = "tokqa29f31c0"
FILES = ["contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt"]
PATTERNS = [
    ("SCHEME", re.compile(r"(?i)(?:https?|ftp|ftps|rtmp|udp|ssh)://")),
    ("CREDENTIAL", re.compile(r"(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential|bearer)[\s:=]")),
    ("PIN", re.compile(r"(?i)\bpin[\s:=]?\d{4,}")),
    ("DRM", re.compile(r"(?i)(?:widevine|fairplay|playready|drm|license[_-]?key|key[_-]?id)[\s:=]")),
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
