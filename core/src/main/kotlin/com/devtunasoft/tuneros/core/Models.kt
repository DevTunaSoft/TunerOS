package com.devtunasoft.tuneros.core

data class Channel(
    val id: String, val name: String, val streamUrl: String,
    val guideId: String? = null, val logoUrl: String? = null,
    val group: String? = null, val userAgent: String? = null, val referer: String? = null,
)

data class GuideProgram(
    val channelId: String, val title: String, val startEpochSeconds: Long,
    val endEpochSeconds: Long, val description: String? = null,
)

data class GuideSnapshot(
    val programs: List<GuideProgram>, val channelNames: Map<String, String>,
    val warnings: List<String> = emptyList(),
) {
    private data class Schedule(val entries: List<GuideProgram>, val prefixMaxEnd: LongArray)
    private val byChannel: Map<String, Schedule> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Group once and sort the same lists in place. Avoid allocating a second sorted
        // copy of every channel's programme references during large XMLTV imports.
        val groups = HashMap<String, MutableList<GuideProgram>>()
        for (program in programs) groups.getOrPut(program.channelId) { ArrayList() }.add(program)
        val indexed = HashMap<String, Schedule>(groups.size)
        val ordering = compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds }
        for ((id, entries) in groups) {
            entries.sortWith(ordering)
            val prefix = LongArray(entries.size)
            var maximum = Long.MIN_VALUE
            for (i in entries.indices) {
                maximum = maxOf(maximum, entries[i].endEpochSeconds)
                prefix[i] = maximum
            }
            indexed[id] = Schedule(entries, prefix)
        }
        indexed
    }
    /** Call on the import worker; never construct the index on the UI thread. */
    fun prepareIndex() { byChannel.size }
    fun hasSchedule(channelId: String): Boolean = byChannel[channelId]?.entries?.isNotEmpty() == true
    /** Returns only entries intersecting the requested window; no per-call guide-wide scan. */
    fun programsBetween(channelId: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<GuideProgram> {
        require(toEpochSeconds > fromEpochSeconds)
        val schedule = byChannel[channelId] ?: return emptyList()
        val entries = schedule.entries
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].startEpochSeconds < toEpochSeconds) low = middle + 1 else high = middle
        }
        val exclusiveEnd = low
        // Prefix maxima are monotonic: binary search instead of walking backwards
        // through potentially thousands of expired or overlapping programmes.
        var from = 0
        var until = exclusiveEnd
        while (from < until) {
            val middle = (from + until) ushr 1
            if (schedule.prefixMaxEnd[middle] <= fromEpochSeconds) from = middle + 1
            else until = middle
        }
        if (from == exclusiveEnd) return emptyList()
        return entries.subList(from, exclusiveEnd).filter { it.endEpochSeconds > fromEpochSeconds }
    }
    /** Binary search with prefix maxima handles overlapping programmes correctly. */
    fun now(channelId: String, epochSeconds: Long): GuideProgram? {
        val schedule = byChannel[channelId] ?: return null
        val entries = schedule.entries
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].startEpochSeconds <= epochSeconds) low = middle + 1 else high = middle
        }
        var index = low - 1
        while (index >= 0 && schedule.prefixMaxEnd[index] > epochSeconds) {
            val entry = entries[index]
            if (entry.endEpochSeconds > epochSeconds) return entry
            index--
        }
        return null
    }
    /** Build a snapshot on a background worker; refresh when the clock changes. */
    fun currentProgramsAt(epochSeconds: Long): Map<String, GuideProgram> {
        // Build only the result map; avoid intermediate Pair/List allocations per channel.
        val current = HashMap<String, GuideProgram>()
        for (id in byChannel.keys) {
            val program = now(id, epochSeconds)
            if (program != null) current[id] = program
        }
        return current
    }
}
