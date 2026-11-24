# QA-44 fixture — bounded timeshift ring filesystem seam

Deterministic scratch data for the QA-44 gate. All names, counts and markers are invented; no
provider address, credential, PIN or recording payload is present. The fixture is generated from
a fixed seed by `scripts/qa44/run.sh generate` and must be byte-identical on every regeneration.

Files: `contract.txt`, `dimensions.tsv`, `cases.tsv`, `playlist.txt`, `sha256.txt`.
Seven families x eight cases = 56 rows: safe create, safe delete, missing delete, corrupt names,
playlist replacement, delete failure and playlist rename failure. Corrupt names cover parent,
absolute, inner, dot, dotdot, empty, doubled separator and trailing separator forms; all must be
refused before a file is created outside the ring.

The pinned production digest is the committed `app/src/main/.../AndroidTimeshiftRing.kt` bytes;
the host gate checks it, and the instrumented mirror skips that host-only check.
