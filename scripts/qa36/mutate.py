#!/usr/bin/env python3
"""QA-36 scratch mutator: one bounded mutation of the pinned HTTP-07 source.

The mutation is applied only to a scratch overlay copy. The pinned git object is
never edited, and every action prints digests so restoration can be proven.
"""
import argparse
import hashlib
import os
import subprocess
import sys

SRC = "app/src/main/java/com/devtunasoft/tuneros/SourceHttp.kt"
NAME = "SourceHttp.kt"

OLD = (
    b"            } catch (_: Exception) {\n"
    b"                connection.disconnect()\n"
    b"                connections.release(connection)\n"
    b"                throw IllegalArgumentException(MALFORMED_SOURCE)\n"
    b"            }"
)
NEW = (
    b"            } catch (e: Exception) {\n"
    b"                connection.disconnect()\n"
    b"                connections.release(connection)\n"
    b"                throw IllegalArgumentException(\"raw redirect ${e.message} $location\")\n"
    b"            }"
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_blob(repo, sha):
    out = subprocess.run(
        ["git", "-C", repo, "show", "{}:{}".format(sha, SRC)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if out.returncode != 0 or not out.stdout:
        raise SystemExit("MUTATOR\tFAIL\tblob-unavailable")
    return out.stdout


def paths(args):
    return (
        os.path.join(args.dir, "original"),
        os.path.join(args.dir, "overlay", NAME),
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--http07", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("action", choices=("prepare", "mutate", "restore", "verify"))
    args = ap.parse_args()
    original, overlay = paths(args)

    if args.action == "prepare":
        data = read_blob(args.repo, args.http07)
        os.makedirs(os.path.dirname(overlay), exist_ok=True)
        with open(original, "wb") as handle:
            handle.write(data)
        with open(overlay, "wb") as handle:
            handle.write(data)
        print("MUTATOR\tPREPARE\tsha256={}".format(digest(data)))
        return 0

    if not os.path.isfile(original):
        raise SystemExit("MUTATOR\tFAIL\toriginal-missing")
    with open(original, "rb") as handle:
        original_bytes = handle.read()

    if args.action == "mutate":
        count = original_bytes.count(OLD)
        if count != 1:
            print("MUTATOR\tFAIL\tneedle-count={}".format(count))
            return 1
        mutant = original_bytes.replace(OLD, NEW, 1)
        if mutant == original_bytes:
            print("MUTATOR\tFAIL\tno-change")
            return 1
        with open(overlay, "wb") as handle:
            handle.write(mutant)
        print("MUTATOR\tMUTATE\tsha256={}".format(digest(mutant)))
        return 0

    if not os.path.isfile(overlay):
        raise SystemExit("MUTATOR\tFAIL\toverlay-missing")
    with open(overlay, "rb") as handle:
        current = handle.read()

    if args.action == "restore":
        with open(overlay, "wb") as handle:
            handle.write(original_bytes)
        current = original_bytes

    same = digest(current) == digest(original_bytes)
    print(
        "MUTATOR\t{}\toriginal={}\toverlay={}\t{}".format(
            args.action.upper(), digest(original_bytes), digest(current),
            "BYTE_IDENTICAL" if same else "MISMATCH",
        )
    )
    return 0 if same else 1


if __name__ == "__main__":
    sys.exit(main())
