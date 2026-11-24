package com.devtunasoft.tuneros.qa61

import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.TimelineEntry
import com.devtunasoft.tuneros.core.TimelineFocusPolicy
import java.security.MessageDigest

/**
 * QA-61 framework-free contract for bounded timeline window-shift focus retention.
 * The same source is compiled into the JVM host gate and the instrumented mirror.
 * It calls the real production class; no focus policy is reimplemented here.
 */
object Qa61Contract {
    const val SEED = 0x51415937L
    const val MAX_ROWS = 24
    const val FIELD_COUNT = 7
    const val FAMILY_COUNT = 8
    const val MAX_ENTRIES = 8
    const val MAX_LINE_BYTES = 256
    const val HEADER = "id\tfamily\tdirection\tprior\tstarts\texpected\tnote"
    const val NOTE = "synthetic"
    const val REPORT = "QA-61 bounded timeline window-shift focus gate. Scratch fixture only."

    val ALL_FILES = listOf(".gitattributes", "rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt")
    val DATA_FILES = listOf("corrupt.txt", "dimensions.tsv", "report-copy.txt", "rows.tsv")
    val FAMILIES = listOf(
        "exact_start", "nearest_right", "nearest_left", "fallback_first",
        "fallback_last", "gaps_only", "null_prior", "boundary_touch",
    )
    private val EXPECTED_CORRUPT = listOf(
        "header-bad" to "HEADER",
        "fields-short" to "FIELDS",
        "id-range" to "ID_RANGE",
        "family-unknown" to "FAMILY",
        "direction-bad" to "DIRECTION",
        "prior-nonnumeric" to "PRIOR",
        "starts-bad" to "STARTS",
        "overlong" to "LENGTH",
        "hex-bad" to "HEX",
    )

    private class Case(
        val entries: List<TimelineEntry>,
        val starts: LongArray,
        val indexes: IntArray,
    )

    private class Row(val fields: List<String>) {
        val id: String get() = fields[0]
        val family: String get() = fields[1]
        val direction: String get() = fields[2]
        val prior: String get() = fields[3]
        val starts: String get() = fields[4]
        val expected: String get() = fields[5]
        fun render(): String = fields.joinToString("\t")
    }

    private fun longs(text: String): LongArray? {
        if (text.isEmpty()) return null
        val parts = text.split(',')
        if (parts.isEmpty() || parts.size > 4) return null
        val out = LongArray(parts.size)
        for (index in parts.indices) {
            out[index] = parts[index].toLongOrNull() ?: return null
        }
        return out
    }

    /** Builds a bounded row from fixture bytes. Gaps are real TimelineEntry gaps, not selected cells. */
    private fun caseFor(starts: LongArray, gapsOnly: Boolean): Case {
        if (gapsOnly) {
            return Case(listOf(TimelineEntry(starts[0], starts[0] + 60L)), starts, IntArray(0))
        }
        val entries = ArrayList<TimelineEntry>(MAX_ENTRIES)
        val indexes = IntArray(starts.size)
        var cursor = starts[0]
        for (index in starts.indices) {
            if (cursor < starts[index]) {
                entries += TimelineEntry(cursor, starts[index])
            }
            val end = starts[index] + 60L
            entries += TimelineEntry(
                starts[index], end,
                GuideProgram("c61-$index", "Slot $index", starts[index], end),
            )
            indexes[index] = entries.lastIndex
            cursor = end
        }
        if (cursor < starts.last() + 500L) {
            entries += TimelineEntry(cursor, starts.last() + 500L)
        }
        return Case(entries, starts, indexes)
    }

    private fun referenceExpected(family: String, seed: Int, case: Case): Int = when (family) {
        "exact_start", "fallback_last" -> case.indexes[0]
        "nearest_right" -> case.indexes[1]
        "nearest_left" -> case.indexes[2]
        "fallback_first", "boundary_touch" -> case.indexes[3]
        "gaps_only" -> -1
        "null_prior" -> if (seed % 2 == 0) case.indexes[0] else case.indexes[3]
        else -> Int.MIN_VALUE
    }

