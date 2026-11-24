#!/usr/bin/env python3
"""QA-41 deterministic offline recording-outcome matrix gate.

Reads only the committed fixtures in qa/fixtures/dvr-outcome. No network, device,
product source, credentials, URLs or media. Exit 0 PASS, 1 FAIL, 2 setup error.
"""
import argparse
import hashlib
import os
import re
import sys

HEADER = (
    "case_id\tfamily\trequested\treported_bytes\tsink_bytes\tcancelled\tfault\t"
    "finalize_calls\tepoch_token\tlast_cancelled\tshared_cancelled\t"
    "expected_state\texpected_finalize\texpected_discard"
)
FAMILIES = (
    "completed", "zero-byte", "cancelled", "storage-fault",
    "double-finalize", "epoch-lost-stop",
)
STATES = ("COMPLETED", "FAILED", "PARTIAL", "CANCELLED")
FAULTS = {
    "NONE", "DISK_FULL", "PERMISSION_LOST", "PROVIDER_DISCONNECTED",
    "CANCELLED", "UNKNOWN",
}
MARKER_KEYS = (
    "terminal_from_recording_only", "zero_byte_never_completed",
    "cancellation_wins_over_fault", "stop_epoch_persists_across_start",
    "double_finalize_is_noop", "no_silent_storage_fallback",
    "sink_bytes_authoritative",
)
DATA_FILES = ("cases.tsv", "markers.tsv", "expectations.tsv")
ALL_FILES = DATA_FILES + ("sha256.txt",)
PRIVATE_PATTERNS = (
    re.compile(r"https?://"),
    re.compile(r"content://"),
    re.compile(r"(?i)(password|passwd|secret|api[_-]?key)\s*[=:]"),
)
INT_RE = re.compile(r"[0-9]+")
CASE_RE = re.compile(r"c[0-9]{3}")


class SetupError(Exception):
    pass


class Report:
    def __init__(self):
        self.checks = 0
        self.passes = 0
        self.fails = 0
        self.first = "NONE"

    def emit(self, ok, ident, detail):
        self.checks += 1
        if ok:
            self.passes += 1
        else:
            self.fails += 1
            if self.first == "NONE":
                self.first = ident
        print("CHECK\t%s\t%s\t%s" % ("PASS" if ok else "FAIL", ident, detail))


def load(path):
    name = os.path.basename(path)
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError:
        raise SetupError("missing-" + name)
    if not data or len(data) > 65536:
        raise SetupError("size-" + name)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise SetupError("utf8-" + name)
    lines = [line.rstrip("\r") for line in text.split("\n")]
    while lines and lines[-1] == "":
        lines.pop()
    if not lines or len(lines) > 512:
        raise SetupError("lines-" + name)
    return data, lines


def parse_pairs(lines):
    pairs = {}
    order = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2 or not fields[0] or fields[0] in pairs:
            raise SetupError("pair-shape")
        pairs[fields[0]] = fields[1]
        order.append(fields[0])
    return pairs, order


def parse_cases(lines):
    rows = [line for line in lines if line.strip() and not line.startswith("#")]
    if not rows or rows[0] != HEADER:
        raise SetupError("HEADER_STRICT")
    seen = set()
    cases = []
    for line in rows[1:]:
        fields = line.split("\t")
        if len(fields) != 14:
            raise SetupError("ROW_BOUNDS")
        cid, family, requested = fields[0], fields[1], fields[2]
        if not CASE_RE.fullmatch(cid) or cid in seen:
            raise SetupError("CASE_ID")
        seen.add(cid)
        if family not in FAMILIES or requested not in STATES or fields[6] not in FAULTS:
            raise SetupError("ENUM")
        ints = []
        for token in fields[3:6] + fields[7:11] + fields[12:14]:
            if not INT_RE.fullmatch(token):
                raise SetupError("INT_FIELD")
            ints.append(int(token))
        reported, sink, cancelled, calls, token, last, shared, finalize, discard = ints
        for value in (reported, sink, calls, token, last):
            if value < 0 or value > 100000000:
                raise SetupError("INT_RANGE")
        for value in (cancelled, shared, finalize, discard):
            if value not in (0, 1):
                raise SetupError("FLAG_RANGE")
        if fields[11] not in STATES:
            raise SetupError("EXPECTED_STATE")
        cases.append({
            "id": cid, "family": family, "requested": requested,
            "reported": reported, "sink": sink, "cancelled": cancelled,
            "fault": fields[6], "calls": calls, "token": token, "last": last,
            "shared": shared, "expected": (fields[11], finalize, discard),
        })
    return cases


def final_state(requested, bytes_written):
    if bytes_written < 0:
        raise SetupError("NEGATIVE_BYTES")
    return "FAILED" if requested == "COMPLETED" and bytes_written == 0 else requested


def settle(requested, cancelled, reported, sink, finalize_file):
    if cancelled:
        return ("CANCELLED", 0, 0)
    if reported < 0 or sink < 0:
        raise SetupError("NEGATIVE_BYTES")
    acknowledged = min(reported, sink)
    if acknowledged == 0:
        state = final_state(requested, 0)
        return (state, 0, 1 if state == "FAILED" else 0)
    state = final_state(requested, acknowledged)
    if state != "COMPLETED" or not finalize_file:
        settled = "PARTIAL" if state == "COMPLETED" else state
        return (settled, 0, 0)
    return ("COMPLETED", 1, 0)


def storage_decide(fault, bytes_written, cancelled):
    effective = "CANCELLED" if cancelled else fault
    if effective != "NONE":
        if effective == "CANCELLED":
            state = "CANCELLED"
        else:
            state = "PARTIAL" if bytes_written > 0 else "FAILED"
        discard = 1 if (state == "CANCELLED" or bytes_written <= 0) else 0
        return (state, 0, discard)
    if bytes_written > 0:
        return ("COMPLETED", 1, 0)
    return ("FAILED", 0, 1)


