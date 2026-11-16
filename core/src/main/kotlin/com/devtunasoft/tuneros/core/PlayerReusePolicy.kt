package com.devtunasoft.tuneros.core

/** Whether a channel switch may keep the current player instead of creating a new one. */
object PlayerReusePolicy {
    /**
     * A player is reused only for another live channel with exactly the same provider request headers, and never
     * while it plays a recording. Keeping a player across different headers would send one provider's headers to
     * another provider's stream.
     */
    fun canReuse(
        currentHeaders: Map<String, String>?,
        nextHeaders: Map<String, String>,
        playingRecording: Boolean,
    ): Boolean = currentHeaders != null && !playingRecording && currentHeaders == nextHeaders
}
