#!/usr/bin/env python3
"""QA-22 scratch production-copy mutators. The real core source is never modified."""
import pathlib
import sys

MUTATIONS = {
    "iv-guard": (
        b"explicitHex.length != hex.length + 2",
        b"explicitHex.length > hex.length + 2",
    ),
    "truncate-guard": (
        b'throw IOException("AES-128 segment is not whole blocks")',
        b"return",
    ),
    "pad-guard": (
        b'throw IOException("AES-128 padding is invalid")',
        b"return",
    ),
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py SRC_DST NAME")
    target = pathlib.Path(sys.argv[1])
    name = sys.argv[2]
    if name not in MUTATIONS:
        raise SystemExit("SOURCE_MUTANT_UNKNOWN")
    old, new = MUTATIONS[name]
    data = target.read_bytes()
    if data.count(old) != 1:
        raise SystemExit("SOURCE_MUTANT_TARGET_COUNT %d" % data.count(old))
    target.write_bytes(data.replace(old, new, 1))
    print("SOURCE_MUTATE\tOK\t" + name)


if __name__ == "__main__":
    main()
