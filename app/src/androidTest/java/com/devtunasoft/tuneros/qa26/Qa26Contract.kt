package com.devtunasoft.tuneros.qa26

import com.devtunasoft.tuneros.core.DashManifest
import com.devtunasoft.tuneros.core.DashPlanOutcome
import java.io.File
import java.security.MessageDigest

/**
 * QA-26 shared, Android-free contract for bounded DASH MPD planning.
 *
 * The same source is compiled into the host gate and into the instrumented
 * APK. It generates the scratch fixture from fixed constants, renders each
 * committed case row into an MPD document, calls the real production planner,
 * and derives every check from the committed bytes. No provider address,
 * credential, personal code or recording payload is stored or printed.
 */
object Qa26Contract {
    const val SEED = 20261123L
    const val CASE_HEADER =
        "id\tfamily\tshape\ttype\tbandwidth\tsegTicks\ttimescale\tstartNumber\tmediaToken\ttimeline\tlistCount\texpect\tdetail"
    const val CORRUPT_HEADER = "id\tdefect\treason"
    const val DIM_HEADER = "key\tvalue"
    const val CONTRACT_TEXT =
        "qa26 scratch contract v1\n" +
            "Tables are tab separated with an exact header; dimensions use tab pairs.\n" +
            "Every value is an invented token derived from the fixed seed.\n" +
            "The verifier parses committed bytes and renders each row into a small MPD.\n" +
            "Only check ids, counts and digests are emitted; no raw input is printed.\n"

    private val DATA_FILES = listOf("contract.txt", "cases.tsv", "corrupt.tsv", "dimensions.tsv")
    private val SCHEME = Regex("""(?i)(?:https?|ftp|ftps|rtmp|udp)://""")
    private val SENSITIVE = Regex(
        """(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential)[\s:=]""",
    )
    private val PIN = Regex("""(?i)\bpin[\s:=]?\d{4,}""")
    private val PAYLOAD = Regex("""(?i)(?:recording|media)[_-]?(?:payload|bytes)[\s:=]""")

    data class CaseRow(
        val id: String,
        val family: String,
        val shape: String,
        val type: String,
        val bandwidth: Long,
        val segTicks: Long,
        val timescale: Long,
        val startNumber: Long,
        val mediaToken: String,
        val timeline: String,
        val listCount: Int,
        val expect: String,
        val detail: String,
    ) {
        fun line(): String = listOf(
            id, family, shape, type, bandwidth, segTicks, timescale, startNumber,
            mediaToken, timeline, listCount, expect, detail,
        ).joinToString("\t")
    }

    data class CorruptRow(val id: String, val defect: String, val reason: String) {
        fun line(): String = listOf(id, defect, reason).joinToString("\t")
    }

    data class Check(val code: String, val ok: Boolean, val note: String = "")

    /** Stable synthetic marker derived from the seed; never written as a literal. */
    fun marker(): String {
        val mixed = (SEED * 2654435761L) and 0x7FFFFFFFFFFFFFFFL
        return "tok" + java.lang.Long.toHexString(mixed).takeLast(8)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(64) {
            for (b in digest) append(java.lang.Integer.toHexString(b.toInt() and 0xff).padStart(2, '0'))
        }
    }

    fun fingerprint(outcome: DashPlanOutcome): String = when (outcome) {
        is DashPlanOutcome.Planned -> {
            val p = outcome.plan
            listOf(
                "P", p.representationId, p.bandwidth, p.startNumber, p.timescale,
                p.startTimeTicks, p.segmentDurationTicks, p.segmentCount,
                p.timing.javaClass.simpleName, p.listedSegments?.size ?: -1,
                p.listedTimed, p.openTail?.startTicks ?: -1, p.openTail?.durationTicks ?: -1,
                p.presentationTimeOffsetTicks, p.initializationTemplate ?: "-",
                p.baseUrl ?: "-", p.representationBaseUrl ?: "-",
                p.listedInitialization?.range?.offset ?: -1,
                p.listedInitialization?.range?.length ?: -1,
            ).joinToString("|")
        }
        is DashPlanOutcome.Unsupported -> "U|" + outcome.reason
        is DashPlanOutcome.NeedsIndex -> listOf(
            "N", outcome.indexRange.offset, outcome.indexRange.length,
            outcome.partial.representationId, outcome.partial.bandwidth,
            outcome.partial.listedInitialization?.range?.offset ?: -1,
        ).joinToString("|")
    }