    private fun directionFor(family: String, seed: Int): Int = when (family) {
        "null_prior" -> if (seed % 2 == 0) 1 else -1
        "exact_start", "nearest_left", "fallback_last" -> -1
        else -> 1
    }

    private fun priorFor(family: String, starts: LongArray): String = when (family) {
        "exact_start", "fallback_last" -> starts[0].toString()
        "nearest_right" -> (starts[0] + 1L).toString()
        "nearest_left" -> (starts[3] - 1L).toString()
        "fallback_first" -> (starts[3] + 500L).toString()
        "gaps_only" -> starts[0].toString()
        "boundary_touch" -> starts[3].toString()
        "null_prior" -> "null"
        else -> "null"
    }

    private fun startsFor(family: String, seed: Int): LongArray {
        val base = 1000L + seed.toLong() * 400L
        if (family == "gaps_only") return longArrayOf(base)
        return LongArray(4) { base + it.toLong() * 120L }
    }

    private fun defaultRows(): List<Row> = (0 until MAX_ROWS).map { index ->
        val family = FAMILIES[index % FAMILIES.size]
        val seed = index / FAMILIES.size
        val starts = startsFor(family, seed)
        val case = caseFor(starts, family == "gaps_only")
        Row(
            listOf(
                "q61-" + index.toString().padStart(2, '0'),
                family,
                directionFor(family, seed).toString(),
                priorFor(family, starts),
                starts.joinToString(","),
                referenceExpected(family, seed, case).toString(),
                NOTE,
            ),
        )
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }

