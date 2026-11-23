#!/usr/bin/env python3
"""MV-03 prerequisite probe: does the app source expose the two-tile UI contract?

This is a deterministic, bounded, host-side check. It reads only
app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt and reports whether the
user-visible hooks required by the compiled MV-03 instrumentation exist. It never
contacts a device, a provider or the network.

Exit 0 means the hooks are present. Exit 1 means the MV-02 prerequisite is absent
and the device acceptance suite must be reported BLOCKED, not PASS.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
ACTIVITY = ROOT / "app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt"

# Labels are the stable, user-visible contract asserted by the instrumentation.
REQUIRED = (
    'button("Multiview: off")',
    '"Multiview: on"',
    'Video tile ${index + 1}, empty',
    'Video tile ${index + 1}, audible',
    'Two tiles are open',
    'Sound switched to the focused tile',
    'fun toggleMultiview()',
    'tileController.stop()',
)


def main() -> int:
    if not ACTIVITY.is_file():
        print(f"FAIL\tmv03-app-contract\tMainActivity missing at {ACTIVITY}")
        return 1
    source = ACTIVITY.read_text(encoding="utf-8")
    missing = [token for token in REQUIRED if token not in source]
    if missing:
        print(
            "FAIL\tmv03-app-contract\t"
            f"missing {len(missing)}/{len(REQUIRED)} two-tile UI hooks: "
            + "; ".join(missing)
        )
        return 1
    print(f"PASS\tmv03-app-contract\tall {len(REQUIRED)} two-tile UI hooks present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
