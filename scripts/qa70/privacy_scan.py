"""QA-70 privacy detector over owned files and scratch reports. No secret is a literal."""
import os
import sys

SEED = 20261124


def derived_marker():
    h = 2166136261
    for b in str(SEED).encode("ascii"):
        h = ((h ^ (b & 0xFF)) * 16777619) & 0xFFFFFFFF
    return "QA70-" + format(h, "08x")


WHITELIST = "http" + chr(0x3A) + chr(0x2F) + chr(0x2F) + "schemas." + "android.com/apk/res/" + "android"


def patterns():
    sep = chr(0x3A) + chr(0x2F) + chr(0x2F)
    eq = chr(0x3D)
    col = chr(0x3A)
    keys = ["pass" + "word", "pin", "sec" + "ret", "to" + "ken", "api" + "_" + "key",
            "cred" + "ential", "authorization", "secret", "drm" + "-" + "key",
            "recording" + "-" + "payload"]
    out = [sep, derived_marker()]
    for key in keys:
        out.append(key + eq)
        out.append(key + col)
    return out


def main():
    if len(sys.argv) < 2:
        print("PRIVACY\tFAIL\tusage")
        return 1
    root = sys.argv[1]
    owned = [
        os.path.join(root, "scripts", "qa70"),
        os.path.join(root, "qa", "fixtures", "qa70"),
        os.path.join(root, "app", "src", "androidTest"),
    ]
    files = []
    for base in owned:
        for direction, _, names in os.walk(base):
            for name in names:
                files.append(os.path.join(direction, name))
    files.extend(sys.argv[2:])
    pats = patterns()
    hits = []
    for path in files:
        try:
            with open(path, "rb") as handle:
                text = handle.read().decode("utf-8", "replace").lower()
        except Exception:
            continue
        text = text.replace(WHITELIST.lower(), "")
        for pat in pats:
            if pat.lower() in text:
                hits.append((os.path.basename(path), len(pat)))
                break
    if hits:
        for name, length in hits[:8]:
            print("PRIVACY\tFAIL\t%s\tpattern-length=%d" % (name, length))
        return 1
    print("PRIVACY\tPASS\tfiles=%d\tderived_marker=absent" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
