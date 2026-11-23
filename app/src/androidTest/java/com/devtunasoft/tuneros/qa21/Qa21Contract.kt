package com.devtunasoft.tuneros.qa21

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/*
 * QA-21 shared scratch-state contract. No Android imports: the same source is compiled
 * into the offline JVM host gate and into the instrumented APK mirror.
 *
 * Slice: the OnDeviceSelfTest run-marker / atomic report-publication lifecycle
 * (app/src/main/.../OnDeviceSelfTest.kt lines 137-147 and 637-644). QA-10 owns event
 * line aggregation and the shareable summary; QA-16 owns import staging and rollback;
 * QA-18 owns doctor evidence envelopes. This gate owns the publication seam only.
 *
 * Every identifier is invented. The sensitive marker is derived from a fixed seed at
 * run time and is never written to source, fixture, report or heartbeat.
 */
object Qa21Contract {
    private const val VERSION = "qa21-fixture-v1"
    private const val SEED = 20261123L
    private const val RUN_COUNT = 64
    private const val MAX_LINES = 32
    private const val MAX_BYTES = 4096
    private const val ABS_LINE_CAP = 512
    private const val ABS_BYTE_CAP = 65536
    private const val THREAD_CAP = 8
    private const val CYCLES = 6
    private const val HEAP_CAP_MB = 96
    private const val HEAP_DRIFT = 8L * 1024 * 1024
    private const val MIRROR_DIGEST = "785c0d4daa352bce671f5736333ee4c500c67d1b898e693249313add188b0512"

    private val STAGES = listOf(
        "CLEAN", "PENDING_OK", "PENDING_CORRUPT", "RENAME_FAILED",
        "MARKER_DELETE_FAILED", "OVERSIZE",
    )
    private val DATA_FILES = listOf("contract.txt", "dimensions.tsv", "runs.tsv", "corrupt.hex")
    private val ALL_FILES = DATA_FILES + "sha256.txt"
    private val CORRUPT_REASONS = mapOf(
        "bad-header" to "HEADER_STRICT",
        "bad-meta" to "META_STRICT",
        "bad-state" to "BAD_STATE",
        "bad-encoding" to "BAD_ENCODING",
        "missing-end" to "MISSING_END",
        "oversize" to "OVERSIZE",
    )
    private val RUN_HEADER =
        "case\tmarker\tstage\tlines\tbytes\trename_ok\tmarker_delete_ok\tverdict\tprevious\tcleared"
    private val DIM_HEADER = "#qa21-dimensions-v1"
    private val CORRUPT_HEADER = "#qa21-corrupt-v1"
    private val PENDING_HEADER = "QA21PENDING1"
    private val CONTRACT_TEXT =
        "QA-21 synthetic scratch fixture for the self-test report publication gate.\n" +
            "Every marker is derived at run time; no feed address, access marker or private media.\n" +
            "Rows replay marker, pending-report and atomic-publication decisions on scratch names only.\n"

    data class Result(
        val verdict: String,
        val reason: String,
        val previous: String,
        val markerCleared: Int,
    )

