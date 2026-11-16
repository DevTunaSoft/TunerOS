package com.devtunasoft.tuneros.core

/**
 * A series rule records every programme whose title matches, on one channel or on any channel. A title matches exactly
 * after normalisation: letters and digits in lower case, and every other run of characters as one space. The id is the
 * normalised title and the channel, so the same rule added twice is one rule.
 */
data class SeriesRule(val titleKey: String, val channelId: String?) {
    init {
        require(titleKey.isNotEmpty())
        require(channelId == null || channelId.isNotEmpty())
    }

    /** The storage key: the normalised title, a separator that titles cannot contain, then the channel or `*`. */
    val id: String get() = "$titleKey|${channelId ?: "*"}"
}

object SeriesRules {
    /** The most rules the planner accepts, so one guide is matched within a fixed budget. */
    const val MAX_RULES = 50

    /** The normalised title of [title], or null when it has no letters or digits to match on. */
    fun titleKeyOf(title: String): String? {
        val out = StringBuilder(title.length)
        var pendingSpace = false
        for (ch in title) {
            if (ch.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                out.append(ch.lowercaseChar())
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return if (out.isEmpty()) null else out.toString()
    }

    /** A rule for [title] on [channelId], or null when the title has nothing to match on. */
    fun ruleFor(title: String, channelId: String?): SeriesRule? {
        val key = titleKeyOf(title) ?: return null
        return SeriesRule(key, channelId?.takeIf { it.isNotEmpty() })
    }

    /** Whether [program] is one [rule] names: the same title after normalisation, on the rule's channel if it has one. */
    fun matches(rule: SeriesRule, program: GuideProgram): Boolean =
        (rule.channelId == null || rule.channelId == program.channelId) && titleMatches(rule.titleKey, program.title)

    /**
     * The programmes to record for [rules]: each programme that starts after [nowEpochSeconds] and matches at least one
     * rule, once, in guide order. A title is compared without allocating, so a large guide is matched within a bounded
     * budget; only the planned programmes are kept.
     */
    fun plan(rules: List<SeriesRule>, programs: List<GuideProgram>, nowEpochSeconds: Long): List<GuideProgram> {
        require(rules.size <= MAX_RULES)
        if (rules.isEmpty()) return emptyList()
        val planned = ArrayList<GuideProgram>()
        val seen = HashSet<String>()
        for (program in programs) {
            if (program.startEpochSeconds <= nowEpochSeconds) continue
            if (rules.none { matches(it, program) }) continue
            if (seen.add("${program.channelId}|${program.startEpochSeconds}")) planned += program
        }
        return planned
    }

    /** Whether [title] normalises to [key], walked without allocating. [key] is already normalised. */
    private fun titleMatches(key: String, title: String): Boolean {
        var position = 0
        var pendingSpace = false
        var started = false
        for (ch in title) {
            if (ch.isLetterOrDigit()) {
                if (pendingSpace && started) {
                    if (position >= key.length || key[position] != ' ') return false
                    position++
                }
                if (position >= key.length || key[position] != ch.lowercaseChar()) return false
                position++
                started = true
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return position == key.length
    }
}
