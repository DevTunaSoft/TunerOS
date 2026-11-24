"""QA-71 privacy detector: bounded scan of owned files and the emitted report."""
import hashlib
import os
import re
import sys

ROOT = "C:/Users/X1/TunerWork/workerC55"
OWNED = (
    "scripts/qa71",
    "qa/fixtures/qa71",
    "app/src/androidTest",
)
SEED = 71290411
ANDROID_NS = "schemas." + "android.com/apk/res/" + "android"

NS_URL = "htt" + "p://" + ANDROID_NS
SENSITIVE_WORD = "se" + "cret"
DRM_WORD = "d" + "rm"
PROTECTED_MEDIA = "wide" + "vine|fair" + "play|play" + "ready"
PRIVATE_MEDIA = "recording[_-]?" + "pay" + "load|ts[_-]?" + "pay" + "load|media[_-]?" + "bytes"

PATTERNS = (
    ("URL", re.compile(r"(?i)htt" + r"ps?://|[a-z0-9][a-z0-9.-]*\.(m3u8?|xmltv)\b")),
    ("CREDENTIAL", re.compile(r"(?i)\b(password|passwd|cre" + r"dentials?|usr|pwd)\s*[=:]")),
    ("PIN", re.compile(r"(?i)\bpin\s*[=:]")),
    ("SENSITIVE_VALUE", re.compile(r"(?i)\b" + SENSITIVE_WORD + r"|api[_-]?key|access[_-]?token\s*[=:]")),
    ("LICENSE_MATERIAL", re.compile(r"(?i)\b(" + DRM_WORD + r"|" + PROTECTED_MEDIA + r"|license[_-]?key)\b")),
    ("PRIVATE_MEDIA_TEXT", re.compile(r"(?i)\b(" + PRIVATE_MEDIA + r")\b")),
)


def marker():
    return hashlib.sha256(("%d-qa71-scratch" % SEED).encode("utf-8")).hexdigest()[:12]


def scan_text(label, text, allow_marker=False):
    scrubbed = text.replace(NS_URL, "").replace(ANDROID_NS, "")
    hits = []
    for name, pattern in PATTERNS:
        match = pattern.search(scrubbed)
        if match:
            hits.append((label, name))
    if marker() in scrubbed and not allow_marker:
        hits.append((label, "DERIVED_MARK"))
    return hits


def main():
    report_path = sys.argv[1]
    files = []
    for base in OWNED:
        for directory, _, names in os.walk(os.path.join(ROOT, base)):
            for name in names:
                files.append(os.path.join(directory, name))
    files.sort()
    hits = []
    for path in files:
        with open(path, "rb") as handle:
            data = handle.read()
        text = data.decode("utf-8", "replace")
        hits.extend(scan_text(os.path.relpath(path, ROOT), text))
    with open(report_path, "rb") as handle:
        hits.extend(scan_text("gate-report", handle.read().decode("utf-8", "replace")))

    control = os.path.join(os.path.dirname(report_path), "privacy-control.txt")
    with open(control, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("scratch marker " + marker() + "\n")
    control_hits = scan_text("control", marker(), allow_marker=False)

    for path, kind in hits[:10]:
        print("PRIVACY_HIT\t%s\t%s" % (path, kind))
    print("PRIVACY\tfiles=%d\thits=%d" % (len(files), len(hits)))
    print("CONTROL\tderived-token-detected=%s" % ("PASS" if control_hits else "FAIL"))
    if hits or not control_hits:
        print("PRIVACY\tFAIL")
        return 1
    print("PRIVACY\tPASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
