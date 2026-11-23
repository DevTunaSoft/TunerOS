#!/usr/bin/env python3
"""Render the MV-03 scratch fixtures. Only 127.0.0.1 URLs, no provider data."""

import argparse
import datetime
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3]
TEMPLATES = ROOT / "app/src/androidTest/assets/mv03"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--anchor", type=int, required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    def stamp(seconds: int) -> str:
        return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).strftime("%Y%m%d%H%M%S")

    values = {
        "PORT": str(args.port),
        "T_MINUS": stamp(args.anchor - 1800),
        "T_NOW": stamp(args.anchor),
        "T_PLUS": stamp(args.anchor + 1800),
    }
    target = pathlib.Path(args.out)
    target.mkdir(parents=True, exist_ok=True)
    for name in ("playlist.m3u.tmpl", "guide.xml.tmpl"):
        text = (TEMPLATES / name).read_text(encoding="utf-8")
        for key, value in values.items():
            text = text.replace("{{" + key + "}}", value)
        if "{{" in text:
            raise SystemExit("unsubstituted fixture token in " + name)
        (target / name.replace(".tmpl", "")).write_text(text, encoding="utf-8")
    print("MV-03 fixtures rendered: 3 scratch channels, 6 scratch programmes, 127.0.0.1 only")


if __name__ == "__main__":
    main()
