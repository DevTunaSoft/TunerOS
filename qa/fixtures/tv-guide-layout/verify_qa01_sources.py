#!/usr/bin/env python3
"""Static acceptance gate for QA-01 (no Android SDK required).

Checks that the owned QA-01 sources exist, use no test dependencies, and carry
the required objective assertions. This is a source gate, NOT compiled-test or
device evidence. The real check is scripts/qa_tv_layout_check.sh.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
MANIFEST = ROOT / "app/src/androidTest/AndroidManifest.xml"
TEST = ROOT / "app/src/androidTest/java/com/devtunasoft/tuneros/TvGuideLayoutInstrumentation.kt"
PLAYLIST = ROOT / "app/src/androidTest/assets/tv-guide-layout/playlist.m3u.tmpl"
GUIDE = ROOT / "app/src/androidTest/assets/tv-guide-layout/guide.xml.tmpl"
GENERATOR = ROOT / "qa/fixtures/tv-guide-layout/generate_fixture.py"
RUNNER = ROOT / "scripts/qa_tv_layout_check.sh"

CHECKS = [
    "guide-loaded-vs-unloaded",
    "guide-row-bounds-no-clipping",
    "dpad-focus-transitions",
    "ok-select-to-play",
]
FORBIDDEN_IMPORTS = ("androidx.", "org.junit", "junit.framework", "kotlin.test")
SENSITIVE = ("password", "passwd", "apikey", "api_key", "secret=", "bearer ", "provider.example")


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def main() -> int:
    results = []
    manifest = text(MANIFEST)
    source = text(TEST)
    playlist = text(PLAYLIST)
    guide = text(GUIDE)
    generator = text(GENERATOR)
    runner = text(RUNNER)

    def check(name, ok, detail):
        results.append((name, bool(ok), detail))

    check("qa01-static-manifest",
          "TvGuideLayoutInstrumentation" in manifest and
          'android:targetPackage="com.devtunasoft.tuneros"' in manifest,
          "androidTest manifest declares the custom runner and target package")
    check("qa01-static-tests-present",
          source.count("private fun check") >= len(CHECKS) and all(name in source for name in CHECKS),
          "four named objective checks exist in the instrumentation source")
    check("qa01-static-dpad-and-ok",
          all(key in source for key in ("KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN",
                                        "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT",
                                        "KEYCODE_DPAD_CENTER")),
          "UP/DOWN/LEFT/RIGHT and OK key codes are injected")
    check("qa01-static-zero-test-dependencies",
          not any(word in source for word in FORBIDDEN_IMPORTS) and
          "android.app.Instrumentation" in source,
          "runner uses platform Instrumentation only, no AndroidX/JUnit test dependency")
    check("qa01-static-report",
          "qa-tv-layout-report.txt" in source and 'writeText' in source,
          "bounded per-check PASS/FAIL report is written to app-private files")
    check("qa01-static-templates",
          "{{PORT}}" in playlist and "{{T_NOW}}" in guide and
          guide.count("<programme") == 9 and guide.count("<channel") == 3,
          "loopback templates hold 3 channels, 9 programmes and time/port tokens")
    check("qa01-static-generator",
          "--anchor" in generator and "sha256" in generator and "8192" in generator,
          "fixture generator is anchored, hashed and size-bounded")
    check("qa01-static-runner",
          all(part in runner for part in ("assembleDebugAndroidTest", "am instrument",
                                          "adb devices", "NOT RUN", "verify_qa01_sources.py")),
          "bounded runner builds the test APK, requires a device and reports NOT RUN")

    owned = "\n".join(text(p) for p in (PLAYLIST, GUIDE, GENERATOR, RUNNER))
    check("qa01-static-no-sensitive-tokens",
          not any(token in owned.lower() for token in SENSITIVE),
          "no credential-like sentinel in fixtures or runner")

    for name, ok, detail in results:
        print(("PASS " if ok else "FAIL ") + name + " " + detail)
    failures = sum(1 for _, ok, _ in results if not ok)
    print(f"QA-01 STATIC GATE: {'PASS' if failures == 0 else 'FAIL'} "
          f"({len(results) - failures}/{len(results)} checks, source only)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
