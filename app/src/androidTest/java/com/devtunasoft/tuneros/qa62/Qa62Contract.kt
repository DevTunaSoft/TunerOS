package com.devtunasoft.tuneros.qa62

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Qa62Check(val code: String, val ok: Boolean, val detail: String)

data class Qa62Event(
    val id: String,
    val sequence: Long,
    val durationMs: Long,
    val bytes: Long,
    val programStart: Long,
    val flag: String,
    val family: String,
)

/** Reference copy of a retained segment, derived only from fixture fields. */
data class Qa62Segment(
    val sequence: Long,
    val durationMs: Long,
    val bytes: Long,
    val fileName: String,
    val discontinuity: Boolean,
    val programStart: Long,
)

/**
 * Framework-free fixture generator and reference model shared by the host gate and the
 * instrumented mirror. Nothing here calls product code or declares Android classes.
 */
object Qa62Contract {
    const val EVENTS_HEADER = "id\tsequence\tdurationMillis\tbytes\tprogramStart\tflag\tfamily"
    const val CORRUPT_HEADER = "name\thex"
    const val DIMENSIONS_HEADER = "key\tvalue"
    const val MAX_SEGMENTS = 3
    const val MAX_BYTES = 2500L
    const val ROW_COUNT = 24
    const val FAMILY_COUNT = 6
    const val CORRUPT_COUNT = 6
    const val PINNED_EVENTS_SHA = "3cb4a321291c8d1f8f7dce0a42f58e6d99a5a250afdd739c3c89ebbcce2be7a8"
    val FILE_NAMES = listOf(".gitattributes", "dimensions.tsv", "events.tsv", "corrupt.tsv", "sha256.txt")

    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(Character.forDigit(v ushr 4, 16))
            append(Character.forDigit(v and 0x0f, 16))
        }
    }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun derivedMarker(): String {
        val seed = "qa62-seed-v1".toByteArray(Charsets.UTF_8)
        return "SK" + sha256(MessageDigest.getInstance("SHA-256").digest(seed)).take(8)
    }

    private fun dimensionsText(): String = buildString {
        append(DIMENSIONS_HEADER).append('\n')
        append("maxSegments\t").append(MAX_SEGMENTS).append('\n')
        append("maxBytes\t").append(MAX_BYTES).append('\n')
        append("rows\t").append(ROW_COUNT).append('\n')
        append("families\t").append(FAMILY_COUNT).append('\n')
        append("corruptVariants\t").append(CORRUPT_COUNT).append('\n')
    }

    private fun eventsText(): String = buildString {
        append(EVENTS_HEADER).append('\n')
        val families = listOf("single", "segment-cap", "byte-cap", "discontinuity", "date", "mixed")
        var clock = 1_700_000_000_000L
        for (i in 0 until ROW_COUNT) {
            val family = families[i % families.size]
            val sequence = i.toLong() + 1L
            val durationMs = 1_000L + ((i * 937L) % 8_000L)
            val bytes = 120L + ((i * 317L) % 1_100L)
            val flag = if (family == "discontinuity" && i % 4 == 3) "D" else "S"
            clock += durationMs
            append("e").append(String.format(Locale.ROOT, "%04d", i + 1))
            append('\t').append(sequence)
            append('\t').append(durationMs)
            append('\t').append(bytes)
            append('\t').append(clock - durationMs)
            append('\t').append(flag)
            append('\t').append(family)
            append('\n')
        }
    }

    private fun corruptRows(): List<Pair<String, String>> {
        val base = "e0001\t1\t1000\t120\t1700000999000\tS\tsingle"
        return listOf(
            "bad-id" to base.replace("e0001", "bad-id"),
            "non-numeric-bytes" to base.replace("\t120\t", "\txx\t"),
            "negative-duration" to base.replace("\t1000\t", "\t-1\t"),
            "field-count" to (base + "\textra"),
            "bad-flag" to base.replace("\tS\t", "\tQ\t"),
            "negative-sequence" to base.replace("\t1\t1000", "\t-1\t1000")
        )
    }

    private fun corruptText(): String = buildString {
        append(CORRUPT_HEADER).append('\n')
        for ((name, row) in corruptRows()) {
            append(name).append('\t').append(hex(row.toByteArray(Charsets.UTF_8))).append('\n')
        }
    }

    /** Generates every fixture file in memory. The manifest covers the three data files. */
    fun generateFiles(): Map<String, ByteArray> {
        val dimensions = dimensionsText().toByteArray(Charsets.UTF_8)
        val events = eventsText().toByteArray(Charsets.UTF_8)
        val corrupt = corruptText().toByteArray(Charsets.UTF_8)
        val manifest = buildString {
            append("# qa62-pinned-sha256\n")
            append(sha256(dimensions)).append("\tdimensions.tsv\n")
            append(sha256(events)).append("\tevents.tsv\n")
            append(sha256(corrupt)).append("\tcorrupt.tsv\n")
        }.toByteArray(Charsets.UTF_8)
        val attrs = "* -text\n.gitattributes -text\n".toByteArray(Charsets.UTF_8)
        return linkedMapOf(
            ".gitattributes" to attrs,
            "dimensions.tsv" to dimensions,
            "events.tsv" to events,
            "corrupt.tsv" to corrupt,
            "sha256.txt" to manifest,
        )
    }

    fun writeFiles(dir: File): List<String> {
        dir.mkdirs()
        val files = generateFiles()
        for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
        return files.keys.toList()
    }

    fun strictLines(text: String): List<String> = text.lines().dropLastWhile { it.isEmpty() }

    fun parseDimensions(text: String): Map<String, String>? {
        val lines = strictLines(text)
        if (lines.firstOrNull() != DIMENSIONS_HEADER) return null
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t', limit = 2)
            if (fields.size != 2 || fields[1].isEmpty()) return null
            out[fields[0]] = fields[1]
        }
        return out
    }

    fun parseEvent(line: String): Qa62Event? {
        val fields = line.split('\t')
        if (fields.size != 7) return null
        if (!Regex("e[0-9]{4}").matches(fields[0])) return null
        val seq = fields[1].toLongOrNull() ?: return null
        val duration = fields[2].toLongOrNull() ?: return null
        val bytes = fields[3].toLongOrNull() ?: return null
        val start = fields[4].toLongOrNull() ?: return null
        if (seq < 1 || duration <= 0L || bytes < 0L || start <= 0L) return null
        if (fields[5] != "S" && fields[5] != "D") return null
        if (fields[6] !in setOf("single", "segment-cap", "byte-cap", "discontinuity", "date", "mixed")) return null
        return Qa62Event(fields[0], seq, duration, bytes, start, fields[5], fields[6])
    }

    fun parseEvents(text: String): List<Qa62Event>? {
        val lines = strictLines(text)
        if (lines.firstOrNull() != EVENTS_HEADER) return null
        val out = ArrayList<Qa62Event>()
        for (line in lines.drop(1)) out += parseEvent(line) ?: return null
        return out
    }

    /** Deliberately permissive: every corrupt row must be accepted by this parser. */
    fun parseEventLax(line: String): Boolean = line.isNotBlank()

    fun parseCorrupt(text: String): List<Pair<String, String>>? {
        val lines = strictLines(text)
        if (lines.firstOrNull() != CORRUPT_HEADER) return null
        val out = ArrayList<Pair<String, String>>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != 2 || !Regex("c[0-9]{2}|[a-z-]+").matches(fields[0])) return null
            val raw = unhex(fields[1]) ?: return null
            out += fields[0] to String(raw, Charsets.UTF_8)
        }
        return out
    }

    fun manifestEntries(text: String): Map<String, String>? {
        val lines = strictLines(text)
        if (lines.firstOrNull() != "# qa62-pinned-sha256") return null
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != 2 || !Regex("[0-9a-f]{64}").matches(fields[0])) return null
            out[fields[1]] = fields[0]
        }
        return out
    }

    fun checkPrivacy(parts: Collection<String>): List<String> {
        val patterns = linkedMapOf(
            "URL_SCHEME" to Regex("(?i)[a-z][a-z0-9+.-]*://"),
            "CREDENTIAL_ASSIGNMENT" to Regex("(?i)(password|passwd|pass|token|secret|api[_-]?key|authorization)\\s*[=:]"),
            "PIN_ASSIGNMENT" to Regex("(?i)pin\\s*[=:]\\s*[0-9]{4,8}"),
            "KEY_TAG" to Regex("(?i)EXT-X-KEY"),
            "RECORDING_PAYLOAD_TAG" to Regex("(?i)recording-payload"),
        )
        val hits = ArrayList<String>()
        for (name in patterns.keys) {
            if (patterns.getValue(name).containsMatchIn(parts.joinToString("\n"))) hits += name
        }
        if (parts.joinToString("\n").contains(derivedMarker())) hits += "DERIVED_MARKER"
        return hits
    }

    data class Reference(val journal: List<String>, val maxRetained: Int, val byteEvictions: Int, val familyCounts: Map<String, Int>)

    fun playlist(segments: List<Qa62Segment>): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        if (segments.isEmpty()) {
            append("#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
            return@buildString
        }
        val target = Math.ceil(segments.maxOf { it.durationMs.toDouble() / 1000.0 }).toLong().coerceAtLeast(1L)
        append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        append("#EXT-X-MEDIA-SEQUENCE:").append(segments.first().sequence).append('\n')
        for (segment in segments) {
            if (segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
            append("#EXT-X-PROGRAM-DATE-TIME:").append(DATE.format(Instant.ofEpochMilli(segment.programStart))).append('\n')
            append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", segment.durationMs / 1000.0)).append(",\n")
            append(segment.fileName).append('\n')
        }
    }

    fun reference(events: List<Qa62Event>, maxSegments: Int, maxBytes: Long): Reference {
        val journal = ArrayList<String>()
        val retained = ArrayList<Qa62Segment>()
        var created = 0L
        var maxRetained = 0
        var byteEvictions = 0
        journal += "create:seg-$created.ts"
        created++
        val counts = LinkedHashMap<String, Int>()
        for (event in events) {
            counts[event.family] = (counts[event.family] ?: 0) + 1
            val name = "seg-${created - 1}.ts"
            val segment = Qa62Segment(event.sequence, event.durationMs, event.bytes, name, event.flag == "D", event.programStart)
            retained += segment
            while (retained.size > maxSegments || retained.sumOf { it.bytes } > maxBytes) {
                val oldest = retained.removeAt(0)
                journal += "delete:${oldest.fileName}"
                if (oldest.bytes > maxBytes) byteEvictions++
            }
            journal += "playlist:" + playlist(retained)
            journal += "create:seg-$created.ts"
            created++
            maxRetained = maxOf(maxRetained, retained.size)
        }
        return Reference(journal, maxRetained, byteEvictions, counts)
    }

    /** Pure checks run on both host and instrumented mirror. */
    fun pureChecks(dir: File, mode: String): List<Qa62Check> {
        val results = ArrayList<Qa62Check>()
        fun add(code: String, ok: Boolean, detail: String) { results += Qa62Check(code, ok, detail) }
        val generated = generateFiles()
        val actual = LinkedHashMap<String, ByteArray>()
        var readable = true
        for (name in FILE_NAMES) {
            val file = File(dir, name)
            if (!file.isFile) { readable = false; break }
            actual[name] = file.readBytes()
        }
        if (!readable) {
            add("HEADER_STRICT", false, "fixture-file-missing")
            return results
        }
        if (mode != "verify-manifest" && mode != "verify-pinned" && mode != "verify-semantic") {
            val same = FILE_NAMES.all { generated[it]!!.contentEquals(actual[it]!!) }
            add("BYTE_IDENTITY", same, "files=${FILE_NAMES.size}")
        }
        val manifest = String(actual.getValue("sha256.txt"), Charsets.UTF_8)
        val entries = manifestEntries(manifest)
        val manifestOk = entries != null && FILE_NAMES.filter { it != "sha256.txt" && it != ".gitattributes" }
            .all { entries[it] == sha256(actual.getValue(it)) }
        if (mode != "verify-pinned" && mode != "verify-semantic") {
            add("MANIFEST_CONSISTENT", manifestOk, "data-files=3")
        }
        if (mode != "verify-semantic") {
            add("PINNED_DIGEST", sha256(actual.getValue("events.tsv")) == PINNED_EVENTS_SHA, "events-digest-pinned")
        }
        val dimText = String(actual.getValue("dimensions.tsv"), Charsets.UTF_8)
        val dims = parseDimensions(dimText)
        val eventText = String(actual.getValue("events.tsv"), Charsets.UTF_8)
        val corruptText = String(actual.getValue("corrupt.tsv"), Charsets.UTF_8)
        val rawLines = strictLines(eventText)
        val headerOk = rawLines.firstOrNull() == EVENTS_HEADER
        add("HEADER_STRICT", headerOk, "events-and-dimensions-headers")
        val rowsOk = rawLines.drop(1).all { it.split('\t').size == 7 } && parseDimensions(dimText) != null
        add("ROW_BOUNDS", rowsOk, "rows=${rawLines.size - 1}")
        val events = if (headerOk) parseEvents(eventText) else null
        val corrupt = parseCorrupt(corruptText)
        val maxSegments = dims?.get("maxSegments")?.toIntOrNull() ?: -1
        val maxBytes = dims?.get("maxBytes")?.toLongOrNull() ?: -1L
        val capOk = events != null && MAX_SEGMENTS == maxSegments && MAX_BYTES == maxBytes &&
            dims?.get("rows") == ROW_COUNT.toString() && dims?.get("families") == FAMILY_COUNT.toString() &&
            dims?.get("corruptVariants") == CORRUPT_COUNT.toString()
        add("CAP_BINDING", capOk, "maxSegments=$maxSegments,maxBytes=$maxBytes")
        val privacyParts = ArrayList<String>()
        for (name in FILE_NAMES) privacyParts += String(actual.getValue(name), Charsets.UTF_8)
        add("PRIVACY_SCAN", checkPrivacy(privacyParts).isEmpty(), "patterns=5,derived-marker=yes")
        val variants = corrupt ?: emptyList()
        val laxOk = variants.isNotEmpty() && variants.all { parseEventLax(it.second) }
        val strictOk = variants.isNotEmpty() && variants.all { parseEvent(it.second) == null }
        add("CORRUPT_FAIL_CLOSED", laxOk && strictOk, "variants=${variants.size},laxAccepts=${laxOk}")
        val counts = events?.groupBy { it.family }?.mapValues { it.value.size } ?: emptyMap()
        val countsOk = events != null && events.size == ROW_COUNT && counts.size == FAMILY_COUNT &&
            counts.values.all { it > 0 } && variants.size == CORRUPT_COUNT
        add("COUNT_BINDING", countsOk, "rows=${events?.size ?: 0},families=${counts.size},corrupt=${variants.size}")
        return results
    }

    fun mirrorChecks(dir: File): List<Qa62Check> = pureChecks(dir, "verify")

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(if (args.isNotEmpty()) args[0] else ".")
        var pass = 0
        for (check in mirrorChecks(dir)) {
            val status = if (check.ok) "PASS" else "FAIL"
            if (check.ok) pass++ else println("FIRST\t${check.code}")
            println("CHECK\t${check.code}\t$status\t${check.detail}")
        }
        println("SUMMARY\tpass=$pass\tfail=${mirrorChecks(dir).size - pass}")
    }
}
