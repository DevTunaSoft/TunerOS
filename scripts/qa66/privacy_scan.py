"""QA-66 privacy scan for diagnostics (stdlib only).

`selftest` proves the detector fires on synthetic scratch text; `scan` fails if
any supplied diagnostic log contains URL, credential, PIN, secret, DRM-key or
recording-payload text. No real URL, credential or media value is used.
"""
import re
import sys

PATTERNS = {
    "URL": re.compile(r"(?i)(?:https?|ftp)://|www\.|\.m3u8?\b|xmltv"),
    "CREDENTIAL": re.compile(r"(?i)\b(?:password|passwd|pwd|credential)\b|user(?:name)?\s*[:=]"),
    "PIN": re.compile(r"(?i)\bpin\b\s*[:=]?\s*\d|\bpin\s*[:=]"),
    "SECRET": re.compile(r"(?i)\bsecret\b|api[_-]?key|access[_-]?token|token\s*[:=]"),
    "DRM_KEY": re.compile(r"(?i)\bdrm\b|\bkey\s*[:=]|private key|widevine|playready"),
    "RECORDING_PAYLOAD": re.compile(r"(?i)recording[-_ ]?payload|payload\s*[:=]|base64,"),
}

SELFTEST = {
    "URL": "scratch http://example.invalid/feed.m3u8",
    "CREDENTIAL": "scratch password=redacted",
    "PIN": "scratch pin=0000",
    "SECRET": "scratch secret=redacted",
    "DRM_KEY": "scratch drm key=redacted",
    "RECORDING_PAYLOAD": "scratch recording-payload=redacted",
}


def classify(text):
    return sorted(name for name, pattern in PATTERNS.items() if pattern.search(text))


def main():
    if len(sys.argv) < 2:
        raise SystemExit("USAGE selftest|scan <logs...>")
    if sys.argv[1] == "selftest":
        failed = []
        for name, text in SELFTEST.items():
            hits = classify(text)
            if name not in hits:
                failed.append(name)
            print("DETECT\t%s\t%s" % (name, ",".join(hits) if hits else "NONE"))
        if failed:
            print("SELFTEST\tFAIL\t%s" % ",".join(failed))
            return 1
        print("SELFTEST\tPASS\tclasses=6")
        return 0
    if sys.argv[1] != "scan":
        raise SystemExit("UNKNOWN_ACTION\t%s" % sys.argv[1])
    bad = []
    scanned = 0
    for path in sys.argv[2:]:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as inp:
                text = inp.read()
        except OSError as exc:
            bad.append("%s:unreadable" % path)
            print("SCAN\t%s\tFAIL\tunreadable" % path)
            continue
        scanned += 1
        hits = classify(text)
        if hits:
            bad.append("%s:%s" % (path, ",".join(hits)))
            print("SCAN\t%s\tFAIL\t%s" % (path, ",".join(hits)))
        else:
            print("SCAN\t%s\tPASS" % path)
    if bad:
        print("PRIVACY\tFAIL\tlogs=%d\tbad=%d" % (scanned, len(bad)))
        return 1
    print("PRIVACY\tPASS\tlogs=%d\tclasses=6" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
