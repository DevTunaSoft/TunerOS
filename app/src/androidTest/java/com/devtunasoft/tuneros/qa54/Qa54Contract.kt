package com.devtunasoft.tuneros.qa54

import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.FavoriteChannels
import java.io.File
import java.security.MessageDigest

/**
 * QA-54 bounded slice: stable-key favorite admission and playlist-order filtering.
 * Framework-free so the same source compiles into the host gate and the instrumented mirror.
 * All fixture IDs and payloads are synthetic scratch values.
 */
object Qa54Contract {
    const val SCHEMA = "qa54-favorites-v1"
    const val CHANNEL_COUNT = 32
    const val CASE_COUNT = 48
    const val TOGGLE_COUNT = 24
    const val FILTER_COUNT = 24
    const val CORRUPT_COUNT = 8
    const val MAX_CHANNELS = 32
    const val MAX_CASES = 48
    const val MAX_LINE_BYTES = 512
    const val MAX_HEAP_BYTES = 100663296L

    private const val SEED = 20261124L
    private const val CHANNEL_HEADER = "id\tname\tstableKey\tgroup"
    private const val CASE_HEADER = "caseId\tkind\tinputKeys\tchannelId\tfavoritesOnly\texpected"
    private const val CORRUPT_HEADER = "caseId\treason\tpayload"
    private val DATA_FILES = listOf("channels.tsv", "cases.tsv", "corrupt.tsv", "report-copy.txt")
    val ALL_FILES = DATA_FILES + listOf("sha256.txt", "contract.txt")
    private val REASONS = listOf(
        "FIELD_COUNT", "EMPTY_ID", "BAD_ID", "BAD_KEY", "OVERSIZE", "BAD_GROUP", "NON_ASCII", "LEADING_WS",
    )
    private val ID_RE = Regex("^c[0-9]{4}$")
    private val KEY_RE = Regex("^[A-Za-z0-9_-]*$")
    private val GROUP_RE = Regex("^g[0-9]{2}$")
    private val CASE_ID_RE = Regex("^[TF][0-9]{2}$")

    data class ChannelRow(val id: String, val name: String, val stableKey: String, val group: String) {
        fun toChannel(): Channel = Channel(id, name, "SCRATCH_STREAM", group = group, stableKey = stableKey)
        fun storageKey(): String = stableKey.ifEmpty { id }
    }

    data class CaseRow(
        val id: String,
        val kind: String,
        val inputKeys: List<String>,
        val channelId: String,
        val favoritesOnly: Boolean?,
        val expected: String,
    )

    data class CorruptRow(val id: String, val reason: String, val payload: String)

    private class Fixture(
        val raw: Map<String, String>,
        val contract: Map<String, String>,
        val manifest: Map<String, String>,
    )

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
    private fun pad4(value: Int): String = value.toString().padStart(4, '0')

