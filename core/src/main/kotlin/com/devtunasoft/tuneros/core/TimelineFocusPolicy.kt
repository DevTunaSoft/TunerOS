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
}
