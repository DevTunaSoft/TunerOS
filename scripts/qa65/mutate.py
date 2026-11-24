#!/usr/bin/env python3
"""QA-65 scratch copy/mutate helper. Never modifies repository production files."""
import os, shutil, sys

FILES = ("SeriesRules.kt", "Models.kt")

NEEDLES = {
    # id: (needle, replacement, expected first-failure code)
    "M1_CASE": ("out.append(ch.lowercaseChar())", "out.append(ch)"),
    "M2_LEAD": ("if (pendingSpace && out.isNotEmpty()) out.append(' ')",
                "if (pendingSpace) out.append(' ')"),
    "M3_SPACE": ("if (pendingSpace && out.isNotEmpty()) out.append(' ')",
                 "if (false) out.append(' ')"),
    "M4_NULL": ("return if (out.isEmpty()) null else out.toString()",
                "return if (out.isEmpty()) \"\" else out.toString()"),
    "M5_CHANNEL": ('channelId ?: "*"', 'channelId ?: ""'),
    "M6_ID": ('val id: String get() = "$titleKey|${channelId ?: "*"}"',
              'val id: String get() = "$titleKey#${channelId ?: "*"}"'),
}


def copy(src, dst):
    os.makedirs(dst, exist_ok=True)
    for name in FILES:
        shutil.copyfile(os.path.join(src, name), os.path.join(dst, name))
    print("COPIED\t%d" % len(FILES))


def apply(dst, mid):
    if mid not in NEEDLES:
        print("MUTATE_UNKNOWN")
        sys.exit(2)
    needle, replacement = NEEDLES[mid]
    hits = 0
    target = None
    for name in FILES:
        path = os.path.join(dst, name)
        raw = open(path, "r", encoding="utf-8").read().replace("\r\n", "\n")
        n = raw.count(needle)
        if n:
            hits += n
            target = path
    if hits != 1 or target is None:
        print("MUTATE_NEEDLE_COUNT\t%d" % hits)
        sys.exit(2)
    target_path = str(target)
    raw = open(target_path, "r", encoding="utf-8").read().replace("\r\n", "\n")
    open(target_path, "w", encoding="utf-8", newline="\n").write(raw.replace(needle, replacement))
    print("MUTATED\t%s\thits=1" % mid)


if __name__ == "__main__":
    if sys.argv[1] == "copy":
        copy(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "apply":
        apply(sys.argv[2], sys.argv[3])
    else:
        print("USAGE")
        sys.exit(2)
