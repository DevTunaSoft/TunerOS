package com.devtunasoft.tuneros.core

/** One fixed, credential-free diagnostic for a Media3 PlaybackException numeric error code. */
data class StreamDiagnosis(val issue: String, val nextStep: String)

/**
 * Pure Kotlin decision table. No Android dependency, network request, background thread,
 * exception-message access, stream URL, or provider header handling.
 *
 * Codes follow androidx.media3.common.PlaybackException.
 */
object StreamDoctor {
    fun diagnose(code: Int): StreamDiagnosis = when (code) {
        -102, -103 -> StreamDiagnosis(
            "Provider account access", "Check your authorized subscription and sign-in",
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
        2001 -> StreamDiagnosis(
            "Network connection failed", "Check internet access and stream availability",
        )
        2003 -> StreamDiagnosis(
            "Unexpected stream response", "Check whether the provider sent actual video",
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
        3001, 3002 -> StreamDiagnosis(
            "Malformed media or guide", "Try another channel and report the format issue",
        )
        3003, 3004, 4004, 4005 -> StreamDiagnosis(
            "Unsupported media format", "Choose a compatible stream format or device",
        )
        4001, 4002, 4003, 4006 -> StreamDiagnosis(
            "Playback decoder problem", "Try a lower-resolution channel or restart playback",
        )
        in 6000..6099 -> StreamDiagnosis(
            "Protected media issue", "Check your provider's playback and license requirements",
        )
        else -> StreamDiagnosis(
            "Playback failed", "Try another channel and report this numeric code",
        )
    }
}
