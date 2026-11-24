package com.devtunasoft.tuneros.qa59

import com.devtunasoft.tuneros.core.DashCaptureEngine
import com.devtunasoft.tuneros.core.DashCaptureSource
import com.devtunasoft.tuneros.core.DashSegmentNotPublishedException
import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.LoadedDashManifest
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * QA-59 bounded, framework-free contract for the listed-live DASH refresh and publish-grace
 * verdict matrix. The same object is compiled into the JVM host gate and the instrumented
 * mirror; it calls the real production DashCaptureEngine rather than a model of it.
 */
object Qa59Contract {
    const val MAX_ROWS = 12
    const val FIELD_COUNT = 16
    const val HEADER =
        "id\tfamily\tstart\tend\tperiod\tfirst\tlast\tchangeAt\tunansweredFrom\tmissing\treason\tdetail\toutHex\tcopies\treads\tsleeps"
    const val SEED = 0x51415935L
    const val STALL_LIMIT = 30_000L
    const val BASE = "https://dash.invalid/qa59/"
    const val AVAIL = "1970-01-01T00:16:40Z"
    const val REPORT = "QA-59 bounded DASH listed-live verdict matrix. Scratch fixture only."

    val ALL_FILES = listOf("rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt")
    val DATA_FILES = listOf("rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt")
    val FAMILIES = listOf(
        "stall", "fell_behind", "changed", "misaligned", "unanswered_once", "unanswered_stall",
        "clamp_low", "clamp_high", "no_gap", "grace_inside", "grace_outside", "healthy",
    )

    private val DOLLAR = '$'

    class Row(val fields: List<String>) {
        val id: String get() = fields[0]
        val family: String get() = fields[1]
        val start: Long get() = fields[2].toLong()
        val end: Long get() = fields[3].toLong()
        val period: String get() = fields[4]
        val first: Int get() = fields[5].toInt()
        val last: Int get() = fields[6].toInt()
        val changeAt: Int get() = fields[7].toInt()
        val unansweredFrom: Int get() = fields[8].toInt()
        val missing: Int get() = fields[9].toInt()
        val expected: String get() = fields.subList(10, FIELD_COUNT).joinToString("|")
        fun render(): String = fields.joinToString("\t")
    }

    private data class RunResult(
        val reason: String,
        val detail: String,
        val out: ByteArray,
        val copies: List<Int>,
        val reads: Int,
        val sleeps: Int,
    ) {
        fun fingerprint(): String = listOf(
            reason, detail, hex(out), copies.joinToString(","), reads.toString(), sleeps.toString(),
        ).joinToString("|")
    }

    private class Clock(seconds: Long) {
        var millis: Long = seconds * 1000L
        var sleeps: Int = 0
        fun now(): Long = millis / 1000L
        fun sleep(delayMillis: Long) {
            millis += delayMillis
            sleeps++
        }
    }

    private class FakeSource(private val row: Row, private val clock: Clock) : DashCaptureSource {
        var reads = 0
        val copied = ArrayList<Int>()

        override fun loadManifest(url: String): LoadedDashManifest {
            reads++
            if ((row.family == "unanswered_once" && reads == row.unansweredFrom) ||
                (row.family == "unanswered_stall" && reads >= row.unansweredFrom)
            ) {
                throw IllegalStateException("manifest read did not answer")
            }
            return LoadedDashManifest(BASE + "manifest.mpd", manifestAt(row, reads))
        }

        override fun loadRange(url: String, range: HlsByteRange): ByteArray =
            error("no index range in this fixture")

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            val number = if (url.contains("init-v1")) 0 else
                url.substringAfter("chunk-v1-").substringBefore(".m4s").toInt()
            copied += number
            if (cancelled()) return StreamRecorder.Result(0, StreamRecorder.StopReason.CANCELLED)
            if (number != 0 && number == row.missing) {
                val published = when (row.family) {
                    "grace_inside" -> row.start + 59L
                    else -> Long.MAX_VALUE / 4L
                }
                if (clock.now() < published) throw DashSegmentNotPublishedException()
            }
            val bytes = if (number == 0) byteArrayOf(9, 9) else byteArrayOf(number.toByte())
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private fun manifestAt(row: Row, read: Int): String = when (row.family) {
        "fell_behind" -> if (read >= 2) listed(row, 6, 9, "v1") else listed(row, row.first, row.last, "v1")
        "changed" -> if (read >= 2) listed(row, row.first, row.last, "v2") else listed(row, row.first, row.last, "v1")
        "misaligned" -> if (read >= 2) timeline(row, "<S t=\"0\" d=\"2500\" r=\"3\"/>") else
            timeline(row, "<S t=\"0\" d=\"3000\" r=\"1\"/>")
        "grace_inside", "grace_outside" -> template(row, "30000")
        "healthy" -> when {
            read >= 3 -> listed(row, 4, 7, "v1")
            read == 2 -> listed(row, 2, 5, "v1")
            else -> listed(row, 1, 3, "v1")
        }
        else -> listed(row, row.first, row.last, "v1")
    }

