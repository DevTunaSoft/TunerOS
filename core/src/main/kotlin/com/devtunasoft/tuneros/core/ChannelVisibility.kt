package com.devtunasoft.tuneros.core

/**
 * Channel visibility for the recycled TV guide. PlaylistParser limits the source to 25,000
 * channels; there is no additional arbitrary 500-channel display cap.
 */
object ChannelVisibility {
    fun filter(
        channels: List<Channel>,
        guide: GuideSnapshot?,
        query: String,
        hideWithoutGuide: Boolean,
    ): List<Channel> {
        val search = query.trim()
        if (search.isEmpty() && !hideWithoutGuide) return channels
        if (hideWithoutGuide && guide == null) return emptyList()
        return channels.filter { channel ->
            (search.isEmpty() || channel.name.contains(search, ignoreCase = true)) &&
                (!hideWithoutGuide || channel.guideId?.let { guide?.hasSchedule(it) } == true)
        }
    }
}
