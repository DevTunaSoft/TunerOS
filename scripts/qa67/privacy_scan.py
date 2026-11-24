"""QA-67 standalone privacy scan for scratch fixtures, gate prose and emitted reports.

Zero dependencies, bounded input, no content is printed. A run-time derived marker is
used for the self-test so no synthetic secret is stored in source or fixtures.
"""
import hashlib
import pathlib
import re
import sys

PATTERNS = [
    re.compile(r"https?://", re.I),
    re.compile(r"\b(?:m3u8?|xmltv)\b", re.I),
    re.compile(r"\b(?:password|passwd|token|secret|api[_-]?key|authorization|pin)\s*[=:]", re.I),
    re.compile(r"\b(?:drm|widevine|fairplay|playready|keyid)\b", re.I),
    re.compile(r"\b(?:recording[-_ ]?payload)\b", re.I),
    re.compile(r"\bbearer\b", re.I),
]
# Standard Android XML namespace, not a provider address.
ALLOWED = ("http://schemas.android.com/apk/res/android",)


def derived_token():
    digest = hashlib.sha256(b"qa67-seed-67024").hexdigest()
    return "QA67SEED" + digest[:16].upper()


def leaks(text):
    for allowed in ALLOWED:
        text = text.replace(allowed, "")
    for pattern in PATTERNS:
        if pattern.search(text):
            return True
    return derived_token() in text


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "selftest":
        clean = "qa67 bounded fixture rows and pinned UTC wall clock text"
        bad = "note " + derived_token() + " appeared"
        if leaks(clean) or not leaks(bad):
            print("PRIVACY\tFAIL\tselftest")
            return 1
        print("PRIVACY\tPASS\tselftest")
        return 0

    paths = sys.argv[1:]
    if not paths:
        print("PRIVACY\tFAIL\tno-inputs")
        return 1
    scanned = 0
    for raw in paths:
        path = pathlib.Path(raw)
        if not path.is_file():
            print("PRIVACY\tFAIL\tmissing-input")
            return 1
        data = path.read_bytes()
        if len(data) > 2 * 1024 * 1024:
            print("PRIVACY\tFAIL\toversize-input")
            return 1
        scanned += 1
        if leaks(data.decode("utf-8", "replace")):
            print("PRIVACY\tFAIL\tdetector-hit")
            return 1
    print("PRIVACY\tPASS\tfiles=%d" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
