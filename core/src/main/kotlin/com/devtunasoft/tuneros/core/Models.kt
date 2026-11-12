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
    private data class Schedule(
        val entries: List<GuideProgram>,
        // Null for nonoverlapping schedules: those need no per-programme index.
        val prefixMaxEnd: LongArray?,
    )
    private val byChannel: Map<String, Schedule> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Group once and sort the same lists in place. Avoid allocating a second sorted
        // copy of every channel's programme references during large XMLTV imports.
        val groups = HashMap<String, MutableList<GuideProgram>>()
        for (program in programs) groups.getOrPut(program.channelId) { ArrayList() }.add(program)
        val indexed = HashMap<String, Schedule>(groups.size)
        val ordering = compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds }
        for ((id, entries) in groups) {
            entries.sortWith(ordering)
            // For ordinary nonoverlapping XMLTV schedules, end times are
            // already monotonic after sorting by start. Their own end fields
            // are the binary-search index: allocating a LongArray would
            // duplicate 8 bytes per programme without adding information.
            var maximum = Long.MIN_VALUE
            var needsPrefix = false
            for (entry in entries) {
                if (maximum > entry.startEpochSeconds ||
                    entry.endEpochSeconds <= entry.startEpochSeconds
                ) {
                    needsPrefix = true
                    break
                }
                maximum = entry.endEpochSeconds
            }
            val prefix = if (needsPrefix) LongArray(entries.size).also { values ->
                maximum = Long.MIN_VALUE
                for (index in entries.indices) {
                    maximum = maxOf(maximum, entries[index].endEpochSeconds)
                    values[index] = maximum
                }
            } else null
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
            val end = schedule.prefixMaxEnd?.get(middle)
                ?: entries[middle].endEpochSeconds
            if (end <= fromEpochSeconds) from = middle + 1
            else until = middle
        }
        if (from == exclusiveEnd) return emptyList()
        // The common case needs neither a prefix-max array nor a per-query
        // scan. Overlapping/malformed schedules retain exact filtering.
        if (schedule.prefixMaxEnd == null) return entries.subList(from, exclusiveEnd)
        // Overlapping schedules still require filtering of expired entries.
        for (index in from until exclusiveEnd) {
            if (entries[index].endEpochSeconds <= fromEpochSeconds) {
                return entries.subList(from, exclusiveEnd).filter {
                    it.endEpochSeconds > fromEpochSeconds
                }
            }
        }
        return entries.subList(from, exclusiveEnd)
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
        val prefix = schedule.prefixMaxEnd
        // For nonoverlapping schedules, only the latest start <= now can play.
        // No scanning and no extra end-time array are needed.
        if (prefix == null) {
            val entry = entries.getOrNull(index)
            return entry?.takeIf { it.endEpochSeconds > epochSeconds }
        }
        while (index >= 0 && prefix[index] > epochSeconds) {
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
