package com.devtunasoft.tuneros.qa50

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * QA-50 framework-free fixture contract. The host gate and the bounded instrumented mirror
 * both drive these deterministic bytes. All names and identifiers are synthetic scratch data.
 */
object Qa50Contract {
    const val SCHEMA = "QA50-EPG-ADMISSION-V1"
    const val BASE_EPOCH = 1767225600L
    const val WINDOW_OFFSET = 3600L
    const val WINDOW_SPAN = 7200L
    const val WINDOW_FROM = BASE_EPOCH + WINDOW_OFFSET
    const val WINDOW_TO = WINDOW_FROM + WINDOW_SPAN
    const val MAX_PROGRAMS = 8
    const val MAX_CHANNELS = 3
    const val MAX_TITLE = 256
    const val MAX_DESC = 2048
    const val MAX_CHANNEL_NAME = 256
    const val CASES = 17
    const val CHANNELS = 5
    const val MARKER_SEED = 5071
    const val FILE_COUNT = 6

    data class Case(
        val id: String,
        val channel: String,
        val titleKind: String,
        val descKind: String,
        val start: String,
        val stop: String,
        val expected: String,
        val note: String,
    )

    fun cases(): List<Case> = listOf(
        Case("c0001", "chA", "NORMAL", "NORMAL", "$BASE_EPOCH", "$WINDOW_FROM", "EXCLUDE_WINDOW_EDGE_END", "ends at window start"),
        Case("c0002", "chA", "NORMAL", "NORMAL", "${WINDOW_FROM - 1}", "${WINDOW_FROM + 1}", "ADMIT", "straddles window start"),
        Case("c0003", "chA", "NORMAL", "NORMAL", "$WINDOW_FROM", "${WINDOW_FROM + 600}", "ADMIT", "starts at window start"),
        Case("c0004", "chA", "NORMAL", "NORMAL", "$WINDOW_TO", "${WINDOW_TO + 600}", "EXCLUDE_WINDOW_EDGE_START", "starts at window end"),
        Case("c0005", "chA", "NORMAL", "NORMAL", "${WINDOW_TO - 600}", "${WINDOW_TO + 600}", "ADMIT", "straddles window end"),
        Case("c0006", "chA", "NORMAL", "NORMAL", "$BASE_EPOCH", "$BASE_EPOCH", "EXCLUDE_DURATION_ZERO", "zero duration"),
        Case("c0007", "chA", "NORMAL", "NORMAL", "${BASE_EPOCH + 100}", "$BASE_EPOCH", "EXCLUDE_DURATION_NEG", "negative duration"),
        Case("c0008", "chA", "BLANK", "NORMAL", "${WINDOW_FROM + 10}", "${WINDOW_FROM + 20}", "EXCLUDE_TITLE", "blank title"),
        Case("c0009", "", "NORMAL", "NORMAL", "${WINDOW_FROM + 30}", "${WINDOW_FROM + 60}", "EXCLUDE_CHANNEL", "blank channel"),
        Case("c0010", "chA", "NORMAL", "NORMAL", "INVALID", "${WINDOW_FROM + 120}", "EXCLUDE_TIME", "invalid start"),
        Case("c0011", "chA", "LONG", "NORMAL", "${WINDOW_FROM + 140}", "${WINDOW_FROM + 200}", "ADMIT_TRUNC_TITLE", "long title"),
        Case("c0012", "chA", "NORMAL", "LONG", "${WINDOW_FROM + 220}", "${WINDOW_FROM + 280}", "ADMIT_TRUNC_DESC", "long synopsis"),
        Case("c0013", "chB", "NORMAL", "NORMAL", "${WINDOW_FROM + 300}", "${WINDOW_FROM + 360}", "ADMIT", "inside programme cap"),
        Case("c0014", "chB", "NORMAL", "NORMAL", "${WINDOW_FROM + 380}", "${WINDOW_FROM + 440}", "ADMIT", "inside programme cap"),
        Case("c0015", "chC", "NORMAL", "NORMAL", "${WINDOW_FROM + 460}", "${WINDOW_FROM + 520}", "ADMIT", "inside programme cap"),
        Case("c0016", "chC", "NORMAL", "NORMAL", "${WINDOW_FROM + 540}", "${WINDOW_FROM + 600}", "OVERFLOW", "window row over cap"),
        Case("c0017", "chA", "NORMAL", "NORMAL", "${WINDOW_FROM + 620}", "${WINDOW_FROM + 680}", "OVERFLOW", "window row over cap"),
    )

    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT)

    fun stamp(value: String): String = if (value == "INVALID") {
        "not-a-time"
    } else {
        stampFormatter.format(Instant.ofEpochSecond(value.toLong()).atZone(ZoneOffset.UTC))
    }

    fun titleFor(item: Case): String = when (item.titleKind) {
        "BLANK" -> ""
        "LONG" -> "Show-${item.id}-" + "T".repeat(MAX_TITLE + 44)
        else -> "Show-${item.id}"
    }

    fun descFor(item: Case): String = when (item.descKind) {
        "LONG" -> "D".repeat(MAX_DESC + 80)
        else -> "Scratch synopsis for ${item.id}."
    }

    fun sha256(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(raw.size * 2) {
            for (value in raw) {
                val byte = value.toInt() and 0xff
                if (byte < 16) append('0')
                append(Integer.toHexString(byte))
            }
        }
    }

    fun marker(): String = "QA50M-" + sha256(MARKER_SEED.toString().toByteArray(Charsets.US_ASCII)).take(8)

    fun privacyOffenders(text: String): List<String> {
        val found = ArrayList<String>(2)
        if (text.contains(marker())) found.add("DERIVED_MARKER")
        val sensitive = Regex(
            """(?i)(https?://|www\.|\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|pin=|drm=|provider\.|payload=)""",
        )
        if (sensitive.containsMatchIn(text)) found.add("SENSITIVE_PATTERN")
        return found
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun canonicalSmall(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n" +
            "<tv><channel id=\"c\"><display-name>n</display-name></channel></tv>\n"

    fun corruptRows(): List<Pair<String, ByteArray>> {
        val good = canonicalSmall().toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + good
        return listOf(
            "valid-control" to good,
            "bad-doctype" to canonicalSmall().replace("xmltv.dtd", "other.dtd").toByteArray(Charsets.UTF_8),
            "internal-subset" to canonicalSmall()
                .replace("SYSTEM \"xmltv.dtd\">", "SYSTEM \"xmltv.dtd\" [<!ENTITY x \"y\">]>")
                .toByteArray(Charsets.UTF_8),
            "truncated-root" to canonicalSmall().dropLast(12).toByteArray(Charsets.UTF_8),
            "dual-doctype" to canonicalSmall().replace("<tv>", "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv>").toByteArray(Charsets.UTF_8),
            "utf16-bom" to bom,
            "bad-declaration" to canonicalSmall().replace("<!DOCTYPE", "<!XDOCTYPE").toByteArray(Charsets.UTF_8),
        )
    }

    fun generateFiles(): Map<String, ByteArray> {
        val rows = cases()
        val casesText = buildString {
            append("id\tchannel\ttitleKind\tdescKind\tstart\tstop\texpected\tnote\n")
            for (item in rows) {
                append(
                    listOf(
                        item.id, item.channel, item.titleKind, item.descKind,
                        item.start, item.stop, item.expected, item.note,
                    ).joinToString("\t", postfix = "\n"),
                )
            }
        }
        val channelNames = listOf("Alpha", "L".repeat(MAX_CHANNEL_NAME + 1), "Gamma", "Delta", "Epsilon")
        val guideText = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n")
            append("<tv>\n")
            for (index in channelNames.indices) {
                append("<channel id=\"ch").append('A' + index).append("\">\n")
                append("<display-name>").append(escape(channelNames[index])).append("</display-name>\n")
                append("</channel>\n")
            }
            for (item in rows) {
                append("<programme start=\"").append(stamp(item.start))
                    .append("\" stop=\"").append(stamp(item.stop))
                    .append("\" channel=\"").append(escape(item.channel)).append("\">\n")
                append("<title>").append(escape(titleFor(item))).append("</title>\n")
                append("<desc>").append(escape(descFor(item))).append("</desc>\n")
                append("</programme>\n")
            }
            append("</tv>\n")
        }
        val corruptText = buildString {
            append("name\tpayload\n")
            for ((name, bytes) in corruptRows()) {
                append(name).append('\t').append(Base64.getEncoder().encodeToString(bytes)).append('\n')
            }
        }
        val casesBytes = casesText.toByteArray(Charsets.UTF_8)
        val guideBytes = guideText.toByteArray(Charsets.UTF_8)
        val corruptBytes = corruptText.toByteArray(Charsets.UTF_8)
        val dimensions = listOf(
            "schema" to SCHEMA,
            "baseEpoch" to BASE_EPOCH.toString(),
            "windowFrom" to WINDOW_FROM.toString(),
            "windowTo" to WINDOW_TO.toString(),
            "maxPrograms" to MAX_PROGRAMS.toString(),
            "maxChannels" to MAX_CHANNELS.toString(),
            "maxTitleChars" to MAX_TITLE.toString(),
            "maxDescChars" to MAX_DESC.toString(),
            "maxChannelNameChars" to MAX_CHANNEL_NAME.toString(),
            "casesSha256" to sha256(casesBytes),
            "guideSha256" to sha256(guideBytes),
            "corruptSha256" to sha256(corruptBytes),
            "admittedIds" to "c0002,c0003,c0005,c0011,c0012,c0013,c0014,c0015",
            "admittedExpected" to "8",
            "omittedExpected" to "2",
            "channelOmittedExpected" to "2",
            "invalidTimestampExpected" to "1",
            "truncatedTitleExpected" to "1",
            "truncatedDescriptionExpected" to "1",
            "truncatedChannelNameExpected" to "1",
            "windowEdgeExcludedIds" to "c0001,c0004",
            "windowEdgeAdmittedIds" to "c0002,c0003,c0005",
            "markerSeed" to MARKER_SEED.toString(),
            "cycles" to "6",
            "heapLimitBytes" to "100663296",
            "heapDriftBytes" to "67108864",
            "threadLimit" to "16",
        )
        val dimensionsText = buildString {
            append("key\tvalue\n")
            for ((key, value) in dimensions) append(key).append('\t').append(value).append('\n')
        }
        val dimensionsBytes = dimensionsText.toByteArray(Charsets.UTF_8)
        val manifest = listOf(
            "cases.tsv" to casesBytes,
            "guide.xmltv" to guideBytes,
            "corrupt.tsv" to corruptBytes,
            "dimensions.tsv" to dimensionsBytes,
        )
        val shaText = buildString {
            append("sha256\tfile\n")
            for ((name, bytes) in manifest) append(sha256(bytes)).append('\t').append(name).append('\n')
        }
        val readme = (
            "QA-50 scratch fixture for bounded EPG import admission.\n" +
                "All names, identifiers and counters are synthetic. No real feed address,\n" +
                "user credential, private recording or personal datum is represented here.\n" +
                "Regeneration is deterministic and byte-identical from the pinned contract.\n"
            ).toByteArray(Charsets.UTF_8)
        return linkedMapOf(
            "cases.tsv" to casesBytes,
            "guide.xmltv" to guideBytes,
            "corrupt.tsv" to corruptBytes,
            "dimensions.tsv" to dimensionsBytes,
            "sha256.txt" to shaText.toByteArray(Charsets.UTF_8),
            "README.md" to readme,
        )
    }

    private fun lines(text: String): List<String> = text.split('\n').dropLastWhile { it.isEmpty() }

    fun parseDimensions(text: String): Map<String, String> {
        val parts = lines(text)
        require(parts.isNotEmpty() && parts[0] == "key\tvalue") { "dimension header" }
        val result = LinkedHashMap<String, String>()
        for (line in parts.drop(1)) {
            val fields = line.split('\t')
            require(fields.size == 2 && fields[0].isNotEmpty()) { "dimension row" }
            require(result.put(fields[0], fields[1]) == null) { "duplicate dimension" }
        }
        return result
    }

    fun parseCases(text: String): List<Case> {
        val parts = lines(text)
        require(
            parts.isNotEmpty() &&
                parts[0] == "id\tchannel\ttitleKind\tdescKind\tstart\tstop\texpected\tnote",
        ) { "case header" }
        return parts.drop(1).map { line ->
            val fields = line.split('\t')
            require(fields.size == 8) { "case field count" }
            Case(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7])
        }
    }

    fun parseCorrupt(text: String): List<Pair<String, ByteArray>> {
        val parts = lines(text)
        require(parts.isNotEmpty() && parts[0] == "name\tpayload") { "corrupt header" }
        return parts.drop(1).map { line ->
            val fields = line.split('\t')
            require(fields.size == 2 && fields[0].isNotEmpty()) { "corrupt row" }
            fields[0] to Base64.getDecoder().decode(fields[1])
        }
    }

    fun parseManifest(text: String): List<Pair<String, String>> {
        val parts = lines(text)
        require(parts.isNotEmpty() && parts[0] == "sha256\tfile") { "manifest header" }
        return parts.drop(1).map { line ->
            val fields = line.split('\t')
            require(fields.size == 2 && fields[0].length == 64) { "manifest row" }
            fields[0] to fields[1]
        }
    }
}
