package com.devtunasoft.tuneros.qa30

/**
 * Android-free contract shared by the QA-30 host gate and its instrumentation mirror.
 * It describes a bounded admission check for a 206 partial-content byte-range reply.
 * No provider address, credential or media content is part of this contract.
 */
object Qa30Contract {
    const val VERSION = 1
    const val SEED = 20261123
    const val CASE_CAP = 96
    const val CORRUPT_CAP = 6
    const val MAX_HEADER_CHARS = 96

    const val ACCEPT = "ACCEPT"
    const val REJECT = "REJECT"

    const val G_ACCEPT = "ACCEPT"
    const val G_OFFSET = "REJECT_OFFSET"
    const val G_END = "REJECT_END"
    const val G_TOTAL = "REJECT_TOTAL"
    const val G_MALFORMED = "REJECT_MALFORMED"
    const val G_NULL = "REJECT_NULL"
    const val G_OVERFLOW = "REJECT_OVERFLOW"
    const val G_RANGE_ARGS = "REJECT_RANGE_ARGS"

    val GROUPS = listOf(
        G_ACCEPT, G_OFFSET, G_END, G_TOTAL,
        G_MALFORMED, G_NULL, G_OVERFLOW, G_RANGE_ARGS,
    )

    /** Distinct process exit codes, one per named failure. Mutants pin these exactly. */
    const val EXIT_PRIVACY_FIXTURE = 21
    const val EXIT_HEADER_STRICT = 22
    const val EXIT_FIELD_BOUNDS = 23
    const val EXIT_CASE_CAP = 24
    const val EXIT_DIMENSIONS = 25
    const val EXIT_MANIFEST_MATCH = 26
    const val EXIT_BYTE_IDENTITY = 27
    const val EXIT_GUARD_ACCEPT = 28
    const val EXIT_GUARD_NULL = 29
    const val EXIT_GUARD_NEGATIVE_OFFSET = 30
    const val EXIT_GUARD_ZERO_LENGTH = 31
    const val EXIT_GUARD_OVERFLOW = 32
    const val EXIT_GUARD_TOTAL = 33
    const val EXIT_GUARD_MATCH_ENTIRE = 34
    const val EXIT_GUARD_FROM = 35
    const val EXIT_GUARD_TO = 36
    const val EXIT_ACCEPT_EXACT = 37
    const val EXIT_REJECT_OFFSET = 38
    const val EXIT_REJECT_END = 39
    const val EXIT_REJECT_TOTAL = 40
    const val EXIT_REJECT_MALFORMED = 41
    const val EXIT_REJECT_NULL = 42
    const val EXIT_REJECT_OVERFLOW = 43
    const val EXIT_REJECT_RANGE_ARGS = 44
    const val EXIT_CORRUPT_REJECT = 45
    const val EXIT_LAX_ACCEPTS_CORRUPT = 46
    const val EXIT_LEAK_DETECTOR = 47
    const val EXIT_GOLDEN_DIGEST = 48
    const val EXIT_HEAP_BOUND = 49
    const val EXIT_THREAD_BOUND = 50

    val EXIT_BY_CHECK = mapOf(
        "PRIVACY_FIXTURE" to EXIT_PRIVACY_FIXTURE,
        "HEADER_STRICT" to EXIT_HEADER_STRICT,
        "FIELD_BOUNDS" to EXIT_FIELD_BOUNDS,
        "CASE_CAP" to EXIT_CASE_CAP,
        "DIMENSIONS" to EXIT_DIMENSIONS,
        "MANIFEST_MATCH" to EXIT_MANIFEST_MATCH,
        "BYTE_IDENTITY" to EXIT_BYTE_IDENTITY,
        "GUARD_ACCEPT" to EXIT_GUARD_ACCEPT,
        "GUARD_NULL" to EXIT_GUARD_NULL,
        "GUARD_NEGATIVE_OFFSET" to EXIT_GUARD_NEGATIVE_OFFSET,
        "GUARD_ZERO_LENGTH" to EXIT_GUARD_ZERO_LENGTH,
        "GUARD_OVERFLOW" to EXIT_GUARD_OVERFLOW,
        "GUARD_TOTAL" to EXIT_GUARD_TOTAL,
        "GUARD_MATCH_ENTIRE" to EXIT_GUARD_MATCH_ENTIRE,
        "GUARD_FROM" to EXIT_GUARD_FROM,
        "GUARD_TO" to EXIT_GUARD_TO,
        "ACCEPT_EXACT" to EXIT_ACCEPT_EXACT,
        "REJECT_OFFSET" to EXIT_REJECT_OFFSET,
        "REJECT_END" to EXIT_REJECT_END,
        "REJECT_TOTAL" to EXIT_REJECT_TOTAL,
        "REJECT_MALFORMED" to EXIT_REJECT_MALFORMED,
        "REJECT_NULL" to EXIT_REJECT_NULL,
        "REJECT_OVERFLOW" to EXIT_REJECT_OVERFLOW,
        "REJECT_RANGE_ARGS" to EXIT_REJECT_RANGE_ARGS,
        "CORRUPT_REJECT" to EXIT_CORRUPT_REJECT,
        "LAX_ACCEPTS_CORRUPT" to EXIT_LAX_ACCEPTS_CORRUPT,
        "LEAK_DETECTOR" to EXIT_LEAK_DETECTOR,
        "GOLDEN_DIGEST" to EXIT_GOLDEN_DIGEST,
        "HEAP_BOUND" to EXIT_HEAP_BOUND,
        "THREAD_BOUND" to EXIT_THREAD_BOUND,
    )

    /** Prose for the committed contract.txt fixture; deliberately free of sensitive tokens. */
    val CONTRACT_LINES = listOf(
        "QA-30 bounded partial-content admission contract",
        "version=1 seed=20261123 cap=96 header_chars=96 corrupt=6",
        "A reply is admitted only when its range field names the exact requested start and end.",
        "A wildcard total is allowed; a numeric total must exceed the stated end.",
        "A missing field, wrong unit, trailing text, internal marker or oversized field is refused.",
        "A non-positive length, negative start or arithmetic overflow is refused before parsing.",
        "Scratch identifiers and counts are invented; no provider address or content bytes are stored.",
    )
}
