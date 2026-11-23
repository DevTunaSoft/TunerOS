"""QA-30 production-copy mutator: weaken one admission guard in a scratch copy only."""
import os
import sys

REPLACEMENTS = {
    "src-total": ("if (total <= to) return false", "if (false) return false"),
    "src-match-entire": ("headerPattern.matchEntire(header.trim())", "headerPattern.find(header.trim())"),
    "src-from": ("return from == requested.offset && to == expectedEnd", "return to == expectedEnd"),
    "src-to": ("return from == requested.offset && to == expectedEnd", "return from == requested.offset"),
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py <scratch-dir> <name>")
    root, name = sys.argv[1:3]
    if name not in REPLACEMENTS:
        raise SystemExit("unknown source mutant: " + name)
    old, new = REPLACEMENTS[name]
    path = os.path.join(root, "HlsContentRange.kt")
    with open(path, encoding="utf-8", newline=None) as handle:
        text = handle.read()
    count = text.count(old)
    if count != 1:
        raise SystemExit("target not unique in %s: count=%d" % (os.path.basename(path), count))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text.replace(old, new, 1))


if __name__ == "__main__":
    main()
