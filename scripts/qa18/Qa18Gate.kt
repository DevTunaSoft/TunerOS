package com.devtunasoft.tuneros.qa18

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/**
 * QA-18 deterministic host gate for fail-closed ingestion of corrupt diagnostic evidence.
 * The real pure-Kotlin StreamDoctor and GuideDoctor sources are compiled with this gate; no
 * production file is edited. Fixtures and scratch output only. No network and no device access.
 */
object Qa18Gate {
    private const val MANIFEST_CODE = "MANIFEST_MATCH"
    private const val HEAP_DRIFT_LIMIT = 16L * 1024 * 1024
    private const val THREAD_DRIFT_LIMIT = 2
    private const val MIRROR_CHECK_COUNT = 8

    private class Reporter {
        private val lines = ArrayList<String>(Qa18Assertions.MAX_DIAGNOSTIC_LINES + 4)
        private var passed = 0
        private var total = 0

        fun check(id: String, ok: Boolean, detail: String = "") {
            total++
            if (!ok) throw Qa18Failure(id, detail)
            passed++
            lines += if (detail.isEmpty()) "CHECK\tPASS\t$id" else "CHECK\tPASS\t$id\t$detail"
        }

        fun emitFailure(failure: Qa18Failure) {
            for (line in lines) println(line)
            println("FIRST\t${failure.code}\t${failure.detail}")
            println("SUMMARY\tFAIL\t$total\t${failure.code}")
        }

        fun finish() {
            for (line in lines) println(line)
            println("SUMMARY\t$passed/$total\tPASS")
        }

