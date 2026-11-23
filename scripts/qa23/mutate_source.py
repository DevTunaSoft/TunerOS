#!/usr/bin/env python3
"""QA-23 production-guard mutators. Each changes one guard inside a scratch source copy."""
import pathlib
import sys

MUTATIONS = {
    "version-guard": (
        b"val version = bytes[pos].toInt() and 0xFF",
        b"val version = (bytes[pos].toInt() and 0xFF).coerceAtMost(1)",
    ),
    "hier-guard": (
        b"if ((word ushr 31) == 1L)",
        b"if ((word ushr 31) == 2L)",
    ),
    "empty-guard": (
        b"if (length == 0L)",
        b"if (length == -1L)",
    ),
    "dur-guard": (
        b"if (duration == 0L)",
        b"if (duration == -1L)",
    ),
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py SCRATCH_SOURCE NAME")
    path = pathlib.Path(sys.argv[1])
    name = sys.argv[2]
    if name not in MUTATIONS:
        raise SystemExit("MUTATE_UNKNOWN " + name)
    old, new = MUTATIONS[name]
    data = path.read_bytes()
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATE_TARGET_COUNT %d" % count)
    path.write_bytes(data.replace(old, new, 1))
    print("MUTATE_SOURCE\tOK\t" + name)


if __name__ == "__main__":
    main()
