"""QA-69 diagnostics privacy detector: check ids, counts and digests only."""
import os
import re
import sys

SEED = b"20261124"


def derived_marker():
    value = 2166136261
    for byte in SEED:
        value = ((value ^ byte) * 16777619) & 0xFFFFFFFF
    return "QA69-" + format(value, "08x")


PATTERNS = [
    re.compile(r"(?i)(?:password|passwd|pwd|token|secret|credential|authorization|bearer)[^\w]{0,3}[=:]"),
    re.compile(r"(?i)\bpin[^\w]{0,3}[=:]"),
    re.compile(r"(?i)(?:drm|widevine|playready|fairplay|keyid|license)[^\w]{0,3}[=:]"),
    re.compile(r"(?i)(?:payload|media-data|recording-bytes)[^\w]{0,3}[=:]"),
    re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://"),
]


def main(argv):
    fixture = argv[1]
    paths = []
    for name in sorted(os.listdir(fixture)):
        path = os.path.join(fixture, name)
        if os.path.isfile(path):
            paths.append(path)
    paths.extend(argv[2:])
    marker = derived_marker()
    hits = 0
    for path in paths:
        try:
            with open(path, "rb") as handle:
                text = handle.read().decode("utf-8", "replace")
        except OSError:
            hits += 1
            continue
        hits += sum(1 for pattern in PATTERNS if pattern.search(text))
        if marker in text:
            hits += 1
    if hits == 0:
        print("PRIVACY\tPASS\thits=0\tfiles=%d" % len(paths))
        return 0
    print("PRIVACY\tFAIL\thits=%d\tfiles=%d" % (hits, len(paths)))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
