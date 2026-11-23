#!/usr/bin/env python3
"""QA-39 consolidated host gate: verify delivered QA gates still exist and agree.

Reads immutable git objects only. No network, no emulator, no product edit.
Report carries ids, counts and digests only. Synthetic markers only.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

MAX_OBJECT_BYTES = 131072
MAX_REPORT_LINES = 120
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^[0-9a-f]{64}$")
PRIVACY_PATTERNS = [
    re.compile(r"https?://"), re.compile(r"Bearer\s"), re.compile(r"password\s*="),
    re.compile(r"secret\s*="), re.compile(r"api[-_]?key\s*="), re.compile(r"credential\s*="),
]
MANAGED = ["gates.tsv", "blobs.tsv", "semantics.tsv", "hazard.tsv", "links.tsv"]
OVERLAY_NAME = "gates.tsv"
EXPECTED_GATES = ("QA31", "QA32", "QA33", "QA34", "QA35", "QA36", "QA37", "QA38")
MUTATION_FROM = "QA33\thermes/worker-c9-qa33\ta584c39df00e825bf68ff9ebae039fbc5dd6e51e\tDELIVERED\tMERGE-REVIEW-NEEDED\t1\t1\t4\t5"
MUTATION_TO = "QA33\thermes/worker-c9-qa33\ta584c39df00e825bf68ff9ebae039fbc5dd6e51e\tDELIVERED\tPASS\t1\t1\t4\t5"


def git(repo, *args):
    proc = subprocess.run(["git", "-C", repo, *args], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL)
    return proc.returncode, proc.stdout


class Report:
    def __init__(self):
        self.count = 0
        self.passes = 0
        self.fails = 0
        self.first = "NONE"
        self.lines = []

    def check(self, ident, ok, detail):
        if self.count >= MAX_REPORT_LINES - 8:
            raise RuntimeError("REPORT_BOUND_EXCEEDED")
        self.count += 1
        if ok:
            self.passes += 1
        else:
            self.fails += 1
            if self.first == "NONE":
                self.first = ident
        self.lines.append("CHECK\t%s\t%s\t%s" % ("PASS" if ok else "FAIL", ident, detail))

    def emit(self, extra=None):
        for line in self.lines:
            print(line)
        if extra:
            for line in extra:
                print(line)
        if self.fails:
            print("FIRST\t%s" % self.first)
        print("SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s\t%s" % (
            self.count, self.passes, self.fails, self.first,
            "FAIL" if self.fails else "PASS", " ".join(extra_fields)))


extra_fields = []


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_rows(path, ncols):
    rows = []
    with open(path, "rb") as fh:
        for raw in fh.read().decode("utf-8").splitlines():
            if not raw or raw.startswith("#"):
                continue
            cols = raw.split("\t")
            if len(cols) != ncols:
                raise ValueError("BAD_ROW_SHAPE")
            rows.append(cols)
    return rows


def blob_bytes(repo, sha, path):
    rc, data = git(repo, "cat-file", "blob", "%s:%s" % (sha, path))
    if rc != 0 or len(data) > MAX_OBJECT_BYTES:
        raise ValueError("BLOB_READ_FAIL_OR_TOO_LARGE")
    return data


def tree_entries(repo, sha, prefixes):
    rc, out = git(repo, "ls-tree", "-r", "-z", "--format=%(objectname) %(path)",
                  sha, "--", *prefixes)
    entries = {}
    for item in out.split(b"\0"):
        if item and b" " in item:
            oid, path = item.split(b" ", 1)
            entries[path.decode()] = oid.decode()
    return entries


def ref_sha(repo, name):
    rc, out = git(repo, "rev-parse", "--verify", "refs/heads/%s" % name)
    return out.decode().strip() if rc == 0 else None


def run_gate(repo, fixtures, overlay=None):
    report = Report()
    extra_fields.clear()

    def fixture(name):
        if overlay and name == OVERLAY_NAME and os.path.isfile(os.path.join(overlay, name)):
            return os.path.join(overlay, name)
        return os.path.join(fixtures, name)

    # Parsing failures are a bounded FAIL, not a crash.
    try:
        gates = read_rows(fixture("gates.tsv"), 9)
        blobs = read_rows(fixture("blobs.tsv"), 5)
        semantics = read_rows(fixture("semantics.tsv"), 4)
        hazards = read_rows(fixture("hazard.tsv"), 5)
        links = read_rows(fixture("links.tsv"), 3)
    except (OSError, ValueError, UnicodeDecodeError):
        report.check("F_PARSE", False, "fixture-parse-error")
        report.emit()
        return 1

    by_gate = {}
    for row in gates:
        by_gate[row[0]] = row
    blob_by_gate = {}
    for row in blobs:
        blob_by_gate.setdefault(row[0], []).append(row)
    hazard_by_gate = {row[0]: row for row in hazards}

    cache = {}

    def pinned(sha, path):
        key = (sha, path)
        if key not in cache:
            cache[key] = blob_bytes(repo, sha, path)
        return cache[key]

    delivered = [g for g in gates if g[3] == "DELIVERED"]
    for gate in gates:
        gid, branch, sha, status, verdict, hazard, markers, nscript, nfixture = gate
        low = gid.lower()
        if status != "DELIVERED":
            continue

        # Delivery identity and exact ref pin.
        ok = bool(SHA_RE.match(sha)) and ref_sha(repo, branch) == sha
        if ok:
            rc, _ = git(repo, "cat-file", "-e", "%s^{commit}" % sha)
            ok = rc == 0
        report.check("%s_COMMIT" % gid, ok, "ref-match=%s object=%s" % (
            "yes" if ref_sha(repo, branch) == sha else "no",
            "yes" if SHA_RE.match(sha) else "no"))

        # Exact owned file set at the pin.
        declared = blob_by_gate.get(gid, [])
        declared_paths = sorted(row[1] for row in declared)
        tree = tree_entries(repo, sha, ["scripts/%s/" % low, "qa/fixtures/%s/" % low])
        actual_paths = sorted(tree)
        sprefix = "scripts/%s/" % low
        fprefix = "qa/fixtures/%s/" % low
        expected_paths = sorted(set(
            [p for p in declared_paths if p.startswith(sprefix)] +
            [p for p in declared_paths if p.startswith(fprefix)]))
        ok = (actual_paths == expected_paths and len(declared_paths) ==
              int(nscript) + int(nfixture) and len(declared_paths) == len(set(declared_paths)))
        report.check("%s_FILESET" % gid, ok, "files=%d expected=%d scripts=%d fixtures=%d" % (
            len(actual_paths), int(nscript) + int(nfixture), int(nscript), int(nfixture)))

        # Blob identity, byte length and digest.
        bad_blob = bad_len = bad_digest = 0
        for row in declared:
            _, path, blob, want_digest, want_size = row
            try:
                data = pinned(sha, path)
            except ValueError:
                bad_blob += 1
                continue
            if tree.get(path, "") != blob:
                bad_blob += 1
            if len(data) != int(want_size):
                bad_len += 1
            if digest(data) != want_digest:
                bad_digest += 1
        report.check("%s_BLOBS" % gid, bad_blob == bad_len == bad_digest == 0,
                     "files=%d blob-bad=%d size-bad=%d sha-bad=%d" % (
                         len(declared), bad_blob, bad_len, bad_digest))

        # The sibling gate's own fixture manifest must agree with its pinned bytes.
        mismatched = 0
        entries = []
        try:
            manifest = pinned(sha, "qa/fixtures/%s/sha256.txt" % low).decode("utf-8")
            entries = []
            for line in manifest.splitlines():
                if not line.strip():
                    continue
                parts = line.split()
                if len(parts) != 2 or not DIGEST_RE.match(parts[0]):
                    raise ValueError("BAD_MANIFEST")
                entries.append((parts[0], parts[1].lstrip("*")))
            declared_names = {row[1].split("/")[-1]: row[1] for row in declared}
            for want, name in entries:
                path = declared_names.get(name)
                if not path or digest(pinned(sha, path)) != want:
                    mismatched += 1
            ok = len(entries) == int(nfixture) - 2 and mismatched == 0
        except (ValueError, KeyError, UnicodeDecodeError):
            ok, mismatched = False, -1
        report.check("%s_SIBLING_MANIFEST" % gid, ok, "rows=%d bad=%d" % (
            len(entries) if "entries" in locals() else 0, mismatched))

        # Every declared pinned ref is a real commit, distinct and full-length.
        ref_bad = 0
        ref_rows = []
        try:
            ref_rows = []
            ref_keys = []
            for line in pinned(sha, "qa/fixtures/%s/refs.tsv" % low).decode("utf-8").splitlines():
                if not line or line.startswith("#"):
                    continue
                cols = line.split("\t")
                if len(cols) < 2 or not SHA_RE.match(cols[1]):
                    raise ValueError("BAD_REF")
                ref_rows.append(cols[1])
                ref_keys.append(cols[0])
            for ref in ref_rows:
                rc, _ = git(repo, "cat-file", "-e", "%s^{commit}" % ref)
                if rc != 0:
                    ref_bad += 1
            ok = (len(ref_rows) >= 2 and len(ref_keys) == len(set(ref_keys)) and
                  len(set(zip(ref_keys, ref_rows))) == len(ref_keys) and ref_bad == 0)
        except (ValueError, UnicodeDecodeError):
            ok, ref_rows = False, []
        report.check("%s_REFS" % gid, ok, "refs=%d invalid=%d" % (len(ref_rows), ref_bad))

        # Declared verdict semantics in the delivered script and README.
        sem_bad = 0
        for row in semantics:
            if row[0] != gid:
                continue
            source, needle, count = row[1], row[2], int(row[3])
            try:
                data = pinned(sha, "scripts/%s/%s" % (low, source)).decode("utf-8")
                if data.count(needle) != count:
                    sem_bad += 1
            except (ValueError, UnicodeDecodeError):
                sem_bad += 1
        report.check("%s_SEMANTICS" % gid, sem_bad == 0, "needles-bad=%d" % sem_bad)

        # Hazard gates must still declare a non-PASS merge-review verdict.
        if hazard == "1":
            h = hazard_by_gate.get(gid)
            marker_ok = False
            if h:
                try:
                    text = pinned(sha, "qa/fixtures/%s/%s" % (low, h[1])).decode("utf-8")
                    marker_ok = (h[2] in text and int(h[4]) == int(markers) and
                                 text.count(h[2]) >= int(markers))
                except (ValueError, UnicodeDecodeError):
                    marker_ok = False
            run_text = ""
            readme_text = ""
            try:
                run_text = pinned(sha, "scripts/%s/run.sh" % low).decode("utf-8")
                readme_text = pinned(sha, "scripts/%s/README.md" % low).decode("utf-8")
            except (ValueError, UnicodeDecodeError):
                pass
            ok = bool(h and marker_ok and verdict == "MERGE-REVIEW-NEEDED" and
                      "MERGE-REVIEW-NEEDED; rc=3" in run_text and
                      "3 MERGE-REVIEW-NEEDED" in run_text and
                      "MERGE-REVIEW-NEEDED" in readme_text)
            report.check("%s_HAZARD_VERDICT" % gid, ok, "hazard=1 markers=%s declared=%s" % (
                markers, verdict))
        else:
            run_bytes = b""
            try:
                run_bytes = pinned(sha, "scripts/%s/run.sh" % low)
            except ValueError:
                pass
            needle = ("verdict=PASS; rc=0" if verdict == "PASS" else
                      "verdict=FAIL; rc=1" if verdict == "FAIL" else "never-match")
            ok = (gid not in hazard_by_gate and needle != "never-match" and
                  needle.encode() in run_bytes and b"HAZARD\\t" not in run_bytes)
            report.check("%s_NO_HAZARD" % gid, ok, "hazard=0 declared=%s" % verdict)

    # Cross-gate pins must point at the same deliveries this gate pins.
    link_bad = 0
    for gid, key, expected in links:
        parent = by_gate.get(gid)
        if not parent or parent[3] != "DELIVERED" or not SHA_RE.match(expected):
            link_bad += 1
            continue
        try:
            text = pinned(parent[2], "qa/fixtures/%s/refs.tsv" % gid.lower()).decode("utf-8")
            found = [line.split("\t")[1] for line in text.splitlines()
                     if line and not line.startswith("#") and line.split("\t")[0] == key]
            if len(found) != 1 or found[0] != expected:
                link_bad += 1
        except (ValueError, IndexError, UnicodeDecodeError):
            link_bad += 1
    report.check("CROSS_LINKS", link_bad == 0, "links=%d bad=%d" % (len(links), link_bad))

    # Every declared gate must be delivered; no pending placeholder may ride along.
    ok = (tuple(g[0] for g in gates) == EXPECTED_GATES and
          all(g[3] == "DELIVERED" for g in gates) and len(by_gate) == len(EXPECTED_GATES))
    report.check("ALL_GATES", ok, "declared=%d expected=%d" % (len(gates), len(EXPECTED_GATES)))

    # Privacy scan over committed fixture bytes and every emitted line.
    hits = 0
    for name in MANAGED + ["sha256.txt", ".gitattributes"]:
        try:
            with open(fixture(name), "rb") as fh:
                text = fh.read().decode("utf-8")
            for pattern in PRIVACY_PATTERNS:
                hits += len(pattern.findall(text))
        except OSError:
            hits += 1
    for line in report.lines:
        for pattern in PRIVACY_PATTERNS:
            hits += len(pattern.findall(line))
    report.check("PRIVACY", hits == 0, "patterns=%d files=%d" % (hits, len(MANAGED) + 2))

    # Manifest check last: a semantic mutation must be named FIRST.
    try:
        lines = open(fixture("sha256.txt"), encoding="utf-8").read().splitlines()
        entries = {}
        for line in lines:
            parts = line.split()
            if len(parts) != 2 or not DIGEST_RE.match(parts[0]):
                raise ValueError("BAD_MANIFEST")
            entries[parts[1]] = parts[0]
        ok = set(entries) == set(MANAGED)
        for name, want in entries.items():
            with open(fixture(name), "rb") as fh:
                if digest(fh.read()) != want:
                    ok = False
    except (OSError, ValueError, UnicodeDecodeError):
        ok = False
    report.check("M_MANIFEST", ok, "files=%d" % len(MANAGED))

    extra_fields.extend([
        "delivered=%d" % len(delivered),
        "pending=%d" % (len(gates) - len(delivered)),
        "hazard=%d" % sum(1 for g in delivered if g[5] == "1"),
    ])
    report.emit()
    return 0 if report.fails == 0 else 1


def verify(repo, fixtures):
    scratch_root = os.environ.get("QA39_SCRATCH", r"C:/Users/X1/AppData/Local/hermes/cache/scratch")
    work = tempfile.mkdtemp(prefix="qa39-verify-", dir=scratch_root)
    overlay = os.path.join(work, "overlay")
    os.makedirs(overlay)
    original = os.path.join(fixtures, OVERLAY_NAME)
    with open(original, "rb") as fh:
        before = fh.read()
    original_digest = digest(before)
    backup = os.path.join(work, "original.bak")
    shutil.copyfile(original, backup)
    text = before.decode("utf-8")
    if text.count(MUTATION_FROM) != 1:
        print("MUTATION\tFAIL\tneedle-count=%d" % text.count(MUTATION_FROM))
        return 1
    mutated = text.replace(MUTATION_FROM, MUTATION_TO, 1).encode("utf-8")
    target = os.path.join(overlay, OVERLAY_NAME)
    with open(target, "wb") as fh:
        fh.write(mutated)

    env = os.environ.copy()
    env["QA39_OVERLAY_DIR"] = overlay
    red = subprocess.run([sys.executable, os.path.abspath(__file__), "gate-only"],
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    red_out = red.stdout.decode("utf-8", "replace")
    red_first = "NONE"
    for line in red_out.splitlines():
        if line.startswith("FIRST\t"):
            red_first = line.split("\t", 1)[1]
    red_ok = red.returncode != 0 and red_first == "QA33_HAZARD_VERDICT" and "verdict=FAIL" in red_out
    print("RED\t%s\trc=%d\tfirst=%s" % ("PASS" if red_ok else "FAIL", red.returncode, red_first))

    shutil.copyfile(backup, target)
    with open(target, "rb") as fh:
        restored_digest = digest(fh.read())
    shutil.copyfile(backup, original)
    with open(original, "rb") as fh:
        after = fh.read()
    restore_ok = restored_digest == original_digest and digest(after) == original_digest
    print("RESTORE\t%s\tsha256=%s" % ("BYTE_IDENTICAL" if restore_ok else "FAIL", original_digest))

    green = subprocess.run([sys.executable, os.path.abspath(__file__), "gate-only"],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    green_out = green.stdout.decode("utf-8", "replace")
    green_ok = green.returncode == 0 and "verdict=PASS" in green_out
    green_checks = "NONE"
    for line in green_out.splitlines():
        if line.startswith("SUMMARY\t"):
            green_checks = line
    print("GREEN\t%s\t%s" % ("PASS" if green_ok else "FAIL", green_checks))
    shutil.rmtree(work, ignore_errors=True)
    if not (red_ok and restore_ok and green_ok):
        print("MUTATION\tFAIL")
        return 1
    print("MUTATION\tPASS\tsingle-scratch-mutation-detected-and-restored")
    return 0


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("gate-only", "verify"):
        print("usage: Qa39Gate.py gate-only|verify")
        return 2
    script_dir = os.path.dirname(os.path.abspath(__file__))
    rc, out = git(script_dir, "rev-parse", "--show-toplevel")
    if rc != 0:
        print("SETUP\tFAIL\trepo-not-found")
        return 2
    repo = out.decode().strip()
    fixtures = os.path.join(repo, "qa", "fixtures", "qa39")
    if not os.path.isdir(fixtures):
        print("SETUP\tFAIL\tfixtures-absent")
        return 2
    if sys.argv[1] == "verify":
        return verify(repo, fixtures)
    overlay = os.environ.get("QA39_OVERLAY_DIR")
    return run_gate(repo, fixtures, overlay)


if __name__ == "__main__":
    sys.exit(main())
