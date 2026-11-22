package com.devtunasoft.tuneros.core

/**
 * Guide Doctor. Finds channels with no schedule in the loaded guide, applies the user's saved guide mappings, and
 * suggests the guide channel a channel most likely is. A suggestion is never a guide channel without a schedule.
 */
object GuideDoctor {
    data class Suggestion(val guideId: String, val displayName: String, val score: Int)

    /**
     * Counts for the Guide Doctor summary. Channel counts are taken after saved mappings are applied, so
     * [withoutSchedule] equals the size of [channelsWithoutSchedule] for the same inputs. [mappingsForMissingChannels]
     * are saved mappings whose key is not in the playlist. [mappingsToUnscheduled] are saved mappings whose guide
     * channel has no schedule. [staleChannels] are scheduled channels whose programmes all ended by the time given to
     * [diagnose], and [guideEndsAtEpochSeconds] is the latest programme end in the whole guide.
     */
    data class Diagnosis(
        val channels: Int,
        val scheduled: Int,
        val noGuideId: Int,
        val guideIdNotDeclared: Int,
        val noProgrammes: Int,
        val savedMappings: Int,
        val mappingsForMissingChannels: Int,
        val mappingsToUnscheduled: Int,
        val staleChannels: Int,
        val guideEndsAtEpochSeconds: Long?,
    ) {
        val withoutSchedule: Int get() = noGuideId + guideIdNotDeclared + noProgrammes
    }

    private enum class Status { SCHEDULED, NO_GUIDE_ID, GUIDE_ID_NOT_DECLARED, NO_PROGRAMMES }

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
     * Explains [channelsWithoutSchedule] and the saved [mappings]. A guide ID is not declared when the loaded guide has
     * no channel entry with a display name for it. A declared guide channel with no programmes has no schedule. A
     * scheduled channel is stale when every programme of its guide channel ended at or before [nowEpochSeconds].
     * Memory grows with the number of saved mappings, not with the playlist size.
     */
    fun diagnose(
        channels: List<Channel>,
        mappings: Map<String, String>,
        guide: GuideSnapshot,
        nowEpochSeconds: Long,
    ): Diagnosis {
        var scheduled = 0
        var noGuideId = 0
        var notDeclared = 0
        var noProgrammes = 0
        var stale = 0
        val unmatched = HashSet(mappings.keys)
        for (channel in channels) {
            unmatched.remove(channel.storageKey)
            val guideId = mappings[channel.storageKey] ?: channel.guideId
            when (statusOf(guideId, guide)) {
                Status.SCHEDULED -> {
                    scheduled++
                    val end = guideId?.let { guide.latestEnd(it) }
                    if (end != null && end <= nowEpochSeconds) stale++
                }
                Status.NO_GUIDE_ID -> noGuideId++
                Status.GUIDE_ID_NOT_DECLARED -> notDeclared++
                Status.NO_PROGRAMMES -> noProgrammes++
            }
        }
        val unscheduled = mappings.values.count { !guide.hasSchedule(it) }
        return Diagnosis(
            channels = channels.size,
            scheduled = scheduled,
            noGuideId = noGuideId,
            guideIdNotDeclared = notDeclared,
            noProgrammes = noProgrammes,
            savedMappings = mappings.size,
            mappingsForMissingChannels = unmatched.size,
            mappingsToUnscheduled = unscheduled,
            staleChannels = stale,
            guideEndsAtEpochSeconds = guide.programs.maxOfOrNull { it.endEpochSeconds },
        )
    }

    private fun statusOf(guideId: String?, guide: GuideSnapshot): Status = when {
        guideId == null -> Status.NO_GUIDE_ID
        guide.hasSchedule(guideId) -> Status.SCHEDULED
        guideId in guide.channelNames -> Status.NO_PROGRAMMES
        else -> Status.GUIDE_ID_NOT_DECLARED
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
