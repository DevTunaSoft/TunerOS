#!/usr/bin/env python3
"""QA-42 deterministic offline bounded-playback/EPG soak gate.

Reads only committed synthetic fixtures in qa/fixtures/playback-soak42.
No device, network, product source, media, credential or provider data.
Modes: generate (one-shot fixture writer), normal (PASS/FAIL gate),
mutant (skip byte-identity so a semantic marker failure is observable).
Exit 0 PASS, 1 FAIL, 2 setup error.
"""
import argparse
import hashlib
import math
import os
import re
import sys

DATA_FILES = (
    "dimensions.tsv", "markers.tsv", "epg.tsv", "soak.tsv",
    "segments.tsv", "expectations.tsv",
)
ALL_FILES = DATA_FILES + ("sha256.txt",)

DIM_KEYS = (
    "schema.version", "epg.channels", "epg.programs_per_channel",
    "epg.slot_seconds", "epg.page_rows", "epg.max_cells",
    "epg.recycled_slots", "epg.sample_step", "epg.sample_count",
    "soak.steps", "soak.header_groups", "soak.max_live_players",
    "segment.arrivals", "timeshift.max_segments", "timeshift.max_bytes",
    "timeshift.oversize_bytes", "segment.base_bytes", "segment.byte_step",
    "segment.duration_ms",
)
MARKER_KEYS = (
    "evict_oldest_first", "byte_budget_enforced", "oversize_segment_refused",
    "sequence_monotonic", "playlist_live_no_end_tag",
    "reuse_requires_equal_headers", "no_reuse_while_recording",
    "finish_deletes_all",
)

EPG_HEADER = "channel_id\tchannel_name\tstart_epoch_ms\tseed"
SOAK_HEADER = (
    "step\tchannel\theaders_id\trecording\texpected_reuse\texpected_created"
    "\texpected_released\texpected_live"
)
SEGMENT_HEADER = (
    "seq\tduration_ms\tbytes\texpected_evictions\texpected_evict_first"
    "\texpected_retained_count\texpected_retained_bytes\texpected_oversize"
)

PRIVATE_PATTERNS = (
    re.compile(r"https?://"),
    re.compile(r"content://"),
    re.compile(r"(?i)(password|passwd|secret|api[_-]?key)\s*[=:]"),
)
CHANNEL_RE = re.compile(r"ch[0-9]{4}")
NAME_RE = re.compile(r"QA Channel [0-9]{4}")
HEADERS_RE = re.compile(r"H[0-7]")
UINT_RE = re.compile(r"[0-9]+")
INT_RE = re.compile(r"-?[0-9]+")

SAMPLE_CHANNELS = 8
SAMPLE_PAGES = 4
SAMPLE_OFFSETS = 8


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
        elif self.first == "NONE":
            self.fails += 1
            self.first = ident
        else:
            self.fails += 1
        print("CHECK\t%s\t%s\t%s" % ("PASS" if ok else "FAIL", ident, detail))


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load(path):
    name = os.path.basename(path)
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError:
        raise SetupError("missing-" + name)
    if not data or len(data) > 1024 * 1024:
        raise SetupError("size-" + name)
    if b"\r" in data:
        raise SetupError("CRLF-" + name)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise SetupError("utf8-" + name)
    lines = text.split("\n")
    while lines and lines[-1] == "":
        lines.pop()
    if not lines or len(lines) > 1024:
        raise SetupError("lines-" + name)
    return data, lines


def data_rows(lines, header, columns):
    rows = [line for line in lines if line and not line.startswith("#")]
    if not rows or rows[0] != header:
        raise SetupError("HEADER_STRICT")
    for line in rows[1:]:
        if len(line.split("\t")) != columns:
            raise SetupError("ROW_BOUNDS")
    return rows[1:]


def parse_pairs(lines, keys):
    pairs = {}
    order = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2 or not fields[0] or fields[0] in pairs:
            raise SetupError("PAIR_SHAPE")
        pairs[fields[0]] = fields[1]
        order.append(fields[0])
    if set(pairs) != set(keys):
        raise SetupError("PAIR_KEYS")
    for key in keys:
        if key.startswith("schema.") or key.startswith("digest."):
            continue
        if not pairs[key]:
            raise SetupError("PAIR_EMPTY")
    return pairs, order


