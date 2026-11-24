#!/usr/bin/env python3
"""QA-62 external privacy scan of committed fixtures, prose and manifest."""
import hashlib, os, re, sys

ROOT = sys.argv[1]
PATTERNS = [
    ("URL_SCHEME", re.compile(r"(?i)[a-z][a-z0-9+.-]*://")),
    ("CREDENTIAL_ASSIGNMENT", re.compile(r"(?i)(password|passwd|pass|token|secret|api[_-]?key|authorization)\s*[=:]")),
    ("PIN_ASSIGNMENT", re.compile(r"(?i)pin\s*[=:]\s*[0-9]{4,8}")),
    ("KEY_TAG", re.compile(r"(?i)EXT-X-KEY")),
    ("RECORDING_PAYLOAD_TAG", re.compile(r"(?i)recording-payload")),
]
STANDARD_NAMESPACE = "http://schemas.android.com/apk/res/android"
targets = []
for base in ("qa/fixtures/qa62", "scripts/qa62"):
    for folder, _, files in os.walk(os.path.join(ROOT, base)):
        for name in files:
            if name.endswith(".py") or name.endswith(".kt") or name.endswith(".sh"):
                continue
            targets.append(os.path.join(folder, name))
targets.append(os.path.join(ROOT, "app/src/androidTest/AndroidManifest.xml"))
marker = "SK" + hashlib.sha256(hashlib.sha256(b"qa62-seed-v1").digest()).hexdigest()[:8]
findings = []
for path in targets:
    if not os.path.isfile(path):
        findings.append(("MISSING_FILE", os.path.basename(path)))
        continue
    text = open(path, "rb").read().decode("utf-8", "replace").replace(STANDARD_NAMESPACE, "STANDARD_NAMESPACE")
    for label, pattern in PATTERNS:
        if pattern.search(text):
            findings.append((label, os.path.basename(path)))
    if marker in text:
        findings.append(("DERIVED_MARKER", os.path.basename(path)))
if findings:
    for label, name in findings:
        print("PRIVACY\tFAIL\t%s\t%s" % (label, name))
    sys.exit(1)
print("PRIVACY\tPASS\tfiles=%d\tpatterns=%d\tderived-marker=yes" % (len(targets), len(PATTERNS)))
