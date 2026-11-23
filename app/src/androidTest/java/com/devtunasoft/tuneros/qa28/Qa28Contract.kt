package com.devtunasoft.tuneros.qa28

import com.devtunasoft.tuneros.core.MultiviewPolicy
import com.devtunasoft.tuneros.core.MultiviewSession
import java.io.File
import java.security.MessageDigest

/**
 * QA-28 shared, Android-free contract for the bounded multiview tile-admission
 * and single-audible audio-focus state machine. The same source is compiled into
 * the host gate and into the instrumented mirror; it never reads product data.
 */
object Qa28Contract {
    data class Check(val code: String, val ok: Boolean, val note: String = "")

    private const val SEED = 20261123L
    private const val CASE_COUNT = 37
    private const val CORRUPT_COUNT = 9
    private const val MAX_TILES = 4
    private const val MAX_ID_CHARS = 128
    const val MARKER = "tok9c4e7b21"

    private const val CASES_SHA = "173c98ad22c4b467ed647b296bde7368e34cc46809562bf3b17ec461a62b863d"
    private const val CAPACITY_SHA = "75095bd0ded4deb2186dd58722ec9bf3321c1621e7cf1a6fe167721ea2ad7d9b"
    private const val CORRUPT_SHA = "d48e7afaef2182f6188c43990de4240985a6ecc9c0067c927b9ea74852a6980e"

    private const val CASES_HEADER =
        "case_id\tstep\tdecoder\tprovider\tuser_limit\top\ttile_token\texpect\tmax_tiles\ttiles\taudible"
    private const val CAPACITY_HEADER = "decoder\tprovider\tuser_limit\texpected_max"
    private const val CORRUPT_HEADER = "name\tschema\tcode"
    private const val DIM_HEADER = "key\tvalue"

    private val TOP_LEVEL = listOf("contract.txt", "dimensions.tsv", "capacity.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt")
    private val CORRUPT_NAMES = listOf("header", "fields", "numeric", "op", "token", "result", "steps", "truncated", "capacity")
    private val BAD_PATTERNS = listOf(
        Regex("(?i)(?:https?|ftp|ftps|rtmp|udp)://"),
        Regex("(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential)[\\s:=]"),
        Regex("(?i)\\bpin[\\s:=]?\\d{4,}"),
        Regex("(?i)(?:recording|media)[_-]?(?:payload|bytes)[\\s:=]"),
    )

    private data class CapacityRow(val decoder: Int, val provider: Int, val userLimit: Int, val expected: Int)
    private data class OpRow(
        val caseId: String, val step: Int, val decoder: Int, val provider: Int, val userLimit: Int,
        val op: String, val token: String, val expect: String, val maxTiles: Int,
        val tiles: String, val audible: String,
    )
    private data class ParseResult(val rows: List<List<String>>, val error: String?)

    private class RefSession(private val capacity: Int) {
        private val tiles = ArrayList<String>(4)
        private var audible = -1

        fun open(id: String): String {
            if (id.isBlank() || id.length > MAX_ID_CHARS) return "INVALID_ID"
            if (id in tiles) return "DUPLICATE"
            if (tiles.size >= capacity) return "AT_CAPACITY"
            tiles += id
            if (audible < 0) audible = 0
            return "ADMITTED"
        }

        fun focus(id: String): Boolean {
            val index = tiles.indexOf(id)
            if (index < 0) return false
            audible = index
            return true
        }

        fun close(id: String): Boolean {
            val index = tiles.indexOf(id)
            if (index < 0) return false
            val wasAudible = index == audible
            tiles.removeAt(index)
            audible = when {
                tiles.isEmpty() -> -1
                index < audible -> audible - 1
                wasAudible -> minOf(index, tiles.size - 1)
                else -> audible
            }
            return true
        }

        fun snapshot(): Pair<String, String> =
            Pair(if (tiles.isEmpty()) "-" else tiles.joinToString(",") { display(it) }, if (audible < 0) "-" else display(tiles[audible]))

        fun id(token: String): String = tokenToId(token)
    }

    private fun refAllowed(decoder: Int, provider: Int, userLimit: Int): Int =
        minOf(maxOf(decoder, 0), maxOf(provider, 0), minOf(maxOf(userLimit, 1), MAX_TILES), MAX_TILES)

