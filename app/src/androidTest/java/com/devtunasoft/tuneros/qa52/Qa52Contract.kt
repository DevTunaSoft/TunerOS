package com.devtunasoft.tuneros.qa52

import com.devtunasoft.tuneros.core.PlayerReusePolicy
import java.security.MessageDigest

/**
 * QA-52 shared pure-Kotlin contract. It generates the bounded scratch fixture, parses it
 * strictly, and drives the real compiled PlayerReusePolicy.canReuse over every matrix row.
 * No Android imports: the same source is compiled into the host gate and the instrumented mirror.
 */
object Qa52Contract {
    const val FILE_COUNT = 5
    const val CASE_COUNT = 112
    const val CAP_CASES = 112
    const val CORRUPT_ROWS = 6
    const val CASE_HEADER = "id\tcurrent\tnext\trecflag\texpected\tnote"
    const val CORRUPT_HEADER = "name\tpayload"
    const val DIM_HEADER = "key\tvalue"
    const val MARKER_SEED = "qa52-seed-6147"
    const val REUSE = "REUSE"
    const val REFRESH = "REFRESH"

    data class Profile(val name: String, val entries: List<Pair<String, String>>?)
    data class Case(
        val id: String,
        val current: String,
        val next: String,
        val recflag: Int,
        val expected: String,
        val note: String,
    )
    data class Check(val code: String, val ok: Boolean, val detail: String = "")
    data class Result(val checks: List<Check>, val first: String) {
        val failures: Int get() = checks.count { !it.ok }
    }

    val profiles: List<Profile> = listOf(
        Profile("NONE", null),
        Profile("EMPTY", emptyList()),
        Profile("ROUTE_A", listOf("X-Qa52-Route" to "QA52RA7", "X-Qa52-Zone" to "QA52ZA3")),
        Profile("ROUTE_A2", listOf("X-Qa52-Zone" to "QA52ZA3", "X-Qa52-Route" to "QA52RA7")),
        Profile("ROUTE_B", listOf("X-Qa52-Route" to "QA52RB9", "X-Qa52-Zone" to "QA52ZA3")),
        Profile("ROUTE_AX", listOf(
            "X-Qa52-Route" to "QA52RA7",
            "X-Qa52-Zone" to "QA52ZA3",
            "X-Qa52-Extra" to "QA52EX4",
        )),
        Profile("ROUTE_AC", listOf("x-qa52-route" to "QA52RA7", "X-Qa52-Zone" to "QA52ZA3")),
        Profile("ROUTE_AM", listOf("X-Qa52-Route" to "QA52RA7")),
    )
    val nextProfiles: List<Profile> = profiles.filter { it.name != "NONE" }
    val byName: Map<String, Profile> = profiles.associateBy { it.name }

