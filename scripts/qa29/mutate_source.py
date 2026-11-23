"""QA-29 production-copy mutator: change one decision in a scratch copy only."""
import os
import sys

REPLACEMENTS = {
    "zero-byte-guard": (
        "RecordingModels.kt",
        "if (requested == RecordingState.COMPLETED && bytesWritten == 0L) {",
        "if (false) {",
    ),
    "negative-guard": (
        "RecordingModels.kt",
        "    fun finalState(requested: RecordingState, bytesWritten: Long): RecordingState {\n        require(bytesWritten >= 0)\n",
        "    fun finalState(requested: RecordingState, bytesWritten: Long): RecordingState {\n",
    ),
    "state-preserve": (
        "RecordingModels.kt",
        "        } else {\n            requested\n        }\n    }\n}",
        "        } else if (requested != RecordingState.COMPLETED) {\n            RecordingState.CANCELLED\n        } else {\n            requested\n        }\n    }\n}",
    ),
    "contract-cap": (
        "Qa29Contract.kt",
        "const val CASE_CAP = 96",
        "const val CASE_CAP = 16",
    ),
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py <scratch-dir> <name>")
    scratch, name = sys.argv[1:3]
    if name not in REPLACEMENTS:
        raise SystemExit("unknown mutant: " + name)
    filename, old, new = REPLACEMENTS[name]
    path = os.path.join(scratch, filename)
    with open(path, encoding="utf-8", newline=None) as handle:
        text = handle.read()
    if text.count(old) != 1:
        raise SystemExit("target not unique in %s: found %d" % (filename, text.count(old)))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text.replace(old, new, 1))
    print("SOURCE_MUTANT\tPASS\t%s\t%s" % (name, filename))
    return 0


if __name__ == "__main__":
    sys.exit(main())
