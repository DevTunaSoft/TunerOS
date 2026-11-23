package org.json

/** Synthetic JSON object: enough of the surface for GuideMappingStore's tiny string map. No real feed or URL. */
class JSONObject(private val source: String? = null) {
    private val entries = LinkedHashMap<String, String>()

    init {
        if (source != null) parse(source)
    }

    fun put(key: String, value: Any): JSONObject {
        entries[key] = value.toString()
        return this
    }

    fun getString(key: String): String = entries.getValue(key)

    fun length(): Int = entries.size

    fun keys(): MutableIterator<String> = entries.keys.iterator()

    override fun toString(): String = entries.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${escape(key)}\":\"${escape(value)}\""
    }

    private fun parse(raw: String) {
        var index = 0
        fun skipSpace() {
            while (index < raw.length && raw[index].isWhitespace()) index++
        }
        fun quoted(): String {
            skipSpace()
            require(raw[index] == '"') { "synthetic JSON key or value expected" }
            index++
            val out = StringBuilder()
            while (index < raw.length) {
                val ch = raw[index++]
                if (ch == '"') return out.toString()
                if (ch == '\\' && index < raw.length) {
                    when (val escaped = raw[index++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        else -> out.append(escaped)
                    }
                } else {
                    out.append(ch)
                }
            }
            error("synthetic JSON ended inside a quoted value")
        }
        skipSpace()
        require(raw[index++] == '{') { "synthetic JSON object expected" }
        skipSpace()
        if (raw[index] == '}') return
        while (index < raw.length) {
            val key = quoted()
            skipSpace()
            require(raw[index++] == ':') { "synthetic JSON separator expected" }
            entries[key] = quoted()
            skipSpace()
            if (index < raw.length && raw[index] == ',') {
                index++
                skipSpace()
                continue
            }
            break
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
}
