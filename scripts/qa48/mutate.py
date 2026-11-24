"""QA-48 scratch-only mutation probes. Never writes into owned source paths."""
import os
import shutil
import sys

FIXTURE_NAMES = ["dimensions.txt", "cases.tsv", "corrupt.tsv", "expected.txt", "sha256.txt"]

GATE_MUTATIONS = {
    "corrupt": (
        b"static final boolean STRICT_CORRUPT_ENABLED = true;",
        b"static final boolean STRICT_CORRUPT_ENABLED = false;",
    ),
    "privacy": (
        b"static final boolean PRIVACY_ENABLED = true;",
        b"static final boolean PRIVACY_ENABLED = false;",
    ),
    "heap": (
        b"static final long MAX_HEAP_BYTES = 48L * 1024L * 1024L;",
        b"static final long MAX_HEAP_BYTES = 1L;",
    ),
    "thread": (
        b"static final int MAX_WORKER_THREADS = 2;",
        b"static final int MAX_WORKER_THREADS = 64;",
    ),
}

COPY_NEEDLE = b"if (remaining < minFreeBytes || remaining - minFreeBytes < count.toLong()) {"
COPY_REPLACEMENT = b"if (false) {"

CAPACITY_NEEDLE = b"if (reportedAvailable == null || reportedAvailable <= 0) return 0L"
CAPACITY_REPLACEMENT = b"if (reportedAvailable == null) return 1L"

BYTE_NEEDLE = b"c0001\tclean_eos\t6000\t"
BYTE_REPLACEMENT = b"c0001\tclean_eos\t6001\t"


def replace_once(data, needle, replacement, label):
    count = data.count(needle)
    if count != 1:
        raise SystemExit("MUTATOR_NEEDLE_COUNT %s expected=1 actual=%d" % (label, count))
    return data.replace(needle, replacement, 1)


def write_bytes(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(data)


def main():
    if len(sys.argv) < 3:
        raise SystemExit("MUTATOR_USAGE")
    mode = sys.argv[1]
    if mode == "byte":
        src, out = sys.argv[2], sys.argv[3]
        os.makedirs(out, exist_ok=True)
        for name in FIXTURE_NAMES:
            shutil.copyfile(os.path.join(src, name), os.path.join(out, name))
        path = os.path.join(out, "cases.tsv")
        with open(path, "rb") as handle:
            data = handle.read()
        write_bytes(path, replace_once(data, BYTE_NEEDLE, BYTE_REPLACEMENT, "fixture_bytes"))
        return
    if mode == "source":
        kind, src, out = sys.argv[2], sys.argv[3], sys.argv[4]
        if kind not in GATE_MUTATIONS:
            raise SystemExit("MUTATOR_UNKNOWN_KIND")
        with open(src, "rb") as handle:
            data = handle.read()
        needle, replacement = GATE_MUTATIONS[kind]
        write_bytes(out, replace_once(data, needle, replacement, kind))
        return
    if mode == "copy":
        src, out = sys.argv[2], sys.argv[3]
        with open(src, "rb") as handle:
            data = handle.read()
        write_bytes(out, replace_once(data, COPY_NEEDLE, COPY_REPLACEMENT, "copy_guard"))
        return
    if mode == "capacity":
        src, out = sys.argv[2], sys.argv[3]
        with open(src, "rb") as handle:
            data = handle.read()
        write_bytes(out, replace_once(data, CAPACITY_NEEDLE, CAPACITY_REPLACEMENT, "capacity_guard"))
        return
    raise SystemExit("MUTATOR_USAGE")


if __name__ == "__main__":
    main()
