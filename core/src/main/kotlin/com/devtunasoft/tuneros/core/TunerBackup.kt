package com.devtunasoft.tuneros.core

/**
 * What a backup holds: favorites, guide mappings, series rules and restricted channel groups. Playlist and guide URLs,
 * provider credentials, the parental PIN and recordings have no place in the format, so a backup cannot carry them.
 */
data class BackupContents(
    val favorites: Set<String>,
    val guideMappings: Map<String, String>,
    val seriesRules: List<SeriesRule>,
    val restrictedGroups: Set<String>,
    val restrictedChannels: Set<String> = emptySet(),
)

/**
 * A versioned, line-based text format. Each record is a kind and its tab-separated fields; a tab, a line break or a
 * backslash inside a value is escaped. Export and import both refuse a value that looks like a URL or a credential, and an
 * import with any unknown record, a wrong version, or more than the size or record limits is refused as a whole. Error
 * messages never repeat the rejected value.
 */
object TunerBackup {
    const val HEADER = "TUNEROS-BACKUP 1"
    const val MAX_BYTES = 256 * 1024
    const val MAX_RECORDS = 5_000
    const val MAX_VALUE_CHARS = 256

    private val credentialLike = Regex("(?i)(pass(word|wd)?|token|secret|api[_-]?key|authorization)\\s*[=:]")

    fun encode(contents: BackupContents): String {
        val lines = ArrayList<String>()
        for (favorite in contents.favorites.sorted()) {
            lines += "favorite\t" + field(favorite, allowEmpty = false)
        }
        for ((channel, guide) in contents.guideMappings.toSortedMap()) {
            lines += "mapping\t" + field(channel, allowEmpty = false) + "\t" + field(guide, allowEmpty = false)
        }
        for (rule in contents.seriesRules.sortedWith(compareBy({ it.titleKey }, { it.channelId ?: "" }))) {
            lines += "series\t" + field(rule.titleKey, allowEmpty = false) + "\t" + field(rule.channelId ?: "", allowEmpty = true)
        }
        for (group in contents.restrictedGroups.sorted()) {
            lines += "restricted\t" + field(group, allowEmpty = false)
        }
        for (channel in contents.restrictedChannels.sorted()) {
            lines += "restricted-channel\t" + field(channel, allowEmpty = false)
        }
        require(lines.size <= MAX_RECORDS) { "the backup holds too many records" }
        val text = HEADER + "\n" + lines.joinToString("") { it + "\n" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "the backup is too large" }
        return text
    }

    fun decode(text: String): BackupContents {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "the backup is too large" }
        val lines = text.split('\n')
        require(lines.firstOrNull() == HEADER) { "the file is not a TunerOS backup of this version" }
        val favorites = LinkedHashSet<String>()
        val mappings = LinkedHashMap<String, String>()
        val series = ArrayList<SeriesRule>()
        val restricted = LinkedHashSet<String>()
        val channels = LinkedHashSet<String>()
        var records = 0
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            records++
            require(records <= MAX_RECORDS) { "the backup holds too many records" }
            val fields = line.split('\t')
            when (fields[0]) {
                "favorite" -> {
                    require(fields.size == 2) { "a favorite record has the wrong number of fields" }
                    favorites += value(fields[1], allowEmpty = false)
                }
                "mapping" -> {
                    require(fields.size == 3) { "a mapping record has the wrong number of fields" }
                    mappings[value(fields[1], allowEmpty = false)] = value(fields[2], allowEmpty = false)
                }
                "series" -> {
                    require(fields.size == 3) { "a series record has the wrong number of fields" }
                    val channel = value(fields[2], allowEmpty = true).ifEmpty { null }
                    series += SeriesRule(value(fields[1], allowEmpty = false), channel)
                }
                "restricted" -> {
                    require(fields.size == 2) { "a restricted record has the wrong number of fields" }
                    restricted += value(fields[1], allowEmpty = false)
                }
                "restricted-channel" -> {
                    require(fields.size == 2) { "a restricted channel record has the wrong number of fields" }
                    channels += value(fields[1], allowEmpty = false)
                }
                else -> throw IllegalArgumentException("the backup holds a record kind this version does not read")
            }
        }
        return BackupContents(
            favorites,
            mappings,
            series,
            ParentalPolicy.normalizeAll(restricted),
            ParentalPolicy.normalizeAll(channels),
        )
    }

    /** Checks a value before it is written, then escapes it for its record. */
    private fun field(value: String, allowEmpty: Boolean): String {
        check(value, allowEmpty)
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    }

    /** Reads a field back, unescapes it, and checks the same rules as export. */
    private fun value(raw: String, allowEmpty: Boolean): String {
        val value = unescape(raw)
        check(value, allowEmpty)
        return value
    }

    private fun check(value: String, allowEmpty: Boolean) {
        require(allowEmpty || value.isNotEmpty()) { "a backup value is empty" }
        require(value.length <= MAX_VALUE_CHARS) { "a backup value is too long" }
        require(!value.contains("://")) { "a backup never holds a URL" }
        require(!credentialLike.containsMatchIn(value)) { "a backup never holds credentials" }
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current != '\\') {
                out.append(current)
                index++
                continue
            }
            require(index + 1 < raw.length) { "a backup value ends with an escape" }
            when (raw[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> throw IllegalArgumentException("a backup value has an unknown escape")
            }
            index += 2
        }
        return out.toString()
    }
}
