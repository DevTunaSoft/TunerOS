"""QA-47 scratch-only mutators. Never writes into the committed worktree."""
import os
import sys


def source_mutant(kind, src, dst):
    data = open(src, "rb").read()
    needles = {
        "corrupt": (b"STRICT_CORRUPT_ENABLED = true;", b"STRICT_CORRUPT_ENABLED = false;"),
        "privacy": (b"PRIVACY_ENABLED = true;", b"PRIVACY_ENABLED = false;"),
        "thread": (b"THREAD_SLACK = 2;", b"THREAD_SLACK = -5;"),
    }
    if kind == "order":
        # Preserve the file's own newline bytes.
        nl = b"\r\n" if b"\r\n" in data else b"\n"
        old = b"worker.execute(cleanup)" + nl + b"        worker.shutdown()"
        new = b"worker.shutdown()" + nl + b"        worker.execute(cleanup)"
    else:
        old, new = needles[kind]
    assert data.count(old) == 1, (kind, data.count(old))
    data = data.replace(old, new)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "wb").write(data)
    print("MUTATED_SOURCE", kind)


def byte_mutant(fixture, dst):
    os.makedirs(dst, exist_ok=True)
    for name in ["corrupt.tsv", "dimensions.txt", "expected.txt", "sha256.txt", "tasks.tsv"]:
        data = open(os.path.join(fixture, name), "rb").read()
        open(os.path.join(dst, name), "wb").write(data)
    path = os.path.join(dst, "tasks.tsv")
    data = open(path, "rb").read()
    needle = b"qa47m0003"
    assert data.count(needle) == 1, data.count(needle)
    i = data.index(needle) + len(b"qa47m")
    assert data[i:i + 1] == b"0"
    data = data[:i] + b"1" + data[i + 1:]
    open(path, "wb").write(data)
    print("MUTATED_BYTE tasks.tsv")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd in ("source", "order"):
        kind = "order" if cmd == "order" else sys.argv[2]
        if cmd == "order":
            source_mutant(kind, sys.argv[2], sys.argv[3])
        else:
            source_mutant(kind, sys.argv[3], sys.argv[4])
    elif cmd == "byte":
        byte_mutant(sys.argv[2], sys.argv[3])
    else:
        raise SystemExit("unknown mutator command")