    private fun tokenToId(token: String): String = when {
        token == "id:blank" -> " "
        token.startsWith("id:x") -> {
            val count = token.removePrefix("id:x").toIntOrNull() ?: -1
            if (count in 1..1024) "x".repeat(count) else token
        }
        else -> token
    }

    private fun display(id: String): String = when {
        id == " " -> "id:blank"
        id.length in 100..MAX_ID_CHARS && id.all { it == 'x' } -> "id:x${id.length}"
        else -> id
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun writeLines(file: File, lines: List<String>) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun capacityRows(): List<CapacityRow> = listOf(
        CapacityRow(-2, 4, 2, 0), CapacityRow(0, 4, 2, 0), CapacityRow(1, 4, 2, 1),
        CapacityRow(2, 4, 2, 2), CapacityRow(4, 1, 2, 1), CapacityRow(4, 0, 2, 0),
        CapacityRow(4, -2, 2, 0), CapacityRow(4, 2, 0, 1), CapacityRow(4, 2, 1, 1), CapacityRow(4, 2, 4, 4),
        CapacityRow(9, 9, 9, 4), CapacityRow(9, 9, -1, 4), CapacityRow(1024, 1024, 3, 3),
    ).map { CapacityRow(it.decoder, it.provider, it.userLimit, refAllowed(it.decoder, it.provider, it.userLimit)) }

    private fun scenarioRows(): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun scenario(id: String, decoder: Int, provider: Int, userLimit: Int, ops: List<Pair<String, String>>) {
            val cap = refAllowed(decoder, provider, userLimit)
            val ref = RefSession(cap)
            ops.forEachIndexed { index, (op, token) ->
                val step = index + 1
                val outcome = when (op) {
                    "OPEN" -> ref.open(ref.id(token))
                    "FOCUS" -> if (ref.focus(ref.id(token))) "TRUE" else "FALSE"
                    "CLOSE" -> if (ref.close(ref.id(token))) "TRUE" else "FALSE"
                    else -> "OP_ERROR"
                }
                val snapshot = ref.snapshot()
                out += listOf(id, step.toString(), decoder.toString(), provider.toString(), userLimit.toString(),
                    op, token, outcome, cap.toString(), snapshot.first, snapshot.second)
            }
        }
        scenario("S1", 2, 4, 2, listOf(
            "OPEN" to "t01", "OPEN" to "t02", "OPEN" to "t03", "FOCUS" to "t02",
            "CLOSE" to "t01", "CLOSE" to "t02", "FOCUS" to "missing", "OPEN" to "t04", "CLOSE" to "t03", "CLOSE" to "t04",
        ))
        scenario("S2", 4, 1, 2, listOf("OPEN" to "t01", "OPEN" to "t02", "CLOSE" to "t01", "OPEN" to "t02"))
        scenario("S3", 0, 4, 2, listOf("OPEN" to "t01"))
        scenario("S4", 9, 9, 9, listOf("OPEN" to "t01", "OPEN" to "t02", "OPEN" to "t03", "OPEN" to "t04", "OPEN" to "t05"))
        scenario("S5", 4, 4, 2, listOf(
            "OPEN" to "t01", "OPEN" to "t01", "OPEN" to "id:blank", "OPEN" to "id:x129", "OPEN" to "id:x128",
            "FOCUS" to "id:blank", "CLOSE" to "id:blank",
        ))
        scenario("S6", 4, 4, 3, listOf(
            "OPEN" to "t01", "OPEN" to "t02", "OPEN" to "t03", "FOCUS" to "t02", "CLOSE" to "t02",
            "FOCUS" to "t03", "CLOSE" to "t03", "CLOSE" to "t01", "CLOSE" to "t02", "OPEN" to "t04",
        ))
        return out
    }

    private fun corruptFiles(): Map<String, List<String>> {
        val header = CASES_HEADER
        val row1 = "C-01\t1\t2\t2\t2\tOPEN\tt01\tADMITTED\t2\tt01\tt01"
        val row2 = "C-01\t2\t2\t2\t2\tOPEN\tt02\tADMITTED\t2\tt01,t02\tt01"
        return linkedMapOf(
            "header" to listOf(header.replace("case_id", "case"), row1),
            "fields" to listOf(header, row1, "$row2\textra"),
            "numeric" to listOf(header, row1.replace("\t2\t2\t2\tOPEN", "\ttwo\t2\t2\tOPEN"), row2),
            "op" to listOf(header, row1, row2.replace("\tOPEN\t", "\tJUMP\t")),
            "token" to listOf(header, row1, row2.replace("\tt02\t", "\tid:x9999\t")),
            "result" to listOf(header, row1.replace("\tADMITTED\t", "\tADMIT\t"), row2),
            "steps" to listOf(header, row1, row2.replace("\t2\t2\t2\t2\tOPEN", "\t0\t2\t2\t2\tOPEN")),
            "truncated" to listOf(header, "C-01\t1\t2\t2\t2"),
            "capacity" to listOf(CAPACITY_HEADER, "2\t2\t2\t3"),
        )
    }

