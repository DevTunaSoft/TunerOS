#!/usr/bin/env python3
"""QA-63 bounded mutant factory. Copies the committed fixture to scratch and applies ONE named mutation.

Source mutants also emit a mutated scratch copy of GuideDoctor.kt; the committed
production and committed fixtures are never written.
"""
import os, sys, shutil, hashlib

def die(msg):
    print("MUTATE FAIL " + msg)
    sys.exit(1)

def sub_once(data: bytes, old: bytes, new: bytes) -> bytes:
    n = data.count(old)
    if n != 1:
        die("needle count %d for %r" % (n, old[:40]))
    return data.replace(old, new, 1)

def derived_token():
    seed = [11, 29, 47, 83, 101, 113, 149]
    h = 2166136261
    for b in seed:
        h = (h ^ (b & 0xff)) & 0xffffffff
        h = (h * 16777619) & 0xffffffff
    return "QA63-" + str(h)

def main():
    if len(sys.argv) != 4:
        die("usage: mutate.py <fixtures> <out> <mutant>")
    src, out, name = sys.argv[1], sys.argv[2], sys.argv[3]
    if os.path.isdir(out):
        shutil.rmtree(out)
    shutil.copytree(src, out)
    names = ["dimensions.tsv", "input.tsv", "expected.tsv", "corrupt.tsv", "notes.tsv", "sha256.txt"]
    def load(f):
        with open(os.path.join(out, f), "rb") as fh:
            return fh.read().replace(b"\r\n", b"\n")
    def save(f, data):
        with open(os.path.join(out, f), "wb") as fh:
            fh.write(data)
    def remanifest():
        lines = []
        with open(os.path.join(out, "sha256.txt"), "rb") as fh:
            for line in fh.read().replace(b"\r\n", b"\n").splitlines():
                p = line.split(b"\t")
                if len(p) != 2:
                    die("bad manifest line")
                h = hashlib.sha256(load(p[1].decode("ascii"))).hexdigest().encode("ascii")
                lines.append(h + b"\t" + p[1])
        save("sha256.txt", b"\n".join(lines) + b"\n")

    fixture_mutants = {
        "byte-drift", "manifest-tamper", "digest-drift", "cap-drift",
        "corrupt-relax", "privacy-leak",
    }
    source_mutants = {
        "no-schedule-filter", "score-floor", "tie-break-desc",
        "key-no-normalize", "limit-plus-one",
    }
    if name not in fixture_mutants | source_mutants:
        die("unknown mutant " + name)

    if name in ("byte-drift", "digest-drift"):
        data = sub_once(load("input.tsv"), b"g01\tBBC One\t1\n", b"g01\tBBC Onf\t1\n")
        save("input.tsv", data)
        if name == "digest-drift":
            remanifest()
    elif name == "manifest-tamper":
        d = load("sha256.txt")
        lines = d.splitlines()
        if not lines:
            die("empty manifest")
        first, rest = lines[0].split(b"\t", 1)
        c = first[:1]
        flip = b"1" if c == b"0" else b"0"
        lines[0] = flip + first[1:] + b"\t" + rest
        save("sha256.txt", b"\n".join(lines) + b"\n")
    elif name == "cap-drift":
        save("dimensions.tsv", sub_once(load("dimensions.tsv"), b"cases=12\n", b"cases=13\n"))
    elif name == "corrupt-relax":
        save("corrupt.tsv", sub_once(load("corrupt.tsv"), b"\tyes\n", b"\t1\n"))
    elif name == "privacy-leak":
        save("notes.tsv", load("notes.tsv") + (derived_token() + "\n").encode("ascii"))

    if name in source_mutants:
        prod = os.environ.get("QA63_PROD_SRC")
        if not prod or not os.path.isfile(prod):
            die("QA63_PROD_SRC not set")
        prod = str(prod)
        with open(prod, "rb") as fh:
            text = fh.read().replace(b"\r\n", b"\n")
        edits = {
            "no-schedule-filter": (b"if (!hasSchedule(guideId)) continue", b"if (false) continue"),
            "score-floor": (b"if (score > 0) found += Suggestion", b"if (score >= 0) found += Suggestion"),
            "tie-break-desc": (b".thenBy { it.displayName }", b".thenByDescending { it.displayName }"),
            "key-no-normalize": (b'tokens(name).joinToString("")', b"name.lowercase()"),
            "limit-plus-one": (b".take(limit)", b".take(limit + 1)"),
        }
        old, new = edits[name]
        text = sub_once(text, old, new)
        os.makedirs(os.path.join(out, "prod"), exist_ok=True)
        with open(os.path.join(out, "prod", "GuideDoctor.kt"), "wb") as fh:
            fh.write(text)

    print("MUTATE PASS " + name)

if __name__ == "__main__":
    main()
