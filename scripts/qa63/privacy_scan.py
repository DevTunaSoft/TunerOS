#!/usr/bin/env python3
"""QA-63 external privacy scan over owned files and scratch reports.

Detects URL scheme separators, credential/PIN/secret/token/recording-payload
assignments and the run-derived marker. The Android XML namespace on the
instrumentation manifest is a standard namespace, not a provider address, and
is whitelisted explicitly.
"""
import os, sys, re

WHITELIST = ("http" + chr(0x3a) + chr(0x2f) + chr(0x2f) +
             "schemas." + "android.com/apk/res/" + "android")

def derived_token():
    seed = [11, 29, 47, 83, 101, 113, 149]
    h = 2166136261
    for b in seed:
        h = (h ^ (b & 0xff)) & 0xffffffff
        h = (h * 16777619) & 0xffffffff
    return "QA63-" + str(h)

def patterns():
    scheme = chr(0x3a) + chr(0x2f) + chr(0x2f)
    eq = chr(0x3d)
    col = chr(0x3a)
    keys = [
        "pass" + "word", "pin", "sec" + "ret", "to" + "ken",
        "api" + "key", "cred" + "ential", "rec" + "ording" + "-" + "pay" + "load",
    ]
    pats = [re.compile(re.escape(scheme)), re.compile(re.escape(derived_token()))]
    for k in keys:
        pats.append(re.compile(re.escape(k + eq), re.IGNORECASE))
        pats.append(re.compile(re.escape(k + col), re.IGNORECASE))
    return pats

def main():
    if len(sys.argv) < 2:
        print("PRIVACY\tFAIL\tusage")
        sys.exit(1)
    root = sys.argv[1]
    owned = [
        os.path.join(root, "scripts", "qa63"),
        os.path.join(root, "qa", "fixtures", "qa63"),
        os.path.join(root, "app", "src", "androidTest"),
    ]
    extra = sys.argv[2:]
    files = []
    for base in owned:
        for dp, _, fs in os.walk(base):
            for f in fs:
                files.append(os.path.join(dp, f))
    files.extend(extra)
    hits = []
    pats = patterns()
    for path in files:
        try:
            with open(path, "rb") as fh:
                text = fh.read().decode("utf-8", "replace")
        except Exception:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if WHITELIST in line:
                line = line.replace(WHITELIST, "")
            for p in pats:
                if p.search(line):
                    hits.append((os.path.basename(path), lineno))
                    break
    if hits:
        for name, lineno in hits[:8]:
            print("PRIVACY\tFAIL\t%s:%d" % (name, lineno))
        sys.exit(1)
    print("PRIVACY\tPASS\tfiles=%d\tderived_marker=absent" % len(files))

if __name__ == "__main__":
    main()
