# Third-party source provenance

TunerOS may adapt compatible open-source code when it provides a better tested foundation than reimplementing the same behavior.

## Egeniq Android TV Program Guide

- Project: https://github.com/egeniq/android-tv-program-guide
- Upstream license: Apache License 2.0
- Upstream copyright notice: Copyright (c) 2020, Egeniq
- TunerOS usage: timeline normalization concepts used by `core/.../TimelineLayout.kt`.
- TunerOS changes: converted to a pure-Kotlin epoch-seconds model; removed Android UI, ThreeTenABP and Leanback dependencies from the core model; added immutable timeline entries, explicit window clipping, deterministic overlap handling and TunerOS regression tests.
- We have not imported the complete upstream library. Its Android-TV focus/navigation implementation remains a candidate for selective adaptation during the UI phase.

The Apache-2.0 attribution header is retained in the adapted source file. If additional upstream files are copied or substantially adapted, record each source here before release.
