#!/usr/bin/env python3
"""Deterministic large EPG fixture generator for TunerOS QA-02 / PERF-01.

Produces a 25,000-channel M3U playlist and a 250,000-programme XMLTV guide
that fit exactly inside the existing core parser caps. Everything is
synthetic: stream URLs use the reserved .invalid TLD, no network access is
performed, no credential, real provider URL or media byte is embedded.

The generator streams to disk with small buffers and refuses to write more
than MAX_TOTAL_BYTES. Output goes to a caller-supplied directory and is not
checked in; only this generator and its expected manifest are versioned.
"""

from __future__ import annotations

import argparse
import calendar
import hashlib
import os
import sys
import time

SEED = 20261123
CHANNELS = 25_000
NO_EPG_CHANNELS = 250
EPG_CHANNELS = CHANNELS - NO_EPG_CHANNELS          # 24,750
PROGRAMS = 250_000
EXTRA_SLOT_CHANNELS = 2_500                       # first 2,500 get 11 shows, rest 10
SLOT_SECONDS = 1_800                              # 30 minute programmes
ANCHOR_EPOCH = calendar.timegm((2030, 1, 1, 0, 0, 0))
MAX_TOTAL_BYTES = 128 * 1024 * 1024               # hard output ceiling
SEARCH_INDEX = 1
NO_EPG_FIRST_INDEX = EPG_CHANNELS

TITLES = (
    "Morning Bulletin", "City Desk", "World Report", "Evening Magazine",
    "Technical Notes", "Documentary Hour", "Live Session", "Weekend Review",
    "Health Today", "Market Watch", "Science Weekly", "Local Spotlight",
)
GROUPS = ("QA-Fixture News", "QA-Fixture Culture", "QA-Fixture Sport", "QA-Fixture Kids")
DESCRIPTION = "Synthetic QA programme. Contains no real content."


def mix(value: int) -> int:
    """Fixed 64-bit avalanche; stable across Python versions and architectures."""
    x = (value ^ SEED) & 0xFFFFFFFFFFFFFFFF
    x = (x * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 29
    x = (x * 0xBF58476D1CE4E5B9) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 31
    x = (x * 0x94D049BB133111EB) & 0xFFFFFFFFFFFFFFFF
    return x ^ (x >> 33)


def xmltime(epoch: int) -> str:
    return time.strftime("%Y%m%d%H%M%S", time.gmtime(epoch)) + " +0000"


def show_count(index: int) -> int:
    # 1 wholly past, 1 crossing the anchor, the rest strictly future.
    tail = 9 if index < EXTRA_SLOT_CHANNELS else 8
    return 2 + tail


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_playlist(path: str) -> int:
    written = 0
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        def emit(line: str) -> None:
            nonlocal written
            data = (line + "\n").encode("utf-8")
            written += len(data)
            if written > MAX_TOTAL_BYTES:
                raise RuntimeError("playlist exceeded the output byte ceiling")
            out.write(line + "\n")

        emit("#EXTM3U")
        for index in range(CHANNELS):
            name = "Fixture News %05d" % index
            group = GROUPS[mix(index * 4 + 1) % len(GROUPS)]
            emit('#EXTINF:-1 tvg-id="qa.ch.%d" group-title="%s",%s'
                 % (index, group, name))
            emit("https://qa.invalid/live/channel-%05d.m3u8" % index)
    return written


def write_guide(path: str) -> int:
    written = 0
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        def emit(line: str) -> None:
            nonlocal written
            data = (line + "\n").encode("utf-8")
            written += len(data)
            if written > MAX_TOTAL_BYTES:
                raise RuntimeError("guide exceeded the output byte ceiling")
            out.write(line + "\n")

        emit('<?xml version="1.0" encoding="UTF-8"?>')
        emit("<tv>")
        for index in range(CHANNELS):
            emit('<channel id="qa.ch.%d"><display-name>Fixture News %05d</display-name></channel>'
                 % (index, index))
        emitted = 0
        for index in range(EPG_CHANNELS):
            # Slot 0 is wholly past (ends at the anchor); slot 1 is current at the
            # anchor and every later slot is strictly future.
            first = ANCHOR_EPOCH - SLOT_SECONDS
            for slot in range(show_count(index)):
                start = first + slot * SLOT_SECONDS
                stop = start + SLOT_SECONDS
                title = TITLES[mix(index * 131 + slot * 17 + 7) % len(TITLES)]
                emit('<programme start="%s" stop="%s" channel="qa.ch.%d"><title>%s</title>'
                     '<desc>%s</desc></programme>'
                     % (xmltime(start), xmltime(stop), index, title, DESCRIPTION))
                emitted += 1
        emit("</tv>")
    if emitted != PROGRAMS:
        raise RuntimeError("programme count %d != %d" % (emitted, PROGRAMS))
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="directory for generated fixture files")
    args = parser.parse_args()
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    playlist = os.path.join(out_dir, "playlist.m3u")
    guide = os.path.join(out_dir, "guide.xml")
    manifest = os.path.join(out_dir, "manifest.properties")

    playlist_bytes = write_playlist(playlist)
    guide_bytes = write_guide(guide)
    total = playlist_bytes + guide_bytes
    if total > MAX_TOTAL_BYTES:
        raise RuntimeError("combined fixture %d bytes exceeds %d" % (total, MAX_TOTAL_BYTES))

    search_name = "Fixture News %05d" % SEARCH_INDEX
    values = {
        "schema": "tunos-qa-epg-1",
        "seed": SEED,
        "channels": CHANNELS,
        "programs": PROGRAMS,
        "epg_channels": EPG_CHANNELS,
        "no_epg_channels": NO_EPG_CHANNELS,
        "anchor_epoch": ANCHOR_EPOCH,
        "slot_seconds": SLOT_SECONDS,
        "search_name": search_name,
        "search_guid": "qa.ch.%d" % SEARCH_INDEX,
        "no_epg_guid": "qa.ch.%d" % NO_EPG_FIRST_INDEX,
        "future_expected_search": show_count(SEARCH_INDEX) - 1,
        "future_expected_other": show_count(EXTRA_SLOT_CHANNELS) - 1,
        "playlist_sha256": sha256_of(playlist),
        "guide_sha256": sha256_of(guide),
        "playlist_bytes": playlist_bytes,
        "guide_bytes": guide_bytes,
        "max_total_bytes": MAX_TOTAL_BYTES,
        # Measured locally at ~160 MiB sampled peak for this fixture; 384 MiB leaves
        # CI headroom while still failing a ~2x allocation regression.
        "max_heap_bytes": 384 * 1024 * 1024,
        "max_rss_kb": 1024 * 1024,
    }
    with open(manifest, "w", encoding="utf-8", newline="\n") as out:
        for key, value in values.items():
            out.write("%s=%s\n" % (key, value))

    print("QA fixture generated: channels=%d programs=%d epg_channels=%d no_epg_channels=%d"
          % (CHANNELS, PROGRAMS, EPG_CHANNELS, NO_EPG_CHANNELS))
    print("anchor_epoch=%d total_bytes=%d limit=%d" % (ANCHOR_EPOCH, total, MAX_TOTAL_BYTES))
    print("playlist_sha256=%s" % values["playlist_sha256"])
    print("guide_sha256=%s" % values["guide_sha256"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
