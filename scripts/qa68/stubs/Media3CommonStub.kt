package androidx.media3.common

/** Minimal offline stand-in used only to compile the real PlaybackEvidence.kt in the QA-68 host gate. */
open class PlaybackException(message: String?, cause: Throwable?, errorCode: Int) : Exception(message, cause)
