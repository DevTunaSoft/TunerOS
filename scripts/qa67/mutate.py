"""QA-67 scratch-only mutation helper. Writes copies; never edits owned or production originals."""
import hashlib
import pathlib
import shutil
import sys

DATA_FILES = ("cases.tsv", "corrupt.hex", "dimensions.tsv")
NEEDLES = {
    "P1_FORMAT": ("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"),
    "P2_NULL_TAG": ("segment.programDateMillis?.let {", "(segment.programDateMillis ?: 0L).let {"),
    "P3_ORDER": ("for (segment in window) {", "for (segment in window.asReversed()) {"),
    "P4_ZONE": ("ZoneOffset.UTC", "ZoneOffset.ofHours(1)"),
}


def require_once(data, needle, label):
    count = data.count(needle)
    if count != 1:
        raise SystemExit("MUT_NEEDLE_%s count=%d" % (label, count))


def replace_once(data, old, new, label):
    require_once(data, old, label)
    return data.replace(old, new, 1)


def remanifest(dst):
    lines = []
    for name in DATA_FILES:
        digest = hashlib.sha256((dst / name).read_bytes()).hexdigest()
        lines.append(digest + "\t" + name + "\n")
    (dst / "sha256.txt").write_bytes("".join(lines).encode("ascii"))


def fixture_mutant(mid, src, dst):
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    cases = dst / "cases.tsv"
    dims = dst / "dimensions.tsv"
    manifest = dst / "sha256.txt"
    corrupt = dst / "corrupt.hex"

    if mid == "F1_BYTE":
        old = b"qa67x-00001\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z"
        cases.write_bytes(replace_once(cases.read_bytes(), old, old[:-5] + b".001Z", mid))
    elif mid == "F2_MANIFEST":
        raw = manifest.read_bytes()
        first, sep, rest = raw.partition(b"\n")
        if len(first) < 66 or sep != b"\n" or b"\t" not in first:
            raise SystemExit("MUT_MANIFEST_SHAPE")
        changed = (b"f" if first[:1] != b"f" else b"e") + first[1:]
        manifest.write_bytes(changed + sep + rest)
    elif mid == "F3_DIGEST":
        raw = dims.read_bytes()
        prefix = b"expected_cases_sha\t"
        index = raw.find(prefix)
        if index < 0:
            raise SystemExit("MUT_DIM_KEY")
        end = raw.find(b"\n", index)
        dims.write_bytes(raw[:index + len(prefix)] + b"0" * 64 + raw[end:])
        remanifest(dst)
    elif mid == "F4_HEADER":
        raw = cases.read_bytes()
        cases.write_bytes(replace_once(raw, b"id\tfamily\tepochMillis", b"idx\tfamily\tepochMillis", mid))
        remanifest(dst)
    elif mid == "F5_ROWS":
        raw = cases.read_bytes()
        lines = raw.split(b"\n")
        target = None
        for i, line in enumerate(lines):
            if line.startswith(b"qa67x-00002\t"):
                target = i
                break
        if target is None:
            raise SystemExit("MUT_ROW_MISSING")
        fields = lines[target].split(b"\t")
        if len(fields) != 6:
            raise SystemExit("MUT_ROW_FIELDS")
        lines[target] = b"\t".join(fields[:5])
        cases.write_bytes(b"\n".join(lines))
        remanifest(dst)
    elif mid == "F6_CAP":
        dims.write_bytes(replace_once(dims.read_bytes(), b"case_count\t48\n", b"case_count\t47\n", mid))
        remanifest(dst)
    elif mid == "F7_CORRUPT":
        raw = corrupt.read_bytes()
        lines = raw.split(b"\n")
        for i, line in enumerate(lines):
            if line.startswith(b"BAD_EPOCH\t"):
                payload = bytes.fromhex(line.split(b"\t", 1)[1].decode("ascii"))
                payload = replace_once(payload, b"NOT_A_NUMBER", b"0", mid)
                lines[i] = b"BAD_EPOCH\t" + payload.hex().encode("ascii")
                break
        else:
            raise SystemExit("MUT_CORRUPT_MISSING")
        corrupt.write_bytes(b"\n".join(lines))
        remanifest(dst)
    elif mid == "F8_EXPECTED":
        old = b"qa67x-00001\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\t1250\t125"
        new = b"qa67x-00001\tEPOCH_ZERO\t0\t1970-01-01T00:00:01.000Z\t1250\t125"
        cases.write_bytes(replace_once(cases.read_bytes(), old, new, mid))
        remanifest(dst)
    else:
        raise SystemExit("MUT_UNKNOWN_" + mid)
    print("MUT_FIXTURE\t%s\t%s" % (mid, dst))


def source_mutant(mid, src, dst):
    raw = pathlib.Path(src).read_bytes().replace(b"\r\n", b"\n")
    if mid == "G1_PRIVACY":
        old = b"private static final boolean LEAK_PROBE = false;"
        out = replace_once(raw, old, old.replace(b"false", b"true"), mid)
    elif mid in NEEDLES:
        old, new = NEEDLES[mid]
        out = replace_once(raw, old.encode("utf-8"), new.encode("utf-8"), mid)
    else:
        raise SystemExit("MUT_UNKNOWN_" + mid)
    target = pathlib.Path(dst)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(out)
    print("MUT_SOURCE\t%s\t%s" % (mid, dst))


def main():
    if len(sys.argv) != 5 or sys.argv[1] not in ("fixture", "source"):
        raise SystemExit("USAGE mutate.py fixture|source <id> <src> <dst>")
    mode, mid, src, dst = sys.argv[1:]
    if mode == "fixture":
        fixture_mutant(mid, pathlib.Path(src), pathlib.Path(dst))
    else:
        source_mutant(mid, src, dst)


if __name__ == "__main__":
    main()
