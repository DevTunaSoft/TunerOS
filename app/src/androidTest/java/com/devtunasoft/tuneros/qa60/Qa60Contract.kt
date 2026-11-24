package com.devtunasoft.tuneros.qa60

import com.devtunasoft.tuneros.core.AbortableSlot
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * QA-60 framework-free contract for bounded AbortableSlot cancellation semantics.
 * The same source is compiled into the JVM host gate and the instrumented mirror.
 * It calls the real production class; no cancellation policy is reimplemented here.
 */
object Qa60Contract {
    const val SEED = 0x51415936L
    const val MAX_ROWS = 24
    const val FIELD_COUNT = 5
    const val FAMILY_COUNT = 8
    const val MAX_LINE_BYTES = 512
    const val HEADER = "id\tfamily\tseed\texpected\tnote"
    const val NOTE = "synthetic"
    const val REPORT = "QA-60 bounded AbortableSlot cancellation gate. Scratch fixture only."

    val ALL_FILES = listOf(".gitattributes", "rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt")
    val DATA_FILES = listOf("corrupt.txt", "dimensions.tsv", "report-copy.txt", "rows.tsv")
    val FAMILIES = listOf(
        "register_release",
        "release_stale",
        "abort_current",
        "abort_after_release",
        "repeated_abort",
        "reset_clears_abort",
        "reset_forgets_current",
        "double_register",
    )
    private val EXPECTED_CORRUPT = listOf(
        "header-bad" to "HEADER",
        "fields-short" to "FIELDS",
        "id-range" to "ID_RANGE",
        "family-unknown" to "FAMILY",
        "seed-nonnumeric" to "SEED",
        "hex-bad" to "HEX",
        "overlong" to "LENGTH",
    )

    private class Probe(val tag: String) {
        var aborts = 0
    }

    private class Row(val fields: List<String>) {
        val id: String get() = fields[0]
        val family: String get() = fields[1]
        val seed: String get() = fields[2]
        val expected: String get() = fields[3]
        fun render(): String = fields.joinToString("\t")
    }

