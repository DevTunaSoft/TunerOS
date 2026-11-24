"""QA-70 scratch-only mutator. Never writes to committed paths."""
import os
import shutil
import sys

SEED = 20261124


def marker():
    h = 2166136261
    for b in str(SEED).encode("ascii"):
        h = ((h ^ (b & 0xFF)) * 16777619) & 0xFFFFFFFF
    return "QA70-" + format(h, "08x")


def replace_once(path, old, new):
    with open(path, "rb") as handle:
        data = handle.read().replace(b"\r\n", b"\n")
    needle = old.encode("utf-8")
    count = data.count(needle)
    if count != 1:
        raise RuntimeError("needle count %d in %s" % (count, os.path.basename(path)))
    with open(path, "wb") as handle:
        handle.write(data.replace(needle, new.encode("utf-8")))


def main():
    if len(sys.argv) != 5:
        print("MUTATE\tFAIL\tusage")
        return 2
    fixture, target, name, prod = sys.argv[1:5]
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target, exist_ok=True)
    dst_fixture = os.path.join(target, "fixture")
    dst_prod = os.path.join(target, "prod")
    shutil.copytree(fixture, dst_fixture)
    shutil.copytree(prod, dst_prod)

    if name == "byte-drift":
        path = os.path.join(dst_fixture, "cases.tsv")
        with open(path, "rb") as handle:
            lines = handle.read().replace(b"\r\n", b"\n").split(b"\n")
        old = lines[1]
        fields = old.split(b"\t")
        body = fields[4]
        if body == b"-" or len(body) < 2:
            raise RuntimeError("byte-drift target missing")
        flip = b"1" if body[0:1] == b"0" else b"0"
        fields[4] = flip + body[1:]
        new = b"\t".join(fields)
        if lines.count(old) != 1:
            raise RuntimeError("byte-drift row not unique")
        lines[1] = new
        with open(path, "wb") as handle:
            handle.write(b"\n".join(lines))
    elif name == "sha-drift":
        path = os.path.join(dst_fixture, "sha256.txt")
        with open(path, "rb") as handle:
            lines = handle.read().replace(b"\r\n", b"\n").split(b"\n")
        old = lines[0]
        flip = b"1" if old[0:1] == b"0" else b"0"
        new = flip + old[1:]
        if lines.count(old) != 1:
            raise RuntimeError("sha row not unique")
        lines[0] = new
        with open(path, "wb") as handle:
            handle.write(b"\n".join(lines))
    elif name == "digest-drift":
        path = os.path.join(dst_fixture, "dimensions.tsv")
        with open(path, "rb") as handle:
            lines = handle.read().replace(b"\r\n", b"\n").split(b"\n")
        idx = next(i for i, line in enumerate(lines) if line.startswith(b"cases_digest\t"))
        old = lines[idx]
        prefix = b"cases_digest\t"
        flip = b"1" if old[len(prefix):len(prefix) + 1] == b"0" else b"0"
        new = prefix + flip + old[len(prefix) + 1:]
        if lines.count(old) != 1:
            raise RuntimeError("digest row not unique")
        lines[idx] = new
        with open(path, "wb") as handle:
            handle.write(b"\n".join(lines))
    elif name == "range-bounds-relax":
        replace_once(
            os.path.join(dst_prod, "AndroidDashCaptureSource.kt"),
            "if (length <= 0 || length > DashSegmentIndex.MAX_INDEX_BYTES)",
            "if (length <= 0)",
        )
    elif name == "status-relax":
        replace_once(
            os.path.join(dst_prod, "AndroidDashCaptureSource.kt"),
            "if (status != HttpURLConnection.HTTP_PARTIAL)",
            "if (status < 200)",
        )
    elif name == "short-relax":
        replace_once(
            os.path.join(dst_prod, "AndroidDashCaptureSource.kt"),
            'if (read < 0) throw IOException("DASH index range is short")',
            'if (read < -1) throw IOException("DASH index range is short")',
        )
    elif name == "key-size-relax":
        replace_once(
            os.path.join(dst_prod, "AndroidHlsCaptureSource.kt"),
            "if (total > HlsAes128.KEY_BYTES)",
            "if (total > HlsAes128.KEY_BYTES + 1)",
        )
    elif name == "privacy-leak":
        with open(os.path.join(dst_fixture, "report-copy.txt"), "w", newline="\n") as handle:
            handle.write("CHECK\tPRIVACY_SCAN\tFAIL\t" + marker() + "\n")
    else:
        print("MUTATE\tFAIL\tunknown")
        return 2

    print("MUTATED\t" + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
