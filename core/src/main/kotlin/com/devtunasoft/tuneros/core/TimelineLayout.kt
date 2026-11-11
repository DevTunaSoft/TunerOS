/*
 * Portions adapted from Egeniq android-tv-program-guide.
 * Copyright (c) 2020, Egeniq
 * Licensed under the Apache License, Version 2.0.
 *
 * TunerOS modifications: pure-Kotlin model, epoch-seconds API, immutable output,
 * explicit clipping/gap handling, and no Android/ThreeTen dependencies.
 */
package com.devtunasoft.tuneros.core

data class TimelineEntry(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val program: GuideProgram? = null,
) {
    init { require(endEpochSeconds > startEpochSeconds) }
    val isGap: Boolean get() = program == null
    val durationSeconds: Long get() = endEpochSeconds - startEpochSeconds
}

object TimelineLayout {
    /**
     * Produces a contiguous, non-overlapping row for a visible guide window.
     * Real programmes are clipped to the window. Uncovered time becomes gap entries.
     * When XMLTV overlaps, the earlier visible programme wins and the later entry is
     * clipped forward rather than drawing two cells on top of each other.
     */
    fun row(
        programs: List<GuideProgram>,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
    ): List<TimelineEntry> {
        require(toEpochSeconds > fromEpochSeconds)
        if (programs.isEmpty()) return listOf(TimelineEntry(fromEpochSeconds, toEpochSeconds))

        val sorted = programs.asSequence()
            .filter { it.endEpochSeconds > fromEpochSeconds && it.startEpochSeconds < toEpochSeconds }
            .sortedWith(compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds })
            .toList()

        if (sorted.isEmpty()) return listOf(TimelineEntry(fromEpochSeconds, toEpochSeconds))

        val result = ArrayList<TimelineEntry>(sorted.size * 2 + 1)
        var cursor = fromEpochSeconds
        for (program in sorted) {
            val clippedStart = maxOf(program.startEpochSeconds, fromEpochSeconds, cursor)
            val clippedEnd = minOf(program.endEpochSeconds, toEpochSeconds)
            if (clippedEnd <= clippedStart) continue
            if (clippedStart > cursor) result += TimelineEntry(cursor, clippedStart)
            result += TimelineEntry(clippedStart, clippedEnd, program)
            cursor = clippedEnd
            if (cursor >= toEpochSeconds) break
        }
        if (cursor < toEpochSeconds) result += TimelineEntry(cursor, toEpochSeconds)
        return result
    }

    /** Converts duration to a stable proportional width without Android display dependencies. */
    fun widthUnits(entry: TimelineEntry, unitsPerHour: Int = 240): Int {
        require(unitsPerHour > 0)
        return maxOf(1, ((entry.durationSeconds * unitsPerHour) / 3600L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