def parse_dimensions(lines):
    pairs, _ = parse_pairs(lines, DIM_KEYS)
    if pairs["schema.version"] != "1":
        raise SetupError("SCHEMA_VERSION")
    dims = {}
    for key in DIM_KEYS:
        token = pairs[key]
        if not UINT_RE.fullmatch(token):
            raise SetupError("DIM_INT_" + key)
        dims[key] = int(token)
    if dims["epg.channels"] <= dims["epg.page_rows"] or dims["epg.recycled_slots"] >= dims["epg.page_rows"]:
        raise SetupError("DIM_BOUNDS")
    if dims["soak.steps"] <= 0 or dims["segment.arrivals"] <= 0:
        raise SetupError("DIM_BOUNDS")
    return dims


def parse_epg(lines):
    rows = []
    seen = set()
    for line in data_rows(lines, EPG_HEADER, 4):
        cid, name, start, seed = line.split("\t")
        if not CHANNEL_RE.fullmatch(cid) or not NAME_RE.fullmatch(name):
            raise SetupError("EPG_ID")
        if cid in seen or not UINT_RE.fullmatch(start) or not UINT_RE.fullmatch(seed):
            raise SetupError("EPG_ROW")
        seen.add(cid)
        rows.append((cid, name, int(start), int(seed)))
    if [row[0] for row in rows] != sorted(row[0] for row in rows):
        raise SetupError("EPG_ORDER")
    return rows


def parse_soak(lines):
    rows = []
    for line in data_rows(lines, SOAK_HEADER, 8):
        fields = line.split("\t")
        if not UINT_RE.fullmatch(fields[0]):
            raise SetupError("SOAK_STEP")
        if not HEADERS_RE.fullmatch(fields[2]):
            raise SetupError("SOAK_HEADERS")
        ints = []
        for token in (fields[1], fields[3], fields[4], fields[5], fields[6], fields[7]):
            if not UINT_RE.fullmatch(token):
                raise SetupError("SOAK_INT")
            ints.append(int(token))
        if ints[1] not in (0, 1) or ints[2] not in (0, 1):
            raise SetupError("SOAK_FLAG")
        rows.append((int(fields[0]), ints[0], fields[2], ints[1], ints[2], ints[3], ints[4], ints[5]))
    if [row[0] for row in rows] != list(range(len(rows))):
        raise SetupError("SOAK_SEQUENCE")
    return rows


def parse_segments(lines):
    rows = []
    previous = -1
    for line in data_rows(lines, SEGMENT_HEADER, 8):
        fields = line.split("\t")
        ints = []
        for token in fields:
            if not INT_RE.fullmatch(token):
                raise SetupError("SEGMENT_INT")
            ints.append(int(token))
        seq, duration_ms, size, evictions, first, retained, retained_bytes, oversize = ints
        if seq <= previous or duration_ms <= 0 or size < 0:
            raise SetupError("SEGMENT_RANGE")
        if evictions < 0 or retained < 0 or retained_bytes < 0 or oversize not in (0, 1):
            raise SetupError("SEGMENT_RANGE")
        if (evictions == 0) != (first == -1):
            raise SetupError("SEGMENT_EVICTION_SHAPE")
        previous = seq
        rows.append((seq, duration_ms, size, evictions, first, retained, retained_bytes, oversize))
    return rows


def expand_epg(channels, programs_per_channel, slot_seconds):
    rows = []
    for cid, _name, start, _seed in channels:
        for index in range(programs_per_channel):
            begin = start + index * slot_seconds * 1000
            rows.append((cid, index, begin, begin + slot_seconds * 1000,
                         "QA Program %s-%02d" % (cid, index)))
    return rows


def epg_rows_digest(rows):
    lines = ["%s\t%d\t%d\t%d\t%s" % row for row in rows]
    return digest("\n".join(lines))


def page_indices(channels, page_rows):
    for first in range(0, channels, page_rows):
        yield first, min(first + page_rows, channels)


def recycle_sample(channels, first, page_rows, slots):
    occupied = [0] * slots
    peak = 0
    reuse = 0
    for index in range(first, min(first + page_rows, channels)):
        slot = index % slots
        if occupied[slot] > 0:
            reuse += 1
        occupied[slot] += 1
        peak = max(peak, max(occupied))
    return peak, reuse