    /** Derived at run time from a fixed seed; the token is never stored as a literal. */
    fun derivedToken(): String {
        val mixed = (SEED * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
        return "qa54mk" + mixed.toString(16).padStart(16, '0')
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (b in bytes) {
            append(digits[(b.toInt() shr 4) and 0x0f])
            append(digits[b.toInt() and 0x0f])
        }
    }

    private fun sha(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun aggregateDigest(data: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in data.keys.sorted()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(data.getValue(name).toByteArray(Charsets.UTF_8))
        }
        return hex(digest.digest())
    }

    private fun buildChannels(): List<ChannelRow> = (0 until CHANNEL_COUNT).map { i ->
        val stable = when {
            i % 11 == 5 -> "kshared"
            i % 7 == 3 -> ""
            else -> "k" + pad4(i)
        }
        ChannelRow("c" + pad4(i), "Name-" + pad4(i), stable, "g" + pad2(i % 8))
    }

    private fun buildCases(channels: List<ChannelRow>): List<CaseRow> {
        val out = ArrayList<CaseRow>(CASE_COUNT)
        for (j in 0 until TOGGLE_COUNT) {
            val input = mutableListOf("k" + pad4(j % CHANNEL_COUNT), "k" + pad4((j + 5) % CHANNEL_COUNT))
            val target: String
            val expected: List<String>
            when (j % 6) {
                0 -> { target = ""; expected = input.toList() }
                1 -> { target = "k-add-" + pad2(j); expected = input + target }
                2 -> { target = input.first(); expected = input - target }
                3 -> {
                    input.add("k-dup"); input.add("k-dup")
                    target = "k-dup"; expected = input.distinct() - target
                }
                4 -> { target = "kshared"; expected = input + target }
                else -> {
                    input.add(input.first())
                    target = "k-unknown"; expected = input.distinct() + target
                }
            }
            out += CaseRow(
                "T" + pad2(j), "TOGGLE", input.distinct().sorted(), target, null,
                expected.distinct().sorted().joinToString(","),
            )
        }
        val emptyKeyId = channels.first { it.stableKey.isEmpty() }.id
        for (j in 0 until FILTER_COUNT) {
            val input: List<String>
            val enabled: Boolean
            val expected: String
            when (j % 6) {
                0 -> { input = listOf("k0001", "k0002"); enabled = false; expected = "ALL" }
                1 -> { input = emptyList(); enabled = true; expected = "NONE" }
                2 -> { input = listOf("k0000", emptyKeyId, "k0007"); enabled = true; expected = "" }
                3 -> { input = listOf("kshared"); enabled = true; expected = "" }
                4 -> { input = listOf("k-none"); enabled = true; expected = "NONE" }
                else -> { input = listOf("k0001"); enabled = false; expected = "ALL" }
            }
            val resolved = if (expected.isEmpty()) {
                val selected = channels.filter { it.storageKey() in input }.map { it.id }
                if (selected.isEmpty()) "NONE" else selected.joinToString(",")
            } else expected
            out += CaseRow("F" + pad2(j), "FILTER", input.sorted(), "-", enabled, resolved)
        }
        return out
    }

    private fun buildCorrupt(): List<CorruptRow> {
        val longName = "X".repeat(MAX_LINE_BYTES)
        val payloads = listOf(
            "c9001\tName-9001\tk9001",
            "\tName-9002\tk9002\tg00",
            "c!003\tName-9003\tk9003\tg00",
            "c9004\tName-9004\tk!bad\tg00",
            "c9005\t$longName\tk9005\tg00",
            "c9006\tName-9006\tk9006\tGROUP",
            "c9007\tNam\u00e9-9007\tk9007\tg00",
            "c9008\t Name-9008\tk9008\tg00",
        )
        return payloads.mapIndexed { i, payload ->
            CorruptRow("C" + pad2(i + 1), REASONS[i], payload)
        }
    }

    private fun renderChannels(rows: List<ChannelRow>): String = buildString {
        append(CHANNEL_HEADER).append('\n')
        for (row in rows) {
            append(row.id).append('\t').append(row.name).append('\t')
                .append(row.stableKey).append('\t').append(row.group).append('\n')
        }
    }

    private fun renderCases(rows: List<CaseRow>): String = buildString {
        append(CASE_HEADER).append('\n')
        for (row in rows) {
            append(row.id).append('\t').append(row.kind).append('\t')
                .append(row.inputKeys.joinToString(",")).append('\t').append(row.channelId).append('\t')
                .append(if (row.favoritesOnly == null) "-" else row.favoritesOnly.toString()).append('\t')
                .append(row.expected).append('\n')
        }
    }

    private fun renderCorrupt(rows: List<CorruptRow>): String = buildString {
        append(CORRUPT_HEADER).append('\n')
        for (row in rows) {
            append(row.id).append('\t').append(row.reason).append('\t').append(row.payload).append('\n')
        }
    }

    private val BENIGN_REPORT = "QA54 scratch report copy\ncheck=PASS\nsynthetic-marker=QA54SCRATCH\n"

    /** One in-memory builder is the only source of committed fixture bytes. */
    private fun regenerateData(): Map<String, String> {
        val channels = buildChannels()
        return linkedMapOf(
            "channels.tsv" to renderChannels(channels),
            "cases.tsv" to renderCases(buildCases(channels)),
            "corrupt.tsv" to renderCorrupt(buildCorrupt()),
            "report-copy.txt" to BENIGN_REPORT,
        )
    }

    private fun renderAll(): Map<String, String> {
        val data = regenerateData()
        val manifest = buildString {
            for (name in data.keys.sorted()) append(sha(data.getValue(name))).append("  ").append(name).append('\n')
        }
        val contract = buildString {
            append("schema=").append(SCHEMA).append('\n')
            append("channel_count=").append(CHANNEL_COUNT).append('\n')
            append("case_count=").append(CASE_COUNT).append('\n')
            append("toggle_count=").append(TOGGLE_COUNT).append('\n')
            append("filter_count=").append(FILTER_COUNT).append('\n')
            append("corrupt_count=").append(CORRUPT_COUNT).append('\n')
            append("max_channels=").append(MAX_CHANNELS).append('\n')
            append("max_cases=").append(MAX_CASES).append('\n')
            append("max_line_bytes=").append(MAX_LINE_BYTES).append('\n')
            append("mirror_digest=").append(aggregateDigest(data)).append('\n')
        }
        return data + mapOf("sha256.txt" to manifest, "contract.txt" to contract)
    }

    fun generate(dir: File, sink: (String) -> Unit = { println(it) }) {
        dir.mkdirs()
        val all = renderAll()
        for (name in ALL_FILES) {
            File(dir, name).writeBytes(all.getValue(name).toByteArray(Charsets.UTF_8))
        }
        sink("GENERATE\tfiles=" + ALL_FILES.size + "\tmirror_digest=" + aggregateDigest(regenerateData()))
    }

    private fun readLines(text: String): List<String> = text.split("\n").dropLastWhile { it.isEmpty() }

    private fun load(dir: File): Fixture {
        val raw = LinkedHashMap<String, String>()
        for (name in ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) throw IllegalStateException("MISSING_$name")
            raw[name] = file.readBytes().toString(Charsets.UTF_8)
        }
        val contract = LinkedHashMap<String, String>()
        for (line in readLines(raw.getValue("contract.txt"))) {
            val pair = line.split("=", limit = 2)
            if (pair.size != 2) throw IllegalStateException("CONTRACT_SHAPE")
            contract[pair[0]] = pair[1]
        }
        val manifest = LinkedHashMap<String, String>()
        val entry = Regex("^([0-9a-f]{64})  (\\S+)$")
        for (line in readLines(raw.getValue("sha256.txt"))) {
            val match = entry.matchEntire(line) ?: throw IllegalStateException("MANIFEST_SHAPE")
            manifest[match.groupValues[2]] = match.groupValues[1]
        }
        return Fixture(raw, contract, manifest)
    }

