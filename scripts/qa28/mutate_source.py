"""QA-28 source mutator: inject one defect into a scratch copy of production/gate code."""
import os
import sys

TARGETS = {
    "policy-userlimit-clamp": ("MultiviewPolicy.kt", "capacity.userLimit.coerceIn(1, 4)", "capacity.userLimit"),
    "policy-decoder-floor": ("MultiviewPolicy.kt", "capacity.decoderSlots.coerceAtLeast(0)", "capacity.decoderSlots"),
    "policy-provider-floor": ("MultiviewPolicy.kt", "capacity.providerConnections.coerceAtLeast(0)", "capacity.providerConnections"),
    "session-invalid-guard": ("MultiviewSession.kt", "if (tileId.isBlank() || tileId.length > MAX_ID_CHARS) return Admission.INVALID_ID", "if (false) return Admission.INVALID_ID"),
    "session-id-boundary": ("MultiviewSession.kt", "tileId.length > MAX_ID_CHARS", "tileId.length >= MAX_ID_CHARS"),
    "session-duplicate-guard": ("MultiviewSession.kt", "if (tileId in tiles) return Admission.DUPLICATE", "if (false) return Admission.DUPLICATE"),
    "session-audio-first": ("MultiviewSession.kt", "if (audibleIndex < 0) audibleIndex = 0", "if (false) audibleIndex = 0"),
    "session-focus-guard": ("MultiviewSession.kt", "val index = tiles.indexOf(tileId)\n        if (index < 0) return false\n        audibleIndex = index", "val index = tiles.indexOf(tileId)\n        if (false) return false\n        audibleIndex = index"),
    "session-close-handoff": ("MultiviewSession.kt", "wasAudible -> minOf(index, tiles.size - 1)", "wasAudible -> audibleIndex"),
    "session-close-earlier": ("MultiviewSession.kt", "index < audibleIndex -> audibleIndex - 1", "index < audibleIndex -> audibleIndex"),
    "gate-thread-limit": ("Qa28Gate.kt", "private const val THREAD_LIMIT = 8", "private const val THREAD_LIMIT = 0"),
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mutate_source.py <scratch-dir> <name>")
    scratch, name = sys.argv[1:3]
    if name not in TARGETS:
        raise SystemExit("MUTATE_UNKNOWN %s" % name)
    filename, old, new = TARGETS[name]
    path = os.path.join(scratch, filename)
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    if text.count(old) != 1:
        raise SystemExit("MUTATE_TARGET %s count=%d" % (filename, text.count(old)))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text.replace(old, new, 1))
    print("MUTATE_SOURCE\tPASS\t%s" % name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
