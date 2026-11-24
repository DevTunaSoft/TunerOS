#!/usr/bin/env python3
"""QA-41 scratch-only marker mutator.

Alters exactly one policy marker in the given scratch copy of markers.tsv.
It never writes inside the repository and never touches the committed fixtures.
"""
import sys

NEEDLE = b"stop_epoch_persists_across_start\t1"
REPLACEMENT = b"stop_epoch_persists_across_start\t0"


def main(argv):
    if len(argv) != 2:
        print("MUTATE\tFAIL\tusage")
        return 2
    try:
        with open(argv[1], "rb") as handle:
            data = handle.read()
    except OSError:
        print("MUTATE\tFAIL\tmissing-scratch-marker-file")
        return 1
    count = data.count(NEEDLE)
    if count != 1:
        print("MUTATE\tFAIL\tneedle-count=%d" % count)
        return 1
    with open(argv[1], "wb") as handle:
        handle.write(data.replace(NEEDLE, REPLACEMENT, 1))
    print("MUTATE\tOK\ttarget=stop_epoch_persists_across_start\tneedle=1")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