    private fun corruptCodes(): Map<String, String> = mapOf(
        "header" to "HEADER_STRICT", "fields" to "FIELD_BOUNDS", "numeric" to "NUMERIC_BOUNDS",
        "op" to "OP_BOUNDS", "token" to "TOKEN_BOUNDS", "result" to "RESULT_BOUNDS",
        "steps" to "NUMERIC_BOUNDS", "truncated" to "FIELD_BOUNDS", "capacity" to "CAPACITY_BOUND",
    )

    private fun suspicious(text: String): Boolean {
        if (text.contains(MARKER)) return true
        return BAD_PATTERNS.any { it.containsMatchIn(text) }
    }

    /** Writes the complete pinned scratch fixture; no clock, network or environment input. */
    fun generate(dir: String): Int = try {
        val root = File(dir)
        root.mkdirs()
        val cases = scenarioRows()
        val capacities = capacityRows()
        val corrupt = corruptFiles()
        val casesText = (listOf(CASES_HEADER) + cases.map { it.joinToString("\t") }).joinToString("\n", postfix = "\n")
        val capacityText = (listOf(CAPACITY_HEADER) + capacities.map {
            listOf(it.decoder, it.provider, it.userLimit, it.expected).joinToString("\t")
        }).joinToString("\n", postfix = "\n")
        val corruptDigestSource = corrupt.toSortedMap().entries.joinToString("\n") {
            "${it.key}:" + it.value.joinToString("\n")
        }
        val corruptDigest = sha256((corruptDigestSource + "\n").toByteArray(Charsets.UTF_8))
        val dimensions = listOf(
            DIM_HEADER,
            "seed\t$SEED", "case_count\t${cases.size}", "corrupt_count\t${corrupt.size}",
            "max_tiles\t$MAX_TILES", "max_id_chars\t$MAX_ID_CHARS",
            "cases_sha\t${sha256(casesText.toByteArray(Charsets.UTF_8))}",
            "capacity_sha\t${sha256(capacityText.toByteArray(Charsets.UTF_8))}",
            "corrupt_sha\t$corruptDigest", "marker_len\t${MARKER.length}",
        )
        writeLines(File(root, "contract.txt"), listOf(
            "qa28 scratch contract v1",
            "Tab separated tables with exact headers; integer fields are bounded.",
            "Tile names are invented scratch tokens derived from a fixed seed.",
            "Only check ids, counts and digests are emitted; raw rows are never printed.",
        ))
        writeLines(File(root, "capacity.tsv"), (listOf(CAPACITY_HEADER) + capacities.map {
            listOf(it.decoder, it.provider, it.userLimit, it.expected).joinToString("\t")
        }))
        writeLines(File(root, "cases.tsv"), listOf(CASES_HEADER) + cases.map { it.joinToString("\t") })
        writeLines(File(root, "corrupt.tsv"), listOf(CORRUPT_HEADER) + CORRUPT_NAMES.map {
            "$it\t${if (it == "capacity") "capacity" else "cases"}\t${corruptCodes()[it]}"
        })
        writeLines(File(root, "dimensions.tsv"), dimensions)
        for ((name, lines) in corrupt) writeLines(File(File(root, "corrupt"), "$name.tsv"), lines)
        val manifest = (TOP_LEVEL.dropLast(1).map {
            "${sha256(File(root, it))}  $it"
        } + CORRUPT_NAMES.map { name ->
            "${sha256(File(File(root, "corrupt"), "$name.tsv"))}  corrupt/$name.tsv"
        }).sortedBy { it.substringAfter("  ") }
        writeLines(File(root, "sha256.txt"), manifest)
        0
    } catch (e: Exception) {
        -1
    }