    private fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(64) { for (b in digest) append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff)) }
    }

    private fun secret(): String {
        val raw = hash(("qa21-secret:" + SEED).toByteArray(StandardCharsets.US_ASCII)).take(12)
        return "marker-" + raw
    }

    private fun line(text: String) = text + "\n"

    private fun dimensionsText() = buildString {
        append(line(DIM_HEADER))
        append(line("seed=$SEED"))
        append(line("run_count=$RUN_COUNT"))
        append(line("max_lines=$MAX_LINES"))
        append(line("max_bytes=$MAX_BYTES"))
        append(line("thread_cap=$THREAD_CAP"))
        append(line("cycles=$CYCLES"))
        append(line("heap_cap_mb=$HEAP_CAP_MB"))
        append(line("leak_inject=0"))
    }

    private fun previousFor(index: Int) = if (index % 3 == 0) "INCOMPLETE" else "CLEAN"

    private fun reportLines(
        case: String, previous: String, verdict: String, count: Int, padTotal: Int = 0,
    ): List<String> {
        val out = ArrayList<String>(count)
        out += "QA21 REPORT $case"
        out += "PREVIOUS $previous"
        out += "STATE $verdict"
        out += "CHECK bounded-report lines=$count"
        val detailCount = count - 4
        for (index in 0 until detailCount) {
            val share = padTotal / detailCount + if (index < padTotal % detailCount) 1 else 0
            out += "DETAIL $index" + "x".repeat(share)
        }
        return out
    }

    private fun expected(index: Int): Result {
        val stage = STAGES[index % STAGES.size]
        return when (stage) {
            "PENDING_CORRUPT" -> Result("REJECTED", "PENDING_REJECTED", previousFor(index), 0)
            "OVERSIZE" -> Result("REJECTED", "PUBLISH_BOUNDS", previousFor(index), 0)
            "RENAME_FAILED" -> Result("REJECTED", "RENAME_FAILED", previousFor(index), 0)
            "MARKER_DELETE_FAILED" -> Result("COMPLETE", "PUBLISHED", previousFor(index), 0)
            else -> Result("COMPLETE", "PUBLISHED", previousFor(index), 1)
        }
    }

    private fun pendingFor(index: Int): Pair<Int, String> {
        val case = String.format(Locale.ROOT, "qa21-%03d", index)
        val previous = previousFor(index)
        val verdict = expected(index).verdict
        var lines = 4 + (index % 29)
        var padTotal = 0
        if (index == 42) {
            var found = false
            for (candidate in 5..MAX_LINES) {
                val base = renderPending(case, previous, verdict, candidate)
                    .toByteArray(StandardCharsets.US_ASCII).size
                if (base <= MAX_BYTES) {
                    lines = candidate
                    var lo = 0
                    var hi = MAX_BYTES
                    while (lo < hi) {
                        val mid = lo + (hi - lo) / 2
                        val size = renderPending(case, previous, verdict, candidate, mid)
                            .toByteArray(StandardCharsets.US_ASCII).size
                        if (size < MAX_BYTES) lo = mid + 1 else hi = mid
                    }
                    padTotal = lo
                    found = true
                    break
                }
            }
            if (!found || renderPending(case, previous, verdict, lines, padTotal)
                .toByteArray(StandardCharsets.US_ASCII).size != MAX_BYTES
            ) throw IllegalStateException("no exact cap row")
        }
        return lines to renderPending(case, previous, verdict, lines, padTotal)
    }

    private fun runRow(index: Int): String {
        val case = String.format(Locale.ROOT, "qa21-%03d", index)
        val stage = STAGES[index % STAGES.size]
        val previous = previousFor(index)
        val result = expected(index)
        val (lines, text) = pendingFor(index)
        return listOf(
            case, if (index % 3 == 0) "1" else "0", stage, lines.toString(),
            text.toByteArray(StandardCharsets.US_ASCII).size.toString(),
            if (stage == "RENAME_FAILED") "0" else "1",
            if (stage == "MARKER_DELETE_FAILED") "0" else "1",
            result.verdict, result.previous, result.markerCleared.toString(),
        ).joinToString("\t")
    }

    private fun renderPending(
        case: String, previous: String, verdict: String, lines: Int, pad: Int = 0,
    ): String {
        val body = reportLines(case, previous, verdict, lines, pad).joinToString("\n", postfix = "\n")
        return PENDING_HEADER + "\n" +
            "CASE $case\n" +
            "LINES $lines\n" +
            "BYTES " + body.toByteArray(StandardCharsets.US_ASCII).size + "\n" +
            body + "END\n"
    }

    private fun canonicalPending(): String = renderPending("qa21-canonical", "CLEAN", "COMPLETE", 6)

    private fun corruptVariants(): List<Pair<String, ByteArray>> {
        val canonical = canonicalPending()
        fun bytes(text: String) = text.toByteArray(StandardCharsets.US_ASCII)
        val badHeader = canonical.replaceFirst(PENDING_HEADER, "QA21PENDING2")
        val badMeta = canonical.replaceFirst("LINES 6", "LINES xx")
        val badState = canonical.replaceFirst("STATE COMPLETE", "STATE XXXXXXXX")
        val badEncoding = byteArrayOf(0xff.toByte()) + bytes(canonical)
        val missingEnd = canonical.replace("END\n", "")
        val filler = buildString {
            for (i in 0 until 500) append("FILLER ").append(i).append('\n')
        }
        return listOf(
            "bad-header" to bytes(badHeader),
            "bad-meta" to bytes(badMeta),
            "bad-state" to bytes(badState),
            "bad-encoding" to badEncoding,
            "missing-end" to bytes(missingEnd),
            "oversize" to bytes(canonical.replace("END\n", filler + "END\n")),
        )
    }

    private fun corruptText() = buildString {
        append(line(CORRUPT_HEADER))
        for ((name, payload) in corruptVariants()) {
            append(name).append('\t')
            for (b in payload) append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
            append('\n')
        }
    }

    private fun manifestText(files: Map<String, ByteArray>) = buildString {
        for (name in DATA_FILES) append(hash(files.getValue(name))).append("  ").append(name).append('\n')
    }

    private fun buildFiles(): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["contract.txt"] = CONTRACT_TEXT.toByteArray(StandardCharsets.US_ASCII)
        files["dimensions.tsv"] = dimensionsText().toByteArray(StandardCharsets.US_ASCII)
        val runs = buildString {
            append(line(RUN_HEADER))
            for (i in 0 until RUN_COUNT) append(line(runRow(i)))
        }
        files["runs.tsv"] = runs.toByteArray(StandardCharsets.US_ASCII)
        files["corrupt.hex"] = corruptText().toByteArray(StandardCharsets.US_ASCII)
        files["sha256.txt"] = manifestText(files).toByteArray(StandardCharsets.US_ASCII)
        return files
    }

    private fun treeDigest(files: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        for (name in files.keys.toSortedSet()) sb.append(name).append('\n').append(hash(files.getValue(name))).append('\n')
        return hash(sb.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun mirrorDigest(files: Map<String, ByteArray>) = hash(files.getValue("runs.tsv"))

    private fun strictLines(bytes: ByteArray, header: String): List<String> {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw GateFail("BAD_ENCODING")
        }
        if (!text.endsWith("\n")) throw GateFail("TRUNCATED_TEXT")
        val lines = text.dropLast(1).split('\n')
        if (lines.isEmpty() || lines[0] != header) throw GateFail("HEADER_STRICT")
        return lines.drop(1)
    }

    private class GateFail(val code: String) : Exception(code)

    private data class Run(
        val case: String, val marker: Int, val stage: String, val lines: Int, val bytes: Int,
        val renameOk: Int, val markerDeleteOk: Int, val verdict: String,
        val previous: String, val cleared: Int,
    )

    private fun parseRuns(bytes: ByteArray): List<Run> {
        val rows = strictLines(bytes, RUN_HEADER)
        val out = ArrayList<Run>(rows.size)
        for (row in rows) {
            val fields = row.split('\t')
            if (fields.size != 10) throw GateFail("FIELD_COUNT")
            val nums = intArrayOf(1, 3, 4, 5, 6, 9)
            for (i in nums) {
                val value = fields[i].toIntOrNull() ?: throw GateFail("INT_BOUNDS")
                if (value < 0) throw GateFail("INT_BOUNDS")
            }
            val lines = fields[3].toInt()
            val bytesCount = fields[4].toInt()
            if (lines > ABS_LINE_CAP || bytesCount > ABS_BYTE_CAP) throw GateFail("INT_BOUNDS")
            if (fields[1] !in setOf("0", "1") || fields[5] !in setOf("0", "1") ||
                fields[6] !in setOf("0", "1") || fields[9] !in setOf("0", "1")
            ) throw GateFail("ENUM_STRICT")
            if (fields[2] !in STAGES || fields[7] !in setOf("COMPLETE", "REJECTED") ||
                fields[8] !in setOf("CLEAN", "INCOMPLETE")
            ) throw GateFail("ENUM_STRICT")
            out += Run(
                fields[0], fields[1].toInt(), fields[2], lines, bytesCount,
                fields[5].toInt(), fields[6].toInt(), fields[7], fields[8], fields[9].toInt(),
            )
        }
        return out
    }

    private fun parseDimensions(bytes: ByteArray): Map<String, String> {
        val rows = strictLines(bytes, DIM_HEADER)
        val out = LinkedHashMap<String, String>()
        for (row in rows) {
            val at = row.indexOf('=')
            if (at <= 0) throw GateFail("FIELD_COUNT")
            out[row.substring(0, at)] = row.substring(at + 1)
        }
        return out
    }

    private fun parseCorrupt(bytes: ByteArray): Map<String, ByteArray> {
        val rows = strictLines(bytes, CORRUPT_HEADER)
        val out = LinkedHashMap<String, ByteArray>()
        for (row in rows) {
            val fields = row.split('\t')
            if (fields.size != 2 || fields[0] !in CORRUPT_REASONS) throw GateFail("FIELD_COUNT")
            if (fields[1].isEmpty() || fields[1].length % 2 != 0) throw GateFail("CORRUPT_HEX")
            val payload = ByteArray(fields[1].length / 2)
            for (i in payload.indices) {
                val value = fields[1].substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: throw GateFail("CORRUPT_HEX")
                payload[i] = value.toByte()
            }
            out[fields[0]] = payload
        }
        return out
    }

    /** Strict pending-report decoder. Every rejection carries a pinned reason. */
    private fun parsePending(payload: ByteArray, maxLines: Int, maxBytes: Int): String {
        if (payload.size > maxBytes) return "OVERSIZE"
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (error: CharacterCodingException) {
            return "BAD_ENCODING"
        }
        if (!text.endsWith("END\n")) return "MISSING_END"
        val lines = text.dropLast(1).split('\n')
        if (lines.isEmpty() || lines[0] != PENDING_HEADER) return "HEADER_STRICT"
        if (lines.size < 6 || lines[1].startsWith("CASE ").not() ||
            lines[2].startsWith("LINES ").not() || lines[3].startsWith("BYTES ").not()
        ) return "META_STRICT"
        val declaredLines = lines[2].removePrefix("LINES ").toIntOrNull() ?: return "META_STRICT"
        val declaredBytes = lines[3].removePrefix("BYTES ").toIntOrNull() ?: return "META_STRICT"
        if (declaredLines <= 0 || declaredLines > maxLines || declaredLines > ABS_LINE_CAP) return "OVERSIZE"
        if (declaredBytes <= 0 || declaredBytes > maxBytes) return "OVERSIZE"
        val body = lines.drop(4).dropLast(1)
        if (body.size != declaredLines) return "LINES_MISMATCH"
        val bodyBytes = body.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.US_ASCII).size
        if (bodyBytes != declaredBytes) return "BYTES_MISMATCH"
        if (body.none { it.startsWith("STATE ") }) return "BAD_STATE"
        val state = body.first { it.startsWith("STATE ") }.removePrefix("STATE ")
        if (state !in setOf("COMPLETE", "REJECTED")) return "BAD_STATE"
        if (body.any { it.length > maxBytes }) return "OVERSIZE"
        return "OK"
    }

    /** Deliberately lax control parser: ignores header, metadata and terminator. */
    private fun laxAccepts(payload: ByteArray): Boolean {
        val text = String(payload, StandardCharsets.ISO_8859_1)
        return text.contains("CASE ") && text.lines().any { it.startsWith("DETAIL ") }
    }

    private fun simulate(row: Run, maxLines: Int, maxBytes: Int): Result {
        val index = row.case.removePrefix("qa21-").toIntOrNull() ?: -1
        val pending = if (index in 0 until RUN_COUNT) {
            pendingFor(index).second.toByteArray(StandardCharsets.US_ASCII)
        } else {
            renderPending(row.case, row.previous, row.verdict, row.lines)
                .toByteArray(StandardCharsets.US_ASCII)
        }
        if (row.stage == "OVERSIZE" || row.lines > maxLines || pending.size > maxBytes) {
            return Result("REJECTED", "PUBLISH_BOUNDS", row.previous, 0)
        }
        val parsed = parsePending(pending, maxLines, maxBytes)
        if (parsed != "OK") return Result("REJECTED", "PENDING_REJECTED", row.previous, 0)
        if (row.stage == "PENDING_CORRUPT") return Result("REJECTED", "PENDING_REJECTED", row.previous, 0)
        if (row.renameOk == 0) return Result("REJECTED", "RENAME_FAILED", row.previous, 0)
        return Result("COMPLETE", "PUBLISHED", row.previous, row.markerDeleteOk)
    }

    private fun privacyClean(text: String): Boolean {
        if (text.contains(secret())) return false
        val lower = text.lowercase(Locale.ROOT)
        for (forbidden in listOf("://", "password", "pin=", "credential", "drm-key", "recording-payload")) {
            if (lower.contains(forbidden)) return false
        }
        return true
    }

    class CheckReport {
        val lines = ArrayList<String>()
        var first: String = "NONE"
            private set
        var passCount = 0
            private set
        var failCount = 0
            private set

        fun check(id: String, condition: Boolean) {
            if (condition) {
                passCount++
                lines += "CHECK\tPASS\t$id"
            } else {
                failCount++
                if (first == "NONE") first = id
                lines += "CHECK\tFAIL\t$id"
            }
        }
    }

    fun runChecks(files: Map<String, ByteArray>, mutant: Boolean, checkManifest: Boolean): CheckReport {
        val report = CheckReport()
        val generated = buildFiles()
        if (!mutant) {
            report.check("BYTE_IDENTITY", DATA_FILES.all { files[it] contentEquals generated.getValue(it) })
            report.check("PINNED_MIRROR", hash(files.getValue("runs.tsv")) == MIRROR_DIGEST)
        }
        val manifest = String(files.getValue("sha256.txt"), StandardCharsets.US_ASCII)
        val manifestOk = DATA_FILES.all { name ->
            manifest.contains(hash(files.getValue(name)) + "  " + name + "\n")
        }
        if (checkManifest) {
            report.check("MANIFEST_MISMATCH", manifestOk)
        }

        val dims = parseDimensions(files.getValue("dimensions.tsv"))
        val runs = parseRuns(files.getValue("runs.tsv"))
        val corrupt = parseCorrupt(files.getValue("corrupt.hex"))

        var corruptOk = corrupt.size == CORRUPT_REASONS.size
        for ((name, payload) in corrupt) {
            val reason = parsePending(payload, MAX_LINES, MAX_BYTES)
            if (reason != CORRUPT_REASONS[name]) corruptOk = false
        }
        val laxVariant = corrupt["bad-header"] ?: ByteArray(0)
        if (!laxAccepts(laxVariant)) corruptOk = false
        report.check("CORRUPT_REJECT", corruptOk)

        var outcomes = runs.size == RUN_COUNT
        val diagnostics = StringBuilder()
        runs.forEachIndexed { index, row ->
            val actual = simulate(row, MAX_LINES, MAX_BYTES)
            val wanted = expected(index)
            if (actual != wanted || row.verdict != actual.verdict ||
                row.previous != actual.previous || row.cleared != actual.markerCleared
            ) outcomes = false
            diagnostics.append("QA21 ").append(row.case).append('\t')
                .append(actual.verdict).append('\t').append(actual.reason).append('\n')
        }
        report.check("OUTCOME_MISMATCH", outcomes)

        val leakInjected = dims["leak_inject"] == "1"
        var diagnosticText = diagnostics.toString() + CONTRACT_TEXT
        if (mutant && leakInjected) diagnosticText += "DETAIL " + secret() + "\n"
        report.check("PRIVACY_LEAK", privacyClean(diagnosticText))
        val capLines = dims["max_lines"]?.toIntOrNull() ?: -1
        val capBytes = dims["max_bytes"]?.toIntOrNull() ?: -1
        report.check("CAP_BINDING", runs.any { it.lines == capLines } && runs.any { row ->
            val index = row.case.removePrefix("qa21-").toIntOrNull() ?: -1
            if (index in 0 until RUN_COUNT) {
                pendingFor(index).second.toByteArray(StandardCharsets.US_ASCII).size == capBytes
            } else {
                false
            }
        })

        var bounds = true
        var firstUsed = 0L
        val runtime = Runtime.getRuntime()
        for (cycle in 0 until CYCLES) {
            val done = java.util.concurrent.CountDownLatch(1)
            var retained = 0
            val worker = Thread({
                try {
                    retained = parseRuns(files.getValue("runs.tsv")).size
                } catch (error: GateFail) {
                    retained = -1
                } finally {
                    done.countDown()
                }
            }, "qa21-cycle-" + cycle)
            worker.start()
            val live = Thread.getAllStackTraces().keys.count { it.name.startsWith("qa21-cycle-") }
            if (live > THREAD_CAP) bounds = false
            if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) bounds = false
            worker.join(2000)
            if (worker.isAlive || retained != RUN_COUNT) bounds = false
            if (Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("qa21-cycle-") }) bounds = false
            System.gc()
            try { Thread.sleep(20) } catch (ignored: InterruptedException) { Thread.currentThread().interrupt() }
            val used = runtime.totalMemory() - runtime.freeMemory()
            if (cycle == 0) firstUsed = used else if (used - firstUsed > HEAP_DRIFT) bounds = false
        }
        report.check("RESOURCE_BOUNDS", bounds)

        val dimensionsOk = dims["seed"] == SEED.toString() &&
            dims["run_count"] == RUN_COUNT.toString() &&
            dims["max_lines"] == MAX_LINES.toString() &&
            dims["max_bytes"] == MAX_BYTES.toString() &&
            dims["thread_cap"] == THREAD_CAP.toString() &&
            dims["cycles"] == CYCLES.toString() &&
            dims["heap_cap_mb"] == HEAP_CAP_MB.toString() &&
            dims["leak_inject"] == "0"
        report.check("DIMENSIONS", dimensionsOk)

        val countsOk = runs.count { it.verdict == "COMPLETE" } == 32 &&
            runs.count { it.verdict == "REJECTED" } == 32 &&
            runs.count { it.marker == 1 } == 22 &&
            runs.count { it.cleared == 0 } == 42 &&
            runs.maxOf { it.lines } == MAX_LINES
        report.check("SUMMARY_COUNTS", countsOk)
        return report
    }

    fun deviceChecks(): List<String> {
        val files = buildFiles()
        val report = runChecks(files, mutant = false, checkManifest = true)
        return report.lines + listOf(
            "SUMMARY\tpass=" + report.passCount + "\tfail=" + report.failCount +
                "\tfirst=" + report.first + "\trows=$RUN_COUNT\tcorrupt=${CORRUPT_REASONS.size}\tmirror=$MIRROR_DIGEST",
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("FAIL\tARGS\tusage: generate|verify|verify-mutant|verify-mutant-raw|digest|device [dir]")
            exitProcess(2)
        }
        val mode = args[0]
        if (mode == "device") {
            deviceChecks().forEach(::println)
            return
        }
        if (mode == "digest") {
            val fresh = buildFiles()
            println("DIGEST\tPASS\tmirror=${mirrorDigest(fresh)}\ttree=${treeDigest(fresh)}")
            return
        }
        val dir = args.getOrNull(1)
        if (dir == null) {
            println("FAIL\tARGS\tfixture directory required")
            exitProcess(2)
        }
        val target = File(dir)
        when (mode) {
            "generate" -> {
                if (!target.isDirectory && !target.mkdirs()) {
                    println("FAIL\tGENERATE_DIR")
                    exitProcess(2)
                }
                val files = buildFiles()
                for ((name, bytes) in files) File(target, name).writeBytes(bytes)
                val total = files.values.sumOf { it.size }
                println("GENERATE\tPASS\tfiles=${files.size}\tbytes=$total\ttree=${treeDigest(files)}\tmirror=${mirrorDigest(files)}")
            }
            "verify", "verify-mutant", "verify-mutant-raw" -> {
                val loaded = LinkedHashMap<String, ByteArray>()
                for (name in ALL_FILES) {
                    val file = File(target, name)
                    if (!file.isFile) {
                        println("FAIL\tMISSING_FIXTURE\t$name")
                        exitProcess(2)
                    }
                    loaded[name] = file.readBytes()
                }
                val checkManifest = mode != "verify-mutant"
                val report = try {
                    runChecks(loaded, mutant = mode != "verify", checkManifest = checkManifest)
                } catch (error: GateFail) {
                    println("CHECK\tFAIL\t${error.code}")
                    println("FIRST\t${error.code}")
                    println("SUMMARY\tpass=0\tfail=1\tfirst=${error.code}\tchecks=1\trows=$RUN_COUNT\tcorrupt=${CORRUPT_REASONS.size}\tcycles=$CYCLES\tthreadCap=$THREAD_CAP\theapCapMiB=$HEAP_CAP_MB")
                    exitProcess(1)
                }
                report.lines.forEach(::println)
                println("FIRST\t${report.first}")
                println("SUMMARY\tpass=${report.passCount}\tfail=${report.failCount}\tfirst=${report.first}" +
                    "\tchecks=${report.passCount + report.failCount}\trows=$RUN_COUNT\tcorrupt=${CORRUPT_REASONS.size}\tcycles=$CYCLES\tthreadCap=$THREAD_CAP\theapCapMiB=$HEAP_CAP_MB")
                if (report.failCount != 0) exitProcess(1)
            }
            else -> {
                println("FAIL\tARGS\tunknown mode")
                exitProcess(2)
            }
        }
    }
}
