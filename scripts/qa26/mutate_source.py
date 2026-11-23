"""QA-26 source mutants applied only to scratch copies of production or gate code."""
import os
import sys


def mutate(path, old, new, label):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    count = text.count(old)
    if count != 1:
        raise SystemExit("MUTATE_NEEDLE %s count=%d" % (label, count))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text.replace(old, new, 1))


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py <scratch-dir> <name>")
    scratch, name = sys.argv[1:3]
    manifest = os.path.join(scratch, "DashManifest.kt")
    gate = os.path.join(scratch, "Qa26Gate.kt")

    if name == "dtd-guard":
        mutate(manifest,
               'if (text.contains("<!DOCTYPE", ignoreCase = true)) return refuse("DASH manifest declares a DTD, which is not accepted")',
               '// DTD guard removed for QA-26 RED control', name)
    elif name == "size-guard":
        mutate(manifest,
               'if (text.length > MAX_MANIFEST_CHARS) return refuse("DASH manifest is too large")',
               '// size guard removed for QA-26 RED control', name)
    elif name == "contiguity-guard":
        mutate(manifest,
               '} else if (t != null && t != expectedStart) {',
               '} else if (false && t != null && t != expectedStart) {', name)
    elif name == "token-guard":
        mutate(manifest,
               '                else -> return null\n            }\n            index = end + 1',
               '                else -> out.append(token)\n            }\n            index = end + 1', name)
    elif name == "bandwidth-choice":
        mutate(manifest,
               'if (bandwidth > chosenBandwidth) {',
               'if (bandwidth < chosenBandwidth) {', name)
    elif name == "heap-bound":
        mutate(gate,
               'private const val HEAP_DRIFT_LIMIT = 8L * 1024 * 1024',
               'private const val HEAP_DRIFT_LIMIT = 0L * 1024 * 1024', name)
    else:
        raise SystemExit("MUTATE_UNKNOWN " + name)

    print("MUTATE\tPASS\t%s" % name)


if __name__ == "__main__":
    main()
