#!/usr/bin/env python3
"""QA-42 negative control: flip one policy marker in a scratch fixture copy.

Only qa/fixtures/playback-soak42/markers.tsv is ever passed here, and the
runner passes a scratch copy. Committed fixtures are never written.
"""
import sys

NEEDLE = b"byte_budget_enforced\t1"
REPLACEMENT = b"byte_budget_enforced\t0"

if len(sys.argv) != 2:
    print("MUTATE\tFAIL\tusage")
    sys.exit(2)

path = sys.argv[1]
with open(path, "rb") as handle:
    data = handle.read()
if b"\r" in data or data.count(NEEDLE) != 1:
    print("MUTATE\tFAIL\tneedle-not-unique")
    sys.exit(2)
with open(path, "wb") as handle:
    handle.write(data.replace(NEEDLE, REPLACEMENT, 1))
print("MUTATE\tPASS\tbyte_budget_enforced=1->0")
