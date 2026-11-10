# TunerOS audit response — 2026-11-10

The following items were independently checked against `main` as of `80fb008`. Status describes source implementation, not acceptance.

| # | Finding | Disposition |
|---|---|---|
| 1 | EPG/search UI stalls | **FIX IN SOURCE, CI PENDING** — sorted per-channel binary search, worker current-program cache, virtualized ListView, 160 ms search debounce |
| 2 | Missing XMLTV timezone silently dropped | **FIX IN SOURCE, CI PENDING** — parse timezone-less timestamps as UTC, warn; provider-specific timezone setting still needed |
| 3 | Caps discard entire import | **FIX IN SOURCE, CI PENDING** — partial import and visible warning for programme and channel caps |
| 4 | readLine allocates oversized line | **FIX IN SOURCE, CI PENDING** — bounded character-by-character reader |
| 5 | URLs stored plaintext | **FIX IN SOURCE, DEVICE UNVERIFIED** — Android Keystore AES-GCM and legacy preference migration |
| 6 | HTTPS redirect downgrade | **FIX IN SOURCE, CI PENDING** — reject HTTPS to HTTP redirects |
| 7 | Gzip detected only by headers/suffix | **FIX IN SOURCE, CI PENDING** — magic-byte sniffing |
| 8 | Multiview test implies audio switching | **NOT IMPLEMENTED** — policy validation only, actual audio controller future work |
| 9 | Fuzz evidence overstated | **DOC CORRECTED** — seeded smoke distinguished from coverage-guided fuzzing; added JUnit randomized checks and downloader tests |
| 10 | Missing launcher icon/banner | **FIX IN SOURCE, DEVICE UNVERIFIED** — placeholder vector resources; final brand assets pending |
| 11 | No Gradle wrapper | **OPEN** — Gradle 8.9 installed in CI, local install still required |

## Verified earlier evidence
- 2026-11-10 baseline [Actions 42737399473](https://github.com/DevTunaSoft/TunerOS/actions/runs/42737399473): core unit tests, debug APK assembly, artifact upload **PASS**.
- Intermediate runs for stabilization failed because `boundChannels` still expected an exception after changing to partial import, and a UTC warning used `Z` rather than `UTC`. Both were addressed in subsequent commits.
- **NOT RUN:** Real Android TV and phone validation, source-URL encryption/migration on hardware, UI search benchmark, real IPTV network fault injection.

## Security limits
- User-supplied HTTP sources remain supported for compatibility; they are not encrypted on the wire. HTTPS-to-HTTP redirect downgrade is blocked.
- Android Keystore protects saved source URLs, not secrets contained in stream URLs while playing. Do not include source URLs in public logs.
- XMLTV missing-timezone interpretation is a documented UTC fallback, not a claim of provider-local correctness.
- Record truncation can omit later programmes and channels; warnings are displayed rather than silently discarding the import.
- FastChannels server remains deferred. No paid playlists or provider credentials are bundled.

## Acceptance gates
1. Latest `main` passes `:core:test :app:assembleDebug` and uploads debug APK.
2. Real Android TV remote navigation and phone touch testing.
3. Profile large 25k-channel playlists and 250k-programme EPG imports; no main-thread stalls.
4. Device test encrypted URL save/reopen/migration, redirect/gzip failure recovery and app lifecycle.
5. Reconcile remaining roadmap work and update this document with actual evidence.