def replay_soak(steps):
    current = None
    created = 0
    released = 0
    live = 0
    reuse = 0
    peak = 0
    trace = []
    for step in steps:
        _index, _channel, headers, recording = step[:4]
        can_reuse = current is not None and recording == 0 and current == headers
        if can_reuse:
            reuse += 1
        else:
            if live:
                released += 1
                live -= 1
            created += 1
            live += 1
            current = headers
        peak = max(peak, live)
        trace.append((can_reuse, created, released, live))
    released += 1  # finish releases the last live player
    live -= 1
    return trace, created, released, peak, reuse, live


def replay_segments(segments, max_segments, max_bytes):
    window = []
    retained = 0
    repetitions = 0
    oversize = 0
    byte_evictions = 0
    count_evictions = 0
    peak_window = 0
    trace = []
    for seq, duration_ms, size, _e, _f, _c, _b, _o in segments:
        if size > max_bytes:
            oversize += 1
            evicted = [(seq, size)]
            repetitions += 1
        else:
            window.append((seq, size))
            retained += size
            evicted = []
            while len(window) > max_segments or retained > max_bytes:
                reason = "count" if len(window) > max_segments else "bytes"
                old = window.pop(0)
                retained -= old[1]
                evicted.append(old)
                if reason == "bytes":
                    byte_evictions += 1
                else:
                    count_evictions += 1
        peak_window = max(peak_window, len(window))
        first = evicted[0][0] if evicted else -1
        trace.append((len(evicted), first, len(window), retained, 1 if size > max_bytes else 0))
    return (window, retained, oversize, repetitions, trace,
            byte_evictions, count_evictions, peak_window)


def playlist_text(window, segment_rows):
    if not window:
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n"
    durations = {row[0]: row[1] for row in segment_rows}
    target = max(1, math.ceil(max(durations[seq] for seq, _size in window) / 1000.0))
    out = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % target,
           "#EXT-X-MEDIA-SEQUENCE:%d" % window[0][0]]
    for seq, _size in window:
        out.append("#EXTINF:%.3f," % (durations[seq] / 1000.0))
        out.append("seg-%d.ts" % seq)
    return "\n".join(out) + "\n"