    /** Returns the pinned rejection reason, or null when a strictly valid channel row is supplied. */
    fun classifyChannelRow(line: String): String? {
        val fields = line.split("\t")
        if (fields.size != 4) return "FIELD_COUNT"
        if (fields[0].isEmpty()) return "EMPTY_ID"
        if (!ID_RE.matches(fields[0])) return "BAD_ID"
        if (!KEY_RE.matches(fields[2])) return "BAD_KEY"
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return "OVERSIZE"
        if (!GROUP_RE.matches(fields[3])) return "BAD_GROUP"
        if (line.any { it.code > 127 }) return "NON_ASCII"
        if (fields.any { it != it.trim() }) return "LEADING_WS"
        return null
    }

    private fun softChannels(raw: Map<String, String>): List<List<String>> =
        readLines(raw.getValue("channels.tsv")).drop(1).map { it.split("\t") }

    private fun channelRows(raw: Map<String, String>): List<ChannelRow> =
        softChannels(raw).map {
            require(it.size == 4) { "CHANNEL_FIELDS" }
            require(classifyChannelRow(it.joinToString("\t")) == null) { "CHANNEL_REJECT" }
            ChannelRow(it[0], it[1], it[2], it[3])
        }

    private fun caseRows(raw: Map<String, String>): List<CaseRow> =
        readLines(raw.getValue("cases.tsv")).drop(1).map { line ->
            val f = line.split("\t")
            require(f.size == 6) { "CASE_FIELDS" }
            require(CASE_ID_RE.matches(f[0])) { "CASE_ID" }
            val kind = f[1]
            require(kind == "TOGGLE" || kind == "FILTER") { "CASE_KIND" }
            val keys = if (f[2].isEmpty()) emptyList() else f[2].split(",")
            require(keys.all { KEY_RE.matches(it) }) { "CASE_KEY" }
            when (kind) {
                "TOGGLE" -> {
                    require(f[3].isEmpty() || KEY_RE.matches(f[3])) { "CASE_TARGET" }
                    require(f[4] == "-") { "CASE_FLAG" }
                    require(f[5].isEmpty() || f[5].split(",").all { KEY_RE.matches(it) }) { "CASE_EXPECTED" }
                }
                else -> {
                    require(f[3] == "-") { "CASE_TARGET" }
                    require(f[4] == "true" || f[4] == "false") { "CASE_FLAG" }
                    require(f[5] == "ALL" || f[5] == "NONE" || f[5].split(",").all { ID_RE.matches(it) }) { "CASE_EXPECTED" }
                }
            }
            CaseRow(f[0], kind, keys, f[3], if (kind == "TOGGLE") null else f[4].toBoolean(), f[5])
        }

