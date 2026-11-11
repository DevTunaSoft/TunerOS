package com.devtunasoft.tuneros.core

/**
 * Verify that a 206 Partial Content response contains exactly the HLS bytes requested.
 * Reject missing/malformed headers and wrong offsets rather than appending corrupt media.
 */
object HlsContentRange {
    private val headerPattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun matches(header: String?, requested: HlsByteRange): Boolean {
        if (header == null || requested.offset < 0 || requested.length <= 0) return false
        val expectedEnd = runCatching {
            Math.addExact(requested.offset, requested.length - 1)
        }.getOrNull() ?: return false
        val groups = headerPattern.matchEntire(header.trim())?.groupValues ?: return false
        val from = groups[1].toLongOrNull() ?: return false
        val to = groups[2].toLongOrNull() ?: return false
        val totalRaw = groups[3]
        if (totalRaw != "*") {
            val total = totalRaw.toLongOrNull() ?: return false
            if (total <= to) return false
        }
        return from == requested.offset && to == expectedEnd
    }
}
