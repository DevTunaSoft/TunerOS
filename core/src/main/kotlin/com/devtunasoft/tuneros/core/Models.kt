package com.devtunasoft.tuneros.core

/** Network requests are made only from explicitly configured source URLs. */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val guideId: String? = null,
    val logoUrl: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
)

data class GuideProgram(
    val channelId: String,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val description: String? = null,
)

data class GuideSnapshot(
    val programs: List<GuideProgram>,
    val channelNames: Map<String, String>,
) {
    // Build the index off the UI thread after import. Avoid O(channels × all programs)
    // scans when rendering a large guide or searching channels.
    private val byChannel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        programs.groupBy { it.channelId }
    }

    fun prepareIndex() { byChannel.size }

    /** A channel with future schedule entries still counts as having EPG. */
    fun hasSchedule(channelId: String): Boolean = byChannel[channelId]?.isNotEmpty() == true

    fun now(channelId: String, epochSeconds: Long): GuideProgram? =
        byChannel[channelId]?.firstOrNull {
            it.startEpochSeconds <= epochSeconds && epochSeconds < it.endEpochSeconds
        }
}
