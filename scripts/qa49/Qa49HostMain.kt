package com.devtunasoft.tuneros

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.devtunasoft.tuneros.qa49.Qa49Contract
import java.io.File
import java.io.IOException

/**
 * QA-49 host entry point. Compiled together with the real production unit and host-only android.*
 * signature mirrors. It never imports JUnit, AndroidX, Gradle or any third-party code.
 */
object Qa49HostMain {
    private const val CAP_LINES = 256

    private class HostCursor(
        private val mime: String,
        private val flags: Long,
        private val empty: Boolean,
    ) : Cursor {
        var closed = false
            private set

        override fun moveToFirst(): Boolean = !empty
        override fun getString(index: Int): String? = mime
        override fun getLong(index: Int): Long = flags
        override fun getColumnIndexOrThrow(columnName: String): Int =
            if (columnName == "mime_type") 0 else 1

        override fun close() {
            closed = true
        }
    }

    private class HostResolver(
        private val cursorMode: String,
        private val mime: String,
        private val flags: Long,
    ) : ContentResolver() {
        var queries = 0
            private set

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
            return when (cursorMode) {
                "NULL" -> null
                "EMPTY" -> HostCursor(mime, flags, empty = true)
                else -> HostCursor(mime, flags, empty = false)
            }
        }
    }

    private class VolumeFile(private val syntheticPath: String, private val syntheticUsable: Long) : File(syntheticPath) {
        override fun exists(): Boolean = true
        override fun mkdirs(): Boolean = true
        override fun getAbsolutePath(): String = syntheticPath
        override fun getUsableSpace(): Long = syntheticUsable
    }

    private class HostContext(
        override val contentResolver: ContentResolver,
        override val applicationInfo: ApplicationInfo,
        private val dirs: Array<File?>,
    ) : Context() {
        override fun getExternalFilesDirs(type: String?): Array<File?> = dirs
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2 || args.size > 3) {
            println("usage: Qa49HostMain <generate|verify|verify-mutant|verify-mutant-byte> <fixtureDir> [unused]")
            return
        }
        val mode = args[0]
        val dir = File(args[1])
        val ok = when (mode) {
            "generate" -> generate(dir)
            "verify", "verify-mutant", "verify-mutant-byte" -> verify(mode, dir)
            else -> {
                println("VERDICT\tFAIL\tMODE")
                false
            }
        }
        kotlin.system.exitProcess(if (ok) 0 else 1)
    }

    private fun generate(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            println("VERDICT\tFAIL\tDIR")
            return false
        }
        for ((name, text) in Qa49Contract.generate()) {
            File(dir, name).writeBytes(text.toByteArray(Charsets.UTF_8))
        }
        println("GENERATE\tPASS\tfiles=${Qa49Contract.DATA_FILES.size + 1}\tmarkerMode=derived")
        println("VERDICT\tPASS\tfirst=NONE")
        return true
    }

    private fun bytesOf(dir: File, name: String): ByteArray? = try {
        File(dir, name).readBytes()
    } catch (_: IOException) {
        null
    }

    private fun verify(mode: String, dir: File): Boolean {
        val report = ArrayList<String>(CAP_LINES)
        var first: String? = null

        fun emit(line: String) {
            if (report.size < CAP_LINES) {
                report.add(line)
                println(line)
            }
        }

        fun check(id: String, ok: Boolean): Boolean {
            emit("CHECK\t$id\t${if (ok) "PASS" else "FAIL"}")
            if (!ok && first == null) first = id
            return ok
        }

        val committed = LinkedHashMap<String, ByteArray>()
        for (name in Qa49Contract.DATA_FILES + "sha256.txt") {
            committed[name] = bytesOf(dir, name) ?: ByteArray(0)
        }

        // 1. Manifest consistency is checked against the bytes actually present (mutant-safe).
        var manifestOk = false
        try {
            val manifest = Qa49Contract.parseManifest(String(committed.getValue("sha256.txt"), Charsets.UTF_8))
            var ok = manifest.size == Qa49Contract.DATA_FILES.size
            for (name in Qa49Contract.DATA_FILES) {
                val actual = Qa49Contract.sha256(committed.getValue(name))
                ok = ok && manifest[name] == actual
            }
            manifestOk = ok
        } catch (_: Exception) {
            manifestOk = false
        }
        check("MANIFEST_MISMATCH", manifestOk)

        // 2. Dimensions pin is a digest of the committed data rows, not of the manifest.
        var dimensions: Map<String, String> = emptyMap()
        var dimensionsOk = false
        try {
            dimensions = Qa49Contract.parseDimensions(String(committed.getValue("dimensions.tsv"), Charsets.UTF_8))
            dimensionsOk = dimensions["casesSha256"] == Qa49Contract.sha256(committed.getValue("cases.tsv"))
        } catch (_: Exception) {
            dimensionsOk = false
        }
        check("CASES_DIGEST", dimensionsOk)

        // 3. Privacy scan runs before structural checks so a leak mutant cannot be masked.
        val fixtureBytes = committed.values.joinToString("\n") { String(it, Charsets.UTF_8) }
        val privacyOffenders = Qa49Contract.privacyOffenders(fixtureBytes)
        check("PRIVACY_SCAN", privacyOffenders.isEmpty())

        // 4. Strict parsers; a thrown code is itself the emitted check id and the pinned FIRST.
        var cases: List<Qa49Contract.Case> = emptyList()
        var parseOk = false
        try {
            cases = Qa49Contract.parseCases(String(committed.getValue("cases.tsv"), Charsets.UTF_8))
            parseOk = true
        } catch (error: Qa49Contract.ContractException) {
            check(error.code, false)
        } catch (_: Exception) {
            check("PARSE_EXCEPTION", false)
        }
        if (parseOk) {
            check("HEADER_STRICT", true)
            check("FIELD_COUNT", true)
            check("CASE_ID", true)
            check("FAMILY_CODE", true)
            check("INT_BOUNDS", true)
            check("CURSOR_CODE", true)
            check("EXPECT_CODE", true)
            check("TRACE_CODE", true)
            check("DIMENSION_STRICT", dimensions["cases"] == Qa49Contract.CASES.toString() &&
                dimensions["families"] == Qa49Contract.FAMILIES.toString() &&
                dimensions["perFamily"] == Qa49Contract.PER_FAMILY.toString() &&
                dimensions["cycles"] == Qa49Contract.CYCLES.toString() &&
                dimensions["seed"] == Qa49Contract.SEED.toString())
            check("CORRUPT_EXPECTATION", cases.all {
                Qa49Contract.EXPECT_CODES.contains(it.expect) && it.expect ==
                    if (it.family == "ADMIT" || it.family == "ADMIT_ALT") "ADMIT" else "REFUSE"
            })
            check("CORRUPT_TRACE", cases.size == Qa49Contract.CASES && cases.all { it.trace in Qa49Contract.TRACE_CODES })
        }

        // 5. Byte identity is active in GREEN and in the dedicated byte-drift control.
        if (mode != "verify-mutant") {
            val regenerated = Qa49Contract.generate()
            var identical = regenerated.size == committed.size
            for ((name, text) in regenerated) {
                identical = identical && committed[name]?.contentEquals(text.toByteArray(Charsets.UTF_8)) == true
            }
            check("BYTE_IDENTITY", identical)
        }

        if (!parseOk) {
            emit("SUMMARY\tchecks=${report.count { it.startsWith("CHECK\t") }} pass=0 fail=1 first=$first")
            emit("VERDICT\tFAIL\tfirst=$first")
            return false
        }

        // 6. Drive the real compiled production unit over every fixture row, repeatedly.
        val marker = Qa49Contract.marker()
        val diagOffenders = HashSet<String>()
        var admissionOk = true
        var traceOk = true
        var admitted = 0
        var refused = 0
        var inspections = 0
        val rt = Runtime.getRuntime()
        var heapStart = 0L
        var threadStart = 0
        var heapEnd = 0L
        var threadEnd = 0

        for (cycle in 0 until Qa49Contract.CYCLES) {
            for (row in cases) {
                val dirs: Array<File?> = arrayOf(
                    VolumeFile("/storage/emulated/0/Android/data/qa49",
                        if (row.volumeId == "primary") row.usableBytes else 67_108_864L),
                    VolumeFile("/storage/ABCD-1234/Android/data/qa49",
                        if (row.volumeId == "ABCD-1234") row.usableBytes else 33_554_432L),
                    VolumeFile("/storage/NEGVOL/Android/data/qa49", -1L),
                    null,
                )
                val resolver = HostResolver(row.cursor, row.mime, row.flags)
                val context = HostContext(
                    resolver,
                    ApplicationInfo(ApplicationInfo.FLAG_DEBUGGABLE),
                    dirs,
                )
                val raw = "content://${row.authority}/tree=${row.treeId}?qa49=$marker"
                val uri = Uri(row.scheme, row.authority, raw)
                Log.clear()
                val info = SafStorageInspector.inspect(context, uri)
                val trace = Qa49Contract.traceOf(Log.snapshot().toList())
                inspections++
                if (info != null) {
                    admitted++
                    if (row.expect != "ADMIT" || info.rootTitle != row.volumeId ||
                        info.availableBytes != row.usableBytes
                    ) {
                        admissionOk = false
                    }
                } else {
                    refused++
                    if (row.expect != "REFUSE") admissionOk = false
                }
                if (trace != row.trace) traceOk = false
                diagOffenders.addAll(Qa49Contract.privacyOffenders(Log.snapshot().joinToString("\n")))
            }
            if (cycle == 0) {
                System.gc()
                try {
                    Thread.sleep(30L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                heapStart = rt.totalMemory() - rt.freeMemory()
                threadStart = Thread.getAllStackTraces().size
            }
        }
        System.gc()
        try {
            Thread.sleep(30L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        heapEnd = rt.totalMemory() - rt.freeMemory()
        threadEnd = Thread.getAllStackTraces().size

        check("CONTRACT_ADMISSION", admissionOk)
        check("CONTRACT_TRACE", traceOk)
        val expectedAdmit = 2 * Qa49Contract.PER_FAMILY * Qa49Contract.CYCLES
        check("COUNTS", admitted == expectedAdmit && refused == Qa49Contract.CASES * Qa49Contract.CYCLES - expectedAdmit &&
            inspections == Qa49Contract.CASES * Qa49Contract.CYCLES)

        val qaThreads = Thread.getAllStackTraces().keys.count { it.name.startsWith("qa49-") }
        val heapGrowth = heapEnd - heapStart
        val drift = kotlin.math.abs(threadEnd - threadStart)
        check("BOUNDS", heapEnd > 0L && heapEnd < rt.maxMemory() && heapGrowth < 24L * 1024 * 1024 &&
            drift <= 2 && qaThreads == 0 && report.size < CAP_LINES)

        emit("COUNTS\tadmitted=$admitted\trefused=$refused\tinspections=$inspections")
        emit("BOUNDS\theapStart=$heapStart\theapEnd=$heapEnd\theapMax=${rt.maxMemory()}\tthreadStart=$threadStart\tthreadEnd=$threadEnd\tdrift=$drift\tqaThreads=$qaThreads")

        val reportOffenders = Qa49Contract.privacyOffenders(report.joinToString("\n"))
        val diagOk = diagOffenders.isEmpty() && reportOffenders.isEmpty()
        emit("DIAG_SCAN\t${if (diagOk) "PASS" else "FAIL"}\toffenders=${diagOffenders + reportOffenders}")
        if (!diagOk && first == null) first = "DIAG_SCAN"

        val total = report.count { it.startsWith("CHECK\t") }
        val passed = report.count { it.startsWith("CHECK\t") && it.endsWith("\tPASS") }
        val failed = total - passed
        emit("SUMMARY\tchecks=$total\tpass=$passed\tfail=$failed\tfirst=${first ?: "NONE"}")
        emit("VERDICT\t${if (failed == 0) "PASS" else "FAIL"}\tfirst=${first ?: "NONE"}")
        return failed == 0
    }
}
