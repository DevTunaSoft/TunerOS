#!/usr/bin/env python3
"""QA-54 scratch mutation driver. Copies fixtures and production sources to scratch and
applies exactly one byte-level mutation. Nothing outside the scratch target is written."""

import os
import shutil
import sys

SEED = 20261124
MIX = (SEED * 6364136223846793005 + 1442695040888963407) & 0x7FFFFFFFFFFFFFFF
TOKEN = "qa54mk" + format(MIX, "016x")


def replace_once(path, old, new):
    with open(path, "rb") as fh:
        data = fh.read()
    count = data.count(old)
    if count != 1:
        raise SystemExit("MUTATOR_NEEDLE_COUNT path=%s count=%d" % (path, count))
    with open(path, "wb") as fh:
        fh.write(data.replace(old, new, 1))


def flip_hex(value):
    return ("0" if value[:1] != "0" else "1") + value[1:]


def mutate(fixture_src, target, name, prod_src):
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    fixture_dir = os.path.join(target, "fixture")
    prod_dir = os.path.join(target, "prod")
    shutil.copytree(fixture_src, fixture_dir)
    shutil.copytree(prod_src, prod_dir)
    fpath = lambda n: os.path.join(fixture_dir, n)
    ppath = lambda n: os.path.join(prod_dir, n)

    if name == "byte-drift":
        replace_once(fpath("channels.tsv"), b"Name-0000", b"Name-X000")
    elif name == "sha-drift":
        with open(fpath("sha256.txt"), "rb") as fh:
            first = fh.readline().decode("ascii").strip()
        old_hash = first[:64]
        if len(old_hash) != 64:
            raise SystemExit("MUTATOR_MANIFEST_SHAPE")
        replace_once(fpath("sha256.txt"), old_hash.encode("ascii"), flip_hex(old_hash).encode("ascii"))
    elif name == "digest-drift":
        with open(fpath("contract.txt"), "rb") as fh:
            lines = fh.read().decode("ascii").splitlines()
        old = next(line.split("=", 1)[1] for line in lines if line.startswith("mirror_digest="))
        replace_once(fpath("contract.txt"), old.encode("ascii"), flip_hex(old).encode("ascii"))
    elif name == "header-drop":
        replace_once(fpath("channels.tsv"), b"id\tname\tstableKey\tgroup", b"id\tname\tgroup\tstableKey")
    elif name == "field-extra":
        with open(fpath("cases.tsv"), "rb") as fh:
            lines = fh.read().split(b"\n")
        hits = [line for line in lines if line.startswith(b"T00\t")]
        if len(hits) != 1:
            raise SystemExit("MUTATOR_CASE_ROW_COUNT=%d" % len(hits))
        replace_once(fpath("cases.tsv"), hits[0], hits[0] + b"\textra")
    elif name == "case-count":
        replace_once(fpath("contract.txt"), b"case_count=48", b"case_count=47")
    elif name == "corrupt-relax":
        replace_once(
            fpath("corrupt.tsv"),
            b"C02\tEMPTY_ID\t\tName-9002\tk9002\tg00",
            b"C02\tEMPTY_ID\tc9002\tName-9002\tk9002\tg00",
        )
    elif name == "privacy-leak":
        replace_once(fpath("report-copy.txt"), b"check=PASS\n", ("check=PASS\n" + TOKEN + "\n").encode("ascii"))
    elif name == "prod-blank-guard":
        replace_once(
            ppath("FavoriteChannels.kt"),
            b"if (channelId.isBlank()) return favorites",
            b"if (false) return favorites",
        )
    elif name == "prod-add-only":
        replace_once(
            ppath("FavoriteChannels.kt"),
            b"if (channelId in favorites) favorites - channelId else favorites + channelId",
            b"favorites + channelId",
        )
    elif name == "prod-filter-invert":
        replace_once(
            ppath("FavoriteChannels.kt"),
            b"it.storageKey in favorites",
            b"it.storageKey !in favorites",
        )
    else:
        raise SystemExit("MUTATOR_UNKNOWN=" + name)
    print("MUTATED\t%s" % name)


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: mutate.py <fixture-src> <target> <mutant> <prod-src>")
    mutate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
