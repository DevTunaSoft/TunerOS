package com.devtunasoft.tuneros.qa06

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import kotlin.system.exitProcess

data class Qa06Result(val id: String, val passed: Boolean, val detail: String)

/**
 * QA-06 large EPG assertions, shared by the platform instrumentation and the JVM smoke run.
 *
 * The fixture is generated in memory from fixed constants: 25,000 channels, 250,000 programmes,
 * a three-hour guide window and a 24-row paged guide model. Nothing is fetched, no app source is
 * touched, and every report/diagnostic is checked for URL-like or credential-like tokens before it
 * is emitted. This complements QA-01 (compiled small-layout row/D-pad/OK) and QA-05 (small-fixture
 * search/hide-no-EPG); it does not repeat their cases.
 */
object Qa06LargeEpgAssertions {
    private const val CHANNEL_COUNT = 25_000
    private const val PROGRAMMES_PER_CHANNEL = 10
    private const val PAGE_ROWS = 24
    private const val MAX_CELLS = 8
    private const val SLOT = 1_800L
    private const val BASE = 1_800_000_000L
    private const val WINDOW_FROM = BASE + 2L * SLOT
    private const val WINDOW_TO = WINDOW_FROM + 6L * SLOT
    private const val PAGE_COUNT = (CHANNEL_COUNT + PAGE_ROWS - 1) / PAGE_ROWS
    private const val TOTAL_PROGRAMMES = CHANNEL_COUNT * PROGRAMMES_PER_CHANNEL
    private const val MAX_FIXTURE_BYTES = 32 * 1024 * 1024
    private const val SAMPLE_STEP = 97
    private const val MARKER = "QUA06-LARGE-EPG-V1"
    private const val MANIFEST_BEGIN = "// QA06-MANIFEST-BEGIN"
    private const val MANIFEST_END = "// QA06-MANIFEST-END"

