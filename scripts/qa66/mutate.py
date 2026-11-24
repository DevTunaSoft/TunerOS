"""QA-66 scratch-only mutation helper.

copy <srcdir> <dstdir>  - byte-identical copy of the two production sources
apply <dstdir> <mutant>  - replace exactly one needle in the scratch copy
Never writes to the committed production tree or fixture directory.
"""
import os
import shutil
import sys

SOURCES = ("ChannelVisibility.kt", "Models.kt")

# Single-needle mutations of ChannelVisibility.kt; each is checked for uniqueness.
MUTANTS = {
    "M1_TRIM": (
        "val search = query.trim()",
        "val search = query",
    ),
    "M2_CASE": (
        "channel.name.contains(search, ignoreCase = true)",
        "channel.name.contains(search, ignoreCase = false)",
    ),
    "M3_NULL_GUIDE": (
        "if (hideWithoutGuide && guide == null) return emptyList()",
        "if (hideWithoutGuide && guide == null) return channels",
    ),
    "M4_SCHEDULE": (
        "channel.guideId?.let { guide?.hasSchedule(it) } == true",
        "channel.guideId != null",
    ),
    "M5_PASSTHROUGH": (
        "if (search.isEmpty() && !hideWithoutGuide) return channels",
        "if (search.isEmpty() && !hideWithoutGuide) return emptyList()",
    ),
}


def main():
    if len(sys.argv) < 2:
        raise SystemExit("USAGE copy|apply|list ...")
    action = sys.argv[1]
    if action == "list":
        for key in MUTANTS:
            print(key)
        return
    if action == "copy":
        src, dst = sys.argv[2], sys.argv[3]
        os.makedirs(dst, exist_ok=True)
        for name in SOURCES:
            with open(os.path.join(src, name), "rb") as inp:
                data = inp.read()
            with open(os.path.join(dst, name), "wb") as out:
                out.write(data)
            print("COPIED\t%s\t%d" % (name, len(data)))
        return
    if action == "apply":
        dst, mutant = sys.argv[2], sys.argv[3]
        needle, replacement = MUTANTS[mutant]
        path = os.path.join(dst, "ChannelVisibility.kt")
        with open(path, "r", encoding="utf-8", newline="") as inp:
            text = inp.read()
        count = text.count(needle)
        if count != 1:
            raise SystemExit("NEEDLE_COUNT\t%d\t%s" % (count, mutant))
        text = text.replace(needle, replacement, 1)
        with open(path, "w", encoding="utf-8", newline="") as out:
            out.write(text)
        print("MUTATED\t%s\tneedles=1\tbytes=%d" % (mutant, len(text.encode("utf-8"))))
        return
    raise SystemExit("UNKNOWN_ACTION\t%s" % action)


if __name__ == "__main__":
    main()
