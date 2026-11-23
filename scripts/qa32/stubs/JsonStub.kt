package org.json
class JSONException(message: String) : RuntimeException(message)
/** Minimal org.json-shaped object used only to host the real GuideMappingStore on the JVM. */
class JSONObject(raw: String? = null) {
    private val map = LinkedHashMap<String, Any?>()
    init { if (raw != null) parse(raw) }
    fun length(): Int = map.size
    fun keys(): Iterator<String> = map.keys.iterator()
    fun get(key: String): Any? {
        if (!map.containsKey(key)) throw JSONException("missing:" + key)
        return map[key]
    }
    fun getString(key: String): String {
        val v = get(key)
        if (v !is String) throw JSONException("not-string:" + key)
        return v
    }
    fun put(key: String, value: String): JSONObject { map[key] = value; return this }
    override fun toString(): String =
        map.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + render(it.value) }
    private fun render(v: Any?): String = when (v) {
        is String -> quote(v)
        null -> "null"
        else -> v.toString()
    }
    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> b.append("\\\\"); '"' -> b.append("\\\"")
            '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
    private var pos = 0
    private fun parse(text: String) {
        ws(text)
        if (text[pos] != '{') throw JSONException("not-object")
        pos++
        ws(text)
        if (text[pos] == '}') { pos++; return }
        while (true) {
            ws(text)
            val key = parseString(text)
            ws(text)
            if (text[pos] != ':') throw JSONException("missing-colon")
            pos++
            ws(text)
            map[key] = parseValue(text)
            ws(text)
            if (text[pos] == ',') { pos++; continue }
            if (text[pos] == '}') { pos++; return }
            throw JSONException("bad-object")
        }
    }
    private fun ws(text: String) { while (pos < text.length && text[pos].isWhitespace()) pos++ }
    private fun parseString(text: String): String {
        if (text[pos] != '"') throw JSONException("bad-string")
        pos++
        val b = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            if (c == '"') return b.toString()
            if (c == '\\') {
                if (pos >= text.length) throw JSONException("bad-escape")
                when (val e = text[pos++]) {
                    '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                    'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'u' -> { if (pos + 4 > text.length) throw JSONException("bad-unicode")
                        b.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                    else -> throw JSONException("bad-escape-char")
                }
            } else b.append(c)
        }
        throw JSONException("unterminated-string")
    }
    private fun parseValue(text: String): Any? {
        if (pos >= text.length) throw JSONException("no-value")
        return when (text[pos]) {
            '"' -> parseString(text)
            't' -> { expect(text, "true"); true }
            'f' -> { expect(text, "false"); false }
            'n' -> { expect(text, "null"); null }
            else -> {
                val start = pos
                while (pos < text.length && (text[pos].isDigit() || text[pos] == '-' ||
                    text[pos] == '+' || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E')) pos++
                if (start == pos) throw JSONException("bad-value")
                val s = text.substring(start, pos)
                if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDouble() else s.toLong()
            }
        }
    }
    private fun expect(text: String, word: String) {
        if (!text.startsWith(word, pos)) throw JSONException("bad-literal")
        pos += word.length
    }
}