    /** Literal outcomes from the real slot: register verdicts followed by abort counts. */
    private fun runCase(family: String, seed: Int): String {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val a = Probe("a$seed")
        val b = Probe("b$seed")
        val c = Probe("c$seed")
        val out = ArrayList<String>()
        fun register(value: Probe): String = if (slot.register(value)) "T" else "F"
        when (family) {
            "register_release" -> {
                out += register(a)
                slot.release(a)
                out += register(b)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "release_stale" -> {
                out += register(a)
                out += register(b)
                slot.release(a)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "abort_current" -> {
                out += register(a)
                slot.abortCurrent()
                out += register(b)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "abort_after_release" -> {
                out += register(a)
                slot.release(a)
                slot.abortCurrent()
                out += register(b)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "repeated_abort" -> {
                out += register(a)
                slot.abortCurrent()
                slot.abortCurrent()
                out += register(b)
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "reset_clears_abort" -> {
                out += register(a)
                slot.abortCurrent()
                out += register(b)
                slot.reset()
                out += register(c)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
                out += c.aborts.toString()
            }
            "reset_forgets_current" -> {
                out += register(a)
                slot.reset()
                slot.abortCurrent()
                out += register(b)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
            "double_register" -> {
                out += register(a)
                out += register(b)
                slot.abortCurrent()
                out += a.aborts.toString()
                out += b.aborts.toString()
            }
        }
        return out.joinToString("|")
    }

    private fun defaultRows(): List<Row> = (0 until MAX_ROWS).map { index ->
        val family = FAMILIES[index % FAMILIES.size]
        val seed = index / FAMILIES.size
        Row(
            listOf(
                "q60-%02d".format(index),
                family,
                seed.toString(),
                runCase(family, seed),
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
        val badId = sample.fields.toMutableList().also { it[0] = "q60-99" }.joinToString("\t")
        val badFamily = sample.fields.toMutableList().also { it[1] = "unknown" }.joinToString("\t")
        val badSeed = sample.fields.toMutableList().also { it[2] = "seed-x" }.joinToString("\t")
        val overlong = sample.fields.toMutableList().also { it[4] = "x".repeat(MAX_LINE_BYTES) }.joinToString("\t")
        return listOf(
            "header-bad" to HEADER.substringBeforeLast('\t'),
            "fields-short" to sample.fields.dropLast(1).joinToString("\t"),
            "id-range" to badId,
            "family-unknown" to badFamily,
            "seed-nonnumeric" to badSeed,
            "hex-bad" to "not-hex",
            "overlong" to overlong,
        )
    }

    private fun classifyCorrupt(text: String): String {
        val bytes = unhex(text) ?: return "HEX"
        val payload = String(bytes, Charsets.UTF_8)
        if (payload == HEADER.substringBeforeLast('\t')) return "HEADER"
        val fields = payload.split('\t')
        if (fields.size != FIELD_COUNT) return "FIELDS"
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return "LENGTH"
        val id = fields[0]
        if (!id.matches(Regex("q60-[0-9]{2}")) || id.drop(4).toInt() >= MAX_ROWS) return "ID_RANGE"
        if (!FAMILIES.contains(fields[1])) return "FAMILY"
        if (fields[2].toIntOrNull() == null) return "SEED"
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

    private fun literalLateRegistration(): Boolean {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        slot.abortCurrent()
        val late = Probe("late")
        val accepted = slot.register(late)
        return !accepted && late.aborts == 1
    }

    private fun literalResetClearsAbort(): Boolean {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val first = Probe("first")
        slot.register(first)
        slot.abortCurrent()
        val refused = Probe("refused")
        slot.register(refused)
        slot.reset()
        val next = Probe("next")
        val accepted = slot.register(next)
        val cleanBeforeAbort = next.aborts == 0
        slot.abortCurrent()
        return accepted && cleanBeforeAbort && first.aborts == 1 && refused.aborts == 1 && next.aborts == 1
    }

    private fun literalStaleRelease(): Boolean {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val first = Probe("first")
        val second = Probe("second")
        slot.register(first)
        slot.register(second)
        slot.release(first)
        slot.abortCurrent()
        return first.aborts == 0 && second.aborts == 1
    }

    private fun literalAbortIdempotent(): Boolean {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val first = Probe("first")
        slot.register(first)
        slot.abortCurrent()
        slot.abortCurrent()
        return first.aborts == 1
    }

    private fun literalResetForgetsCurrent(): Boolean {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val first = Probe("first")
        slot.register(first)
        slot.reset()
        slot.abortCurrent()
        return first.aborts == 0
    }

    /** Bounded lock-scope probe: a blocked abort callback must not hold the slot lock. */
    private fun raceCallbackOutsideLock(): Boolean {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val abortResult = AtomicReference<String>()
        val lateResult = AtomicReference<String>()
        val first = Probe("race-first")
        val late = Probe("race-late")
        val slot = AbortableSlot<Probe> { value ->
            value.aborts++
            if (value === first) {
                entered.countDown()
                try {
                    release.await(3, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        slot.register(first)
        val aborter = Thread {
            try {
                slot.abortCurrent()
                abortResult.set("done")
            } catch (failure: Throwable) {
                abortResult.set("error")
            }
        }
        aborter.start()
        val sawCallback = entered.await(3, TimeUnit.SECONDS)
        val lateThread = Thread {
            try {
                lateResult.set(if (slot.register(late)) "T" else "F")
            } catch (failure: Throwable) {
                lateResult.set("error")
            }
        }
        lateThread.start()
        lateThread.join(3000)
        val lateReturned = !lateThread.isAlive
        release.countDown()
        aborter.join(3000)
        if (lateThread.isAlive) {
            lateThread.interrupt()
            lateThread.join(3000)
        }
        if (aborter.isAlive) {
            aborter.interrupt()
            aborter.join(3000)
        }
        return sawCallback && lateReturned && lateResult.get() == "F" &&
            abortResult.get() == "done" && first.aborts == 1 && late.aborts == 1 &&
            !lateThread.isAlive && !aborter.isAlive
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
        val boundsOk = body.size <= MAX_ROWS && body.all { it.split('\t').size == FIELD_COUNT }
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
        val rows = body.map { Row(it.split('\t')) }
        val mirror = sha256((rows.joinToString("\n") { it.render() } + "\n").toByteArray(Charsets.UTF_8))
        if (active("PINNED_DIGEST")) check("PINNED_DIGEST", dims["MIRROR_DIGEST"] == mirror, "rows=${rows.size}")

        val capOk = rows.size == MAX_ROWS && dims["ROWS"] == MAX_ROWS.toString() &&
            dims["FIELD_COUNT"] == FIELD_COUNT.toString() &&
            dims["FAMILY_COUNT"] == FAMILIES.size.toString() &&
            rows.map { it.family }.distinct().size == FAMILIES.size &&
            FAMILIES.all { family -> rows.any { it.family == family } }
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

        if (active("LATE_REGISTRATION")) check("LATE_REGISTRATION", literalLateRegistration(), "slot=literal")
        if (active("RESET_CLEARS_ABORT")) check("RESET_CLEARS_ABORT", literalResetClearsAbort(), "slot=literal")
        if (active("RELEASE_STALE")) check("RELEASE_STALE", literalStaleRelease(), "slot=literal")
        if (active("ABORT_IDEMPOTENT")) check("ABORT_IDEMPOTENT", literalAbortIdempotent(), "slot=literal")
        if (active("RESET_FORGETS_CURRENT")) {
            check("RESET_FORGETS_CURRENT", literalResetForgetsCurrent(), "slot=literal")
        }
        if (active("RACE_CALLBACK_OUTSIDE_LOCK")) {
            check("RACE_CALLBACK_OUTSIDE_LOCK", raceCallbackOutsideLock(), "threads=2 bounded")
        }

        var verdictOk = true
        for (row in rows) {
            val actual = runCase(row.family, row.seed.toInt())
            if (actual != row.expected) {
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
            repeat(rows.size) { runCase(rows[it % rows.size].family, it % 3) }
            System.gc()
            Thread.sleep(10L)
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (cycle == 0) baseline = used else if (used > baseline + 8L * 1024 * 1024) stable = false
            if (used > peak) peak = used
        }
        say("MEM\theapMax=$heapMax\tpeakUsed=$peak\tbaseline=$baseline")
        if (active("MEM_STABLE")) {
            check("MEM_STABLE", heapMax <= 100663296L && peak < 16L * 1024 * 1024 && stable, "heapMax=$heapMax")
        }

        val before = Thread.getAllStackTraces().size
        repeat(rows.size) { runCase(rows[it].family, it % 3) }
        val after = Thread.getAllStackTraces().size
        val leaked = Thread.getAllStackTraces().keys.count { it.name.contains("qa60", ignoreCase = true) }
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
