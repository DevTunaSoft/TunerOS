package com.devtunasoft.tuneros.core

/**
 * Guide Doctor. Finds channels with no schedule in the loaded guide, applies the user's saved guide mappings, and
 * suggests the guide channel a channel most likely is. A suggestion is never a guide channel without a schedule.
 */
object GuideDoctor {
    data class Suggestion(val guideId: String, val displayName: String, val score: Int)

    private val separator = Regex("[^\\p{L}\\p{N}]+")
    private val qualityWords = setOf("hd", "sd", "fhd", "uhd", "4k", "tv", "channel", "the", "and")

    /**
     * Channels with no guide ID, or whose guide ID has no schedule. Every channel is listed when no guide is loaded.
     */
    fun channelsWithoutSchedule(channels: List<Channel>, guide: GuideSnapshot?): List<Channel> =
        channels.filter { channel ->
            val guideId = channel.guideId
            guide == null || guideId == null || !guide.hasSchedule(guideId)
        }

    /**
     * Returns [channels] itself when [mappings] is empty. Otherwise a mapped channel takes its mapped guide ID, and
     * every other channel is returned unchanged.
     */
    fun applyMappings(channels: List<Channel>, mappings: Map<String, String>): List<Channel> {
        if (mappings.isEmpty()) return channels
        return channels.map { channel ->
            val mapped = mappings[channel.storageKey]
            if (mapped == null || mapped == channel.guideId) channel else channel.copy(guideId = mapped)
        }
    }

    /**
     * Ranks guide channels that have a schedule for [channel] by name. Names are compared without case,
     * punctuation or quality words such as HD. An exact match scores 100, one name containing the other scores 60,
     * and a shared word of three or more letters scores 30. Ties are ordered by name and then by ID.
     */
    fun suggestions(
        channel: Channel,
        guideNames: Map<String, String>,
        hasSchedule: (String) -> Boolean,
        limit: Int = 5,
    ): List<Suggestion> {
        val wanted = key(channel.name)
        if (wanted.isEmpty() || limit <= 0) return emptyList()
        val wantedWords = sharedWords(channel.name)
        val found = ArrayList<Suggestion>()
        for ((guideId, displayName) in guideNames) {
            if (!hasSchedule(guideId)) continue
            val candidate = key(displayName)
            val score = when {
                candidate == wanted || key(guideId) == wanted -> 100
                candidate.length >= 3 && wanted.length >= 3 &&
                    (candidate.contains(wanted) || wanted.contains(candidate)) -> 60
                wantedWords.isNotEmpty() && sharedWords(displayName).any { it in wantedWords } -> 30
                else -> 0
            }
            if (score > 0) found += Suggestion(guideId, displayName, score)
        }
        return found
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.displayName }.thenBy { it.guideId })
            .take(limit)
    }

    /** Lower-case words without punctuation or quality words, for example "BBC One HD" becomes [bbc, one]. */
    private fun tokens(name: String): List<String> =
        name.lowercase().split(separator).filter { it.isNotEmpty() && it !in qualityWords }

    private fun key(name: String): String = tokens(name).joinToString("")

    private fun sharedWords(name: String): Set<String> = tokens(name).filter { it.length >= 3 }.toSet()
}