    private fun unhex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val hi = Character.digit(text[index * 2], 16)
            val lo = Character.digit(text[index * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[index] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun lines(bytes: ByteArray): List<String> =
        String(bytes, Charsets.UTF_8).split('\n').dropLastWhile { it.isEmpty() }

    /** Runtime-derived marker; never stored in source, fixture, report or heartbeat. */
    fun derivedToken(): String {
        var value = SEED
        for (index in 0 until 16) {
            value = value * 6364136223846793005L + 1442695040888963407L
            value = value xor (value ushr 17)
        }
        return java.lang.Long.toHexString(value and 0xffffffffL).padStart(8, '0')
    }

    private fun corruptBlocks(): List<Pair<String, String>> {
        val sample = defaultRows()[0]
        val badId = sample.fields.toMutableList().also { it[0] = "q61-99" }.joinToString("\t")
        val badFamily = sample.fields.toMutableList().also { it[1] = "unknown" }.joinToString("\t")
        val badDirection = sample.fields.toMutableList().also { it[2] = "0" }.joinToString("\t")
        val badPrior = sample.fields.toMutableList().also { it[3] = "seed-x" }.joinToString("\t")
        val badStarts = sample.fields.toMutableList().also { it[4] = "100,x" }.joinToString("\t")
        val overlong = sample.fields.toMutableList().also { it[6] = "x".repeat(MAX_LINE_BYTES) }.joinToString("\t")
        return listOf(
            "header-bad" to HEADER.substringBeforeLast('\t'),
            "fields-short" to sample.fields.dropLast(1).joinToString("\t"),
            "id-range" to badId,
            "family-unknown" to badFamily,
            "direction-bad" to badDirection,
            "prior-nonnumeric" to badPrior,
            "starts-bad" to badStarts,
            "overlong" to overlong,
            "hex-bad" to "not-hex",
        )
    }

    private fun classifyCorrupt(text: String): String {
        val bytes = unhex(text) ?: return "HEX"
        if (bytes.size > MAX_LINE_BYTES) return "LENGTH"
        val payload = String(bytes, Charsets.UTF_8)
        if (payload == HEADER.substringBeforeLast('\t')) return "HEADER"
        val fields = payload.split('\t')
        if (fields.size != FIELD_COUNT) return "FIELDS"
        if (!fields[0].matches(Regex("q61-[0-9]{2}")) || fields[0].drop(4).toInt() >= MAX_ROWS) return "ID_RANGE"
        if (!FAMILIES.contains(fields[1])) return "FAMILY"
        val direction = fields[2].toIntOrNull() ?: return "DIRECTION"
        if (direction != -1 && direction != 1) return "DIRECTION"
        if (fields[3] != "null" && fields[3].toLongOrNull() == null) return "PRIOR"
        val starts = longs(fields[4]) ?: return "STARTS"
        if (starts.isEmpty() || starts.size > 4) return "STARTS"
        if (fields[5].toIntOrNull() == null) return "EXPECTED"
        if (fields[6] != NOTE) return "NOTE"
        return "ACCEPTED"
    }

    fun generate(): Map<String, ByteArray> {
        val rows = defaultRows()
        val rowsText = HEADER + "\n" + rows.joinToString("\n") { it.render() } + "\n"
        val mirror = sha256((rows.joinToString("\n") { it.render() } + "\n").toByteArray(Charsets.UTF_8))
        val dimensions = listOf(
            "ROWS\t${rows.size}",
            "FIELD_COUNT\t$FIELD_COUNT",
            "FAMILY_COUNT\t${FAMILIES.size}",
            "MAX_ENTRIES\t$MAX_ENTRIES",
            "MAX_LINE_BYTES\t$MAX_LINE_BYTES",
            "MIRROR_DIGEST\t$mirror",
        ).joinToString("\n") + "\n"
        val corrupt = corruptBlocks().joinToString("") { (name, payload) ->
            val encoded = if (name == "hex-bad") payload else hex(payload.toByteArray(Charsets.UTF_8))
            "BEGIN $name\n$encoded\nEND $name\n"
        }
        val data = linkedMapOf(
            "rows.tsv" to rowsText.toByteArray(Charsets.UTF_8),
            "dimensions.tsv" to dimensions.toByteArray(Charsets.UTF_8),
            "corrupt.txt" to corrupt.toByteArray(Charsets.UTF_8),
            "report-copy.txt" to (REPORT + "\n").toByteArray(Charsets.UTF_8),
        )
        val manifest = DATA_FILES.sorted().joinToString("") { name ->
            sha256(data.getValue(name)) + "\t" + name + "\n"
        }
        val out = LinkedHashMap<String, ByteArray>()
        out[".gitattributes"] = "* -text\n.gitattributes -text\n".toByteArray(Charsets.UTF_8)
        out.putAll(data)
        out["sha256.txt"] = manifest.toByteArray(Charsets.UTF_8)
        return out
    }

    private fun actualFor(row: Row): Int? {
        val starts = longs(row.starts) ?: return null
        if (starts.isEmpty() || starts.size > 4) return null
        val case = caseFor(starts, row.family == "gaps_only")
        if (case.entries.size > MAX_ENTRIES) return null
        val prior = if (row.prior == "null") null else row.prior.toLongOrNull() ?: return null
        val direction = row.direction.toIntOrNull() ?: return null
        return try {
            TimelineFocusPolicy.shiftedIndex(case.entries, prior, direction)
        } catch (failure: Throwable) {
            null
        }
    }

    private fun throwsForDirection(row: Row, direction: Int): Boolean {
        val starts = longs(row.starts) ?: return false
        val case = caseFor(starts, row.family == "gaps_only")
        val prior = if (row.prior == "null") null else row.prior.toLongOrNull() ?: return false
        return try {
            TimelineFocusPolicy.shiftedIndex(case.entries, prior, direction)
            false
        } catch (failure: IllegalArgumentException) {
            true
        } catch (failure: Throwable) {
            false
        }
    }

    /** One emitted line per check; the runner parses these fields by position. */
    fun verifyModel(mode: String, files: Map<String, ByteArray>, sink: (String) -> Unit): Int {
        val emitted = ArrayList<String>()
        fun say(line: String) {
            emitted += line
            sink(line)
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

        val missing = ALL_FILES.firstOrNull { !files.containsKey(it) }
        if (missing != null) {
            say("FIRST\tMISSING_FIXTURE")
            say("VERDICT\tFAIL")
            return 1
        }

        val rowsRaw = lines(files.getValue("rows.tsv"))
        if (active("HEADER_STRICT")) check("HEADER_STRICT", rowsRaw.firstOrNull() == HEADER, "header")
        val body = rowsRaw.drop(1)
        val boundsOk = body.size <= MAX_ROWS && body.all { line ->
            line.split('\t').size == FIELD_COUNT && line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES
        }
        if (active("ROW_BOUNDS")) check("ROW_BOUNDS", boundsOk, "rows=${body.size}")

        val manifest = hashMapOf<String, String>()
        for (line in lines(files.getValue("sha256.txt"))) {
            val parts = line.split('\t')
            if (parts.size == 2) manifest[parts[1]] = parts[0]
        }
        val manifestOk = DATA_FILES.size == manifest.size &&
            DATA_FILES.all { manifest[it] == sha256(files.getValue(it)) }
        if (active("MANIFEST_CONSISTENT")) {
            check("MANIFEST_CONSISTENT", manifestOk, "files=${DATA_FILES.size}")
        }

        val regenerated = generate()
        if (active("BYTE_IDENTITY")) {
            check(
                "BYTE_IDENTITY",
                ALL_FILES.all { regenerated.getValue(it).contentEquals(files.getValue(it)) },
                "files=${ALL_FILES.size}",
            )
        }

        val dims = hashMapOf<String, String>()
        for (line in lines(files.getValue("dimensions.tsv"))) {
            val parts = line.split('\t')
            if (parts.size == 2) dims[parts[0]] = parts[1]
        }
        val rows = if (boundsOk) body.map { Row(it.split('\t')) } else emptyList()
        val mirror = sha256((rows.joinToString("\n") { it.render() } + "\n").toByteArray(Charsets.UTF_8))
        if (active("PINNED_DIGEST")) check("PINNED_DIGEST", dims["MIRROR_DIGEST"] == mirror, "rows=${rows.size}")

        val capOk = rows.size == MAX_ROWS && dims["ROWS"] == MAX_ROWS.toString() &&
            dims["FIELD_COUNT"] == FIELD_COUNT.toString() &&
            dims["FAMILY_COUNT"] == FAMILIES.size.toString() &&
            dims["MAX_ENTRIES"] == MAX_ENTRIES.toString() &&
            rows.map { it.family }.distinct().size == FAMILIES.size &&
            FAMILIES.all { family -> rows.any { it.family == family } } &&
            rows.any { row ->
                val starts = longs(row.starts) ?: return@any false
                caseFor(starts, row.family == "gaps_only").entries.size == MAX_ENTRIES
            }
        if (active("CAP_BINDING")) check("CAP_BINDING", capOk, "rows=${rows.size} cap=$MAX_ROWS")

        val corruptLines = lines(files.getValue("corrupt.txt"))
        val corruptResult = LinkedHashMap<String, String>()
        var corruptIndex = 0
        while (corruptIndex + 2 < corruptLines.size &&
            corruptLines[corruptIndex].startsWith("BEGIN ") &&
            corruptLines[corruptIndex + 2] == "END " + corruptLines[corruptIndex].removePrefix("BEGIN ")
        ) {
            corruptResult[corruptLines[corruptIndex].removePrefix("BEGIN ")] =
                classifyCorrupt(corruptLines[corruptIndex + 1])
            corruptIndex += 3
        }
        val corruptOk = corruptResult.size == EXPECTED_CORRUPT.size &&
            EXPECTED_CORRUPT.all { (name, code) -> corruptResult[name] == code }
        if (active("CORRUPT_FAIL_CLOSED")) {
            check("CORRUPT_FAIL_CLOSED", corruptOk, "variants=${EXPECTED_CORRUPT.size}")
        }

        fun familyOk(family: String): Boolean {
            val subset = rows.filter { it.family == family }
            return subset.isNotEmpty() && subset.all { row ->
                val starts = longs(row.starts) ?: return@all false
                val case = caseFor(starts, row.family == "gaps_only")
                val seed = (row.id.drop(4).toIntOrNull() ?: return@all false) / FAMILIES.size
                actualFor(row) == referenceExpected(family, seed, case)
            }
        }
        if (active("EXACT_START_RETAIN")) check("EXACT_START_RETAIN", familyOk("exact_start"), "family=exact_start")
        if (active("NEAREST_RIGHT")) check("NEAREST_RIGHT", familyOk("nearest_right"), "family=nearest_right")
        if (active("NEAREST_LEFT")) check("NEAREST_LEFT", familyOk("nearest_left"), "family=nearest_left")
        if (active("FALLBACK_EDGE")) check("FALLBACK_EDGE", familyOk("fallback_first") && familyOk("fallback_last"), "families=fallback_first,fallback_last")
        if (active("GAPS_ONLY")) check("GAPS_ONLY", familyOk("gaps_only"), "family=gaps_only")
        if (active("NULL_PRIOR")) check("NULL_PRIOR", familyOk("null_prior"), "family=null_prior")
        if (active("DIRECTION_GUARD")) {
            val sample = rows.firstOrNull()
            check(
                "DIRECTION_GUARD",
                sample != null && throwsForDirection(sample, 0) && throwsForDirection(sample, 2),
                "directions=0,2",
            )
        }
        val fields = TimelineFocusPolicy::class.java.declaredFields.count { it.name != "INSTANCE" }
        if (active("NO_RETAINED_STATE")) check("NO_RETAINED_STATE", fields == 0, "fields=$fields")

        var verdictOk = rows.isNotEmpty()
        for (row in rows) {
            val actual = actualFor(row)
            if (actual == null || actual.toString() != row.expected) {
                verdictOk = false
                say("MISMATCH\t${row.id}\t${row.family}")
            }
        }
        if (active("CONTRACT_VERDICTS")) check("CONTRACT_VERDICTS", verdictOk, "cases=${rows.size}")

        val allText = files.values.joinToString("\n") { String(it, Charsets.UTF_8) } +
            "\n" + emitted.joinToString("\n")
        val url = Regex("(?i)https?://|ftp://|www\\.")
        val credential = Regex("(?i)password|passwd|api[_-]?key|key[=:]|token[=:]|secret|pin[=:]|credential")
        val payload = Regex("(?i)recording-payload|m3u8|xmltv|stream-key|payload=")
        val leak = allText.contains(derivedToken()) || url.containsMatchIn(allText) ||
            credential.containsMatchIn(allText) || payload.containsMatchIn(allText)
        if (active("PRIVACY_SCAN")) check("PRIVACY_SCAN", !leak, "scan=fixture+report")

        val heapMax = Runtime.getRuntime().maxMemory()
        var peak = 0L
        var baseline = 0L
        var stable = true
        for (cycle in 0 until 3) {
            repeat(rows.size) { index -> actualFor(rows[index % rows.size]) }
            System.gc()
            Thread.sleep(10L)
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (cycle == 0) baseline = used else if (used > baseline + 8L * 1024L * 1024L) stable = false
            if (used > peak) peak = used
        }
        say("MEM\theapMax=$heapMax\tpeakUsed=$peak\tbaseline=$baseline")
        if (active("MEM_STABLE")) {
            check("MEM_STABLE", heapMax <= 100663296L && peak < 16L * 1024L * 1024L && stable, "heapMax=$heapMax")
        }

        val before = Thread.getAllStackTraces().size
        repeat(rows.size) { index -> actualFor(rows[index % rows.size]) }
        val after = Thread.getAllStackTraces().size
        val leaked = Thread.getAllStackTraces().keys.count { it.name.contains("qa61", ignoreCase = true) }
        say("THREADS\tbefore=$before\tafter=$after\tleaked=$leaked")
        if (active("THREAD_STABLE")) {
            check("THREAD_STABLE", before == after && leaked == 0, "before=$before after=$after")
        }

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
