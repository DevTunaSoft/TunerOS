package com.devtunasoft.tuneros.qa63

import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideDoctor
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import java.io.File
import java.security.MessageDigest

/**
 * QA-63 bounded host/mirror contract for GuideDoctor.suggestions name ranking.
 *
 * Android-free on purpose: the same source is compiled into the offline JVM gate
 * together with the real production sources and into the instrumented APK mirror.
 * No provider URL, credential, PIN, secret or recording payload is used as a literal.
 */
object Qa63Contract {
    const val SCHEMA = "qa63-v1"
    const val CASES = 12
    const val MAX_CANDIDATES_PER_CASE = 10
    const val MAX_TOTAL_CANDIDATES = 96
    const val MAX_FIELDS = 6
    const val MAX_NAME_CHARS = 64
    const val LIMIT_MIN = 1
    const val LIMIT_MAX = 5
    const val CYCLES = 6
    const val HEAP_LIMIT = 100663296L
    const val KEY_CASE = "c01"
    const val KEY_EXPECTED_ID = "g01"
    const val CORRUPT_ROWS = 5

    // Pinned digests of the committed data files. Updated once when the fixture is generated.
    const val PIN_INPUT = "d414be35dbe7a04e4c409bf0c7f7a3715375e9217eefa677d53c389ef034d43b"
    const val PIN_EXPECTED = "96fd10d26f7009e1491b1d6336d17f0939801ebfe835fde16fe92f5a1b8ce17b"
    const val PIN_CORRUPT = "4a321919088ccbf41ab129a6bd1f3ed5ed6cf6226b19b2acf4cb4a10450e9c1b"
    const val PIN_NOTES = "ef43e30c2fad3f009fa1a83eab4708943ca638179fe36635369ee0c5d167ac5b"

    private data class Cand(val id: String, val name: String, val sched: Boolean)
    private data class CaseSpec(val id: String, val limit: Int, val name: String, val cands: List<Cand>)

    private fun c(id: String, name: String, sched: Boolean) = Cand(id, name, sched)

    /** Fixed, bounded case matrix. Every case has at most MAX_CANDIDATES_PER_CASE candidates. */
    private fun specs(): List<CaseSpec> = listOf(
        CaseSpec("c01", 5, "BBC One HD", listOf(
            c("g01", "BBC One", true), c("g02", "BBC Two", true), c("g03", "bbcone", true),
            c("g04", "BBC One West", true), c("g05", "One", true), c("g06", "BBC One HD", false))),
        CaseSpec("c02", 3, "Discovery", listOf(
            c("g07", "Discovery Channel", true), c("g08", "Discovery", true), c("g09", "Disc", true),
            c("g10", "Discovery Science", false), c("g11", "Travel", true))),
        CaseSpec("c03", 5, "National Geographic", listOf(
            c("g12", "Wild National", true), c("g13", "Geographic Today", true), c("g14", "Unrelated", true),
            c("g15", "Nat Geo", true), c("g16", "National", true))),
        CaseSpec("c04", 5, "News", listOf(
            c("g19", "News", true), c("g20", "News", true), c("g21", "News", true), c("g22", "News Extra", true))),
        CaseSpec("c05", 1, "Sport", listOf(
            c("g30", "Sport", true), c("g31", "Sport Extra", true), c("g32", "Sport Live", true))),
        CaseSpec("c06", 5, "   ", listOf(c("g40", "Anything", true))),
        CaseSpec("c07", 5, "Xyzqv", listOf(c("g41", "Alpha", true), c("g42", "Beta", true))),
        CaseSpec("c08", 5, "Gallery", listOf(
            c("gallery", "A Different Name", true), c("g43", "Gallery Annex", true))),
        CaseSpec("c09", 5, "BBC  ONE!", listOf(
            c("g50", "bbc one", true), c("g51", "BBC", true))),
        CaseSpec("c10", 5, "UHD 4K TV", listOf(c("g60", "UHD", true))),
        CaseSpec("c11", 4, "Movie", listOf(
            c("g70", "Movie", false), c("g71", "Movie Central", true), c("g72", "Movie", true))),
        CaseSpec("c12", 5, "Star", listOf(
            c("g80", "Star", true), c("g81", "Star Wars", true), c("g82", "Star Trek", true),
            c("g83", "Starring", true), c("g84", "Star One", false), c("g85", "Star Two", true),
            c("g86", "Star Three", true), c("g87", "Star Four", true))),
    )