    private fun readTable(file: File, header: String, limit: Int): ParseResult {
        return try {
            val text = file.readText(Charsets.UTF_8)
        if (suspicious(text)) return ParseResult(emptyList(), "PRIVACY_FIXTURE")
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != header) return ParseResult(emptyList(), "HEADER_STRICT")
        if (lines.size - 1 > limit) return ParseResult(emptyList(), "FIELD_BOUNDS")
        val rows = ArrayList<List<String>>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != header.count { it == '\t' } + 1) return ParseResult(emptyList(), "FIELD_BOUNDS")
            rows += fields
        }
        ParseResult(rows, null)
    } catch (e: Exception) {
        ParseResult(emptyList(), "FIELD_BOUNDS")
    }
    }

    private fun parseCases(file: File): ParseResult {
        val base = readTable(file, CASES_HEADER, 1024)
        if (base.error != null) return base
        for (row in base.rows) {
            val step = row[1].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val decoder = row[2].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val provider = row[3].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val userLimit = row[4].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            if (step !in 1..65535 || decoder !in -1024..1024 || provider !in -1024..1024 || userLimit !in -1024..1024)
                return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            if (row[5] !in setOf("OPEN", "FOCUS", "CLOSE")) return ParseResult(emptyList(), "OP_BOUNDS")
            val token = row[6]
            if (token.startsWith("id:x")) {
                val count = token.removePrefix("id:x").toIntOrNull() ?: return ParseResult(emptyList(), "TOKEN_BOUNDS")
                if (count !in 1..1024) return ParseResult(emptyList(), "TOKEN_BOUNDS")
            } else if (token == "id:blank") {
                // allowed synthetic blank token
            } else if (token.isEmpty() || token.length > MAX_ID_CHARS) {
                return ParseResult(emptyList(), "TOKEN_BOUNDS")
            }
            if (row[7] !in setOf("ADMITTED", "DUPLICATE", "INVALID_ID", "AT_CAPACITY", "TRUE", "FALSE"))
                return ParseResult(emptyList(), "RESULT_BOUNDS")
            if (row[8].toIntOrNull() != refAllowed(decoder, provider, userLimit))
                return ParseResult(emptyList(), "CAPACITY_BOUND")
        }
        return base
    }

    private fun parseCapacity(file: File): ParseResult {
        val base = readTable(file, CAPACITY_HEADER, 256)
        if (base.error != null) return base
        for (row in base.rows) {
            val decoder = row[0].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val provider = row[1].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val userLimit = row[2].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            val expected = row[3].toIntOrNull() ?: return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            if (decoder !in -1024..1024 || provider !in -1024..1024 || userLimit !in -1024..1024 || expected !in 0..MAX_TILES)
                return ParseResult(emptyList(), "NUMERIC_BOUNDS")
            if (expected != refAllowed(decoder, provider, userLimit)) return ParseResult(emptyList(), "CAPACITY_BOUND")
        }
        return base
    }

    private data class Replay(val admission: Boolean, val audio: Boolean, val close: Boolean) {
        fun failedCount(): Int = (if (admission) 0 else 1) + (if (audio) 0 else 1) + (if (close) 0 else 1)
    }

    private fun replay(rows: List<List<String>>): Replay {
        var admission = true
        var audio = true
        var close = true
        var current = ""
        var session: MultiviewSession? = null
        for (row in rows) {
            val parsed = OpRow(row[0], row[1].toInt(), row[2].toInt(), row[3].toInt(), row[4].toInt(),
                row[5], row[6], row[7], row[8].toIntOrNull() ?: -1, row[9], row[10])
            if (parsed.caseId != current) {
                current = parsed.caseId
                session = MultiviewSession(MultiviewPolicy.Capacity(parsed.decoder, parsed.provider, parsed.userLimit))
            }
            val active = session ?: return Replay(false, false, false)
            val tokenId = tokenToId(parsed.token)
            val actual = when (parsed.op) {
                "OPEN" -> when (active.open(tokenId)) {
                    MultiviewSession.Admission.ADMITTED -> "ADMITTED"
                    MultiviewSession.Admission.DUPLICATE -> "DUPLICATE"
                    MultiviewSession.Admission.INVALID_ID -> "INVALID_ID"
                    MultiviewSession.Admission.AT_CAPACITY -> "AT_CAPACITY"
                }
                "FOCUS" -> if (active.focusAudio(tokenId)) "TRUE" else "FALSE"
                else -> if (active.close(tokenId)) "TRUE" else "FALSE"
            }
            val ids = active.openTiles
            val actualTiles = if (ids.isEmpty()) "-" else ids.joinToString(",") { display(it) }
            val actualAudible = active.audibleTile?.let { display(it) } ?: "-"
            if (actual != parsed.expect || actualTiles != parsed.tiles || ids.size > parsed.maxTiles) {
                if (parsed.op == "FOCUS") audio = false
                else if (parsed.op == "CLOSE") close = false
                else admission = false
            }
            if (actualAudible != parsed.audible) {
                if (parsed.op == "CLOSE") close = false else audio = false
            }
            if ((ids.isEmpty()) != (active.audibleTile == null)) {
                if (parsed.op == "CLOSE") close = false else audio = false
            }
            if (parsed.maxTiles != active.maxTiles) admission = false
        }
        return Replay(admission, audio, close)
    }

    private fun deterministicCycles(): Boolean {
        var x = SEED
        val session = MultiviewSession(MultiviewPolicy.Capacity(3, 4, 2))
        val ids = listOf("a", "b", "c", "d", "e")
        repeat(10_000) {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            val id = ids[((x ushr 33) % ids.size.toLong()).toInt()]
            when (((x ushr 17) % 3L).toInt()) {
                0 -> session.open(id)
                1 -> session.close(id)
                else -> session.focusAudio(id)
            }
            val open = session.openTiles
            val audible = session.audibleTile
            if (open.size > 3 || open != open.distinct() || (open.isEmpty() != (audible == null)) ||
                (audible != null && audible !in open)) return false
        }
        return true
    }

    private fun byteIdentity(root: File, regen: String): Boolean {
        if (regen.isBlank()) return true
        val scratch = File(regen)
        if (generate(scratch.absolutePath) != 0) return false
        val names = TOP_LEVEL.dropLast(1).map { it } + CORRUPT_NAMES.map { "corrupt/$it.tsv" }
        return names.all { name ->
            val a = File(root, name)
            val b = File(scratch, name)
            a.isFile && b.isFile && a.readBytes().contentEquals(b.readBytes())
        }
    }

    private fun manifestMatch(root: File): Boolean {
        val lines = File(root, "sha256.txt").readText(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.size != TOP_LEVEL.size - 1 + CORRUPT_NAMES.size) return false
        val expectedNames = (TOP_LEVEL.dropLast(1) + CORRUPT_NAMES.map { "corrupt/$it.tsv" }).sorted()
        if (lines.map { it.substringAfter("  ") } != expectedNames) return false
        return lines.all { line ->
            val digest = line.substringBefore("  ")
            val name = line.substringAfter("  ")
            digest.matches(Regex("[0-9a-f]{64}")) && sha256(File(root, name)) == digest
        }
    }

    private fun productionCapacityMatches(root: File): Boolean {
        val parsed = parseCapacity(File(root, "capacity.tsv"))
        if (parsed.error != null) return false
        return parsed.rows.all { row ->
            val capacity = MultiviewPolicy.Capacity(row[0].toInt(), row[1].toInt(), row[2].toInt())
            MultiviewPolicy.allowedTiles(capacity) == row[3].toInt()
        }
    }

    private fun dimensionChecks(root: File): Check {
        val parsed = readTable(File(root, "dimensions.tsv"), DIM_HEADER, 32)
        if (parsed.error != null) return Check("DIMENSIONS", false, parsed.error)
        val map = parsed.rows.associate { it[0] to it[1] }
        val expected = mapOf(
            "seed" to SEED.toString(), "case_count" to CASE_COUNT.toString(),
            "corrupt_count" to CORRUPT_COUNT.toString(), "max_tiles" to MAX_TILES.toString(),
            "max_id_chars" to MAX_ID_CHARS.toString(), "cases_sha" to CASES_SHA,
            "capacity_sha" to CAPACITY_SHA, "corrupt_sha" to CORRUPT_SHA,
            "marker_len" to MARKER.length.toString(),
        )
        val ok = map == expected
        return Check("DIMENSIONS", ok, "fields=${expected.size}")
    }

    private fun corruptChecks(root: File): Check {
        val parsed = readTable(File(root, "corrupt.tsv"), CORRUPT_HEADER, 32)
        if (parsed.error != null) return Check("CORRUPT_REJECT", false, parsed.error)
        if (parsed.rows.size != CORRUPT_COUNT) return Check("CORRUPT_REJECT", false, "count=${parsed.rows.size}")
        var rejected = 0
        for (row in parsed.rows) {
            if (row.size != 3 || row[1] !in setOf("cases", "capacity")) continue
            val file = File(File(root, "corrupt"), "${row[0]}.tsv")
            val actual = if (row[1] == "cases") parseCases(file).error else parseCapacity(file).error
            if (actual == row[2]) rejected++
        }
        return Check("CORRUPT_REJECT", rejected == CORRUPT_COUNT, "rejected=$rejected/$CORRUPT_COUNT")
    }

    private fun privacyFixture(root: File): Check {
        val names = TOP_LEVEL.dropLast(1) + CORRUPT_NAMES.map { "corrupt/$it.tsv" }
        var scanned = 0
        for (name in names) {
            val file = File(root, name)
            if (!file.isFile || file.length() > 65536L) return Check("PRIVACY_FIXTURE", false, "unbounded=$name")
            if (suspicious(file.readText(Charsets.UTF_8))) return Check("PRIVACY_FIXTURE", false, "hit=$name")
            scanned++
        }
        return Check("PRIVACY_FIXTURE", true, "files=$scanned")
    }

    /** Executes every fixture-driven check; host-only heap sampling lives in Qa28Gate. */
    fun checks(dir: String, mode: String, regen: String = ""): List<Check> {
        val root = File(dir)
        val out = ArrayList<Check>()
        if (mode == "verify") {
            out += Check("BYTE_IDENTITY", byteIdentity(root, regen), "regenerated")
            out += Check("MANIFEST_MATCH", manifestMatch(root), "files=${TOP_LEVEL.size - 1 + CORRUPT_NAMES.size}")
        }
        if (mode == "digest") out += Check("MANIFEST_MATCH", manifestMatch(root), "files=${TOP_LEVEL.size - 1 + CORRUPT_NAMES.size}")
        out += dimensionChecks(root)

        val casesParsed = parseCases(File(root, "cases.tsv"))
        val capacityParsed = parseCapacity(File(root, "capacity.tsv"))
        out += Check("HEADER_STRICT", casesParsed.error != "HEADER_STRICT" && capacityParsed.error != "HEADER_STRICT",
            "cases=${casesParsed.error ?: "OK"} capacity=${capacityParsed.error ?: "OK"}")
        out += Check("FIELD_BOUNDS", casesParsed.error != "FIELD_BOUNDS" && capacityParsed.error != "FIELD_BOUNDS")
        out += Check("NUMERIC_BOUNDS", casesParsed.error != "NUMERIC_BOUNDS" && capacityParsed.error != "NUMERIC_BOUNDS")
        out += Check("OP_BOUNDS", casesParsed.error != "OP_BOUNDS")
        out += Check("TOKEN_BOUNDS", casesParsed.error != "TOKEN_BOUNDS")
        out += Check("RESULT_BOUNDS", casesParsed.error != "RESULT_BOUNDS")
        out += Check("CAPACITY_BOUND", capacityParsed.error == null && casesParsed.error == null &&
            capacityParsed.rows.size >= 12 && productionCapacityMatches(root))

        val replayed = if (casesParsed.error == null) replay(casesParsed.rows) else Replay(false, false, false)
        out += Check("ADMISSION_SEMANTICS", replayed.admission, "rows=${casesParsed.rows.size}")
        out += Check("AUDIO_EXCLUSIVE", replayed.audio)
        out += Check("CLOSE_HANDOFF", replayed.close)
        out += corruptChecks(root)
        out += Check("PRIVACY_FIXTURE", privacyFixture(root).ok, privacyFixture(root).note)
        out += Check("DETERMINISTIC_INVARIANTS", deterministicCycles(), "ops=10000")
        val bounded = out.all {
            it.code.matches(Regex("[A-Z][A-Z_0-9]*")) && it.note.length <= 160 &&
                !it.note.contains("://") && !it.note.contains(MARKER)
        }
        out += Check("DIAGNOSTIC_BOUNDS", bounded, "checks=${out.size}")
        return out
    }

    /** On-device mirror: scratch generation plus the same fixture-driven checks. */
    fun mirror(dir: String): List<Check> {
        val generated = generate(dir)
        if (generated != 0) return listOf(Check("MIRROR_GENERATE", false, "status=$generated"))
        return checks(dir, "mirror")
    }
}
