# QA-63 — bounded GuideDoctor name-ranking host gate

Deterministic, offline, zero-new-dependency host gate over the real compiled
pure-Kotlin `core/GuideDoctor.kt::suggestions` name-ranking contract. No product,
Gradle, workflow, shared document or sibling gate path is edited.

## Slice and audit boundary

The slice is bounded ranking of guide-channel suggestions: score tiers 100/60/30,
quality-word and punctuation normalisation, an explicit `hasSchedule` admission,
ascending `limit`, and score/name/id tie-breaking.

Cross-branch audit scanned 445 local and remote refs (128 refs carrying QA files,
860 distinct QA blobs, 15,744,078 corpus bytes). QA-01..QA-62 were catalogued from
their README slice statements. QA-09 and QA-18 own the shareable `diagnose` /
`summaryText` reports; QA-02 mirrors `ChannelVisibility` search; QA-50 and QA-55
compile `XmltvProlog` behind `GuideParser`; QA-26 drives `DashManifest.expand` with
its own template probes. No audited gate references `GuideDoctor.suggestions`,
`qualityWords` or this ranking table. Zero-hit keywords in both the QA corpus and
the JVM unit-test corpus: `hideWithoutGuide`, `qualityWords`, `internal DTD
subsets`, `MAX_TEMPLATE_CHARS`. The JVM suite has small unit tests for ranking, but
no bounded deterministic fixture, pinned bytes, mutation control or instrumented
mirror covers it. NO_EQUIVALENT_GATE.

Exclusions: do not duplicate QA-09/QA-18 diagnostics, QA-02 large-EPG search,
QA-26 template expansion, QA-50/QA-55 import and prolog admission.

## What runs

`run.sh generate` writes six fixture files from fixed constants and pins their
SHA-256 values. `run.sh verify` regenerates byte-for-byte, checks the manifest and
pinned digests, parses the committed fixture, drives the real production object,
runs eleven negative controls, proves all owned/fixture/production bytes restored,
then runs 22 positive checks under a 96 MiB heap with a zero thread-drift bound.

Modes `verify-semantic` and `verify-digest` skip only the structural checks that
would otherwise mask a named mutant. Every RED control must exit nonzero and print
its pinned `FIRST` code.

## On-device mirror

`app/src/androidTest/java/com/devtunasoft/tuneros/qa63/` holds the Android-free
shared contract and a bounded `Instrumentation` runner; the manifest in
`app/src/androidTest` declares only QA-63's instrumentation entry. Sibling workers
own their own entries in the shared instrumentation manifest; merging those entries
is an integrator decision, not one made here. Device execution is not claimed.

## Privacy

All markers are derived at run time from a fixed numeric seed; no address,
credential, PIN, secret or recording payload appears as a literal in source,
fixtures or diagnostics. The external scanner checks owned text and scratch
reports, and the in-gate check scans emitted prose and the derived marker.