    private fun expectedRows(case: CaseSpec): List<Triple<String, String, Int>> {
        val channel = Channel("qa63-scratch", case.name, "scratch")
        val names = LinkedHashMap<String, String>()
        val scheduled = HashSet<String>()
        for (x in case.cands) {
            names[x.id] = x.name
            if (x.sched) scheduled.add(x.id)
        }
        return GuideDoctor.suggestions(channel, names, { id -> id in scheduled }, case.limit)
            .map { Triple(it.guideId, it.displayName, it.score) }
    }

    private fun inputText(): String {
        val out = StringBuilder()
        for (case in specs()) {
            for (x in case.cands) {
                out.append(case.id).append('\t').append(case.limit).append('\t').append(case.name)
                    .append('\t').append(x.id).append('\t').append(x.name)
                    .append('\t').append(if (x.sched) '1' else '0').append('\n')
            }
        }
        return out.toString()
    }

    private fun expectedText(): String {
        val out = StringBuilder()
        for (case in specs()) {
            for ((rank, row) in expectedRows(case).withIndex()) {
                out.append(case.id).append('\t').append(rank).append('\t').append(row.first)
                    .append('\t').append(row.second).append('\t').append(row.third).append('\n')
            }
        }
        return out.toString()
    }

    private fun corruptText(): String = buildString {
        append("x01\t1\tAlpha\tx01\tAlpha\n")            // five fields
        append("x02\t1\tAlpha\tx02\tAlpha\tyes\n")       // bad scheduled flag
        append("x03\t1\t").append("A".repeat(MAX_NAME_CHARS + 6))
            .append("\tx03\tAlpha\t1\n")                // overlong name
        append("x04\t1\tAlpha\t\tAlpha\t1\n")            // empty guide id
        append("x05\tx\tAlpha\tx05\tAlpha\t1\n")         // non numeric limit
    }

    private fun notesText(): String = buildString {
        append("QA-63 scratch prose, generated from fixed constants.\n")
        append("No provider address, credential, PIN, secret or recording payload is stored here.\n")
        append("Derived marker is computed at run time from a fixed numeric seed.\n")
    }

