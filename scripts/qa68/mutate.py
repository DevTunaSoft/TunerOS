"""QA-68 scratch-only mutation driver. Never writes into the repository."""
import hashlib
import os
import shutil
import sys

SEED = "20261124"
DATA_FILES = ("dimensions.tsv", "cases.tsv", "corrupt.tsv")
PROD_NAME = "PlaybackEvidence.kt"

PROD_MUTANTS = {
    "prod-status": (
        b"status = status ?: cause.responseCode",
        b"status = cause.responseCode",
    ),
    "prod-type": (
        b"type = type ?: cause.contentType",
        b"type = cause.contentType",
    ),
    "prod-transport": (
        b"is SSLException -> transport = transport ?: TransportFault.TLS",
        b"is Exception -> transport = transport ?: TransportFault.TLS",
    ),
    "prod-precedence": (
        b"firstResponse?.contentType ?: StreamDoctor.safeContentType(type)",
        b"StreamDoctor.safeContentType(type) ?: firstResponse?.contentType",
    ),
    "prod-depth": (
        b"depth < MAX_CAUSE_DEPTH",
        b"depth < 99",
    ),
}


def replace_once(data, old, new, label):
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATOR_NEEDLE_COUNT %s count=%d" % (label, count))
    return data.replace(old, new, 1)


def remanifest(fixture_dir):
    lines = []
    for name in DATA_FILES:
        with open(os.path.join(fixture_dir, name), "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        lines.append(digest.encode("ascii") + b"\t" + name.encode("ascii") + b"\n")
    with open(os.path.join(fixture_dir, "sha256.txt"), "wb") as handle:
        handle.write(b"".join(lines))


def flip_hex(value):
    return ("0" if value[:1] != "0" else "1") + value[1:]


def mutate(fixture_src, target, name, prod_src):
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    fixture_dir = os.path.join(target, "fixture")
    prod_dir = os.path.join(target, "prod")
    shutil.copytree(fixture_src, fixture_dir)
    os.makedirs(prod_dir)
    with open(prod_src, "rb") as handle:
        raw = handle.read()
    # Scratch copies need not preserve line endings; normalize so multi-line needles cannot miss.
    prod = raw.replace(b"\r\n", b"\n")

    if name in PROD_MUTANTS:
        old, new = PROD_MUTANTS[name]
        prod = replace_once(prod, old, new, name)
    elif name == "byte-drift":
        path = os.path.join(fixture_dir, "cases.tsv")
        with open(path, "rb") as handle:
            data = handle.read()
        data = replace_once(data, b"C001\tI400\t", b"C001\tI401\t", name)
        with open(path, "wb") as handle:
            handle.write(data)
    elif name == "sha-drift":
        path = os.path.join(fixture_dir, "sha256.txt")
        with open(path, "rb") as handle:
            data = handle.read()
        first = data.split(b"\n", 1)[0]
        old = first[:64]
        if len(old) != 64:
            raise SystemExit("MUTATOR_MANIFEST_SHAPE")
        data = replace_once(data, old, flip_hex(old.decode("ascii")).encode("ascii"), name)
        with open(path, "wb") as handle:
            handle.write(data)
    elif name == "digest-drift":
        path = os.path.join(fixture_dir, "dimensions.tsv")
        with open(path, "rb") as handle:
            lines = handle.read().decode("ascii").splitlines()
        old = next(line.split("\t", 1)[1] for line in lines if line.startswith("cases_digest\t"))
        with open(path, "wb") as handle:
            handle.write(("\n".join(lines) + "\n").replace(old, flip_hex(old), 1).encode("ascii"))
        remanifest(fixture_dir)
    elif name == "corrupt-relax":
        path = os.path.join(fixture_dir, "corrupt.tsv")
        with open(path, "rb") as handle:
            data = handle.read()
        old = b"X01\tINVALID_STATUS\t" + b"49616263"
        new = b"X01\tINVALID_STATUS\t" + b"49343034"
        data = replace_once(data, old, new, name)
        with open(path, "wb") as handle:
            handle.write(data)
        remanifest(fixture_dir)
    elif name == "privacy-leak":
        path = os.path.join(fixture_dir, "report-copy.txt")
        scheme = "http" + chr(0x3a) + chr(0x2f) + chr(0x2f) + "scratch.invalid/x"
        with open(path, "wb") as handle:
            handle.write(scheme.encode("ascii") + b"\n")
    else:
        raise SystemExit("MUTATOR_UNKNOWN=" + name)

    with open(os.path.join(prod_dir, PROD_NAME), "wb") as handle:
        handle.write(prod)
    print("MUTATED\t" + name)


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: mutate.py <fixture-src> <target> <mutant> <prod-src>")
    mutate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
