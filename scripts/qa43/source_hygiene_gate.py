#!/usr/bin/env python3
"""QA-43 deterministic offline source privacy/secret-hygiene gate.

Scans COMMITTED app/core Kotlin/Java sources and tests for regressions of the
SEC-01 (bare credential assignment in a shareable summary) and CORE-49/SEC-04
(hostile URI token / diagnostic sink) kinds. It never edits a scanned file.

Modes:
  scan   -- check committed sources against the contract; exit 0 only on clean
  probe  -- mutation control on a scratch copy; exit 0 only if every mutant is
            rejected with its pinned first finding code and committed bytes are
            unchanged

Diagnostics carry check ids, counts and hashes only: never matched text.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "qa" / "fixtures" / "source-hygiene43"
OWNED = (
    Path("scripts/qa43"),
    Path("qa/fixtures/source-hygiene43"),
    Path("app/src/test/java/com/devtunasoft/tuneros/qa43"),
)
SOURCE_DIRS = ("app/src", "core/src")
SUFFIXES = (".kt", ".java")
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')

# The canary value is derived at run time from a fixed seed. It is never written
# as a literal into source, fixtures, reports or chat.
SEED = b"qa43-source-hygiene-canary-seed-v1"
CANARY_PREFIX = "QA43SYNTH-"


def canary():
    return CANARY_PREFIX + hashlib.sha256(SEED).hexdigest()[:8]


def emit(check, verdict, detail=""):
    line = "CHECK\t%s\t%s" % (check, verdict)
    if detail:
        line += "\t" + str(detail)
    print(line)


def read_lines(path):
    return [line for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def load_contract():
    patterns = []
    for line in read_lines(FIXTURES / "contract.tsv"):
        parts = line.split("\t")
        if len(parts) != 4:
            raise ValueError("CONTRACT_SHAPE")
        pid, source, scope, _description = parts
        try:
            compiled = re.compile(source)
        except re.error as exc:
            raise ValueError("CONTRACT_REGEX:" + type(exc).__name__)
        patterns.append((pid, compiled, scope))
    return patterns


def load_expectations():
    result = {}
    for line in read_lines(FIXTURES / "expectations.tsv"):
        if "=" not in line:
            raise ValueError("EXPECTATIONS_SHAPE")
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def load_manifest():
    manifest = {}
    for line in read_lines(FIXTURES / "sha256.txt"):
        parts = line.split()
        if len(parts) != 2:
            raise ValueError("MANIFEST_SHAPE")
        manifest[parts[1]] = parts[0]
    return manifest


def committed_sources():
    try:
        proc = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "--"] + list(SOURCE_DIRS),
            capture_output=True, text=True, check=False, timeout=60,
        )
    except (OSError, subprocess.SubprocessError):
        raise ValueError("GIT_UNAVAILABLE")
    if proc.returncode != 0:
        raise ValueError("GIT_FAILED")
    return sorted(
        line for line in proc.stdout.splitlines()
        if line.endswith(SUFFIXES)
    )


def scan_text(text, patterns, is_test):
    """Return [(code, line_number)] with no matched content."""
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        for literal in STRING_LITERAL.findall(line):
            for code, regex, scope in patterns:
                if scope == "test" and not is_test:
                    continue
                if regex.search(literal):
                    findings.append((code, number))
    return findings


def aggregate_digest():
    digest = hashlib.sha256()
    for rel in sorted(str(p.relative_to(ROOT)).replace("\\", "/")
                      for owned in OWNED for p in (ROOT / owned).rglob("*")
                      if p.is_file() and "__pycache__" not in p.parts
                      and p.suffix != ".pyc"):
        data = (ROOT / rel).read_bytes()
        digest.update(rel.encode("utf-8") + b"\0" + len(data).to_bytes(8, "big") + data)
    return digest.hexdigest(), len([
        p for owned in OWNED for p in (ROOT / owned).rglob("*")
        if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"
    ])


def check_manifest():
    try:
        manifest = load_manifest()
    except ValueError as exc:
        emit("FIXTURE_MANIFEST", "FAIL", str(exc))
        return False
    bad = []
    for name, expected in sorted(manifest.items()):
        path = FIXTURES / name
        if not path.is_file():
            bad.append(name + ":absent")
            continue
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            bad.append(name + ":digest")
    emit("FIXTURE_MANIFEST", "PASS" if not bad and manifest else "FAIL",
        "files=%d bad=%d" % (len(manifest), len(bad)))
    return not bad and bool(manifest)


def mode_scan(_args):
    ok = check_manifest()
    try:
        patterns = load_contract()
        expected = load_expectations()
    except ValueError as exc:
        emit("CONTRACT_SHAPE", "FAIL", str(exc))
        return 1
    ids = [item[0] for item in patterns]
    contract_ok = (
        len(patterns) == int(expected["patterns"])
        and ids == ["CREDENTIAL_ASSIGNMENT", "URI_CREDENTIAL_TOKEN",
                    "HOSTILE_URI_CONTROL", "PRIVATE_FILE_PATH",
                    "DRM_KEY_LITERAL", "CANARY_MARKER"]
    )
    emit("CONTRACT_SHAPE", "PASS" if contract_ok else "FAIL",
        "patterns=%d expected=%s" % (len(patterns), expected.get("patterns")))
    ok = ok and contract_ok

    try:
        files = committed_sources()
    except ValueError as exc:
        emit("SOURCE_SCOPE", "BLOCKED", str(exc))
        return 2
    scope_ok = len(files) >= int(expected["min_files"])
    emit("SOURCE_SCOPE", "PASS" if scope_ok else "FAIL",
        "files=%d min=%s app=%d core=%d" % (
            len(files), expected.get("min_files"),
            sum(f.startswith("app/") for f in files),
            sum(f.startswith("core/") for f in files)))
    ok = ok and scope_ok

    counts = {pid: 0 for pid in ids}
    locations = []
    for rel in files:
        data = (ROOT / rel).read_bytes()
        text = data.decode("utf-8", errors="replace")
        is_test = "/test/" in rel
        for code, number in scan_text(text, patterns, is_test):
            counts[code] += 1
            if len(locations) < int(expected["max_reported"]):
                locations.append("%s:%d:%s" % (rel, number, code))
    findings = sum(counts.values())
    clean = findings == 0
    emit("PATTERN_SCAN", "PASS" if clean else "FAIL",
        "findings=%d files=%d patterns=%d codes=%s" % (
            findings, len(files), len(patterns),
            ",".join("%s=%d" % (k, counts[k]) for k in ids)))
    for item in locations:
        print("LOCATION\t" + item)
    ok = ok and clean
    print("SCAN\t%s\tfiles=%d\tpatterns=%d\tfindings=%d" % (
        "PASS" if ok else "FAIL", len(files), len(patterns), findings))
    return 0 if ok else 1


def mode_probe(args):
    ok = True
    before, owned_count = aggregate_digest()
    print("DIGEST\tBEFORE\t%s\tfiles=%d" % (before, owned_count))

    try:
        expected = load_expectations()
        patterns = load_contract()
        substrate = (FIXTURES / "clean-sample.kt.txt").read_bytes()
        mutants = [line.split("\t") for line in read_lines(FIXTURES / "mutants.tsv")]
        if any(len(row) != 3 for row in mutants):
            raise ValueError("MUTANTS_SHAPE")
    except (ValueError, OSError) as exc:
        emit("PROBE_INPUT", "BLOCKED", type(exc).__name__)
        return 2

    scratch = Path(args.scratch).resolve() if args.scratch else Path(tempfile.gettempdir())
    work = scratch / ("qa43-probe-" + str(os.getpid()))
    probe_file = work / "mutant.kt.txt"
    try:
        work.mkdir(parents=True, exist_ok=True)
        probe_file.write_bytes(substrate)
        base = scan_text(probe_file.read_text(encoding="utf-8"), patterns, False)
        emit("PROBE_BASELINE", "PASS" if not base else "FAIL",
            "findings=%d" % len(base))
        ok = ok and not base

        expected_mutants = int(expected["mutants"])
        ok = ok and len(mutants) == expected_mutants
        for mid, template, wanted in mutants:
            needle = template.replace("{CANARY}", canary())
            probe_file.write_bytes(substrate + b"\n" + needle.encode("utf-8") + b"\n")
            found = scan_text(probe_file.read_text(encoding="utf-8"), patterns, False)
            first = found[0][0] if found else "NONE"
            passed = bool(found) and first == wanted
            print("PROBE\tMUTANT\t%s\texpect=%s\tfirst=%s\t%s" % (
                mid, wanted, first, "PASS" if passed else "FAIL"))
            ok = ok and passed

        probe_file.write_bytes(substrate)
        restored = scan_text(probe_file.read_text(encoding="utf-8"), patterns, False)
        emit("PROBE_RESTORE", "PASS" if not restored else "FAIL",
            "findings=%d" % len(restored))
        ok = ok and not restored
    finally:
        shutil.rmtree(work, ignore_errors=True)

    after, _ = aggregate_digest()
    same = before == after
    emit("COMMITTED_BYTES", "PASS" if same else "FAIL",
        "before=%s after=%s" % (before, after))
    ok = ok and same
    print("PROBE\t%s\tmutants=%d\tcanary=DERIVED" % (
        "PASS" if ok else "FAIL", len(mutants)))
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("scan", "probe"))
    parser.add_argument("--scratch", default="", help="native scratch directory for probe mode")
    args = parser.parse_args()
    try:
        return mode_scan(args) if args.mode == "scan" else mode_probe(args)
    except ValueError as exc:
        print("GATE\tBLOCKED\t%s" % type(exc).__name__)
        return 2


if __name__ == "__main__":
    sys.exit(main())
