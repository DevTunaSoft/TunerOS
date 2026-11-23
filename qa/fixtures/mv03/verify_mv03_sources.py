#!/usr/bin/env python3
"""Deterministic host-side gate for the MV-03 acceptance suite.

Verifies the owned source files assert the full two-tile contract, that the runner is
registered in the instrumented APK, that the compiled test has no dependency on the
parallel MV-02 production classes, and that scratch fixtures contain no external URL
or credential. No device, network or Gradle invocation is involved.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
MANIFEST = ROOT / "app/src/androidTest/AndroidManifest.xml"
SOURCE = ROOT / "app/src/androidTest/java/com/devtunasoft/tuneros/MultiviewUiAcceptanceInstrumentation.kt"
TEMPLATES = ROOT / "app/src/androidTest/assets/mv03"

RUNNER = "com.devtunasoft.tuneros.MultiviewUiAcceptanceInstrumentation"
CHECKS = (
    "mv03-loopback-import",
    "mv03-two-bounded-tiles-shown",
    "mv03-third-tile-refused",
    "mv03-single-audible-tile",
    "mv03-tiles-released-cleanly",
    "mv03-relaunch-has-no-tiles",
)
ASSERTIONS = (
    'button == "Multiview: on"',
    '"Video tile 1, audible"',
    '"Video tile $slot, muted"',
    '"Two tiles are open"',
    '"Sound switched to the focused tile"',
    'shown.count { it.audible } == 1',
    'shown.none { it.muted }',
    'it.playerAttached == false',
    'shownTiles.none { it.slot == 3 }',
    'TARGET_ACTIVITY = "com.devtunasoft.tuneros.MainActivity"',
)
FORBIDDEN = ("password", "passwd", "apikey", "api_key", "secret", "Bearer ")


def fail(message: str) -> None:
    print("FAIL\tmv03-source-gate\t" + message)


def main() -> int:
    problems = []
    if not MANIFEST.is_file():
        problems.append("androidTest manifest is missing")
        manifest = ""
    else:
        manifest = MANIFEST.read_text(encoding="utf-8")
        if RUNNER not in manifest or 'android:targetPackage="com.devtunasoft.tuneros"' not in manifest:
            problems.append("manifest does not register the MV-03 runner for the app package")
    if not SOURCE.is_file():
        problems.append("MV-03 instrumentation source is missing")
        source = ""
    else:
        source = SOURCE.read_text(encoding="utf-8")
        for check in CHECKS:
            if '"' + check + '"' not in source:
                problems.append("check not asserted: " + check)
        for assertion in ASSERTIONS:
            if assertion not in source:
                problems.append("assertion missing: " + assertion)
        if "import com.devtunasoft.tuneros.multiview" in source:
            problems.append("test must not depend on parallel MV-02 production classes")
        if "Thread({ runChecks() }" not in source or "finish(" not in source:
            problems.append("runner must start its own thread and finish explicitly")
        if "REQUIRED_CHECKS = " + str(len(CHECKS)) not in source:
            problems.append("required check count is wrong")
    for name in ("playlist.m3u.tmpl", "guide.xml.tmpl"):
        path = TEMPLATES / name
        if not path.is_file():
            problems.append("fixture template missing: " + name)
            continue
        text = path.read_text(encoding="utf-8")
        lowered = text.lower()
        for token in FORBIDDEN:
            if token.lower() in lowered:
                problems.append(f"{name} contains a credential-like token: {token}")
        for url in re.findall(r"https?://[^\s\"']+", text):
            if not url.startswith("http://127.0.0.1:{{PORT}}/"):
                problems.append(f"{name} contains a non-loopback URL")
    if problems:
        for problem in problems:
            fail(problem)
        return 1
    print(f"PASS\tmv03-source-gate\t{len(CHECKS)} checks, registered runner, loopback-only fixtures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