def generate(target):
    dims = {
        "schema.version": "1",
        "epg.channels": "96",
        "epg.programs_per_channel": "48",
        "epg.slot_seconds": "1800",
        "epg.page_rows": "24",
        "epg.max_cells": "8",
        "epg.recycled_slots": "16",
        "epg.sample_step": "37",
        "epg.sample_count": str(SAMPLE_OFFSETS),
        "soak.steps": "96",
        "soak.header_groups": "8",
        "soak.max_live_players": "1",
        "segment.arrivals": "160",
        "timeshift.max_segments": "12",
        "timeshift.max_bytes": "105000",
        "timeshift.oversize_bytes": "150000",
        "segment.base_bytes": "4200",
        "segment.byte_step": "1700",
        "segment.duration_ms": "6000",
    }
    markers = {
        "evict_oldest_first": "1",
        "byte_budget_enforced": "1",
        "oversize_segment_refused": "1",
        "sequence_monotonic": "1",
        "playlist_live_no_end_tag": "1",
        "reuse_requires_equal_headers": "1",
        "no_reuse_while_recording": "1",
        "finish_deletes_all": "1",
    }
    channels = [
        ("ch%04d" % index, "QA Channel %04d" % index,
         1760000000000 + index * 60000, 7919 + index)
        for index in range(int(dims["epg.channels"]))
    ]
    epg_lines = [EPG_HEADER] + ["\t".join(str(field) for field in row) for row in channels]

    soak_lines = [SOAK_HEADER]
    current = None
    created = released = live = reuse = peak = 0
    for step in range(int(dims["soak.steps"])):
        channel = (step * 7) % int(dims["epg.channels"])
        headers = "H%d" % ((step // 3) % int(dims["soak.header_groups"]))
        recording = 1 if step % 11 == 0 else 0
        can_reuse = current is not None and recording == 0 and current == headers
        if can_reuse:
            reuse += 1
        else:
            if live:
                released += 1
                live -= 1
            created += 1
            live += 1
            current = headers
        peak = max(peak, live)
        soak_lines.append("\t".join(str(value) for value in (
            step, channel, headers, recording, int(can_reuse), created, released, live)))
    released += 1
    live -= 1

    segments = []
    seq = 1000
    max_segments = int(dims["timeshift.max_segments"])
    max_bytes = int(dims["timeshift.max_bytes"])
    window = []
    retained = 0
    eviction_total = 0
    byte_evictions = 0
    count_evictions = 0
    peak_window = 0
    for index in range(int(dims["segment.arrivals"])):
        size = (int(dims["timeshift.oversize_bytes"]) if index == 77
                else int(dims["segment.base_bytes"]) + (index % 7) * int(dims["segment.byte_step"]))
        if size > max_bytes:
            evicted = [(seq, size)]
        else:
            window.append((seq, size))
            retained += size
            evicted = []
            while len(window) > max_segments or retained > max_bytes:
                reason = "count" if len(window) > max_segments else "bytes"
                old = window.pop(0)
                retained -= old[1]
                evicted.append(old)
                if reason == "bytes":
                    byte_evictions += 1
                else:
                    count_evictions += 1
        peak_window = max(peak_window, len(window))
        eviction_total += len(evicted)
        first = evicted[0][0] if evicted else -1
        segments.append((seq, int(dims["segment.duration_ms"]), size,
                         len(evicted), first, len(window), retained, int(size > max_bytes)))
        seq += 1
    segment_lines = [SEGMENT_HEADER] + ["\t".join(str(field) for field in row) for row in segments]

    expanded = expand_epg(channels, int(dims["epg.programs_per_channel"]), int(dims["epg.slot_seconds"]))
    playlist = playlist_text(window, segments)
    expectations = {
        "epg.channels": str(len(channels)),
        "epg.programs": str(len(expanded)),
        "epg.pages": str(len(list(page_indices(len(channels), int(dims["epg.page_rows"]))))),
        "epg.digest": epg_rows_digest(expanded),
        "soak.steps": str(len(soak_lines) - 1),
        "soak.reuse": str(reuse),
        "soak.created": str(created),
        "soak.released": str(released),
        "soak.peak_live": str(peak),
        "segment.arrivals": str(len(segments)),
        "segment.evictions": str(eviction_total),
        "segment.oversize": "1",
        "segment.retained_count": str(len(window)),
        "segment.retained_bytes": str(retained),
        "segment.peak_retained_count": str(peak_window),
        "segment.byte_evictions": str(byte_evictions),
        "segment.count_evictions": str(count_evictions),
        "playlist.digest": digest(playlist),
        "markers.total": str(len(markers)),
    }

    os.makedirs(target, exist_ok=True)
    written = {
        "dimensions.tsv": [key + "\t" + dims[key] for key in DIM_KEYS],
        "markers.tsv": [key + "\t" + markers[key] for key in MARKER_KEYS],
        "epg.tsv": epg_lines,
        "soak.tsv": soak_lines,
        "segments.tsv": segment_lines,
        "expectations.tsv": [key + "\t" + expectations[key] for key in sorted(expectations)],
    }
    manifest = []
    for name in DATA_FILES:
        payload = ("\n".join(written[name]) + "\n").encode("utf-8")
        with open(os.path.join(target, name), "wb") as handle:
            handle.write(payload)
        manifest.append("%s\t%s" % (hashlib.sha256(payload).hexdigest(), name))
    with open(os.path.join(target, "sha256.txt"), "wb") as handle:
        handle.write(("\n".join(manifest) + "\n").encode("utf-8"))
    print("GENERATED\tfiles=%d\tepg=%d\tsoak=%d\tsegments=%d" % (
        len(ALL_FILES), len(expanded), len(soak_lines) - 1, len(segments)))
    return 0


def run_gate(fixtures, mode):
    report = Report()
    try:
        blobs = {}
        lines = {}
        for name in ALL_FILES:
            blobs[name], lines[name] = load(os.path.join(fixtures, name))
        dims = parse_dimensions(lines["dimensions.tsv"])
        markers, _ = parse_pairs(lines["markers.tsv"], MARKER_KEYS)
        expect, _ = parse_pairs(lines["expectations.tsv"], tuple(
            line.split("\t")[0] for line in lines["expectations.tsv"] if line and not line.startswith("#")))
        manifest, manifest_order = parse_pairs(lines["sha256.txt"], tuple(
            line.split("\t")[0] for line in lines["sha256.txt"] if line and not line.startswith("#")))
        channels = parse_epg(lines["epg.tsv"])
        steps = parse_soak(lines["soak.tsv"])
        segments = parse_segments(lines["segments.tsv"])
    except SetupError as error:
        print("SETUP\tFAIL\t%s" % error)
        return 2
    except (KeyError, ValueError, IndexError):
        print("SETUP\tFAIL\tEXPECTATION_PARSE")
        return 2

    if mode == "normal":
        by_name = {value: key for key, value in manifest.items()}
        ok = (len(manifest_order) == len(DATA_FILES) and len(by_name) == len(DATA_FILES) and all(
            by_name.get(name, "").lower() == hashlib.sha256(blobs[name]).hexdigest()
            for name in DATA_FILES))
        report.emit(ok, "FIXTURE_SHA256", "digests=%d" % len(DATA_FILES))
    else:
        report.emit(all(os.path.isfile(os.path.join(fixtures, name)) for name in DATA_FILES),
                    "MANIFEST_SCOPE", "files=%d" % len(DATA_FILES))

    report.emit(dims["schema.version"] == 1 and len(dims) == len(DIM_KEYS),
                "DIMENSIONS_SCHEMA", "keys=%d" % len(dims))
    bad_markers = [key for key in MARKER_KEYS if markers.get(key) != "1"]
    report.emit(not bad_markers and int(expect["markers.total"]) == len(MARKER_KEYS),
                "POLICY_MARKER", "count=%d bad=%s" % (
                    len(MARKER_KEYS), ",".join(bad_markers) if bad_markers else "none"))

    report.emit(len(channels) == dims["epg.channels"] and
                len(steps) == dims["soak.steps"] and
                len(segments) == dims["segment.arrivals"],
                "INPUT_COUNTS", "epg=%d soak=%d segments=%d" % (
                    len(channels), len(steps), len(segments)))

    expanded = expand_epg(channels, dims["epg.programs_per_channel"], dims["epg.slot_seconds"])
    report.emit(len(expanded) == dims["epg.channels"] * dims["epg.programs_per_channel"],
                "EPG_PROGRAM_TOTAL", "programs=%d" % len(expanded))
    by_channel = {}
    for row in expanded:
        by_channel.setdefault(row[0], []).append(row)
    for index in range(SAMPLE_CHANNELS):
        cid = channels[index * (len(channels) // SAMPLE_CHANNELS)][0]
        rows = by_channel[cid]
        contiguous = all(
            rows[i][2] + dims["epg.slot_seconds"] * 1000 == rows[i + 1][2]
            for i in range(len(rows) - 1))
        report.emit(contiguous and len(rows) == dims["epg.programs_per_channel"],
                    "EPG_CONTIGUOUS", "channel=%s rows=%d" % (cid, len(rows)))
    pages = list(page_indices(len(channels), dims["epg.page_rows"]))
    for index, (first, last) in enumerate(pages[:SAMPLE_PAGES]):
        report.emit(first == index * dims["epg.page_rows"] and last - first <= dims["epg.page_rows"],
                    "EPG_PAGE_BOUNDS", "page=%d rows=%d" % (index, last - first))
    limit = len(channels) - dims["epg.page_rows"]
    for index in range(SAMPLE_OFFSETS):
        first = (index * dims["epg.sample_step"]) % (limit + 1)
        peak, reuse = recycle_sample(len(channels), first, dims["epg.page_rows"], dims["epg.recycled_slots"])
        in_range = all(first <= pos < first + dims["epg.page_rows"] for pos in range(first, min(first + dims["epg.page_rows"], len(channels))))
        report.emit(in_range and peak <= dims["epg.recycled_slots"] and reuse > 0,
                    "EPG_RECYCLE_BOUND", "offset=%d peak=%d reuse=%d" % (first, peak, reuse))
    report.emit(epg_rows_digest(expanded) == expect["epg.digest"],
                "EPG_DIGEST", "digest=%s" % expect["epg.digest"][:12])

    trace, created, released, peak, reuse, final_live = replay_soak(steps)
    for step, actual in zip(steps, trace):
        expected = (step[4], step[5], step[6], step[7])
        report.emit(actual == expected, "SOAK_REUSE_DECISION",
                    "step=%d headers=%s reuse=%d live=%d" % (step[0], step[2], actual[0], actual[3]))
    report.emit(created == released and final_live == 0,
                "SOAK_LEDGER", "created=%d released=%d live=%d" % (created, released, final_live))
    report.emit(peak == int(expect["soak.peak_live"]) and 0 < peak <= dims["soak.max_live_players"],
                "SOAK_PEAK_LIVE", "peak=%d cap=%d" % (peak, dims["soak.max_live_players"]))

    (window, retained, oversize, repetitions, seg_trace,
     byte_evictions, count_evictions, peak_window) = replay_segments(
        segments, dims["timeshift.max_segments"], dims["timeshift.max_bytes"])
    for segment, actual in zip(segments, seg_trace):
        expected = (segment[3], segment[4], segment[5], segment[6], segment[7])
        report.emit(actual == expected, "SEGMENT_ARRIVAL",
                    "seq=%d evict=%d retained=%d" % (segment[0], actual[0], actual[2]))
    report.emit(retained <= dims["timeshift.max_bytes"],
                "TIMESHIFT_BYTE_BOUND", "bytes=%d cap=%d" % (retained, dims["timeshift.max_bytes"]))
    report.emit(len(window) <= dims["timeshift.max_segments"] and
                len(window) == int(expect["segment.retained_count"]),
                "TIMESHIFT_SEGMENT_BOUND", "segments=%d cap=%d" % (
                    len(window), dims["timeshift.max_segments"]))
    report.emit(byte_evictions > 0 and count_evictions > 0 and
                peak_window == int(expect["segment.peak_retained_count"]) and
                peak_window == dims["timeshift.max_segments"] and
                byte_evictions == int(expect["segment.byte_evictions"]) and
                count_evictions == int(expect["segment.count_evictions"]),
                "BOTH_RETENTION_CAPS_BIND", "byte_evictions=%d count_evictions=%d peak=%d" % (
                    byte_evictions, count_evictions, peak_window))
    report.emit(oversize == int(expect["segment.oversize"]) and repetitions == oversize,
                "OVERSIZE_REFUSED", "oversize=%d refused=%d" % (oversize, repetitions))

    playlist = playlist_text(window, segments)
    report.emit("#EXT-X-ENDLIST" not in playlist and playlist.startswith("#EXTM3U\n"),
                "PLAYLIST_LIVE_NO_END", "lines=%d" % len(playlist.splitlines()))
    report.emit(digest(playlist) == expect["playlist.digest"],
                "PLAYLIST_DIGEST", "digest=%s" % expect["playlist.digest"][:12])
    deleted = len(window) + 1
    report.emit(deleted == int(expect["segment.retained_count"]) + 1 and len(window) > 0,
                "FINISH_DELETES_ALL", "deleted=%d" % deleted)
    coverage = (
        ("SOAK_REUSE_COVERAGE", reuse, int(expect["soak.reuse"])),
        ("SOAK_CREATED_COVERAGE", created, int(expect["soak.created"])),
        ("SOAK_RELEASED_COVERAGE", released, int(expect["soak.released"])),
        ("SEGMENT_EVICTION_COVERAGE", sum(row[3] for row in segments), int(expect["segment.evictions"])),
        ("SEGMENT_RETAINED_COVERAGE", len(window), int(expect["segment.retained_count"])),
        ("SEGMENT_BYTES_COVERAGE", retained, int(expect["segment.retained_bytes"])),
    )
    for ident, got, want in coverage:
        report.emit(got == want and got > 0, ident, "got=%d want=%d" % (got, want))
    corpus = "\n".join(blobs[name].decode("utf-8") for name in ALL_FILES)
    hits = [pattern.pattern for pattern in PRIVATE_PATTERNS if pattern.search(corpus)]
    report.emit(not hits, "PRIVACY_SCAN", "hits=%d" % len(hits))

    print("COUNTS\tepg=%d\tprograms=%d\tsoak=%d\treuse=%d\tsegments=%d\tevictions=%d" % (
        len(channels), len(expanded), len(steps), reuse, len(segments),
        sum(row[3] for row in segments)))
    print("FIRST\t%s" % report.first)
    print("SUMMARY\tchecks=%d\tpass=%d\tfail=%d\tfirst=%s\tverdict=%s" % (
        report.checks, report.passes, report.fails, report.first,
        "PASS" if report.fails == 0 else "FAIL"))
    return 0 if report.fails == 0 else 1


def main():
    parser = argparse.ArgumentParser(description="QA-42 bounded playback/EPG soak gate")
    parser.add_argument("--fixtures", required=True)
    parser.add_argument("--mode", choices=("generate", "normal", "mutant"), default="normal")
    args = parser.parse_args()
    if args.mode == "generate":
        return generate(args.fixtures)
    return run_gate(args.fixtures, args.mode)


if __name__ == "__main__":
    sys.exit(main())