    private fun mpd(period: String, body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\" availabilityStartTime=\"$AVAIL\" " +
            "minimumUpdatePeriod=\"PT$period\">\n" +
            "  <Period id=\"0\" start=\"PT0S\">\n" +
            "    <AdaptationSet mimeType=\"video/mp4\">\n" +
            body + "\n" +
            "    </AdaptationSet>\n  </Period>\n</MPD>\n"

    private fun listed(row: Row, first: Int, last: Int, rep: String): String {
        val urls = (first..last).joinToString("\n") { n ->
            "        <SegmentURL media=\"chunk-v1-" + "%05d".format(n) + ".m4s\"/>"
        }
        return mpd(
            row.period,
            "      <Representation id=\"$rep\" bandwidth=\"500000\">\n" +
                "        <SegmentList timescale=\"1000\" duration=\"3000\" startNumber=\"$first\">\n" +
                "          <Initialization sourceURL=\"init-v1.m4s\"/>\n" +
                urls + "\n" +
                "        </SegmentList>\n      </Representation>",
        )
    }

    private fun template(row: Row, duration: String): String =
        mpd(
            row.period,
            "      <Representation id=\"v1\" bandwidth=\"500000\">\n" +
                "        <SegmentTemplate timescale=\"1000\" duration=\"$duration\" startNumber=\"1\" " +
                "initialization=\"init-v1.m4s\" media=\"chunk-v1-$DOLLAR" + "Number%05d$DOLLAR.m4s\"/>\n" +
                "      </Representation>",
        )

    private fun timeline(row: Row, entries: String): String =
        mpd(
            row.period,
            "      <Representation id=\"v1\" bandwidth=\"500000\">\n" +
                "        <SegmentTemplate timescale=\"1000\" startNumber=\"1\" " +
                "initialization=\"init-v1.m4s\" media=\"chunk-v1-$DOLLAR" + "Number%05d$DOLLAR.m4s\">\n" +
                "          <SegmentTimeline>$entries</SegmentTimeline>\n" +
                "        </SegmentTemplate>\n      </Representation>",
        )

    private fun runCase(row: Row): RunResult {
        val clock = Clock(row.start)
        val source = FakeSource(row, clock)
        val out = ByteArrayOutputStream()
        val result = DashCaptureEngine().record(
            BASE + "manifest.mpd", out, row.end, source, { false }, clock::now, clock::sleep,
        )
        return RunResult(
            result.stopReason.name, result.detail.orEmpty(), out.toByteArray(),
            source.copied, source.reads, clock.sleeps,
        )
    }

    private fun defaultCases(): List<Row> {
        val params = listOf(
            arrayOf("stall", "1000", "1100", "10S", "1", "3", "0", "0", "0"),
            arrayOf("fell_behind", "1000", "1100", "2S", "1", "3", "0", "0", "0"),
            arrayOf("changed", "1000", "1100", "2S", "1", "3", "2", "0", "0"),
            arrayOf("misaligned", "1005", "1100", "2S", "1", "4", "2", "0", "0"),
            arrayOf("unanswered_once", "1000", "1020", "2S", "1", "3", "0", "2", "0"),
            arrayOf("unanswered_stall", "1000", "1100", "10S", "1", "3", "0", "2", "0"),
            arrayOf("clamp_low", "1000", "1100", "0.2S", "1", "3", "0", "0", "0"),
            arrayOf("clamp_high", "1000", "1100", "60S", "1", "3", "0", "0", "0"),
            arrayOf("no_gap", "1000", "1100", "2S", "1", "3", "0", "0", "3"),
            arrayOf("grace_inside", "1060", "1200", "2S", "1", "1", "0", "0", "2"),
            arrayOf("grace_outside", "1060", "1200", "2S", "1", "1", "0", "0", "2"),
            arrayOf("healthy", "1000", "1012", "2S", "1", "3", "0", "0", "0"),
        )
        return params.mapIndexed { index, p ->
            Row(
                listOf("q59-%02d".format(index + 1)) + p.asList() +
                    listOf("", "", "", "", "", ""),
            )
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Derived at run time from the seed; never written as a literal in source, fixture or report. */
    fun derivedToken(): String {
        var value = SEED
        for (index in 0 until 16) {
            value = value * 6364136223846793005L + 1442695040888963407L
            value = value xor (value ushr 17)
        }
        return java.lang.Long.toHexString(value and 0xffffffffL).padStart(8, '0')
    }

    private fun lines(bytes: ByteArray): List<String> =
        String(bytes, Charsets.UTF_8).split('\n').dropLastWhile { it.isEmpty() }

    private fun corruptBlocks(): List<Pair<String, String>> {
        val shortRow = defaultCases()[0].render().split('\t').dropLast(1).joinToString("\t")
        val unknown = defaultCases()[0].fields.toMutableList().also { it[1] = "unknown" }.joinToString("\t")
        val outOfRange = defaultCases()[0].fields.toMutableList().also { it[0] = "q59-9" }.joinToString("\t")
        return listOf(
            "header-bad" to HEADER.substringBeforeLast('\t'),
            "fields-short" to shortRow,
            "family-unknown" to unknown,
            "id-range" to outOfRange,
        )
    }

    private fun parseCorrupt(bytes: ByteArray): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val all = lines(bytes)
        var index = 0
        while (index < all.size) {
            if (!all[index].startsWith("BEGIN ")) return emptyList()
            val name = all[index].removePrefix("BEGIN ")
            if (index + 2 >= all.size || all[index + 2] != "END $name") return emptyList()
            val payload = all[index + 1]
            val code = when {
                payload != HEADER && payload.startsWith("id\t") && payload.split('\t').size < FIELD_COUNT -> "ROW_BOUNDS"
                payload.split('\t').size != FIELD_COUNT -> "ROW_BOUNDS"
                else -> {
                    val row = Row(payload.split('\t'))
                    when {
                        !row.id.matches(Regex("q59-[0-9]{2}")) -> "ID_RANGE"
                        !FAMILIES.contains(row.family) -> "FAMILY_REJECT"
                        else -> "ACCEPTED"
                    }
                }
            }
            result += name to code
            index += 3
        }
        return result
    }

    fun generate(): Map<String, ByteArray> {
        val rows = defaultCases().map { row ->
            val run = runCase(row)
            Row(row.fields.take(10) + listOf(
                run.reason, run.detail, hex(run.out), run.copies.joinToString(","),
                run.reads.toString(), run.sleeps.toString(),
            ))
        }
        val rowsText = HEADER + "\n" + rows.joinToString("\n") { it.render() } + "\n"
        val mirror = sha256((rows.joinToString("\n") { it.render() } + "\n").toByteArray(Charsets.UTF_8))
        val dims = listOf(
            "ROWS\t${rows.size}",
            "FIELD_COUNT\t$FIELD_COUNT",
            "FAMILY_COUNT\t${rows.map { it.family }.distinct().size}",
            "MAX_ROWS\t$MAX_ROWS",
            "MIRROR_DIGEST\t$mirror",
        ).joinToString("\n") + "\n"
        val corrupt = corruptBlocks().joinToString("") { (name, payload) ->
            "BEGIN $name\n$payload\nEND $name\n"
        }
        val report = REPORT + "\n"
        val data = linkedMapOf(
            "rows.tsv" to rowsText.toByteArray(Charsets.UTF_8),
            "dimensions.tsv" to dims.toByteArray(Charsets.UTF_8),
            "corrupt.txt" to corrupt.toByteArray(Charsets.UTF_8),
            "report-copy.txt" to report.toByteArray(Charsets.UTF_8),
        )
        val manifest = DATA_FILES.sorted().joinToString("") { name ->
            sha256(data.getValue(name)) + "\t" + name + "\n"
        }
        val out = LinkedHashMap<String, ByteArray>(data)
        out["sha256.txt"] = manifest.toByteArray(Charsets.UTF_8)
        return out
    }

    /** One emitted line per check; the runner parses these fields by position. */
    fun verifyModel(mode: String, files: Map<String, ByteArray>, sink: (String) -> Unit): Int {
        val emitted = ArrayList<String>()
        fun say(line: String) {
            emitted += line
            sink(line)
        }
        var first = ""
        var passed = 0
        var failed = 0
        fun check(code: String, ok: Boolean, detail: String = "") {
            if (ok) {
                passed++
                say("CHECK\t$code\tPASS\t$detail")
            } else {
                failed++
                if (first.isEmpty()) first = code
                say("CHECK\t$code\tFAIL\t$detail")
            }
        }
        val skip = when (mode) {
            "verify" -> emptySet()
            "verify-manifest" -> setOf("BYTE_IDENTITY")
            "verify-pinned" -> setOf("BYTE_IDENTITY", "MANIFEST_CONSISTENT")
            "verify-semantic" -> setOf("BYTE_IDENTITY", "MANIFEST_CONSISTENT", "PINNED_DIGEST")
            else -> {
                say("FIRST\tBAD_MODE")
                say("VERDICT\tFAIL")
                return 2
            }
        }
        fun active(code: String) = !skip.contains(code)

        val missing = ALL_FILES.firstOrNull { !files.containsKey(it) }
        if (missing != null) {
            say("FIRST\tMISSING_FIXTURE")
            say("VERDICT\tFAIL")
            return 1
        }

        val rowsRaw = lines(files.getValue("rows.tsv"))
        if (active("HEADER_STRICT")) check("HEADER_STRICT", rowsRaw.firstOrNull() == HEADER, "header")
        val body = rowsRaw.drop(1)
        val boundsOk = body.size <= MAX_ROWS + 1 && body.all { it.split('\t').size == FIELD_COUNT }
        if (active("ROW_BOUNDS")) check("ROW_BOUNDS", boundsOk, "rows=${body.size}")

        val manifest = hashMapOf<String, String>()
        for (line in lines(files.getValue("sha256.txt"))) {
            val parts = line.split('\t')
            if (parts.size == 2) manifest[parts[1]] = parts[0]
        }
        val consistent = DATA_FILES.size == manifest.size &&
            DATA_FILES.all { manifest[it] == sha256(files.getValue(it)) }
        if (active("MANIFEST_CONSISTENT")) check("MANIFEST_CONSISTENT", consistent, "files=${DATA_FILES.size}")

        val regenerated = generate()
        if (active("BYTE_IDENTITY")) {
            check(
                "BYTE_IDENTITY", ALL_FILES.all { regenerated[it]!!.contentEquals(files.getValue(it)) },
                "files=${ALL_FILES.size}",
            )
        }

        val dims = hashMapOf<String, String>()
        for (line in lines(files.getValue("dimensions.tsv"))) {
            val parts = line.split('\t')
            if (parts.size == 2) dims[parts[0]] = parts[1]
        }
        val rows = body.map { Row(it.split('\t')) }
        val mirror = sha256((rows.joinToString("\n") { it.render() } + "\n").toByteArray(Charsets.UTF_8))
        if (active("PINNED_DIGEST")) check("PINNED_DIGEST", dims["MIRROR_DIGEST"] == mirror, "rows=${rows.size}")

        val capOk = rows.size == MAX_ROWS && dims["ROWS"] == MAX_ROWS.toString() &&
            dims["FIELD_COUNT"] == FIELD_COUNT.toString() &&
            dims["FAMILY_COUNT"] == FAMILIES.size.toString() &&
            rows.map { it.family }.distinct().size == FAMILIES.size &&
            FAMILIES.all { family -> rows.any { it.family == family } }
        if (active("CAP_BINDING")) check("CAP_BINDING", capOk, "rows=${rows.size} cap=$MAX_ROWS")

        val expectedCorrupt = corruptBlocks()
        val parsedCorrupt = parseCorrupt(files.getValue("corrupt.txt"))
        val corruptOk = parsedCorrupt.size == expectedCorrupt.size &&
            parsedCorrupt.zip(expectedCorrupt).all { (actual, expected) ->
                actual.first == expected.first && actual.second != "ACCEPTED"
            }
        if (active("CORRUPT_FAIL_CLOSED")) check("CORRUPT_FAIL_CLOSED", corruptOk, "variants=${expectedCorrupt.size}")

        var verdictOk = true
        val noGapRows = mutableListOf<Row>()
        for (row in rows) {
            val run = runCase(row)
            val actual = run.fingerprint()
            if (actual != row.expected) {
                verdictOk = false
                say("MISMATCH\t${row.id}\t" + run.reason)
            }
            if (row.family == "no_gap") noGapRows += row
        }
        if (active("CONTRACT_VERDICTS")) check("CONTRACT_VERDICTS", verdictOk, "cases=${rows.size}")

        // Independent literal expectations: the boundary must bind at the exact threshold.
        val graceRow = rows.first { it.family == "grace_outside" }
        val graceRun = runCase(graceRow)
        val graceOk = graceRun.reason == "UNSUPPORTED" && graceRun.sleeps == 120 &&
            graceRun.out.contentEquals(byteArrayOf(9, 9)) && graceRun.copies.none { it > 2 }
        if (active("BOUNDARY_GRACE")) check("BOUNDARY_GRACE", graceOk, "sleeps=${graceRun.sleeps}")

        val stallRow = rows.first { it.family == "stall" }
        val stallRun = runCase(stallRow)
        val stallOk = stallRun.reason == "UNSUPPORTED" && stallRun.sleeps == 3
        if (active("BOUNDARY_STALL")) {
            if (stallRun.sleeps >= STALL_LIMIT / 10_000L) {
                check("BOUNDARY_STALL", stallOk, "sleeps=${stallRun.sleeps}")
            } else {
                check("BOUNDARY_STALL", false, "sleeps=${stallRun.sleeps}")
            }
        }

        val low = runCase(rows.first { it.family == "clamp_low" })
        val high = runCase(rows.first { it.family == "clamp_high" })
        val clampOk = low.sleeps == 30 && high.sleeps == 3
        if (active("BOUNDARY_REFRESH_CLAMP")) {
            check("BOUNDARY_REFRESH_CLAMP", clampOk, "low=${low.sleeps} high=${high.sleeps}")
        }

        var gapOk = true
        for (row in noGapRows) {
            val run = runCase(row)
            val complete = byteArrayOf(9, 9, 1, 2, 3)
            val prefix = run.out.size <= complete.size &&
                complete.copyOfRange(0, run.out.size).contentEquals(run.out) &&
                !run.copies.contains(row.missing + 1)
            if (!prefix) {
                gapOk = false
            }
        }
        if (active("NO_SILENT_GAP")) check("NO_SILENT_GAP", gapOk, "rows=${noGapRows.size}")

        val allText = files.values.joinToString("\n") { String(it, Charsets.UTF_8) } + "\n" + emitted.joinToString("\n")
        val token = derivedToken()
        val url = Regex("(?i)https?://|ftp://|www\\.")
        val credential = Regex("(?i)password|passwd|api[_-]?key|key[=:]|token[=:]|secret|pin[=:]|credential")
        val payload = Regex("(?i)recording-payload|m3u8|xmltv|stream-key|payload=")
        val leak = allText.contains(token) || url.containsMatchIn(allText) ||
            credential.containsMatchIn(allText) || payload.containsMatchIn(allText)
        if (active("PRIVACY_SCAN")) check("PRIVACY_SCAN", !leak, "scan= fixture+report")

        val heapMax = Runtime.getRuntime().maxMemory()
        var peak = 0L
        var baseline = 0L
        var stable = true
        for (cycle in 0 until 3) {
            repeat(rows.size) { runCase(rows[it % rows.size]) }
            System.gc()
            Thread.sleep(10L)
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (cycle == 0) baseline = used else if (used > baseline + 8L * 1024 * 1024) stable = false
            if (used > peak) peak = used
        }
        say("MEM\theapMax=$heapMax\tpeakUsed=$peak\tbaseline=$baseline")
        if (active("MEM_STABLE")) check("MEM_STABLE", heapMax <= 100663296L && peak < 32L * 1024 * 1024 && stable, "heapMax=$heapMax")

        val before = Thread.getAllStackTraces().size
        repeat(rows.size) { runCase(rows[it]) }
        val after = Thread.getAllStackTraces().size
        val leaked = Thread.getAllStackTraces().keys.count { it.name.contains("qa59", ignoreCase = true) }
        say("THREADS\tbefore=$before\tafter=$after\tleaked=$leaked")
        if (active("THREAD_STABLE")) check("THREAD_STABLE", before == after && leaked == 0, "before=$before after=$after")

        if (first.isEmpty()) {
            say("FIRST\tNONE")
            say("SUMMARY\tchecks=$passed\tfail=$failed")
            say("VERDICT\tPASS")
            return 0
        }
        say("FIRST\t$first")
        say("SUMMARY\tchecks=$passed\tfail=$failed")
        say("VERDICT\tFAIL")
        return 1
    }
}