    /** Renders a committed case row into an MPD document using only row fields and fixed constants. */
    fun renderCase(row: CaseRow): String {
        val sb = StringBuilder(8192)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
        sb.append("profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" type=\"").append(row.type).append("\" ")
        sb.append("minBufferTime=\"PT1S\"")
        if (row.type == "static") {
            sb.append(" mediaPresentationDuration=\"PT100S\"")
        } else {
            sb.append(" availabilityStartTime=\"2026-01-01T00:00:00Z\" minimumUpdatePeriod=\"PT2S\"")
        }
        sb.append(">\n<BaseURL>qa26-base</BaseURL>\n")
        sb.append("<Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">\n")
        if (row.shape == "dual") {
            sb.append("<Representation id=\"r").append(row.id).append("a\" bandwidth=\"")
                .append(row.bandwidth / 2).append("\">")
                .append("<SegmentTemplate timescale=\"").append(row.timescale)
                .append("\" duration=\"").append(row.segTicks)
                .append("\" media=\"qa26-set-\$Number\$.m4s\"/>")
                .append("</Representation>\n")
        }
        sb.append("<Representation id=\"r").append(row.id).append("b\" bandwidth=\"")
            .append(row.bandwidth).append("\">\n")
        if (row.shape == "repbase") sb.append("<BaseURL>qa26-rep</BaseURL>\n")
        when {
            row.family.startsWith("list") -> {
                sb.append("<SegmentList timescale=\"").append(row.timescale)
                    .append("\" duration=\"").append(row.segTicks)
                    .append("\" startNumber=\"").append(row.startNumber).append("\">\n")
                sb.append("<Initialization range=\"0-99\"/>\n")
                for (k in 0 until row.listCount) {
                    val first = 100 + k * 100
                    sb.append("<SegmentURL media=\"qa26-seg-").append(k + 1)
                        .append("\" mediaRange=\"").append(first).append('-').append(first + 99)
                        .append("\"/>\n")
                }
                sb.append("</SegmentList>\n")
            }
            row.family == "base_static" -> {
                sb.append("<SegmentBase indexRange=\"0-999\">")
                    .append("<Initialization range=\"0-99\"/></SegmentBase>\n")
            }
            else -> {
                sb.append("<SegmentTemplate timescale=\"").append(row.timescale)
                    .append("\" duration=\"").append(row.segTicks)
                    .append("\" startNumber=\"").append(row.startNumber)
                    .append("\" media=\"").append(row.mediaToken)
                    .append("\" initialization=\"qa26-init.m4s\">")
                if (row.timeline != "-") {
                    sb.append("<SegmentTimeline>")
                    for (entry in row.timeline.split(';')) {
                        val parts = entry.split(':')
                        sb.append("<S d=\"").append(parts[0]).append('"')
                        if (parts.size > 1 && parts[1] != "-") sb.append(" r=\"").append(parts[1]).append('"')
                        if (parts.size > 2 && parts[2] != "-") sb.append(" t=\"").append(parts[2]).append('"')
                        sb.append("/>")
                    }
                    sb.append("</SegmentTimeline>")
                }
                sb.append("</SegmentTemplate>\n")
            }
        }
        sb.append("</Representation>\n</AdaptationSet>\n</Period>\n</MPD>\n")
        return sb.toString()
    }

    /** Renders a committed corrupt row: a small canonical MPD with one named defect. */
    fun renderCorrupt(row: CorruptRow): String {
        fun doc(
            type: String = "static",
            duration: String = " mediaPresentationDuration=\"PT100S\"",
            contentType: String = "video",
            periods: Int = 1,
            periodStart: String = "",
            extraHead: String = "",
            extraSet: String = "",
            repAttrs: String = "",
            addressing: String =
                "<SegmentTemplate timescale=\"1\" duration=\"10\" startNumber=\"1\" media=\"qa26-\$Number\$.m4s\"/>",
            tail: String = "",
        ): String {
            val sb = StringBuilder(2048)
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n").append(extraHead)
            sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"").append(type)
                .append("\" minBufferTime=\"PT1S\"").append(duration).append(">\n")
            sb.append("<BaseURL>qa26-base</BaseURL>\n")
            repeat(periods) { p ->
                sb.append("<Period").append(if (p == 0) periodStart else "").append(">")
                    .append("<AdaptationSet contentType=\"").append(contentType).append("\">")
                    .append(extraSet)
                    .append("<Representation id=\"r").append(p).append("\" bandwidth=\"800\"")
                    .append(repAttrs).append(">")
                    .append("<BaseURL>qa26-rep</BaseURL>").append(addressing)
                    .append("</Representation></AdaptationSet></Period>\n")
            }
            sb.append(tail).append("</MPD>\n")
            return sb.toString()
        }

        return when (row.defect) {
            "dtd" -> doc(extraHead = "<!DOCTYPE MPD [<!ENTITY qa26e \"x\">]>\n")
            "malformed" -> doc().removeSuffix("</MPD>\n")
            "root" -> doc().replace("<MPD ", "<qa26root ").replace("</MPD>", "</qa26root>")
            "unknown-type" -> doc(type = "odd")
            "protection" -> doc(extraSet = "<ContentProtection schemeIdUri=\"qa26-scheme\"/>")
            "audio" -> doc(contentType = "audio")
            "two-periods" -> doc(periods = 2)
            "period-start" -> doc(periodStart = " start=\"PT1S\"")
            "bad-timescale" -> doc(
                addressing = "<SegmentTemplate timescale=\"0\" duration=\"10\" media=\"qa26-\$Number\$\"/>",
            )
            "bad-duration" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" duration=\"0\" media=\"qa26-\$Number\$\"/>",
            )
            "bad-startnumber" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" duration=\"10\" startNumber=\"-1\" media=\"qa26-\$Number\$\"/>",
            )
            "unknown-token" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" duration=\"10\" media=\"qa26-\$Foo\$\"/>",
            )
            "timeline-gap" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" duration=\"10\" media=\"qa26-\$Time\$\">" +
                    "<SegmentTimeline><S d=\"10\" t=\"0\"/><S d=\"10\" t=\"30\"/></SegmentTimeline></SegmentTemplate>",
            )
            "open-static" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" media=\"qa26-\$Time\$\">" +
                    "<SegmentTimeline><S d=\"10\" r=\"-1\"/></SegmentTimeline></SegmentTemplate>",
            )
            "open-not-last" -> doc(
                type = "dynamic", duration = " availabilityStartTime=\"2026-01-01T00:00:00Z\"",
                addressing = "<SegmentTemplate timescale=\"1\" media=\"qa26-\$Time\$\">" +
                    "<SegmentTimeline><S d=\"10\" r=\"-1\"/><S d=\"10\"/></SegmentTimeline></SegmentTemplate>",
            )
            "nested-baseurl" -> doc().replace(
                "<Period>", "<Period><BaseURL>qa26-nested</BaseURL>",
            )
            "live-base" -> doc(
                type = "dynamic", duration = " availabilityStartTime=\"2026-01-01T00:00:00Z\"",
                addressing = "<SegmentBase indexRange=\"0-999\"><Initialization range=\"0-99\"/></SegmentBase>",
            )
            "bad-range" -> doc(
                addressing = "<SegmentList><Initialization range=\"0-99\"/>" +
                    "<SegmentURL media=\"qa26-seg\" mediaRange=\"bad\"/></SegmentList>",
            )
            "no-duration" -> doc(duration = "")
            "no-availability" -> doc(type = "dynamic", duration = "")
            "too-large" -> doc(tail = "<!--" + "q".repeat(DashManifest.MAX_MANIFEST_CHARS + 64) + "-->\n")
            "two-timelines" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" media=\"qa26-\$Time\$\">" +
                    "<SegmentTimeline><S d=\"10\"/></SegmentTimeline>" +
                    "<SegmentTimeline><S d=\"10\"/></SegmentTimeline></SegmentTemplate>",
            )
            "overflow" -> doc(
                addressing = "<SegmentTemplate timescale=\"1\" media=\"qa26-\$Time\$\">" +
                    "<SegmentTimeline><S d=\"9223372036854775807\" r=\"9\"/></SegmentTimeline></SegmentTemplate>",
            )
            else -> doc()
        }
    }

    private fun buildCases(): List<CaseRow> {
        val out = ArrayList<CaseRow>(256)
        fun add(
            family: String, shape: String, type: String, bandwidth: Long,
            segTicks: Long, timescale: Long, startNumber: Long, mediaToken: String,
            timeline: String = "-", listCount: Int = 0,
        ) {
            val id = "c" + (out.size + 1).toString().padStart(4, '0')
            val row = CaseRow(
                id, family, shape, type, bandwidth, segTicks, timescale, startNumber,
                mediaToken, timeline, listCount, "", "",
            )
            val outcome = DashManifest.plan(renderCase(row))
            val expect = when (outcome) {
                is DashPlanOutcome.Planned -> "P"
                is DashPlanOutcome.NeedsIndex -> "N"
                is DashPlanOutcome.Unsupported -> "U"
            }
            out += row.copy(expect = expect, detail = fingerprint(outcome))
        }

        repeat(12) { i ->
            add("tp_num", "single", "static", (i + 1) * 100L, 10, 1, (i % 4 + 1).toLong(), "qa26-\$Number\$.m4s")
        }
        repeat(9) { i ->
            val width = i + 1
            add("tp_pad", "single", "static", (i + 2) * 200L, 20, 2, i.toLong(), "qa26-\$Number%0${width}d\$.m4s")
        }
        repeat(10) { i ->
            val timeline = if (i % 2 == 0) "10:4:-" else "10:0:-;20:1:-"
            add("tp_time", "single", "static", (i + 3) * 300L, 10, 1, 1, "qa26-\$Time\$.m4s", timeline)
        }
        repeat(8) { i ->
            add("tp_bw", "dual", "static", (i + 4) * 400L, 10, 1, 1, "qa26-\$Number\$.m4s")
        }
        repeat(6) { i ->
            add("tp_repbase", "single", "static", (i + 5) * 500L, 10, 1, 1, "qa26-\$Number\$.m4s")
        }
        repeat(8) { i ->
            add("list_static", "single", "static", (i + 6) * 600L, 10, 1, i.toLong(), "-", listCount = i + 2)
        }
        repeat(6) { i ->
            add("list_live", "single", "dynamic", (i + 7) * 700L, 10, 1, 1, "-", listCount = i + 3)
        }
        repeat(6) { i ->
            add("base_static", "single", "static", (i + 8) * 800L, 10, 1, 1, "-")
        }
        repeat(8) { i ->
            add("live_regular", "single", "dynamic", (i + 9) * 900L, 10, 1, 1, "qa26-\$Time\$.m4s", "10:-1:-")
        }
        repeat(8) { i ->
            add("live_open", "single", "dynamic", (i + 10) * 1000L, 10, 1, 1, "qa26-\$Time\$.m4s", "10:0:-;20:1:-;30:-1:-")
        }
        repeat(6) { i ->
            add("live_clock", "single", "dynamic", (i + 11) * 1100L, 15, 3, 1, "qa26-\$Number\$.m4s")
        }
        add("list_cap", "single", "static", 9600L, 10, 1, 1, "-", listCount = 64)
        add("timeline_cap", "single", "static", 9700L, 10, 1, 1, "qa26-\$Time\$.m4s", "10:999:-")
        return out
    }

    private val corruptDefects = listOf(
        "dtd", "malformed", "root", "unknown-type", "protection", "audio",
        "two-periods", "period-start", "bad-timescale", "bad-duration",
        "bad-startnumber", "unknown-token", "timeline-gap", "open-static",
        "open-not-last", "nested-baseurl", "live-base", "bad-range",
        "no-duration", "no-availability", "too-large", "two-timelines", "overflow",
    )

    private fun buildCorrupt(): List<CorruptRow> = corruptDefects.mapIndexed { i, defect ->
        val id = "x" + (i + 1).toString().padStart(3, '0')
        val outcome = DashManifest.plan(renderCorrupt(CorruptRow(id, defect, "")))
        val reason = when (outcome) {
            is DashPlanOutcome.Unsupported -> outcome.reason
            else -> "ACCEPTED_BY_PRODUCTION"
        }
        CorruptRow(id, defect, reason)
    }

    private fun casesBody(cases: List<CaseRow>): String {
        val sb = StringBuilder(CASE_HEADER).append('\n')
        for (row in cases) sb.append(row.line()).append('\n')
        return sb.toString()
    }

    private fun corruptBody(corrupt: List<CorruptRow>): String {
        val sb = StringBuilder(CORRUPT_HEADER).append('\n')
        for (row in corrupt) sb.append(row.line()).append('\n')
        return sb.toString()
    }

    private fun dimBody(values: Map<String, String>): String {
        val sb = StringBuilder(DIM_HEADER).append('\n')
        for ((key, value) in values) sb.append(key).append('\t').append(value).append('\n')
        return sb.toString()
    }

    private fun dimensions(cases: List<CaseRow>, manifestDigest: String): Map<String, String> {
        val families = cases.map { it.family }.distinct().size
        val listedCap = cases.first { it.family == "timeline_cap" }.timeline.split(':')[1].toInt() + 1
        return linkedMapOf(
            "seed" to SEED.toString(),
            "case_count" to cases.size.toString(),
            "corrupt_count" to corruptDefects.size.toString(),
            "family_count" to families.toString(),
            "list_cap" to cases.filter { it.family.startsWith("list") }.maxOf { it.listCount }.toString(),
            "listed_cap" to listedCap.toString(),
            "cycle_count" to "6",
            "cases_sha" to sha256(casesBody(cases).toByteArray(Charsets.UTF_8)),
            "manifest_sha" to manifestDigest,
            "marker_len" to marker().length.toString(),
        )
    }

    /** Rebuilds every fixture byte in memory and compares against the committed artifact. */
    fun allBytes(dir: String): Map<String, ByteArray> {
        val cases = buildCases()
        val corrupt = buildCorrupt()
        val placeholder = dimBody(dimensions(cases, "pending"))
        val dimDigest = sha256(placeholder.toByteArray(Charsets.UTF_8))
        val finalDim = dimBody(dimensions(cases, dimDigest))
        val manifest = StringBuilder()
        for (name in DATA_FILES) {
            val bytes = when (name) {
                "contract.txt" -> CONTRACT_TEXT.toByteArray(Charsets.UTF_8)
                "cases.tsv" -> casesBody(cases).toByteArray(Charsets.UTF_8)
                "corrupt.tsv" -> corruptBody(corrupt).toByteArray(Charsets.UTF_8)
                else -> finalDim.toByteArray(Charsets.UTF_8)
            }
            manifest.append(sha256(bytes)).append("  ").append(name).append('\n')
        }
        return mapOf(
            "contract.txt" to CONTRACT_TEXT.toByteArray(Charsets.UTF_8),
            "cases.tsv" to casesBody(cases).toByteArray(Charsets.UTF_8),
            "corrupt.tsv" to corruptBody(corrupt).toByteArray(Charsets.UTF_8),
            "dimensions.tsv" to finalDim.toByteArray(Charsets.UTF_8),
            "sha256.txt" to manifest.toString().toByteArray(Charsets.UTF_8),
        )
    }

    /** Writes all pinned fixture bytes; regeneration is byte-identical on every call. */
    fun generate(dir: String): Int {
        val target = File(dir)
        if (!target.isDirectory && !target.mkdirs()) return 2
        val bytes = allBytes(target.path)
        for ((name, content) in bytes) File(target, name).writeBytes(content)
        return 0
    }

    fun readTable(path: String, header: String): List<List<String>> {
        val lines = File(path).readText(Charsets.UTF_8).split('\n')
        require(lines.isNotEmpty() && lines[0] == header) { "HEADER_STRICT" }
        return lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
    }

    fun readDims(path: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in File(path).readText(Charsets.UTF_8).lineSequence()) {
            if (line.isEmpty() || line == DIM_HEADER) continue
            val at = line.indexOf('\t')
            require(at > 0) { "DIM_FORMAT" }
            result[line.substring(0, at)] = line.substring(at + 1)
        }
        return result
    }

    fun privacyOffender(text: String): String? {
        if (text.contains(marker())) return "MARKER"
        if (SCHEME.containsMatchIn(text)) return "SCHEME"
        if (SENSITIVE.containsMatchIn(text)) return "SENSITIVE"
        if (PIN.containsMatchIn(text)) return "PIN"
        if (PAYLOAD.containsMatchIn(text)) return "PAYLOAD"
        return null
    }

    /** Deliberately lax reference parser: accepts any text that mentions the schema token. */
    fun laxAccepts(text: String): Boolean = text.contains("mpd", ignoreCase = true)

    /** Small helper used by the host cycle probe; keeps production calls in one place. */
    object DashPlanProbe {
        fun plan(row: CaseRow): String = fingerprint(DashManifest.plan(renderCase(row)))
    }

    /**
     * Runs the fixture-driven checks. `verify` enables every check. `mutant`
     * skips byte identity, manifest and dimension comparisons so a named
     * semantic guard fires first. `verify-digest` skips byte identity only.
     */
    fun checks(dir: String, mode: String): List<Check> {
        val out = ArrayList<Check>()
        fun check(code: String, ok: Boolean, note: String = "") { out += Check(code, ok, note) }

        if (mode == "verify") {
            val generated = allBytes(dir)
            var identical = true
            for ((name, bytes) in generated) {
                val file = File(dir, name)
                if (!file.isFile || !file.readBytes().contentEquals(bytes)) identical = false
            }
            check("BYTE_IDENTITY", identical)
        }
        if (mode != "mutant") {
            val lines = File(dir, "sha256.txt").readText(Charsets.UTF_8).lineSequence()
                .filter { it.isNotEmpty() }.toList()
            var ok = lines.size == DATA_FILES.size
            for (line in lines) {
                val parts = line.split("  ", limit = 2)
                if (parts.size != 2) {
                    ok = false
                    continue
                }
                val bytes = File(dir, parts[1]).readBytes()
                if (sha256(bytes) != parts[0]) ok = false
            }
            check("MANIFEST_MATCH", ok)
        }

        var dims = emptyMap<String, String>()
        var caseRows = emptyList<List<String>>()
        var corruptRows = emptyList<List<String>>()
        var headerOk = true
        try {
            dims = readDims(File(dir, "dimensions.tsv").path)
            caseRows = readTable(File(dir, "cases.tsv").path, CASE_HEADER)
            corruptRows = readTable(File(dir, "corrupt.tsv").path, CORRUPT_HEADER)
        } catch (e: Exception) {
            headerOk = false
        }
        if (mode != "mutant") {
            check(
                "DIMENSIONS", dims["seed"] == SEED.toString() &&
                    dims["case_count"] == caseRows.size.toString() &&
                    dims["corrupt_count"] == corruptRows.size.toString() &&
                    dims["cases_sha"] == sha256(casesBody(buildCases()).toByteArray(Charsets.UTF_8)) &&
                    dims["marker_len"] == marker().length.toString(),
            )
        }
        check("HEADER_STRICT", headerOk)
        check(
            "FIELD_BOUNDS", caseRows.all { it.size == 13 } && corruptRows.all { it.size == 3 } &&
                caseRows.all { it[0].length <= 16 && it[8].length <= 96 } &&
                corruptRows.all { it[0].length <= 16 && it[1].length <= 24 },
        )

        val expectedFamilies = setOf(
            "tp_num", "tp_pad", "tp_time", "tp_bw", "tp_repbase", "list_static",
            "list_live", "base_static", "live_regular", "live_open", "live_clock",
            "list_cap", "timeline_cap",
        )
        check("CASE_COVERAGE", expectedFamilies == caseRows.map { it[1] }.toSet() && caseRows.size == 89)

        var planOk = true
        var needsOk = true
        for (row in caseRows) {
            if (row.size != 13) continue
            val parsed = CaseRow(
                row[0], row[1], row[2], row[3], row[4].toLongOrNull() ?: 0L,
                row[5].toLongOrNull() ?: 0L, row[6].toLongOrNull() ?: 0L,
                row[7].toLongOrNull() ?: 0L, row[8], row[9],
                row[10].toIntOrNull() ?: 0, row[11], row[12],
            )
            val outcome = DashManifest.plan(renderCase(parsed))
            when (row[11]) {
                "P" -> if (outcome !is DashPlanOutcome.Planned || fingerprint(outcome) != row[12]) planOk = false
                "N" -> if (outcome !is DashPlanOutcome.NeedsIndex || fingerprint(outcome) != row[12]) needsOk = false
                else -> planOk = false
            }
        }
        check("PLAN_FINGERPRINT", planOk)
        check("NEEDS_INDEX", needsOk)

        val expandOk = DashManifest.expand("qa26-\$Number%03d\$.m4s", "r1", 800, 7) == "qa26-007.m4s" &&
            DashManifest.expand("qa26-\$\$-\$Time\$.m4s", "r1", 800, 1, 90) == "qa26-\$-90.m4s" &&
            DashManifest.expand("qa26-\$Foo\$.m4s", "r1", 800, 1) == null
        check("TEMPLATE_EXPAND", expandOk)

        var corruptOk = true
        var laxOk = true
        for (row in corruptRows) {
            if (row.size != 3) continue
            val parsed = CorruptRow(row[0], row[1], row[2])
            val rendered = renderCorrupt(parsed)
            val outcome = DashManifest.plan(rendered)
            val reason = when (outcome) {
                is DashPlanOutcome.Unsupported -> outcome.reason
                else -> "ACCEPTED_BY_PRODUCTION"
            }
            if (reason != row[2] || row[2] == "ACCEPTED_BY_PRODUCTION") corruptOk = false
            if (!laxAccepts(rendered)) laxOk = false
        }
        check("CORRUPT_REJECT", corruptOk)
        check("LAX_ACCEPT", laxOk && corruptRows.size == corruptDefects.size)

        val listMax = caseRows.filter { it[1].startsWith("list") }
            .maxOfOrNull { it[10].toIntOrNull() ?: 0 }
        val capOk = dims["list_cap"] == listMax.toString() &&
            dims["listed_cap"] == "1000" &&
            corruptRows.any { it[1] == "too-large" } &&
            renderCorrupt(CorruptRow("probe", "too-large", "")).length > DashManifest.MAX_MANIFEST_CHARS &&
            dims["cycle_count"] == "6"
        check("CAP_BINDING", capOk)

        val names = DATA_FILES.map { it to privacyOffender(File(dir, it).readText(Charsets.UTF_8)) }
            .filter { it.second != null }
        check("PRIVACY_FIXTURE", names.isEmpty(), names.joinToString(",") { "${it.first}:${it.second}" })
        val diagnostic = "checks=${out.size} marker_len=${marker().length} cases=${caseRows.size} corrupt=${corruptRows.size}"
        check("PRIVACY_REPORT", privacyOffender(diagnostic) == null)
        return out
    }

    /** Deterministic on-device mirror: regenerate, plan pinned probes, report bounded PASS/FAIL lines. */
    fun mirror(dir: String): List<Check> {
        val generated = allBytes(dir)
        var bytesOk = true
        for ((name, bytes) in generated) {
            val file = File(dir, name)
            if (!file.isFile || !file.readBytes().contentEquals(bytes)) bytesOk = false
        }
        val probe = DashManifest.plan(
            "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" " +
                "mediaPresentationDuration=\"PT100S\"><Period><AdaptationSet contentType=\"video\">" +
                "<Representation id=\"mp\" bandwidth=\"800\"><SegmentTemplate timescale=\"1\" duration=\"10\" " +
                "startNumber=\"1\" media=\"qa26-\$Number\$.m4s\"/></Representation></AdaptationSet></Period></MPD>",
        )
        val planOk = probe is DashPlanOutcome.Planned &&
            probe.plan.segmentCount == 10L && probe.plan.representationId == "mp"
        val corrupt = DashManifest.plan(renderCorrupt(CorruptRow("m1", "dtd", "")))
        val corruptOk = corrupt is DashPlanOutcome.Unsupported
        val expandOk = DashManifest.expand("qa26-\$Number%03d\$.m4s", "mp", 800, 7) == "qa26-007.m4s"
        return listOf(
            Check("MIRROR_BYTES", bytesOk),
            Check("MIRROR_PLAN", planOk),
            Check("MIRROR_CORRUPT", corruptOk),
            Check("MIRROR_EXPAND", expandOk),
            Check("MIRROR_DIGEST", sha256(generated.getValue("cases.tsv")) ==
                readDims(File(dir, "dimensions.tsv").path)["cases_sha"]),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = args.getOrElse(1) { "qa/fixtures/qa26" }
        when (args.getOrElse(0) { "mirror" }) {
            "generate" -> {
                val status = generate(dir)
                println("GENERATE\tstatus=$status")
            }
            "mirror" -> {
                val results = mirror(dir)
                for (r in results) println("MIRROR\t${if (r.ok) "PASS" else "FAIL"}\t${r.code}")
                println("MIRROR_SUMMARY\tchecks=${results.size}\tpass=${results.count { it.ok }}")
            }
            else -> println("MODE\tUNSUPPORTED")
        }
    }
}
