package com.devtunasoft.tuneros.core

/**
 * Length rules for a finished HTTP body. A clean end of stream does not prove a complete body: a server or proxy can
 * close early. Compressed or otherwise transformed encodings have no identity length, so they are not verified.
 */
object BodyLength {
    /** Whether a body that ended cleanly can be verified against its declared length. */
    enum class Verdict { COMPLETE, UNVERIFIED, TRUNCATED_OR_OVERRUN }

    /**
     * The identity length to verify against, or -1 when there is none. [declaredLength] is the Content-Length value,
     * or -1 when the server sent none. A non-identity [contentEncoding] has no identity length.
     */
    fun expectedLength(declaredLength: Long, contentEncoding: String?): Long =
        if (contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true)) declaredLength else -1L

    /** The verdict for a body that reached a clean end of stream after [bytesCopied] bytes. */
    fun verifyCleanEnd(expectedLength: Long, bytesCopied: Long): Verdict = when {
        expectedLength < 0L -> Verdict.UNVERIFIED
        bytesCopied == expectedLength -> Verdict.COMPLETE
        else -> Verdict.TRUNCATED_OR_OVERRUN
    }
}
