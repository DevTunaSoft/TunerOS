"""QA-69 mutation driver. All mutated files live in scratch; production is only ever copied."""
import hashlib
import os
import shutil
import sys

SEED = "20261124"
PROD_NAME = "RecordingScheduleStore.kt"


def marker():
    value = 2166136261
    for byte in SEED.encode("ascii"):
        value = ((value ^ byte) * 16777619) & 0xFFFFFFFF
    return "QA69-" + format(value, "08x")


def digest_tree(root):
    entries = []
    for base, dirs, files in os.walk(root):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(base, name)
            rel = os.path.relpath(path, root).replace("\\", "/")
            with open(path, "rb") as handle:
                entries.append(rel + "\t" + hashlib.sha256(handle.read()).hexdigest())
    return hashlib.sha256("\n".join(sorted(entries)).encode("ascii")).hexdigest()


def read_text(path):
    with open(path, "r", encoding="utf-8", newline="") as handle:
        return handle.read().replace("\r\n", "\n")


def write_text(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def replace_once(text, old, new):
    count = text.count(old)
    if count != 1:
        raise RuntimeError("needle count %d for %s" % (count, old[:48]))
    return text.replace(old, new)


def mutate_prod(path, name):
    text = read_text(path)
    if name == "corrupt-relax":
        text = replace_once(
            text,
            "require(entry.endEpochSeconds > entry.startEpochSeconds)",
            "// relaxed scratch mutant",
        )
    elif name == "prod-limit":
        text = replace_once(text, "require(limit in 1..128)", "require(limit in 0..128)")
    elif name == "prod-enum":
        text = replace_once(
            text,
            "if (afterId == null) \"state IN ('PREPARING','RECOVERING')\"",
            "if (afterId == null) \"state IN ('PREPARING','SCHEDULED')\"",
        )
    elif name == "prod-after":
        text = replace_once(
            text,
            "else \"state IN ('PREPARING','RECOVERING') AND id>?\"",
            "else \"state IN ('PREPARING','RECOVERING') AND id>=?\"",
        )
    elif name == "prod-keyfield":
        text = replace_once(
            text,
            "result += OrphanedPreparation(cursor.getString(0), cursor.getString(1))",
            "result += OrphanedPreparation(cursor.getString(0), cursor.getString(0))",
        )
    elif name == "prod-claim":
        text = replace_once(
            text,
            "\"id=? AND secure_source_key=? AND state IN ('PREPARING','RECOVERING')\"",
            "\"id=? AND state IN ('PREPARING','RECOVERING')\"",
        )
    elif name == "prod-delete":
        text = replace_once(
            text,
            "\"id=? AND secure_source_key=? AND state='RECOVERING'\"",
            "\"id=? AND secure_source_key=?\"",
        )
    else:
        raise RuntimeError("unknown production mutant " + name)
    write_text(path, text)


def mutate_fixture(directory, name):
    cases = os.path.join(directory, "cases.tsv")
    dimensions = os.path.join(directory, "dimensions.tsv")
    manifest = os.path.join(directory, "sha256.txt")
    if name == "byte-drift":
        write_text(cases, replace_once(read_text(cases), "scratch show 0000", "scratch show 0009"))
    elif name == "sha-drift":
        text = read_text(manifest)
        if not text or text[0] not in "0123456789abcdef":
            raise RuntimeError("manifest byte is not a hex digit")
        new = "0" if text[0] != "0" else "1"
        write_text(manifest, new + text[1:])
    elif name == "digest-drift":
        text = read_text(dimensions)
        lines = text.split("\n")
        for index, line in enumerate(lines):
            if line.startswith("CASES_DIGEST\t"):
                lines[index] = "CASES_DIGEST\t" + "0" * 64
                break
        else:
            raise RuntimeError("pinned digest missing")
        write_text(dimensions, "\n".join(lines))
        with open(dimensions, "rb") as handle:
            dim_hash = hashlib.sha256(handle.read()).hexdigest()
        with open(cases, "rb") as handle:
            case_hash = hashlib.sha256(handle.read()).hexdigest()
        write_text(manifest, dim_hash + "\tdimensions.tsv\n" + case_hash + "\tcases.tsv\n")
    elif name == "privacy-leak":
        write_text(os.path.join(directory, "leak.txt"), marker() + "\n")
    else:
        raise RuntimeError("unknown fixture mutant " + name)


def main(argv):
    action = argv[1]
    if action == "marker":
        print("MARKER\t" + marker())
        return 0
    if action == "golden":
        fixture, prod, golden = argv[2], argv[3], argv[4]
        shutil.rmtree(golden, ignore_errors=True)
        os.makedirs(os.path.join(golden, "fixture"))
        os.makedirs(os.path.join(golden, "prod"))
        shutil.copytree(fixture, os.path.join(golden, "fixture"), dirs_exist_ok=True)
        shutil.copyfile(prod, os.path.join(golden, "prod", PROD_NAME))
        print("GOLDEN\t" + digest_tree(golden))
        return 0
    if action == "mutate":
        name, fixture, prod, target = argv[2], argv[3], argv[4], argv[5]
        shutil.rmtree(target, ignore_errors=True)
        os.makedirs(os.path.join(target, "fixture"))
        os.makedirs(os.path.join(target, "prod"))
        shutil.copytree(fixture, os.path.join(target, "fixture"), dirs_exist_ok=True)
        copied_prod = os.path.join(target, "prod", PROD_NAME)
        shutil.copyfile(prod, copied_prod)
        if name in ("corrupt-relax", "prod-limit", "prod-enum", "prod-after",
                    "prod-keyfield", "prod-claim", "prod-delete"):
            mutate_prod(copied_prod, name)
        else:
            mutate_fixture(os.path.join(target, "fixture"), name)
        print("MUTATED\t" + name)
        return 0
    if action == "restore":
        target, golden = argv[2], argv[3]
        for sub in ("fixture", "prod"):
            shutil.rmtree(os.path.join(target, sub), ignore_errors=True)
            shutil.copytree(os.path.join(golden, sub), os.path.join(target, sub))
        actual = digest_tree(os.path.join(target, "fixture")) + "|" + digest_tree(os.path.join(target, "prod"))
        expected = digest_tree(os.path.join(golden, "fixture")) + "|" + digest_tree(os.path.join(golden, "prod"))
        if actual != expected:
            print("RESTORE_BAD\t" + actual)
            return 1
        print("RESTORE_OK\t" + actual)
        return 0
    print("BAD_ACTION\t" + action)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
