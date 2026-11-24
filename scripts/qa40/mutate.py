#!/usr/bin/env python3
"""QA-40 scratch-only mutator: overlay pinned git objects, mutate one, restore all.

Never writes inside the repository. All overlay files live in --dir, which the
runner creates under a native scratch directory.
"""
import argparse
import hashlib
import os
import subprocess
import sys

OVERLAY_KEY = "FIX_P220_C10"
MUTATE_FROM = b"internal class SeriesRuleReplaceTest {"
MUTATE_TO = b"internal class SeriesRuleReplaceMutant {"


def read_tsv(path):
    rows = []
    with open(path, "r", encoding="utf-8", newline="") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            rows.append(line.split("\t"))
    return rows


def load_refs(fixtures):
    refs = {}
    for row in read_tsv(os.path.join(fixtures, "refs.tsv")):
        if len(row) == 2:
            refs[row[0]] = row[1]
    return refs


def inputs(repo, fixtures):
    refs = load_refs(fixtures)
    items = []
    for row in read_tsv(os.path.join(fixtures, "fixes.tsv")):
        if len(row) >= 3:
            items.append((row[0], row[1], row[2]))
    items.extend([
        ("Q34_MARKERS", "P221_QA34", "qa/fixtures/qa34/markers.tsv"),
        ("Q34_REFS", "P221_QA34", "qa/fixtures/qa34/refs.tsv"),
        ("Q33_MARKER", "QA33", "qa/fixtures/qa33/marker.txt"),
    ])
    result = []
    for key, ref, path in items:
        sha = refs.get(ref)
        if not sha:
            raise RuntimeError("missing-ref:" + ref)
        result.append((key, ref, path, sha))
    return result


def blob(repo, sha, path):
    proc = subprocess.run(
        ["git", "-C", repo, "show", sha + ":" + path],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if proc.returncode != 0 or not proc.stdout:
        raise RuntimeError("object-unavailable:" + sha + ":" + path)
    return proc.stdout


def overlay_path(directory, ref, path):
    return os.path.join(directory, "overlay", ref + "_" + os.path.basename(path))


def prepare(args):
    items = inputs(args.repo, args.fixtures)
    os.makedirs(os.path.join(args.dir, "overlay"), exist_ok=True)
    manifest = []
    for key, ref, path, sha in items:
        data = blob(args.repo, sha, path)
        target = overlay_path(args.dir, ref, path)
        with open(target, "wb") as handle:
            handle.write(data)
        manifest.append((key, ref, path, sha, hashlib.sha256(data).hexdigest()))
    with open(os.path.join(args.dir, "manifest.tsv"), "w", encoding="utf-8", newline="\n") as handle:
        for key, ref, path, sha, digest in manifest:
            handle.write("\t".join((key, ref, path, sha, digest)) + "\n")
    print("PREPARE\tfiles=%d" % len(manifest))


def mutate(args):
    items = {key: (ref, path, sha) for key, ref, path, sha in inputs(args.repo, args.fixtures)}
    if args.target not in items:
        print("MUTATE\tFAIL\tunknown-target")
        return 1
    ref, path, sha = items[args.target]
    target = overlay_path(args.dir, ref, path)
    with open(target, "rb") as handle:
        data = handle.read()
    if data.count(MUTATE_FROM) != 1:
        print("MUTATE\tFAIL\tneedle-count=%d" % data.count(MUTATE_FROM))
        return 1
    with open(target, "wb") as handle:
        handle.write(data.replace(MUTATE_FROM, MUTATE_TO, 1))
    print("MUTATE\tOK\t%s\tneedle=1" % args.target)
    return 0


def restore(args):
    with open(os.path.join(args.dir, "manifest.tsv"), "r", encoding="utf-8") as handle:
        rows = [line.rstrip("\r\n").split("\t") for line in handle
                if line.strip() and not line.startswith("#")]
    bad = 0
    for key, ref, path, sha, want in rows:
        data = blob(args.repo, sha, path)
        target = overlay_path(args.dir, ref, path)
        with open(target, "wb") as handle:
            handle.write(data)
        got = hashlib.sha256(data).hexdigest()
        if got == want:
            print("RESTORE\t%s\tBYTE_IDENTICAL" % key)
        else:
            print("RESTORE\t%s\tMISMATCH" % key)
            bad += 1
    print("RESTORE\tALL\tfiles=%d\tmismatch=%d" % (len(rows), bad))
    return 0 if bad == 0 else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--fixtures", required=True)
    parser.add_argument("--dir", required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("prepare")
    mutate_parser = sub.add_parser("mutate")
    mutate_parser.add_argument("--target", required=True)
    sub.add_parser("restore")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare(args)
            return 0
        if args.action == "mutate":
            return mutate(args)
        if args.action == "restore":
            return restore(args)
    except (OSError, RuntimeError) as exc:
        print("MUTATOR\tERROR\t%s" % type(exc).__name__)
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())