    private val FORBIDDEN = Pattern.compile(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|drm=|provider\\.)",
    )

    private val EMBEDDED_MANIFEST = """
// QA06-MANIFEST-BEGIN
QUA06-LARGE-EPG-V1
channels=25000
programmes=250000
window_from=1800003600
window_to=1800014400
page_rows=24
max_cells=8
sha256=873347fe676274dc5e2064c572d7b256e5041cdd174cd97d3619dc62b81d57ce
// QA06-MANIFEST-END
""".trimIndent()

    /** Same bytes as qa/fixtures/qa06/manifest.txt; the host gate checks the committed copy. */
    fun embeddedManifest(): String = EMBEDDED_MANIFEST.lineSequence()
        .filter { it != MANIFEST_BEGIN && it != MANIFEST_END }
        .joinToString("\n", postfix = "\n")

    private fun id(index: Int): String = String.format(Locale.ROOT, "c-%05d", index)
    private fun guide(index: Int): String = String.format(Locale.ROOT, "g-%05d", index)
    private fun name(index: Int): String = String.format(Locale.ROOT, "QA06 Channel %05d", index)
    private fun title(index: Int, slot: Int): String =
        String.format(Locale.ROOT, "QA06 Show %05d-%02d", index, slot)

    private fun pageIndices(): List<Int> = listOf(0, 1, PAGE_COUNT / 2, PAGE_COUNT - 1)

    private fun sampleIndices(): Set<Int> {
        val indices = LinkedHashSet<Int>()
        for (page in pageIndices()) {
            val first = page * PAGE_ROWS
            val count = minOf(PAGE_ROWS, CHANNEL_COUNT - first)
            indices += first
            indices += first + count - 1
        }
        for (index in CHANNEL_COUNT - 1 downTo 0 step SAMPLE_STEP) indices += index
        indices += CHANNEL_COUNT - 1
        return indices
    }

    /** Byte-identical to scripts/qa06/Qa06LargeEpgGate.java generate(), including the final LF. */
    fun generateFixture(): String {
        val out = StringBuilder(16 * 1024 * 1024)
        out.append("# QA-06 deterministic synthetic large EPG scratch fixture; loopback-only; do not edit by hand.\n")
        out.append("MARKER ").append(MARKER).append('\n')
        out.append("VERSION 1\n")
        out.append("COUNTS ").append(CHANNEL_COUNT).append(' ').append(TOTAL_PROGRAMMES).append('\n')
        out.append("WINDOW ").append(WINDOW_FROM).append(' ').append(WINDOW_TO).append('\n')
        out.append("PAGE_ROWS ").append(PAGE_ROWS).append('\n')
        out.append("MAX_CELLS ").append(MAX_CELLS).append('\n')
        for (i in 0 until CHANNEL_COUNT) {
            out.append("CH ").append(id(i)).append('|').append(name(i)).append('|')
                .append(guide(i)).append("|scratch-loopback:qa06/")
                .append(String.format(Locale.ROOT, "%05d", i)).append('\n')
        }
        for (i in 0 until CHANNEL_COUNT) {
            for (slot in 0 until PROGRAMMES_PER_CHANNEL) {
                val start = BASE + slot * SLOT
                out.append("PG ").append(guide(i)).append('|').append(title(i, slot)).append('|')
                    .append(start).append('|').append(start + SLOT).append('\n')
            }
        }
        for (page in pageIndices()) {
            val first = page * PAGE_ROWS
            val count = minOf(PAGE_ROWS, CHANNEL_COUNT - first)
            out.append("PAGE ").append(page).append('|').append(first).append('|').append(count)
                .append('|').append(id(first)).append('|').append(id(first + count - 1)).append('\n')
        }
        require(out.length <= MAX_FIXTURE_BYTES) { "fixture byte bound exceeded" }
        return out.toString()
    }

    fun runAll(): List<Qa06Result> {
        val fixture = generateFixture()
        val bytes = fixture.toByteArray(StandardCharsets.UTF_8)
        val sha = digest(bytes)
        val manifest = embeddedManifest()
        val errors = linkedMapOf<String, MutableList<String>>()
        for (name in NAMES) errors[name] = ArrayList()
        fun fail(name: String, detail: String) {
            val list = errors.getValue(name)
            if (list.size < 4) list += detail
        }

        val expectedSha = manifest.lineSequence()
            .firstOrNull { it.startsWith("sha256=") }
            ?.removePrefix("sha256=")
        if (bytes.size <= MAX_FIXTURE_BYTES && sha == expectedSha && manifest == EMBEDDED_MANIFEST.stripMarkers()) {
            // digest and byte bound are correct
        } else {
            fail("qa06-fixture-digest", "bytes=${bytes.size} expected sha absent or mismatched")
        }

        val samples = sampleIndices()
        val sampleGuides = samples.map { guide(it) }.toHashSet()
        val sampleEntries = HashMap<String, MutableList<Pair<Long, Long>>>()
        val programmeCount = HashMap<String, Int>()
        val lastStart = HashMap<String, Long>()
        var channelRecords = 0
        var programmeRecords = 0
        var pageRecords = 0
        var markerCount = 0
        var headerVersion = -1
        var headerChannels = -1
        var headerProgrammes = -1
        var headerPageRows = -1
        var headerMaxCells = -1
        var headerWindowFrom = -1L
        var headerWindowTo = -1L

        for (raw in fixture.lineSequence()) {
            val line = raw.replace("\r", "")
            if (line.isEmpty()) continue
            if (FORBIDDEN.matcher(line).find()) fail("qa06-report-privacy", "fixture line rejected")
            if (line.startsWith("#")) continue
            val fields = line.split('|')
            val head = fields[0].split(' ')
            when (head[0]) {
                "MARKER" -> {
                    markerCount++
                    if (fields.size != 1 || head.size != 2 || head[1] != MARKER) {
                        fail("qa06-fixture-digest", "marker")
                    }
                }
                "VERSION" -> headerVersion = head.getOrNull(1)?.toIntOrNull() ?: -1
                "COUNTS" -> {
                    headerChannels = head.getOrNull(1)?.toIntOrNull() ?: -1
                    headerProgrammes = head.getOrNull(2)?.toIntOrNull() ?: -1
                }
                "WINDOW" -> {
                    headerWindowFrom = head.getOrNull(1)?.toLongOrNull() ?: -1L
                    headerWindowTo = head.getOrNull(2)?.toLongOrNull() ?: -1L
                }
                "PAGE_ROWS" -> headerPageRows = head.getOrNull(1)?.toIntOrNull() ?: -1
                "MAX_CELLS" -> headerMaxCells = head.getOrNull(1)?.toIntOrNull() ?: -1
                "CH" -> {
                    channelRecords++
                    val expected = id(channelRecords - 1)
                    if (fields.size != 4 || head.size != 2 || head[1] != expected ||
                        fields[1] != name(channelRecords - 1) || fields[2] != guide(channelRecords - 1) ||
                        fields[3] != "scratch-loopback:qa06/" + String.format(Locale.ROOT, "%05d", channelRecords - 1)
                    ) {
                        fail("qa06-channel-order", "row=$channelRecords")
                    }
                }
                "PG" -> {
                    programmeRecords++
                    val channelGuide = head.getOrNull(1).orEmpty()
                    val start = fields.getOrNull(2)?.toLongOrNull() ?: -1L
                    val end = fields.getOrNull(3)?.toLongOrNull() ?: -1L
                    val index = channelGuide.removePrefix("g-").toIntOrNull()
                    val slot = programmeCount[channelGuide] ?: 0
                    val expectedTitle = index?.let { title(it, slot) }
                    if (fields.size != 4 || head.size != 2 || index == null ||
                        index !in 0 until CHANNEL_COUNT || end <= start || end - start > 2 * SLOT ||
                        expectedTitle == null || fields[1] != expectedTitle ||
                        (lastStart[channelGuide] != null && start <= lastStart[channelGuide]!!)
                    ) {
                        fail("qa06-programme-order", "programme=$programmeRecords")
                    }
                    programmeCount[channelGuide] = slot + 1
                    lastStart[channelGuide] = start
                    if (channelGuide in sampleGuides && end > WINDOW_FROM && start < WINDOW_TO) {
                        sampleEntries.getOrPut(channelGuide) { ArrayList() } += start to end
                    }
                }
                "PAGE" -> {
                    pageRecords++
                    val page = head.getOrNull(1)?.toIntOrNull() ?: -1
                    val first = fields.getOrNull(1)?.toIntOrNull() ?: -1
                    val count = fields.getOrNull(2)?.toIntOrNull() ?: -1
                    val last = first + count - 1
                    if (fields.size != 5 || head.size != 2 || count !in 1..PAGE_ROWS ||
                        first != page * PAGE_ROWS || page !in pageIndices() || last >= CHANNEL_COUNT ||
                        fields[3] != id(first) || fields[4] != id(last)
                    ) {
                        fail("qa06-page-bounds", "page=$page count=$count")
                    }
                }
                else -> fail("qa06-channel-order", "unknown record: ${head[0]}")
            }
        }

        if (markerCount != 1 || headerVersion != 1 || headerChannels != CHANNEL_COUNT ||
            headerProgrammes != TOTAL_PROGRAMMES || headerPageRows != PAGE_ROWS ||
            headerMaxCells != MAX_CELLS || headerWindowFrom != WINDOW_FROM || headerWindowTo != WINDOW_TO
        ) {
            fail("qa06-fixture-digest", "header")
        }
        if (channelRecords != CHANNEL_COUNT) fail("qa06-channel-order", "records=$channelRecords")
        if (programmeRecords != TOTAL_PROGRAMMES) fail("qa06-programme-order", "records=$programmeRecords")
        if (pageRecords != pageIndices().size) fail("qa06-page-bounds", "records=$pageRecords")
        for (page in pageIndices()) {
            val first = page * PAGE_ROWS
            val count = minOf(PAGE_ROWS, CHANNEL_COUNT - first)
            if (first < 0 || first + count > CHANNEL_COUNT || id(first) >= id(first + count - 1)) {
                fail("qa06-page-bounds", "derived page=$page")
            }
        }

        for (channel in samples) {
            val channelId = id(channel)
            val entries = sampleEntries[guide(channel)].orEmpty().sortedBy { it.first }
            if (entries.isEmpty()) {
                fail("qa06-row-bounds", "$channelId empty")
                fail("qa06-no-clipping", "$channelId empty")
                continue
            }
            var cursor = WINDOW_FROM
            var cells = 0
            var contiguous = true
            for ((rawStart, rawEnd) in entries) {
                val start = maxOf(rawStart, WINDOW_FROM)
                val end = minOf(rawEnd, WINDOW_TO)
                if (end <= start) continue
                cells++
                if (start != cursor || end > WINDOW_TO) contiguous = false
                cursor = end
            }
            if (cells !in 1..MAX_CELLS || cells - 1 !in 0 until MAX_CELLS) {
                fail("qa06-row-bounds", "$channelId cells=$cells")
            }
            if (!contiguous || cursor != WINDOW_TO) fail("qa06-no-clipping", "$channelId cursor=$cursor")
        }

        val results = ArrayList<Qa06Result>()
        for (name in NAMES) {
            val list = errors.getValue(name)
            results += Qa06Result(
                name,
                list.isEmpty(),
                if (list.isEmpty()) {
                    when (name) {
                        "qa06-fixture-digest" -> "$CHANNEL_COUNT channels, $TOTAL_PROGRAMMES programmes, sha256=$sha"
                        "qa06-channel-order" -> "$channelRecords channels in stable id order"
                        "qa06-programme-order" -> "$programmeRecords programmes in stable start order"
                        "qa06-page-bounds" -> "${pageIndices().size} page records, page_rows=$PAGE_ROWS"
                        "qa06-row-bounds" -> "${samples.size} sampled rows, at most $MAX_CELLS cells"
                        "qa06-no-clipping" -> "${samples.size} sampled rows cover the whole window"
                        else -> "checked"
                    }
                } else {
                    "violations=" + (if (list.size == 4) "4+" else list.size.toString()) + " " + list.joinToString("; ")
                },
            )
        }
        val preReport = results.joinToString("\n") { "${if (it.passed) "PASS" else "FAIL"}\t${it.id}\t${it.detail}" } + "\n"
        val privacyOk = !FORBIDDEN.matcher(preReport).find()
        results[NAMES.indexOf("qa06-report-privacy")] = Qa06Result(
            "qa06-report-privacy",
            privacyOk,
            if (privacyOk) "no URL-like or credential-like token in generated report" else "report hygiene violation",
        )
        return results
    }

    private fun String.stripMarkers(): String = lineSequence()
        .filter { it != MANIFEST_BEGIN && it != MANIFEST_END }
        .joinToString("\n", postfix = "\n")

    private fun digest(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        return raw.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    private val NAMES = listOf(
        "qa06-fixture-digest",
        "qa06-channel-order",
        "qa06-programme-order",
        "qa06-page-bounds",
        "qa06-row-bounds",
        "qa06-no-clipping",
        "qa06-report-privacy",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val results = runAll()
        results.forEach { println("${if (it.passed) "PASS" else "FAIL"}\t${it.id}\t${it.detail}") }
        val failures = results.count { !it.passed }
        println("${if (failures == 0) "PASS" else "FAIL"}\tqa06-kotlin-assertions\tchecks=${results.size}"
            + " failures=$failures channels=$CHANNEL_COUNT programmes=$TOTAL_PROGRAMMES")
        if (failures > 0) exitProcess(1)
    }
}
