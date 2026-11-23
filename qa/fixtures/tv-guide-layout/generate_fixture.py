#!/usr/bin/env python3
"""Generate the QA-01 synthetic guide fixtures from the canonical templates.

The templates live next to the instrumented runner in app/src/androidTest/assets
so the Python fixture and the on-device fixture have one source of truth. No
provider URL, credential, real media or clock-dependent count is used: the
fixture shape is always 3 channels and 9 programmes; only the half-hour anchor
and the loopback port are substituted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "app/src/androidTest/assets/tv-guide-layout"
MAX_FIXTURE_BYTES = 8192
HALF_HOUR = 1800
CHANNELS = ("qa.layout.one", "qa.layout.two", "qa.layout.three")


def xml_time(epoch: int) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y%m%d%H%M%S")


def render(template: Path, values: dict[str, str]) -> str:
    result = template.read_text(encoding="utf-8")
    for key, value in values.items():
        result = result.replace("{{" + key + "}}", value)
    if "{{" in result:
        raise ValueError("unsubstituted token in " + template.name)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True, help="loopback port")
    parser.add_argument("--anchor", type=int, default=None,
                        help="fixed window-start epoch seconds (default: current half hour)")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port out of range")
    window = (args.anchor if args.anchor is not None else int(datetime.now(tz=timezone.utc).timestamp()))
    window -= window % HALF_HOUR

    values = {
        "PORT": str(args.port),
        "T_MINUS": xml_time(window - HALF_HOUR),
        "T_NOW": xml_time(window),
        "T_PLUS": xml_time(window + HALF_HOUR),
        "T_LATER": xml_time(window + 3 * HALF_HOUR),
    }
    files = {
        "playlist.m3u": render(ASSETS / "playlist.m3u.tmpl", values),
        "guide.xml": render(ASSETS / "guide.xml.tmpl", values),
    }
    total = sum(len(value.encode("utf-8")) for value in files.values())
    if total > MAX_FIXTURE_BYTES:
        raise SystemExit(f"fixture exceeds {MAX_FIXTURE_BYTES} bytes: {total}")

    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {
        "anchor_epoch_seconds": window,
        "port": args.port,
        "channels": len(CHANNELS),
        "programmes": 9,
        "total_bytes": total,
        "sha256": {},
    }
    for name, content in sorted(files.items()):
        (args.out / name).write_text(content, encoding="utf-8")
        manifest["sha256"][name] = hashlib.sha256(content.encode("utf-8")).hexdigest()
    (args.out / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"PASS qa01-fixture-generated channels=3 programmes=9 bytes={total} anchor={window}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