    private val hex = "0123456789abcdef".toCharArray()
    private val idPattern = Regex("c[0-9]{3}")

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (value in digest) {
            val byte = value.toInt() and 0xff
            out.append(hex[byte shr 4])
            out.append(hex[byte and 15])
        }
        return out.toString()
    }

    fun marker(): String = "QA52M-" + sha256(MARKER_SEED.toByteArray(Charsets.US_ASCII)).take(8)

    /** Detects provider addresses, credential text, PIN assignments and recording payload names. */
    fun privacyOffenders(text: String): List<String> {
        val lower = text.lowercase()
        val found = ArrayList<String>(8)
        for (token in listOf("://", "www.", "api_key", "apikey", "password", "passwd",
            "secret", "credential", "authorization", "bearer ", "pin=", "pin:", ".m3u8", ".xmltv")) {
            if (lower.contains(token)) found += token
        }
        if (Regex("(?:rec|recording)[_-]?20[0-9]{2}", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            found += "recording-payload"
        }
        if (Regex("\\.(?:ts|mp4|mkv)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            found += "media-file"
        }
        if (text.contains(marker())) found += "derived-marker"
        return found.distinct()
    }

    fun expectedOf(current: Profile, next: Profile, recording: Boolean): String {
        val currentMap = current.entries?.toMap() ?: return REFRESH
        if (recording) return REFRESH
        return if (currentMap == next.entries!!.toMap()) REUSE else REFRESH
    }

    fun cases(): List<Case> {
        val out = ArrayList<Case>(CASE_COUNT)
        var number = 0
        for (current in profiles) {
            for (next in nextProfiles) {
                for (rec in 0..1) {
                    number++
                    val expected = expectedOf(current, next, rec == 1)
                    val note = when {
                        current.entries == null -> "no-current"
                        rec == 1 -> "recording-state"
                        expected == REUSE -> "same-headers"
                        else -> "distinct-headers"
                    }
                    out += Case("c" + number.toString().padStart(3, '0'), current.name, next.name,
                        rec, expected, note)
                }
            }
        }
        return out
    }

    private fun corruptRows(): List<Pair<String, String>> = listOf(
        "valid-control" to "c999\tROUTE_A\tROUTE_A\t0\tREUSE\tcontrol",
        "bad-field-count" to "c900\tROUTE_A\tROUTE_B\t0\tREUSE",
        "bad-profile" to "c901\tNO_SUCH_PROFILE\tROUTE_A\t0\tREUSE\tunknown",
        "bad-recflag" to "c902\tROUTE_A\tROUTE_B\t2\tREUSE\tinvalid",
        "bad-verdict" to "c903\tROUTE_A\tROUTE_B\t0\tMAYBE\tinvalid",
        "empty-payload" to "",
    )

    fun generateFiles(): Map<String, ByteArray> {
        val rows = cases()
        val casesText = buildString {
            append(CASE_HEADER).append('\n')
            for (row in rows) {
                append(row.id).append('\t').append(row.current).append('\t').append(row.next)
                    .append('\t').append(row.recflag).append('\t').append(row.expected)
                    .append('\t').append(row.note).append('\n')
            }
        }
        val corruptText = buildString {
            append(CORRUPT_HEADER).append('\n')
            for ((name, payload) in corruptRows()) append(name).append('\t').append(payload).append('\n')
        }
        val reuse = rows.count { it.expected == REUSE }
        val refresh = rows.count { it.expected == REFRESH }
        val nullCurrent = rows.count { it.current == "NONE" }
        val recording = rows.count { it.recflag == 1 }
        val mismatch = rows.count {
            it.recflag == 0 && it.current != "NONE" &&
                (byName.getValue(it.current).entries?.toMap() != byName.getValue(it.next).entries!!.toMap())
        }
        val dims = LinkedHashMap<String, String>()
        dims["caseCount"] = rows.size.toString()
        dims["capCases"] = CAP_CASES.toString()
        dims["reuseExpected"] = reuse.toString()
        dims["refreshExpected"] = refresh.toString()
        dims["nullCurrentExpected"] = nullCurrent.toString()
        dims["recordingExpected"] = recording.toString()
        dims["mismatchExpected"] = mismatch.toString()
        dims["cycles"] = "4"
        dims["heapLimitBytes"] = "67108864"
        dims["heapDriftBytes"] = "16777216"
        dims["threadLimit"] = "8"
        dims["markerSeed"] = MARKER_SEED
        dims["casesSha256"] = sha256(casesText.toByteArray(Charsets.UTF_8))
        dims["corruptSha256"] = sha256(corruptText.toByteArray(Charsets.UTF_8))
        val dimsText = buildString {
            append(DIM_HEADER).append('\n')
            for ((key, value) in dims) append(key).append('\t').append(value).append('\n')
        }
        val manifestText = buildString {
            val data = listOf("cases.tsv" to casesText, "corrupt.tsv" to corruptText,
                "dimensions.tsv" to dimsText)
            for ((name, text) in data) append(sha256(text.toByteArray(Charsets.UTF_8)))
                .append('\t').append(name).append('\n')
        }
        val readme = buildString {
            append("QA-52 bounded scratch fixture.\n")
            append("Matrix: 8 current profiles x 7 next profiles x 2 recflag values = 112 rows.\n")
            append("Profiles are synthetic route labels. No provider addresses or user data appear here.\n")
            append("Verdicts are REUSE or REFRESH. Both ordering variants of route A are equal as maps.\n")
            append("Six corrupt rows prove the strict row parser fails closed on malformed input.\n")
            append("Digests in dimensions.tsv pin the two data files; sha256.txt pins all three.\n")
        }
        val out = LinkedHashMap<String, ByteArray>()
        out["cases.tsv"] = casesText.toByteArray(Charsets.UTF_8)
        out["corrupt.tsv"] = corruptText.toByteArray(Charsets.UTF_8)
        out["dimensions.tsv"] = dimsText.toByteArray(Charsets.UTF_8)
        out["sha256.txt"] = manifestText.toByteArray(Charsets.UTF_8)
        out["README.md"] = readme.toByteArray(Charsets.UTF_8)
        return out
    }

    fun parseRow(line: String): Case {
        val field = line.split('\t')
        require(field.size == 6) { "FIELD_COUNT" }
        require(idPattern.matches(field[0])) { "CASE_ID" }
        require(byName.containsKey(field[1]) && byName.containsKey(field[2])) { "PROFILE" }
        val rec = field[3].toIntOrNull() ?: throw IllegalArgumentException("RECFLAG")
        require(rec == 0 || rec == 1) { "RECFLAG" }
        require(field[4] == REUSE || field[4] == REFRESH) { "VERDICT" }
        require(field[5].isNotEmpty()) { "NOTE" }
        return Case(field[0], field[1], field[2], rec, field[4], field[5])
    }

    fun parseCases(text: String): List<Case> {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        require(lines.firstOrNull() == CASE_HEADER) { "HEADER_STRICT" }
        return lines.drop(1).map { parseRow(it) }
    }

    fun parseCorrupt(text: String): List<Pair<String, String>> {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        require(lines.firstOrNull() == CORRUPT_HEADER) { "CORRUPT_HEADER" }
        return lines.drop(1).map { line ->
            val field = line.split('\t', limit = 2)
            require(field.size == 2 && field[0].isNotEmpty()) { "CORRUPT_FIELD" }
            field[0] to field[1]
        }
    }

    fun parseDimensions(text: String): Map<String, String> {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        require(lines.firstOrNull() == DIM_HEADER) { "DIM_HEADER" }
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val field = line.split('\t')
            require(field.size == 2 && field[0].isNotEmpty()) { "DIM_FIELD" }
            out[field[0]] = field[1]
        }
        return out
    }

    fun parseManifest(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(4)
        for (line in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val field = line.split('\t')
            require(field.size == 2 && field[0].length == 64 &&
                field[0].all { it in '0'..'9' || it in 'a'..'f' }) { "MANIFEST_FIELD" }
            out += field[0] to field[1]
        }
        return out
    }

    private fun verdictFor(row: Case): Boolean {
        val current = byName.getValue(row.current).entries?.toMap()
        val next = byName.getValue(row.next).entries!!.toMap()
        return PlayerReusePolicy.canReuse(current, next, row.recflag == 1)
    }

    /** Runs every check against bytes read from the fixture directory. Codes are stable and pinned by run.sh. */
    fun runChecks(files: Map<String, ByteArray>, skip: Set<String>): Result {
        val checks = ArrayList<Check>(24)
        var first = "NONE"
        fun add(code: String, ok: Boolean, detail: String = "") {
            if (code in skip) return
            checks += Check(code, ok, detail)
            if (!ok && first == "NONE") first = code
        }

        val fixtureText = files.values.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
        val leaks = privacyOffenders(fixtureText)
        add("PRIVACY_SCAN", leaks.isEmpty(), "offenders=${leaks.size}")

        val corrupt = parseCorrupt(String(files.getValue("corrupt.tsv"), Charsets.UTF_8))
        var corruptCount = 0
        var rejected = 0
        var controlOk = false
        for ((name, payload) in corrupt) {
            if (name == "valid-control") {
                controlOk = try { parseRow(payload); true } catch (ignored: Throwable) { false }
            } else {
                corruptCount++
                val refused = try { parseRow(payload); false } catch (ignored: Throwable) { true }
                if (refused) rejected++
            }
        }
        add("CORRUPT_FAIL_CLOSED", controlOk && corruptCount == CORRUPT_ROWS - 1 && rejected == corruptCount,
            "rejected=$rejected")

        val caseText = String(files.getValue("cases.tsv"), Charsets.UTF_8)
        val caseLines = caseText.split('\n').dropLastWhile { it.isEmpty() }
        val dims = parseDimensions(String(files.getValue("dimensions.tsv"), Charsets.UTF_8))
        fun dim(key: String): String = dims.getValue(key)
        fun intDim(key: String): Int = dim(key).toInt()
        add("HEADER_STRICT", caseLines.firstOrNull() == CASE_HEADER)
        add("FIELD_COUNT", caseLines.drop(1).all { it.split('\t').size == 6 })
        val rows: List<Case> = try {
            parseCases(caseText)
        } catch (error: Throwable) {
            val code = error.message?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: "HOST_EXCEPTION"
            if (checks.none { it.code == code }) add(code, false)
            return Result(checks, first)
        }
        add("CASE_COUNT", rows.size == CASE_COUNT && intDim("caseCount") == CASE_COUNT,
            "rows=${rows.size}")

        var wrong = 0
        for (row in rows) {
            if (verdictFor(row) != (row.expected == REUSE)) wrong++
        }
        add("CONTRACT_VERDICT", wrong == 0, "mismatch=$wrong")

        add("MATRIX_COVERAGE",
            intDim("reuseExpected") == rows.count { it.expected == REUSE } &&
                intDim("refreshExpected") == rows.count { it.expected == REFRESH } &&
                intDim("nullCurrentExpected") == rows.count { it.current == "NONE" } &&
                intDim("recordingExpected") == rows.count { it.recflag == 1 } &&
                intDim("mismatchExpected") == rows.count {
                    it.recflag == 0 && it.current != "NONE" &&
                        byName.getValue(it.current).entries?.toMap() != byName.getValue(it.next).entries!!.toMap()
                })
        add("NULL_GUARD", rows.filter { it.current == "NONE" }.all { it.expected == REFRESH })
        add("RECORDING_GUARD", rows.filter { it.recflag == 1 }.all { it.expected == REFRESH })
        add("ORDER_INDEPENDENCE", rows.filter {
            it.recflag == 0 && it.current in setOf("ROUTE_A", "ROUTE_A2") &&
                it.next in setOf("ROUTE_A", "ROUTE_A2")
        }.all { it.expected == REUSE })
        add("CROSS_PROVIDER_GUARD", rows.filter {
            it.recflag == 0 && it.current in setOf("ROUTE_A", "ROUTE_A2") &&
                it.next in setOf("ROUTE_B", "ROUTE_AX", "ROUTE_AC", "ROUTE_AM")
        }.all { it.expected == REFRESH })
        add("CAP_BINDS", intDim("capCases") == CAP_CASES && rows.size == CAP_CASES)

        var stable = true
        var heapMax = 0L
        var heapBase = 0L
        var threadMax = 0
        var threadBase = 0
        repeat(intDim("cycles")) { cycle ->
            for (row in rows) {
                if (verdictFor(row) != (row.expected == REUSE)) stable = false
            }
            System.gc()
            try { Thread.sleep(20L) } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val threads = Thread.activeCount()
            if (cycle == 0) { heapBase = used; threadBase = threads }
            heapMax = maxOf(heapMax, used)
            threadMax = maxOf(threadMax, threads)
        }
        add("REPEAT_CYCLES", stable, "cycles=${intDim("cycles")}")
        add("HEAP_BOUND", heapMax < dim("heapLimitBytes").toLong() &&
            heapMax - heapBase < dim("heapDriftBytes").toLong(), "used=$heapMax")
        add("THREAD_BOUND", threadMax <= intDim("threadLimit") && threadMax - threadBase <= 0,
            "threads=$threadMax")

        val staticStores = PlayerReusePolicy::class.java.declaredFields.count { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                (MutableCollection::class.java.isAssignableFrom(field.type) ||
                    Map::class.java.isAssignableFrom(field.type))
        }
        add("STATIC_STATE", staticStores == 0, "stores=$staticStores")

        val manifest = parseManifest(String(files.getValue("sha256.txt"), Charsets.UTF_8))
        add("MANIFEST_CONSISTENT", manifest.size == 3 && manifest.all { (hash, name) ->
            name in files && sha256(files.getValue(name)) == hash
        }, "entries=${manifest.size}")
        add("PINNED_DIGEST", dim("casesSha256") == sha256(files.getValue("cases.tsv")) &&
            dim("corruptSha256") == sha256(files.getValue("corrupt.tsv")))
        val generated = generateFiles()
        add("BYTE_IDENTITY", files.keys.size == FILE_COUNT && generated.keys == files.keys &&
            generated.all { (name, bytes) -> bytes.contentEquals(files.getValue(name)) },
            "files=${files.size}")
        return Result(checks, first)
    }
}