        fun allLines(): List<String> = lines
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readFiles(directory: File): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa18Assertions.DATA_FILES) {
            val file = File(directory, name)
            if (!file.isFile) throw Qa18Failure("FIXTURE_MISSING", name)
            files[name] = file.readBytes()
        }
        return files
    }

    private fun checkManifest(files: Map<String, ByteArray>, manifest: ByteArray) {
        val text = String(manifest, Charsets.UTF_8)
        val rows = text.lineSequence().filter { it.isNotEmpty() }.toList()
        if (rows.size != Qa18Assertions.DATA_FILES.size) throw Qa18Failure(MANIFEST_CODE, "rows")
        for (index in rows.indices) {
            val fields = rows[index].split("  ")
            if (fields.size != 2 || fields[0].length != 64 || fields[0].any { it !in '0'..'9' && it !in 'a'..'f' } ||
                fields[1] != Qa18Assertions.DATA_FILES[index]
            ) throw Qa18Failure(MANIFEST_CODE, "row")
            if (sha256(files.getValue(fields[1])) != fields[0]) throw Qa18Failure(MANIFEST_CODE, fields[1])
        }
    }

    private fun parseDimensions(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            if (line.isEmpty()) continue
            val fields = line.split('=', limit = 2)
            if (fields.size != 2 || fields[0].isEmpty() || result.containsKey(fields[0])) {
                throw Qa18Failure("DIMENSIONS", "row")
            }
            result[fields[0]] = fields[1]
        }
        val expected = mapOf(
            "version" to Qa18Assertions.VERSION,
            "seed" to Qa18Assertions.SEED,
            "recordsStream" to Qa18Assertions.RECORDS_STREAM.toString(),
            "recordsGuide" to Qa18Assertions.RECORDS_GUIDE.toString(),
            "recordsWarning" to Qa18Assertions.RECORDS_WARNING.toString(),
            "recordsTotal" to Qa18Assertions.RECORDS_TOTAL.toString(),
            "envelopeBytes" to Qa18Assertions.envelopeBytes().size.toString(),
            "envelopeSha256" to sha256(Qa18Assertions.envelopeBytes()),
            "corruptCases" to Qa18Assertions.CORRUPT_CASES.toString(),
            "markerDigest" to Qa18Assertions.marker().digest,
            "laxAcceptCase" to Qa18Assertions.LAX_ACCEPT_CASE,
            "cycles" to Qa18Assertions.CYCLES.toString(),
            "maxRecordBytes" to Qa18Assertions.MAX_RECORD_BYTES.toString(),
            "maxEnvelopeBytes" to Qa18Assertions.MAX_ENVELOPE_BYTES.toString(),
            "maxHeapDriftBytes" to Qa18Assertions.MAX_HEAP_DRIFT_BYTES.toString(),
            "maxThreadDrift" to Qa18Assertions.MAX_THREAD_DRIFT.toString(),
            "maxStreamReportChars" to Qa18Assertions.MAX_STREAM_REPORT_CHARS.toString(),
            "maxGuideReportChars" to Qa18Assertions.MAX_GUIDE_REPORT_CHARS.toString(),
            "maxDiagnosticLines" to Qa18Assertions.MAX_DIAGNOSTIC_LINES.toString(),
        )
        if (result != expected) throw Qa18Failure("DIMENSIONS", "pin")
        return result
    }

    private fun parseCorruptTable(bytes: ByteArray): List<Qa18Assertions.CorruptCase> {
        val cases = ArrayList<Qa18Assertions.CorruptCase>(Qa18Assertions.CORRUPT_CASES)
        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            if (fields.size != 3 || !Regex("[a-z0-9-]+").matches(fields[0]) || fields[1].length > 16 ||
                fields[2].length > 8_192 || fields[2].length % 2 != 0
            ) throw Qa18Failure("CORRUPT_TABLE", "row")
            cases += Qa18Assertions.CorruptCase(fields[0], fields[1], Qa18Assertions.unhex(fields[2]))
        }
        if (cases.size != Qa18Assertions.CORRUPT_CASES) throw Qa18Failure("CORRUPT_COUNT", cases.size.toString())
        return cases
    }

    private fun leakDetector(text: String): Boolean = Qa18Assertions.clean(text)

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Repeated bounded parse/rehydrate cycles on a named worker; all threads are joined. */
    private fun boundedCycles(envelope: ByteArray) {
        val heaps = LongArray(Qa18Assertions.CYCLES)
        val threads = IntArray(Qa18Assertions.CYCLES)
        repeat(Qa18Assertions.CYCLES) { cycle ->
            var workerError: Qa18Failure? = null
            val worker = Thread({
                try {
                    val parsed = Qa18Assertions.parseEnvelope(envelope)
                    Qa18Assertions.reports(parsed)
                } catch (failure: Qa18Failure) {
                    workerError = failure
                }
            }, "qa18-cycle-$cycle")
            worker.start()
            threads[cycle] = Thread.activeCount()
            worker.join(30_000L)
            if (worker.isAlive) throw Qa18Failure("THREAD_JOIN", cycle.toString())
            if (workerError != null) throw Qa18Failure("CYCLE_PARSE", workerError!!.code)
            System.gc()
            Thread.sleep(20L)
            heaps[cycle] = usedHeap()
        }
        val live = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("qa18-") }
        if (live.isNotEmpty()) throw Qa18Failure("THREAD_LEAK", live.size.toString())
        val heapDrift = (heaps.maxOrNull() ?: 0L) - (heaps.minOrNull() ?: 0L)
        if (heapDrift > HEAP_DRIFT_LIMIT) throw Qa18Failure("HEAP_DRIFT", heapDrift.toString())
        val threadDrift = (threads.maxOrNull() ?: 0) - (threads.minOrNull() ?: 0)
        if (threadDrift > THREAD_DRIFT_LIMIT) throw Qa18Failure("THREAD_DRIFT", threadDrift.toString())
    }

    private fun verifyCommon(directory: File, reporter: Reporter, mutantMode: Boolean) {
        val files = readFiles(directory)
        val manifest = File(directory, "sha256.txt").let { file ->
            if (!file.isFile) throw Qa18Failure("FIXTURE_MISSING", "sha256.txt") else file.readBytes()
        }
        checkManifest(files, manifest)
        reporter.check("manifest-consistency", true, "files=${files.size}")

        if (!mutantMode) {
            val generated = Qa18Assertions.buildFiles()
            for (name in Qa18Assertions.DATA_FILES) {
                if (!files.getValue(name).contentEquals(generated.getValue(name))) {
                    throw Qa18Failure("BYTE_IDENTITY", name)
                }
            }
            if (!manifest.contentEquals(Qa18Assertions.manifestBytes(generated))) {
                throw Qa18Failure("BYTE_IDENTITY", "sha256.txt")
            }
            reporter.check("byte-identical-regeneration", true, "files=${files.size + 1}")
        }

        val dims = parseDimensions(files.getValue("dimensions.tsv"))
        reporter.check("dimensions-pinned", true, "records=${dims.getValue("recordsTotal")}")

        val corrupt = parseCorruptTable(files.getValue("corrupt.hex"))
        reporter.check("corrupt-table", true, "cases=${corrupt.size}")

        // Strict rejection with the exact pinned code comes before any lax or report check.
        for (variant in corrupt) {
            val failure = try {
                Qa18Assertions.parseEnvelope(variant.payload)
                null
            } catch (thrown: Qa18Failure) {
                thrown
            }
            if (failure == null || failure.code != variant.code) throw Qa18Failure("CORRUPT_REJECT", variant.name)
        }
        reporter.check("corrupt-fail-closed", true, "cases=${corrupt.size}")

        // Negative controls are executed before positive report checks so a weakened detector is named.
        if (leakDetector(Qa18Assertions.marker().token)) throw Qa18Failure("NEG_LEAK_DETECTOR", "detector")
        reporter.check("neg-leak-detector", true)
        val laxCase = corrupt.firstOrNull { it.name == Qa18Assertions.LAX_ACCEPT_CASE }
            ?: throw Qa18Failure("CORRUPT_TABLE", "lax-case")
        val laxAccepted = try {
            Qa18Assertions.parseEnvelope(laxCase.payload, ignoreDigest = true)
            true
        } catch (thrown: Qa18Failure) {
            false
        }
        if (!laxAccepted) throw Qa18Failure("NEG_CORRUPT_ACCEPT", laxCase.name)
        reporter.check("neg-corrupt-accept", true)

        val envelope = files.getValue("envelopes.bin")
        if (envelope.size > Qa18Assertions.MAX_ENVELOPE_BYTES) throw Qa18Failure("ENVELOPE_CAP", envelope.size.toString())
        val parsed = Qa18Assertions.parseEnvelope(envelope)
        reporter.check("strict-envelope-parse", true, "bytes=${parsed.bytes}")
        if (parsed.records.size != dims.getValue("recordsTotal").toInt()) throw Qa18Failure("RECORD_COUNT", "count")
        val streams = parsed.records.count { it.kind == Qa18Assertions.KIND_STREAM }
        val guides = parsed.records.count { it.kind == Qa18Assertions.KIND_GUIDE }
        val warnings = parsed.records.count { it.kind == Qa18Assertions.KIND_WARNING }
        if (streams != Qa18Assertions.RECORDS_STREAM || guides != Qa18Assertions.RECORDS_GUIDE ||
            warnings != Qa18Assertions.RECORDS_WARNING
        ) throw Qa18Failure("RECORD_COUNTS", "kinds")
        reporter.check("record-kind-counts", true, "stream=$streams guide=$guides warning=$warnings")

        val (streamReports, guideReport) = Qa18Assertions.reports(parsed)
        if (streamReports.size != streams || streamReports.any {
                !leakDetector(it) || it.length > Qa18Assertions.MAX_STREAM_REPORT_CHARS
            }
        ) throw Qa18Failure("STREAM_REPORT_PRIVACY", "reports")
        reporter.check("stream-report-privacy", true, "reports=${streamReports.size}")
        if (!leakDetector(guideReport) || guideReport.length > Qa18Assertions.MAX_GUIDE_REPORT_CHARS) {
            throw Qa18Failure("GUIDE_REPORT_PRIVACY", "report")
        }
        reporter.check("guide-report-privacy", true, "chars=${guideReport.length}")

        boundedCycles(envelope)
        reporter.check("bounded-heap-and-threads", true, "cycles=${Qa18Assertions.CYCLES}")

        val mirror = Qa18Assertions.run()
        if (mirror.size != MIRROR_CHECK_COUNT || mirror.any { !it.startsWith("PASS\t") } ||
            mirror.distinct().size != mirror.size
        ) throw Qa18Failure("MIRROR_RUN", mirror.size.toString())
        reporter.check("mirror-assertions", true, "checks=${mirror.size}")
        val pinned = Qa18Assertions.EXPECTED_ENVELOPE_SHA256
        if (pinned != dims.getValue("envelopeSha256") || pinned != sha256(Qa18Assertions.envelopeBytes())) {
            throw Qa18Failure("MIRROR_DIGEST", "pin")
        }
        reporter.check("mirror-digest-pin", true, "sha256=$pinned")

        if (reporter.allLines().any { !leakDetector(it) }) throw Qa18Failure("PRIVACY_DIAGNOSTICS", "lines")
        reporter.check("privacy-diagnostics", true)
        if (reporter.allLines().size + 2 > Qa18Assertions.MAX_DIAGNOSTIC_LINES) throw Qa18Failure("REPORT_LINES", "bound")
    }

    private fun generate(directory: File) {
        directory.mkdirs()
        val files = Qa18Assertions.buildFiles()
        for ((name, bytes) in files) File(directory, name).writeBytes(bytes)
        File(directory, "sha256.txt").writeBytes(Qa18Assertions.manifestBytes(files))
        println("GENERATE\tPASS\tfiles=${files.size + 1}")
        println("PIN\tenvelopeSha256=${sha256(files.getValue("envelopes.bin"))}")
    }

    /** Scratch-only fixture mutation. The committed fixture and gate sources are never edited. */
    private fun mutate(source: File, destination: File, kind: String) {
        if (!destination.exists() && !destination.mkdirs()) throw Qa18Failure("SCRATCH", "mkdir")
        for (name in Qa18Assertions.DATA_FILES) {
            File(source, name).copyTo(File(destination, name), overwrite = true)
        }
        File(source, "sha256.txt").copyTo(File(destination, "sha256.txt"), overwrite = true)
        val envelopePath = File(destination, "envelopes.bin")
        val bytes = envelopePath.readBytes()
        var rewriteManifest = false
        when (kind) {
            "byte-flip" -> bytes[20] = (bytes[20].toInt() xor 1).toByte()
            "bad-header" -> { bytes[0] = (bytes[0].toInt() xor 1).toByte(); rewriteManifest = true }
            "truncated" -> {
                val shortened = bytes.copyOf(bytes.size - 5)
                envelopePath.writeBytes(shortened)
                rewriteManifest = true
            }
            "record-count" -> { putInt(bytes, 5, Qa18Assertions.RECORDS_TOTAL + 1); rewriteManifest = true }
            "bad-digest" -> {
                val typeLength = ((bytes[9 + 14].toInt() and 0xff) shl 8) or (bytes[9 + 15].toInt() and 0xff)
                val markerLength = ((bytes[9 + 16 + typeLength].toInt() and 0xff) shl 8) or
                    (bytes[9 + 17 + typeLength].toInt() and 0xff)
                val recordLength = 16 + typeLength + 2 + markerLength + 32
                val last = 9 + recordLength - 1
                bytes[last] = (bytes[last].toInt() xor 1).toByte()
                rewriteManifest = true
            }
            else -> throw Qa18Failure("MUTANT_KIND", kind)
        }
        if (kind != "truncated") envelopePath.writeBytes(bytes)
        if (rewriteManifest) {
            val files = readFiles(destination)
            File(destination, "sha256.txt").writeBytes(Qa18Assertions.manifestBytes(files))
        }
        println("MUTATE\tPASS\t$kind")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val reporter = Reporter()
        try {
            if (args.isEmpty()) throw Qa18Failure("USAGE", "mode")
            when (args[0]) {
                "generate" -> generate(File(args[1]))
                "mutate" -> mutate(File(args[1]), File(args[2]), args[3])
                "verify", "verify-mutant" -> {
                    verifyCommon(File(args[1]), reporter, args[0] == "verify-mutant")
                    reporter.finish()
                }
                else -> throw Qa18Failure("USAGE", args[0])
            }
        } catch (failure: Qa18Failure) {
            reporter.emitFailure(failure)
            exitProcess(1)
        } catch (error: Throwable) {
            reporter.emitFailure(Qa18Failure("UNEXPECTED", error.javaClass.simpleName))
            exitProcess(2)
        }
    }
}
