#!/usr/bin/env python3
"""QA-62 bounded negative-control mutator. Writes only scratch copies."""
import hashlib, os, re, shutil, sys

SOURCE_MUTANTS = {
    "no-evict-delete": ("evicted.forEach { ring.delete(it.fileName) }", "evicted.forEach { /* no-op */ }"),
    "oversize-accept": ("if (evicted.any { it.fileName == name }) {", "if (false) {"),
    "finish-twice": ("if (finished) return", "if (finished && false) return"),
    "no-next-file": ("ring.replacePlaylist(window.playlist())\n        openNextFile()", "ring.replacePlaylist(window.playlist())"),
    "no-playlist-publish": ("ring.replacePlaylist(window.playlist())\n        openNextFile()", "ring.replacePlaylist(\"\")\n        openNextFile()"),
    "no-open-delete": ("openName?.let { ring.delete(it) }", "openName?.let { /* no-op */ }"),
}
FIXTURE_MUTANTS = {
    "byte-drift", "manifest-tamper", "digest-drift", "cap-drift", "header-drift",
    "row-bounds", "corrupt-relax", "privacy-leak",
}
DATA_FILES = ("dimensions.tsv", "events.tsv", "corrupt.tsv")


def rewrite_manifest(out):
    lines = ["# qa62-pinned-sha256"]
    for name in DATA_FILES:
        digest = hashlib.sha256(open(os.path.join(out, name), "rb").read()).hexdigest()
        lines.append(digest + "\t" + name)
    with open(os.path.join(out, "sha256.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def replace_once(path, needle, replacement):
    raw = open(path, "rb").read().decode("utf-8").replace("\r\n", "\n")
    count = raw.count(needle)
    if count != 1:
        raise SystemExit("MUTATE\tneedle-count\t%d" % count)
    open(path, "w", encoding="utf-8", newline="\n").write(raw.replace(needle, replacement, 1))


def main():
    src, out, mutant = sys.argv[1], sys.argv[2], sys.argv[3]
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    for name in os.listdir(src):
        if os.path.isfile(os.path.join(src, name)):
            shutil.copyfile(os.path.join(src, name), os.path.join(out, name))

    if mutant in SOURCE_MUTANTS:
        prod = os.environ.get("QA62_PROD_SRC")
        target = os.path.join(out, "prod", "TimeshiftCapture.kt")
        os.makedirs(os.path.dirname(target))
        raw = open(prod, "rb").read().decode("utf-8").replace("\r\n", "\n")
        needle, replacement = SOURCE_MUTANTS[mutant]
        count = raw.count(needle)
        if count != 1:
            raise SystemExit("MUTATE\tneedle-count\t%d" % count)
        open(target, "w", encoding="utf-8", newline="\n").write(raw.replace(needle, replacement, 1))
        print("MUTATE\tPASS\tsource\t%s" % mutant)
        return

    if mutant not in FIXTURE_MUTANTS:
        raise SystemExit("MUTATE\tunknown-mutant")
    events = os.path.join(out, "events.tsv")
    dims = os.path.join(out, "dimensions.tsv")
    corrupt = os.path.join(out, "corrupt.tsv")
    manifest = os.path.join(out, "sha256.txt")

    if mutant == "byte-drift":
        replace_once(events, "e0001\t1\t1000", "e0001\t1\t1001")
    elif mutant == "manifest-tamper":
        raw = open(manifest, "rb").read().decode("utf-8")
        match = re.search(r"[0-9a-f]{64}", raw)
        if match is None:
            raise SystemExit("MUTATE\tmanifest-needle-missing")
        first = match.group(0)[0]
        changed = "1" if first == "0" else "0"
        raw = raw[:match.start()] + changed + match.group(0)[1:] + raw[match.end():]
        open(manifest, "w", encoding="utf-8", newline="\n").write(raw)
    elif mutant == "digest-drift":
        replace_once(events, "e0001\t1\t1000", "e0001\t1\t1001")
        rewrite_manifest(out)
    elif mutant == "cap-drift":
        replace_once(dims, "maxSegments\t3", "maxSegments\t2")
        rewrite_manifest(out)
    elif mutant == "header-drift":
        replace_once(events, "id\tsequence", "ix\tsequence")
        rewrite_manifest(out)
    elif mutant == "row-bounds":
        raw = open(events, "rb").read().decode("utf-8")
        open(events, "w", encoding="utf-8", newline="\n").write(raw.replace("\tsingle\n", "\tsingle\textra\n", 1))
        rewrite_manifest(out)
    elif mutant == "corrupt-relax":
        raw = open(corrupt, "rb").read().decode("utf-8").replace("\r\n", "\n")
        lines = raw.splitlines()
        for i, line in enumerate(lines):
            if line.startswith("bad-id\t"):
                fields = line.split("\t")
                payload = bytes.fromhex(fields[1]).decode("utf-8").replace("bad-id", "e0001")
                fields[1] = payload.encode("utf-8").hex()
                lines[i] = "\t".join(fields)
                break
        else:
            raise SystemExit("MUTATE\tcorrupt-row-missing")
        open(corrupt, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
        rewrite_manifest(out)
    elif mutant == "privacy-leak":
        raw = open(dims, "rb").read().decode("utf-8")
        open(dims, "w", encoding="utf-8", newline="\n").write(raw + "note\ttoken=deadbeef\n")
        rewrite_manifest(out)
    print("MUTATE\tPASS\tfixture\t%s" % mutant)


if __name__ == "__main__":
    main()