    private fun corruptRows(raw: Map<String, String>): List<CorruptRow> =
        readLines(raw.getValue("corrupt.tsv")).drop(1).map { line ->
            val f = line.split("\t", limit = 3)
            require(f.size == 3) { "CORRUPT_FIELDS" }
            require(f[1] in REASONS) { "CORRUPT_REASON" }
            CorruptRow(f[0], f[1], f[2])
        }

    private fun privacyProblems(blob: String): List<String> {
        val problems = ArrayList<String>()
        if (blob.contains(derivedToken())) problems += "DERIVED_TOKEN"
        val patterns = listOf(
            Regex("(?i)(https?|ftp)://"),
            Regex("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|bearer|credential)\\s*[=:]"),
            Regex("(?i)\\bpin\\s*[=:]?\\s*[0-9]{4,}"),
            Regex("(?i)\\brec[0-9]{4,}"),
            Regex("(?i)file_path\\s*="),
            Regex("(?i)stream_url\\s*="),
        )
        for (pattern in patterns) if (pattern.containsMatchIn(blob)) problems += pattern.pattern
        return problems.distinct()
    }

    private val FULL_CHECKS = listOf(
        "HEADER_STRICT", "ROW_BOUNDS", "CAP_BINDING", "CASE_COVERAGE", "MANIFEST_CONSISTENT",
        "PINNED_DIGEST", "BYTE_IDENTITY", "TOGGLE_CASES", "FILTER_CASES", "IDENTITY_OFF",
        "CORRUPT_FAIL_CLOSED", "LAX_ACCEPTS", "PRIVACY_SCAN", "THREAD_DRIFT", "BOUNDED_HEAP",
    )
    private val MUTANT_CHECKS = listOf(
        "HEADER_STRICT", "ROW_BOUNDS", "CAP_BINDING", "CASE_COVERAGE", "TOGGLE_CASES",
        "FILTER_CASES", "IDENTITY_OFF", "CORRUPT_FAIL_CLOSED", "LAX_ACCEPTS",
        "PRIVACY_SCAN", "THREAD_DRIFT", "BOUNDED_HEAP",
    )
    private val DEVICE_CHECKS = listOf(
        "CASE_COVERAGE", "TOGGLE_CASES", "FILTER_CASES", "IDENTITY_OFF",
        "CORRUPT_FAIL_CLOSED", "LAX_ACCEPTS", "PRIVACY_SCAN", "THREAD_DRIFT",
    )

