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
    fun now(channelId: String, epochSeconds: Long): GuideProgram? =
        programs.firstOrNull { it.channelId == channelId && it.startEpochSeconds <= epochSeconds && epochSeconds < it.endEpochSeconds }
}