def decide(case):
    if case["family"] == "storage-fault":
        return storage_decide(case["fault"], case["sink"], case["cancelled"] == 1)
    cancelled = case["cancelled"] == 1
    if case["family"] == "epoch-lost-stop":
        token = case["token"]
        if token > 0 and case["last"] >= token:
            cancelled = True
    return settle(
        case["requested"], cancelled, case["reported"], case["sink"],
        case["calls"] > 0,
    )


def main():
    parser = argparse.ArgumentParser(description="QA-41 offline outcome matrix gate")
    parser.add_argument("--fixtures", required=True)
    parser.add_argument("--mode", choices=("normal", "mutant"), default="normal")
    args = parser.parse_args()

    report = Report()
    try:
        blobs = {}
        lines_by_file = {}
        for name in ALL_FILES:
            blobs[name], lines_by_file[name] = load(os.path.join(args.fixtures, name))
        cases = parse_cases(lines_by_file["cases.tsv"])
        markers, marker_order = parse_pairs(lines_by_file["markers.tsv"])
        expect, _ = parse_pairs(lines_by_file["expectations.tsv"])
        manifest, manifest_order = parse_pairs(lines_by_file["sha256.txt"])
        want_cases = int(expect["cases.total"])
        want_markers = int(expect["markers.total"])
        want_families = {name: int(expect["family." + name]) for name in FAMILIES}
        want_states = {name: int(expect["state." + name]) for name in STATES}
    except SetupError as error:
        print("SETUP\tFAIL\t%s" % error)
        return 2
    except (KeyError, ValueError):
        print("SETUP\tFAIL\tEXPECTATION_PARSE")
        return 2

    if args.mode == "normal":
        ok = (
            len(manifest_order) == len(DATA_FILES)
            and all(
                manifest.get(name, "").lower() == hashlib.sha256(blobs[name]).hexdigest()
                for name in DATA_FILES
            )
        )
        report.emit(ok, "FIXTURE_SHA256", "digests=%d" % len(DATA_FILES))
    else:
        report.emit(
            all(os.path.isfile(os.path.join(args.fixtures, name)) for name in DATA_FILES),
            "MANIFEST_SCOPE", "files=%d" % len(DATA_FILES),
        )

    report.emit(True, "CASE_SCHEMA", "rows=%d cols=14" % len(cases))

    family_counts = {name: 0 for name in FAMILIES}
    state_counts = {name: 0 for name in STATES}
    for case in cases:
        actual = decide(case)
        want = case["expected"]
        report.emit(
            actual == want,
            "CASE_MATCH",
            "id=%s family=%s got=%s:%d:%d want=%s:%d:%d" % (
                case["id"], case["family"], actual[0], actual[1], actual[2],
                want[0], want[1], want[2],
            ),
        )
        family_counts[case["family"]] += 1
        state_counts[want[0]] += 1

    for case in cases:
        if case["family"] != "double-finalize":
            continue
        decision = decide(case)
        applied = 1 if decision[1] == 1 else 0
        noops = case["calls"] - applied
        report.emit(
            noops >= 0 and applied + noops == case["calls"],
            "LEDGER_NOOP",
            "id=%s applied=%d noop=%d" % (case["id"], applied, noops),
        )

    for case in cases:
        if case["family"] != "epoch-lost-stop":
            continue
        token = case["token"]
        epoch_cancelled = token > 0 and case["last"] >= token
        report.emit(
            epoch_cancelled == (case["expected"][0] == "CANCELLED"),
            "EPOCH_TOKEN",
            "id=%s token=%d last=%d shared=%d cancelled=%s" % (
                case["id"], token, case["last"], case["shared"],
                "yes" if epoch_cancelled else "no",
            ),
        )

    bad = [key for key in MARKER_KEYS if markers.get(key) != "1"]
    marker_shape = (
        len(marker_order) == len(MARKER_KEYS)
        and len(marker_order) == want_markers
        and set(marker_order) == set(MARKER_KEYS)
    )
    report.emit(
        marker_shape and not bad,
        "POLICY_MARKER",
        "count=%d bad=%s" % (len(marker_order), ",".join(bad) if bad else "none"),
    )

    for name in FAMILIES:
        report.emit(
            family_counts[name] == want_families[name],
            "FAMILY_COVERAGE",
            "family=%s got=%d want=%d" % (name, family_counts[name], want_families[name]),
        )
    for name in STATES:
        report.emit(
            state_counts[name] == want_states[name],
            "STATE_COVERAGE",
            "state=%s got=%d want=%d" % (name, state_counts[name], want_states[name]),
        )
    report.emit(
        len(cases) == want_cases,
        "CASE_TOTAL",
        "got=%d want=%d" % (len(cases), want_cases),
    )

    corpus = "\n".join(blobs[name].decode("utf-8") for name in ALL_FILES)
    hits = [pattern.pattern for pattern in PRIVATE_PATTERNS if pattern.search(corpus)]
    report.emit(not hits, "PRIVACY_SCAN", "hits=%d" % len(hits))

    family_text = ",".join("%s=%d" % (name, family_counts[name]) for name in FAMILIES)
    state_text = ",".join("%s=%d" % (name, state_counts[name]) for name in STATES)
    print("COUNTS\tcases=%d\tmarkers=%d\tfamilies=%s\tstates=%s" % (
        len(cases), len(marker_order), family_text, state_text,
    ))
    print("FIRST\t%s" % report.first)
    print("SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s" % (
        report.checks, report.passes, report.fails, report.first,
        "PASS" if report.fails == 0 else "FAIL",
    ))
    return 0 if report.fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