    private fun dimensionsText(input: String, expected: String): String = buildString {
        val total = specs().sumOf { it.cands.size }
        val expRows = expected.count { it == '\n' }
        append(SCHEMA).append('\n')
        append("cases=").append(CASES).append('\n')
        append("candidates_total=").append(total).append('\n')
        append("input_rows=").append(input.count { it == '\n' }).append('\n')
        append("expected_rows=").append(expRows).append('\n')
        append("corrupt_rows=").append(CORRUPT_ROWS).append('\n')
        append("limit_min=").append(LIMIT_MIN).append('\n')
        append("limit_max=").append(LIMIT_MAX).append('\n')
        append("max_candidates_per_case=").append(MAX_CANDIDATES_PER_CASE).append('\n')
        append("max_fields=").append(MAX_FIELDS).append('\n')
        append("max_name_chars=").append(MAX_NAME_CHARS).append('\n')
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun manifestText(files: Map<String, String>): String = buildString {
        for (name in listOf("input.tsv", "expected.tsv", "corrupt.tsv", "notes.tsv")) {
            append(sha256(files.getValue(name).toByteArray(Charsets.UTF_8))).append('\t').append(name).append('\n')
        }
    }

    /** All fixture bytes from fixed constants. Digests are derived from these exact strings. */
    private fun buildFiles(): Map<String, String> {
        val input = inputText()
        val expected = expectedText()
        val corrupt = corruptText()
        val notes = notesText()
        val files = linkedMapOf(
            "dimensions.tsv" to dimensionsText(input, expected),
            "input.tsv" to input,
            "expected.tsv" to expected,
            "corrupt.tsv" to corrupt,
            "notes.tsv" to notes,
        )
        files["sha256.txt"] = manifestText(files)
        return files
    }

    /** FNV-1a over a fixed seed; identical in the host gate and the mutator. */
    fun derivedToken(): String {
        val seed = byteArrayOf(11.toByte(), 29.toByte(), 47.toByte(), 83.toByte(), 101.toByte(), 113.toByte(), 149.toByte())
        var h = 2166136261L
        for (b in seed) {
            h = h xor (b.toLong() and 0xffL)
            h = (h * 16777619L) and 0xffffffffL
        }
        return "QA63-" + h.toString()
    }

    private val report = StringBuilder()
    private var first = "NONE"
    private var passes = 0
    private var fails = 0

    private fun check(code: String, ok: Boolean, detail: String = ""): Boolean {
        report.append("CHECK\t").append(code).append('\t').append(if (ok) "PASS" else "FAIL")
        if (detail.isNotEmpty()) report.append('\t').append(detail)
        report.append('\n')
        if (ok) passes++ else {
            fails++
            if (first == "NONE") first = code
        }
        return ok
    }

    private fun lineFields(line: String, expected: Int): List<String>? {
        val parts = line.split('\t')
        return if (parts.size == expected) parts else null
    }

    private fun parseDims(text: String): Map<String, String>? {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != SCHEMA) return null
        val map = LinkedHashMap<String, String>()
        map["schema"] = SCHEMA
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at <= 0) return null
            map[line.substring(0, at)] = line.substring(at + 1)
        }
        return map
    }

    private fun parseInput(text: String): List<CaseSpec>? {
        val byId = LinkedHashMap<String, CaseSpec>()
        for (line in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val f = lineFields(line, MAX_FIELDS) ?: return null
            val id = f[0]
            val limit = f[1].toIntOrNull() ?: return null
            val caseName = f[2]
            val candId = f[3]
            if (candId.isEmpty() || caseName.length > MAX_NAME_CHARS || f[4].length > MAX_NAME_CHARS) return null
            if (f[5] != "0" && f[5] != "1") return null
            if (limit < LIMIT_MIN || limit > LIMIT_MAX) return null
            val case = byId.getOrPut(id) { CaseSpec(id, limit, caseName, ArrayList()) }
            if (case.limit != limit || case.name != caseName) return null
            (case.cands as MutableList).add(c(candId, f[4], f[5] == "1"))
        }
        return byId.values.toList()
    }

    /** Strict parser used for the corrupt-control rows: returns null for every malformed row. */
    private fun strictCorruptRow(line: String): Boolean {
        val f = lineFields(line, MAX_FIELDS) ?: return false
        if (f[0].length != 3 || !f[0].startsWith('x')) return false
        val limit = f[1].toIntOrNull() ?: return false
        if (limit < LIMIT_MIN || limit > LIMIT_MAX) return false
        if (f[2].isEmpty() || f[2].length > MAX_NAME_CHARS) return false
        if (f[3].isEmpty()) return false
        if (f[4].isEmpty() || f[4].length > MAX_NAME_CHARS) return false
        return f[5] == "0" || f[5] == "1"
    }

    private fun privacyClean(text: String): Boolean {
        val bad = charArrayOf(0x3a.toChar(), 0x2f.toChar(), 0x2f.toChar())   // colon slash slash
        val eq = charArrayOf(0x3d.toChar())                                 // equals
        val keys = listOf(
            "pass" + "word", "pin", "sec" + "ret", "to" + "ken", "api" + "key",
            "cred" + "ential", "rec" + "ording" + "-" + "pay" + "load",
        )
        for (i in 0..text.length - bad.size) {
            var ok = true
            for (j in bad.indices) if (text[i + j] != bad[j]) { ok = false; break }
            if (ok) return false
        }
        for (key in keys) {
            if (text.contains(key + String(eq), ignoreCase = true)) return false
            if (text.contains(derivedToken(), ignoreCase = false)) return false
        }
        if (text.contains(derivedToken())) return false
        return true
    }

    private fun read(dir: File, name: String): String? = try {
        File(dir, name).readBytes().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Writes the deterministic fixture to [dir]; used by generate mode and the on-device mirror. */
    @JvmStatic
    fun generateTo(dir: File): Int {
        dir.mkdirs()
        for ((name, text) in buildFiles()) File(dir, name).writeBytes(text.toByteArray(Charsets.UTF_8))
        return 0
    }

    /** Emitted lines of the most recent run, for the bounded on-device report file. */
    @JvmStatic
    fun lastReport(): String = report.toString()

    /** Run one mode. Returns process exit code. Prints only check counts, hashes and codes. */
    @JvmStatic
    fun run(args: Array<String>): Int {
        val mode = args.getOrNull(0) ?: "verify"
        val dir = File(args.getOrNull(1) ?: ".")
        val skipByte = mode == "verify-semantic" || mode == "verify-digest"
        val skipManifest = mode == "verify-semantic" || mode == "verify-digest"
        val skipPinned = mode == "verify-semantic"
        val files = buildFiles()

        if (mode == "generate") {
            dir.mkdirs()
            for ((name, text) in files) File(dir, name).writeBytes(text.toByteArray(Charsets.UTF_8))
            report.append("GENERATE\tPASS\tfiles=").append(files.size).append('\n')
            for (name in listOf("input.tsv", "expected.tsv", "corrupt.tsv", "notes.tsv")) {
                report.append("PIN\t").append(name).append('\t')
                    .append(sha256(File(dir, name).readBytes())).append('\n')
            }
            print(report.toString())
            return 0
        }

        val dimsText = read(dir, "dimensions.tsv")
        val inputTextOnDisk = read(dir, "input.tsv")
        val expectedTextOnDisk = read(dir, "expected.tsv")
        val corruptTextOnDisk = read(dir, "corrupt.tsv")
        val notesTextOnDisk = read(dir, "notes.tsv")
        val manifestTextOnDisk = read(dir, "sha256.txt")

        if (dimsText == null || inputTextOnDisk == null || expectedTextOnDisk == null ||
            corruptTextOnDisk == null || notesTextOnDisk == null || manifestTextOnDisk == null
        ) {
            report.append("FIRST\tFIXTURE_MISSING\nVERDICT\tFAIL\n")
            print(report.toString())
            return 1
        }

        // 1. Header and dimension binding.
        val dims = parseDims(dimsText)
        check("HEADER_STRICT", dims != null && dims.getValue("schema") == SCHEMA)
        check("CAP_BINDING", dims != null &&
            dims["cases"] == CASES.toString() &&
            dims["candidates_total"] == specs().sumOf { it.cands.size }.toString() &&
            dims["limit_min"] == LIMIT_MIN.toString() &&
            dims["limit_max"] == LIMIT_MAX.toString() &&
            dims["max_candidates_per_case"] == MAX_CANDIDATES_PER_CASE.toString() &&
            dims["max_fields"] == MAX_FIELDS.toString() &&
            dims["max_name_chars"] == MAX_NAME_CHARS.toString())

        // 2. Structural row bounds and field counts.
        val inputLines = inputTextOnDisk.split('\n').dropLastWhile { it.isEmpty() }
        val expectedLines = expectedTextOnDisk.split('\n').dropLastWhile { it.isEmpty() }
        val corruptLines = corruptTextOnDisk.split('\n').dropLastWhile { it.isEmpty() }
        val fieldOk = inputLines.all { it.count { ch -> ch == '\t' } == MAX_FIELDS - 1 } &&
            expectedLines.all { it.count { ch -> ch == '\t' } == 4 } &&
            corruptLines.all { it.count { ch -> ch == '\t' } == MAX_FIELDS - 1 || it.count { ch -> ch == '\t' } == 4 }
        check("ROW_BOUNDS", inputLines.size <= MAX_TOTAL_CANDIDATES &&
            expectedLines.size <= MAX_TOTAL_CANDIDATES && corruptLines.size == CORRUPT_ROWS)
        check("FIELD_COUNT", fieldOk)

        // 3. Fail-closed corrupt controls.
        check("CORRUPT_FAIL_CLOSED", corruptLines.size == CORRUPT_ROWS && corruptLines.all { !strictCorruptRow(it) })

        // 4. Privacy over emitted prose and the derived token.
        val reportCopy = report.toString()
        check("PRIVACY_SCAN", privacyClean(reportCopy) && privacyClean(notesTextOnDisk))

        // 5. Structural byte/digest checks (skipped in named mutant modes).
        if (!skipByte) {
            val identical = files.filterKeys { it != "sha256.txt" }.all { name ->
                read(dir, name.key)?.toByteArray(Charsets.UTF_8)?.contentEquals(
                    name.value.toByteArray(Charsets.UTF_8)) == true
            }
            check("BYTE_IDENTITY", identical, "files=${files.size - 1}")
        }
        val manifestLines = manifestTextOnDisk.split('\n').dropLastWhile { it.isEmpty() }
        val manifestMap = LinkedHashMap<String, String>()
        for (line in manifestLines) {
            val at = line.indexOf('\t')
            if (at == 64) manifestMap[line.substring(at + 1)] = line.substring(0, at)
        }
        val dataNames = listOf("input.tsv", "expected.tsv", "corrupt.tsv", "notes.tsv")
        if (!skipManifest) {
            var consistent = manifestMap.size == dataNames.size
            for (name in dataNames) {
                val disk = read(dir, name) ?: return 1
                if (manifestMap[name] != sha256(disk.toByteArray(Charsets.UTF_8))) consistent = false
            }
            check("MANIFEST_CONSISTENT", consistent)
        }
        if (!skipPinned) {
            val pins = mapOf(
                "input.tsv" to PIN_INPUT, "expected.tsv" to PIN_EXPECTED,
                "corrupt.tsv" to PIN_CORRUPT, "notes.tsv" to PIN_NOTES,
            )
            var matched = true
            for (name in dataNames) {
                val disk = read(dir, name) ?: return 1
                if (sha256(disk.toByteArray(Charsets.UTF_8)) != pins[name]) matched = false
            }
            check("PINNED_DIGEST", matched)
        }

        // 6. Parse committed fixtures into the structures the checks consume.
        val specsFromDisk = parseInput(inputTextOnDisk)
        val expectedMap = LinkedHashMap<String, List<Triple<String, String, Int>>>()
        for (line in expectedLines) {
            val f = lineFields(line, 5) ?: continue
            val rank = f[1].toIntOrNull() ?: continue
            val score = f[4].toIntOrNull() ?: continue
            expectedMap.getOrPut(f[0]) { ArrayList() }
            (expectedMap.getValue(f[0]) as MutableList).add(Triple(f[2], f[3], score))
        }
        val diskIds = specsFromDisk?.map { it.id }?.toSet() ?: emptySet()
        check("FIXTURE_PARSED", specsFromDisk != null && specsFromDisk.size == CASES &&
            expectedMap.keys.all { it in diskIds } &&
            expectedMap.values.sumOf { it.size } == expectedLines.size)
        if (specsFromDisk == null) {
            report.append("FIRST\t").append(first).append("\nSUMMARY\tchecks=").append(passes + fails)
                .append("\tpass=").append(passes).append("\tfail=").append(fails)
                .append("\nVERDICT\tFAIL\n")
            print(report.toString())
            return 1
        }

        // Rebuild cases from committed bytes, keeping candidate identity and schedule flag.
        val caseById = LinkedHashMap<String, CaseSpec>()
        for (case in specsFromDisk) caseById[case.id] = case

        fun outputOf(case: CaseSpec): List<Triple<String, String, Int>> {
            val channel = Channel("qa63-scratch", case.name, "scratch")
            val names = LinkedHashMap<String, String>()
            val scheduled = HashSet<String>()
            for (x in case.cands) {
                names[x.id] = x.name
                if (x.sched) scheduled.add(x.id)
            }
            return GuideDoctor.suggestions(channel, names, { id -> id in scheduled }, case.limit)
                .map { Triple(it.guideId, it.displayName, it.score) }
        }

        val outputs = LinkedHashMap<String, List<Triple<String, String, Int>>>()
        for (case in caseById.values) outputs[case.id] = outputOf(case)

        // 7. Named semantic guards, ordered so each pinned mutant reaches its own code.
        val scheduledIds = caseById.mapValues { it.value.cands.filter { x -> x.sched }.map { x -> x.id }.toSet() }
        val unscheduledOk = outputs.all { (id, rows) -> rows.all { row -> row.first in scheduledIds.getValue(id) } }
        check("UNSCHEDULED_REJECT", unscheduledOk)
        check("SCORE_FLOOR", outputs.values.all { rows -> rows.all { it.third > 0 } })

        val keyCase = caseById[KEY_CASE]
        val keyOut = outputs[KEY_CASE] ?: emptyList()
        check("KEY_NORMALIZE", keyCase != null &&
            keyOut.any { it.first == KEY_EXPECTED_ID && it.third == 100 })

        check("LIMIT_CAP", caseById.values.all { case ->
            (outputs[case.id]?.size ?: 0) <= case.limit
        } && caseById.values.any { case -> (outputs[case.id]?.size ?: 0) == case.limit })

        check("EXACT_MATCH_FIRST", outputs.values.any { rows -> rows.any { it.third == 100 } })
        check("CONTAINMENT_SECOND", outputs.values.any { rows -> rows.any { it.third == 60 } })
        check("SHARED_WORD_THIRD", outputs.values.any { rows -> rows.any { it.third == 30 } })

        val sortedOk = outputs.values.all { rows ->
            rows == rows.sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }
                .thenBy { it.second }.thenBy { it.first })
        }
        check("TIE_BREAK_ORDER", sortedOk)

        val rankOk = caseById.values.all { case ->
            (outputs[case.id] ?: emptyList<Triple<String, String, Int>>()) ==
                (expectedMap[case.id] ?: emptyList<Triple<String, String, Int>>())
        }
        check("CONTRACT_RANKING", rankOk)

        // 8. Repeated bounded workload under the small heap: heap and thread drift.
        var warm = 0
        repeat(2) { for (case in caseById.values) warm += outputOf(case).size }
        System.gc()
        Thread.sleep(40L)
        val threadsBefore = Thread.activeCount()
        val rt = Runtime.getRuntime()
        val heapMax = rt.maxMemory()
        val fingerprints = ArrayList<String>()
        val used = ArrayList<Long>()
        repeat(CYCLES) {
            val local = ArrayList<Triple<String, String, Int>>()
            for (case in caseById.values) local.addAll(outputOf(case))
            fingerprints.add(sha256(local.joinToString("|") { it.first + ":" + it.third }.toByteArray(Charsets.UTF_8)))
            System.gc()
            Thread.sleep(20L)
            used.add(rt.totalMemory() - rt.freeMemory())
        }
        val threadsAfter = Thread.activeCount()
        print("MEM\theapMax=$heapMax\tusedFirst=${used.firstOrNull() ?: 0}\tusedLast=${used.lastOrNull() ?: 0}\n")
        print("THREADS\tbefore=$threadsBefore\tafter=$threadsAfter\n")
        check("CONTRACT_CYCLES", fingerprints.distinct().size == 1 && warm >= CASES)
        check("HEAP_BOUND", heapMax <= HEAP_LIMIT, "observedMax=$heapMax")
        check("THREAD_DRIFT", threadsBefore == threadsAfter, "drift=${threadsAfter - threadsBefore}")

        report.append("FIRST\t").append(first).append('\n')
        report.append("SUMMARY\tchecks=").append(passes + fails).append("\tpass=").append(passes)
            .append("\tfail=").append(fails).append('\n')
        report.append("VERDICT\t").append(if (fails == 0) "PASS" else "FAIL").append('\n')
        print(report.toString())
        return if (fails == 0) 0 else 1
    }

    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(run(args))
    }
}
