"""QA-68 privacy detector over owned files and scratch reports. No secrets are literals."""
import os
import sys

SEED = 20261124


def derived_marker():
    h = 2166136261
    for b in str(SEED).encode("ascii"):
        h = ((h ^ (b & 0xFF)) * 16777619) & 0xFFFFFFFF
    return "QA68-" + format(h, "08x")


WHITELIST = "http" + chr(0x3A) + chr(0x2F) + chr(0x2F) + "schemas." + "android.com/apk/res/" + "android"


def patterns():
    sep = chr(0x3A) + chr(0x2F) + chr(0x2F)
    eq = chr(0x3D)
    col = chr(0x3A)
    keys = ["pass" + "word", "pin", "sec" + "ret", "to" + "ken", "api" + "_" + "key",
            "cred" + "ential", "authorization", "secret"]
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
        os.path.join(root, "scripts", "qa68"),
        os.path.join(root, "qa", "fixtures", "qa68"),
        os.path.join(root, "app", "src", "androidTest"),
    ]
    extra = sys.argv[2:]
    files = []
    for base in owned:
        for dp, _, fs in os.walk(base):
            for f in fs:
                files.append(os.path.join(dp, f))
    files.extend(extra)
    pats = patterns()
    hits = []
    for path in files:
        try:
            with open(path, "rb") as handle:
                text = handle.read().decode("utf-8", "replace")
        except Exception:
            continue
        for pat in pats:
            if pat.lower() in text.lower().replace(WHITELIST.lower(), ""):
                hits.append((os.path.basename(path), pat))
                break
    if hits:
        for name, pat in hits[:8]:
            print("PRIVACY\tFAIL\t%s\tpattern-length=%d" % (name, len(pat)))
        return 1
    print("PRIVACY\tPASS\tfiles=%d\tderived_marker=absent" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