    /** Executes the named checks against the real compiled FavoriteChannels object. */
    fun run(mode: String, dir: File, sink: (String) -> Unit = { println(it) }): Int {
        val names = when (mode) {
            "verify" -> FULL_CHECKS
            "verify-byte" -> listOf("BYTE_IDENTITY")
            "verify-manifest" -> listOf("MANIFEST_CONSISTENT")
            "verify-pinned" -> listOf("PINNED_DIGEST")
            "verify-mutant" -> MUTANT_CHECKS
            "verify-device" -> DEVICE_CHECKS
            else -> throw IllegalArgumentException("BAD_MODE")
        }
        val out = ArrayList<String>()
        val beforeThreads = Thread.getAllStackTraces().keys.map { it.name }.toSet()
        val heapMax = Runtime.getRuntime().maxMemory()
        val device = mode == "verify-device"
        val fixture: Fixture
        if (device) {
            val raw = LinkedHashMap<String, String>()
            val all = renderAll()
            for (name in ALL_FILES) raw[name] = all.getValue(name)
            val contract = LinkedHashMap<String, String>()
            for (line in readLines(raw.getValue("contract.txt"))) {
                val pair = line.split("=", limit = 2)
                contract[pair[0]] = pair[1]
            }
            fixture = Fixture(raw, contract, emptyMap())
        } else {
            fixture = load(dir)
        }
        val raw = fixture.raw
        val failures = ArrayList<String>()
        var first: String? = null

        fun step(id: String, body: () -> Pair<Boolean, String>) {
            val result = try {
                body()
            } catch (t: Throwable) {
                false to ("EXCEPTION " + t.javaClass.simpleName)
            }
            val status = if (result.first) "PASS" else "FAIL"
            out.add("CHECK\t$id\t$status\t" + result.second)
            if (!result.first && first == null) first = id
            if (!result.first) failures.add(id)
        }

        fun headerOk(): Boolean {
            val ch = readLines(raw.getValue("channels.tsv"))
            val ca = readLines(raw.getValue("cases.tsv"))
            val co = readLines(raw.getValue("corrupt.tsv"))
            return ch.firstOrNull() == CHANNEL_HEADER && ca.firstOrNull() == CASE_HEADER && co.firstOrNull() == CORRUPT_HEADER
        }

        fun rowBoundsOk(): Boolean {
            // corrupt.tsv is an adversarial block: its payloads intentionally violate row rules.
            for (name in listOf("channels.tsv", "cases.tsv", "report-copy.txt")) {
                for (line in readLines(raw.getValue(name))) {
                    if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return false
                }
            }
            if (softChannels(raw).any { classifyChannelRow(it.joinToString("\t")) != null }) return false
            if (readLines(raw.getValue("cases.tsv")).drop(1).any { it.split("\t").size != 6 }) return false
            if (readLines(raw.getValue("corrupt.tsv")).drop(1).any { it.split("\t", limit = 3).size != 3 }) return false
            return true
        }

        fun capBindingOk(): Boolean {
            val c = fixture.contract
            return c["schema"] == SCHEMA &&
                c["channel_count"]?.toIntOrNull() == CHANNEL_COUNT &&
                c["case_count"]?.toIntOrNull() == CASE_COUNT &&
                c["toggle_count"]?.toIntOrNull() == TOGGLE_COUNT &&
                c["filter_count"]?.toIntOrNull() == FILTER_COUNT &&
                c["corrupt_count"]?.toIntOrNull() == CORRUPT_COUNT &&
                c["max_channels"]?.toIntOrNull() == MAX_CHANNELS &&
                c["max_cases"]?.toIntOrNull() == MAX_CASES &&
                c["max_line_bytes"]?.toIntOrNull() == MAX_LINE_BYTES &&
                softChannels(raw).size == MAX_CHANNELS &&
                readLines(raw.getValue("cases.tsv")).drop(1).size == c["case_count"]?.toIntOrNull() &&
                corruptRows(raw).size == CORRUPT_COUNT
        }

        fun coverageOk(): Boolean {
            val cases = caseRows(raw)
            val channels = channelRows(raw)
            val toggles = cases.filter { it.kind == "TOGGLE" }
            val filters = cases.filter { it.kind == "FILTER" }
            return toggles.size == TOGGLE_COUNT && filters.size == FILTER_COUNT &&
                toggles.any { it.channelId.isEmpty() } &&
                toggles.any { it.channelId.isNotEmpty() && it.channelId !in it.inputKeys && it.channelId in it.expected.split(",") } &&
                toggles.any { it.channelId.isNotEmpty() && it.channelId in it.inputKeys && it.channelId !in it.expected.split(",") } &&
                filters.any { it.favoritesOnly == false } &&
                filters.any { it.favoritesOnly == true && "kshared" in it.inputKeys } &&
                channels.any { it.stableKey.isEmpty() } &&
                filters.any { f -> channels.any { c -> c.stableKey.isEmpty() && c.id in f.inputKeys } }
        }

        fun toggleOk(): Boolean {
            val cases = caseRows(raw).filter { it.kind == "TOGGLE" }
            require(cases.size == TOGGLE_COUNT) { "TOGGLE_COUNT" }
            for (row in cases) {
                val expected = if (row.expected.isEmpty()) emptySet() else row.expected.split(",").toSet()
                val actual = FavoriteChannels.toggle(row.inputKeys.toSet(), row.channelId)
                require(actual == expected) { "TOGGLE_MISMATCH " + row.id }
            }
            return true
        }

        fun filterOk(): Boolean {
            val channels = channelRows(raw)
            val cases = caseRows(raw).filter { it.kind == "FILTER" }
            require(cases.size == FILTER_COUNT) { "FILTER_COUNT" }
            val allIds = channels.map { it.id }
            for (row in cases) {
                val expected = when (row.expected) {
                    "ALL" -> allIds
                    "NONE" -> emptyList()
                    else -> row.expected.split(",")
                }
                val actual = FavoriteChannels.filter(channels.map { it.toChannel() }, row.inputKeys.toSet(), row.favoritesOnly!!).map { it.id }
                require(actual == expected) { "FILTER_MISMATCH " + row.id }
            }
            return true
        }

        for (name in names) {
            when (name) {
                "HEADER_STRICT" -> step(name) { headerOk() to "headers=3" }
                "ROW_BOUNDS" -> step(name) { rowBoundsOk() to "maxLineBytes=$MAX_LINE_BYTES" }
                "CAP_BINDING" -> step(name) { capBindingOk() to "channels=$CHANNEL_COUNT cases=$CASE_COUNT" }
                "CASE_COVERAGE" -> step(name) { coverageOk() to "toggle=$TOGGLE_COUNT filter=$FILTER_COUNT" }
                "MANIFEST_CONSISTENT" -> step(name) {
                    val ok = DATA_FILES.all { nameFile -> fixture.manifest[nameFile] == sha(raw.getValue(nameFile)) }
                    ok to "files=" + DATA_FILES.size
                }
                "PINNED_DIGEST" -> step(name) {
                    val data = LinkedHashMap<String, String>()
                    for (file in DATA_FILES) data[file] = raw.getValue(file)
                    (aggregateDigest(data) == fixture.contract["mirror_digest"]) to "aggregate=sha256"
                }
                "BYTE_IDENTITY" -> step(name) {
                    val expected = renderAll()
                    (ALL_FILES.all { file -> expected.getValue(file).toByteArray(Charsets.UTF_8).contentEquals(raw.getValue(file).toByteArray(Charsets.UTF_8)) }) to "files=" + ALL_FILES.size
                }
                "TOGGLE_CASES" -> step(name) { toggleOk() to "cases=$TOGGLE_COUNT" }
                "FILTER_CASES" -> step(name) { filterOk() to "cases=$FILTER_COUNT" }
                "IDENTITY_OFF" -> step(name) {
                    val channels = channelRows(raw).map { it.toChannel() }
                    val same = FavoriteChannels.filter(channels, setOf("k0001"), false) === channels
                    same to "same-instance"
                }
                "CORRUPT_FAIL_CLOSED" -> step(name) {
                    val rows = corruptRows(raw)
                    require(rows.size == CORRUPT_COUNT) { "CORRUPT_COUNT" }
                    for (row in rows) require(classifyChannelRow(row.payload) == row.reason) { "CORRUPT_REASON " + row.id }
                    true to "rows=$CORRUPT_COUNT"
                }
                "LAX_ACCEPTS" -> step(name) {
                    val rows = corruptRows(raw)
                    val laxAccepts = rows.all { it.payload.split("\t").size >= 2 }
                    laxAccepts to "rows=$CORRUPT_COUNT"
                }
                "PRIVACY_SCAN" -> step(name) {
                    val blob = out.joinToString("\n") + "\n" + raw.values.joinToString("\n")
                    val problems = privacyProblems(blob)
                    problems.isEmpty() to ("patterns=" + if (problems.isEmpty()) "0" else problems.joinToString("+"))
                }
                "THREAD_DRIFT" -> step(name) {
                    val afterThreads = Thread.getAllStackTraces().keys.map { it.name }.toSet()
                    val qaThreads = afterThreads.count { it.startsWith("qa54-") }
                    (afterThreads == beforeThreads && qaThreads == 0) to "before=${beforeThreads.size} after=${afterThreads.size}"
                }
                "BOUNDED_HEAP" -> step(name) { (heapMax <= MAX_HEAP_BYTES) to "heapMax=$heapMax" }
            }
        }
        val afterThreads = Thread.getAllStackTraces().keys.map { it.name }.toSet()
        out.add("MEM\theapMax=$heapMax\tlimit=$MAX_HEAP_BYTES")
        out.add("THREADS\tbefore=${beforeThreads.size}\tafter=${afterThreads.size}")
        val passCount = out.count { it.startsWith("CHECK\t") && it.contains("\tPASS\t") }
        val failCount = out.count { it.startsWith("CHECK\t") && it.contains("\tFAIL\t") }
        out.add("FIRST\t" + (first ?: "NONE"))
        out.add("SUMMARY\tchecks=$passCount\tfail=$failCount")
        out.add("VERDICT\t" + if (failures.isEmpty()) "PASS" else "FAIL")
        for (line in out) sink(line)
        return if (failures.isEmpty()) 0 else 1
    }
}
