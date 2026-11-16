package com.devtunasoft.tuneros.core

/** One fixed, credential-free diagnostic for a Media3 PlaybackException numeric error code. */
data class StreamDiagnosis(val issue: String, val nextStep: String)

/** How a request failed below HTTP. Each label is fixed; no exception message is kept. */
enum class TransportFault(val label: String) {
    DNS("DNS lookup failed"),
    TIMEOUT("connection timed out"),
    REFUSED("connection refused"),
    TLS("TLS handshake failed"),
}

/**
 * Facts a failed request revealed, read by the Android layer. Nothing here can carry a URL, a header
 * value, a response body or an exception message.
 */
data class StreamEvidence(
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val transport: TransportFault? = null,
)

/**
 * Pure Kotlin decision table. No Android dependency, network request, background thread,
 * exception-message access, stream URL, or provider header handling.
 *
 * Codes follow androidx.media3.common.PlaybackException.
 */
object StreamDoctor {
    fun diagnose(code: Int): StreamDiagnosis = when (code) {
        -102 -> StreamDiagnosis(
            "Provider sign-in expired", "Sign in to your authorized provider account again",
        )
        -103 -> StreamDiagnosis(
            "Provider premium access needed", "Check that your subscription includes this channel",
        )
        -105 -> StreamDiagnosis(
            "Parental restriction", "Check the provider's parental control settings",
        )
        -108 -> StreamDiagnosis(
            "Playback setup required", "Complete any setup requested by the provider",
        )
        -104 -> StreamDiagnosis(
            "Provider stream limit", "Stop another stream or check your provider's limits",
        )
        -106 -> StreamDiagnosis(
            "Region restriction", "Confirm your provider permits this channel in your region",
        )
        1002 -> StreamDiagnosis(
            "Live window moved", "Select the channel again to rejoin live playback",
        )
        1003, 2002 -> StreamDiagnosis(
            "Connection timed out", "Check connectivity and retry the channel",
        )
        2000 -> StreamDiagnosis(
            "Media input/output error", "Retry playback or check the recording's storage",
        )
        2001 -> StreamDiagnosis(
            "Network connection failed", "Check internet access and stream availability",
        )
        2003 -> StreamDiagnosis(
            "Unexpected content type", "Check whether the provider sent a video stream",
        )
        2004 -> StreamDiagnosis(
            "Stream server rejected playback", "Check provider access and the stream address",
        )
        2005 -> StreamDiagnosis(
            "Media not found", "Check that the channel or recording still exists",
        )
        2006 -> StreamDiagnosis(
            "Media access denied", "Check the app's media or storage access",
        )
        2007 -> StreamDiagnosis(
            "Insecure HTTP blocked", "Use an HTTPS source if your provider supports one",
        )
        2008 -> StreamDiagnosis(
            "Stream read position unavailable", "Restart playback from the beginning",
        )
        3001, 3002 -> StreamDiagnosis(
            "Malformed media or guide", "Try another channel and report the format issue",
        )
        3003, 3004, 4004, 4005 -> StreamDiagnosis(
            "Unsupported media format", "Choose a compatible stream format or device",
        )
        4001, 4002, 4003, 4006 -> StreamDiagnosis(
            "Playback decoder problem", "Try a lower-resolution channel or restart playback",
        )
        in 5001..5004 -> StreamDiagnosis(
            "Device audio output failed", "Check audio output settings and restart playback",
        )
        in 6000..6008 -> StreamDiagnosis(
            "Protected media issue", "Check your provider's playback and license requirements",
        )
        else -> StreamDiagnosis(
            "Playback failed", "Try another channel and report this numeric code",
        )
    }

    /**
     * A short detail for the diagnostic line, such as "HTTP 404; content type text/html". Values outside
     * their safe form are dropped, never repaired.
     */
    fun describe(evidence: StreamEvidence): String {
        val parts = ArrayList<String>(3)
        evidence.httpStatus?.takeIf { it in 100..599 }?.let { parts += "HTTP $it" }
        safeContentType(evidence.contentType)?.let { parts += "content type $it" }
        evidence.transport?.let { parts += it.label }
        return parts.joinToString("; ")
    }

    /** A bare lowercase type/subtype without parameters, or null. */
    fun safeContentType(raw: String?): String? {
        val value = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (value.length !in 3..64 || value.count { it == '/' } != 1) return null
        if (value.startsWith('/') || value.endsWith('/')) return null
        val safe = value.all { it in 'a'..'z' || it in '0'..'9' || it == '/' || it == '.' || it == '+' || it == '-' }
        return if (safe) value else null
    }
}
