package com.devtunasoft.tuneros.core

/**
 * Allocation-free focus navigation for a timeline row. Gaps are never selectable.
 */
object TimelineFocusPolicy {
    fun preferredIndex(entries: List<TimelineEntry>, preferredProgramStart: Long?): Int {
        if (preferredProgramStart != null) {
            for (index in entries.indices) {
                val program = entries[index].program
                if (program != null && program.startEpochSeconds == preferredProgramStart) {
                    return index
                }
            }
        }
        for (index in entries.indices) {
            if (entries[index].program != null) return index
        }
        return -1
    }

    fun nextIndex(
        entries: List<TimelineEntry>,
        currentIndex: Int,
        direction: Int,
    ): Int {
        require(direction == -1 || direction == 1)
        var index = currentIndex + direction
        while (index in entries.indices) {
            if (entries[index].program != null) return index
            index += direction
        }
        return -1
    }

    /**
     * Select the nearest programme in the travel direction when a timeline row
     * moves to a different time window. Never allocate collections when moving
     * between windows; only the current row is scanned.
     */
    fun shiftedIndex(
        entries: List<TimelineEntry>,
        previousProgramStart: Long?,
        direction: Int,
    ): Int {
        require(direction == -1 || direction == 1)
        var first = -1
        var last = -1
        var nearest = -1
        var same = -1
        var firstStart = Long.MAX_VALUE
        var lastStart = Long.MIN_VALUE
        var nearestStart = if (direction < 0) Long.MIN_VALUE else Long.MAX_VALUE
        for (index in entries.indices) {
            val start = entries[index].program?.startEpochSeconds ?: continue
            if (first < 0) {
                first = index
                firstStart = start
            }
            last = index
            lastStart = start
            if (previousProgramStart == null) continue
            if (start == previousProgramStart) same = index
            if (direction < 0 && start < previousProgramStart && start >= nearestStart) {
                nearest = index
                nearestStart = start
            } else if (direction > 0 && start > previousProgramStart && start <= nearestStart) {
                nearest = index
                nearestStart = start
            }
        }
        if (nearest >= 0) return nearest
        if (previousProgramStart != null) {
            if (same >= 0) return same
            if (direction > 0 && lastStart <= previousProgramStart) return last
            if (direction < 0 && firstStart >= previousProgramStart) return first
        }
        return if (direction < 0) last else first
    }
}
