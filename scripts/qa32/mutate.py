#!/usr/bin/env python3
"""QA-32 scratch-only source mutation helper. Never writes to product paths."""
import argparse
import hashlib
import pathlib
import subprocess
import sys


def read_expectations(path):
    out = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


def read_refs(path):
    out = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--refs", required=True)
    ap.add_argument("--expect", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--gate", required=True, choices=("map", "core"))
    ap.add_argument("--mode", required=True, choices=("prepare", "mutate", "restore"))
    args = ap.parse_args()

    exp = read_expectations(args.expect)
    refs = read_refs(args.refs)
    key = "map" if args.gate == "map" else "core"
    ref = refs["MAP04" if key == "map" else "CORE49"]
    path = exp[key + ".source.path"]
    from_needle = exp[key + ".mutation.from"]
    to_needle = exp[key + ".mutation.to"]
    work = pathlib.Path(args.dir)
    work.mkdir(parents=True, exist_ok=True)
    original = work / ("original." + key + ".kt")
    scratch = work / ("scratch." + key + ".kt")

    if args.mode == "prepare":
        blob = subprocess.run(
            ["git", "-C", args.repo, "show", ref + ":" + path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
        original.write_bytes(blob)
        scratch.write_bytes(blob)
        print("PREPARE\t%s\tbytes=%d\tsha256=%s" % (key, len(blob), digest(blob)))
        return 0

    if not original.is_file() or not scratch.is_file():
        print("MUTATE\tFAIL\tprepare-missing")
        return 2
    if args.mode == "mutate":
        blob = scratch.read_bytes()
        text = blob.decode("utf-8")
        expected_count = int(exp[key + ".mutation.count"])
        count = text.count(from_needle)
        if count != expected_count:
            print("MUTATE\tFAIL\tneedle-count=%d expected=%d" % (count, expected_count))
            return 2
        changed = text.replace(from_needle, to_needle).encode("utf-8")
        if digest(changed) == digest(blob):
            print("MUTATE\tFAIL\tno-byte-change")
            return 2
        scratch.write_bytes(changed)
        print("MUTATE\t%s\tfrom-sha256=%s\tto-sha256=%s" %
              (key, digest(blob), digest(changed)))
        return 0

    original_bytes = original.read_bytes()
    scratch.write_bytes(original_bytes)
    if digest(scratch.read_bytes()) != digest(original_bytes):
        print("RESTORE\tFAIL\tbyte-mismatch")
        return 2
    print("RESTORE\t%s\tsha256=%s\tbytes=%d" %
          (key, digest(original_bytes), len(original_bytes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
