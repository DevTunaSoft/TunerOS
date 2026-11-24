package com.devtunasoft.tuneros.qa50

import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideWindow
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.system.exitProcess

/** QA-50 bounded host gate driving the real compiled GuideParser over scratch fixture bytes. */
object Qa50HostMain {
    private const val CASE_HEADER = "id\tchannel\ttitleKind\tdescKind\tstart\tstop\texpected\tnote"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args[0] !in setOf("generate", "verify", "verify-mutant", "verify-mutant-byte")) {
            println("USAGE\tqa50\tgenerate|verify|verify-mutant|verify-mutant-byte\tDIR")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        val lines = ArrayList<String>(64)
        var first = "NONE"
        var checks = 0
        var failures = 0

        fun emit(line: String) {
            lines.add(line)
            println(line)
        }
        val skipped = when (mode) {
            "verify-mutant" -> setOf("MANIFEST_CONSISTENT", "PINNED_DIGEST", "BYTE_IDENTITY")
            "verify-mutant-byte" -> setOf("MANIFEST_CONSISTENT", "PINNED_DIGEST")
            else -> emptySet()
        }
        fun check(code: String, ok: Boolean, detail: String = "") {
            if (code in skipped) return
            checks++
            if (!ok) {
                failures++
                if (first == "NONE") first = code
            }
            val suffix = if (detail.isEmpty()) "" else "\t$detail"
            emit("CHECK\t$code\t${if (ok) "PASS" else "FAIL"}$suffix")
        }

        try {
            if (mode == "generate") {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    emit("VERDICT\tFAIL\tfirst=DIR_CREATE")
                    exitProcess(1)
                }
                for ((name, bytes) in Qa50Contract.generateFiles()) {
                    File(dir, name).writeBytes(bytes)
                    emit("GENERATE\t$name\tbytes=${bytes.size}")
                }
                emit("VERDICT\tPASS\tfiles=${Qa50Contract.FILE_COUNT}")
                return
            }

            val names = Qa50Contract.generateFiles().keys.toList()
            val bytes = LinkedHashMap<String, ByteArray>()
            for (name in names) {
                val file = File(dir, name)
                if (!file.isFile) {
                    emit("VERDICT\tFAIL\tfirst=MISSING_FILE")
                    exitProcess(1)
                }
                bytes[name] = file.readBytes()
            }
            val text = { name: String -> String(bytes.getValue(name), Charsets.UTF_8) }

            // PRIVACY_SCAN runs first so a scratch leak mutant cannot be masked by a golden check.
            val fixtureText = bytes.values.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
            val leakScan = Qa50Contract.privacyOffenders(fixtureText + "\n" + lines.joinToString("\n"))
            check("PRIVACY_SCAN", leakScan.isEmpty(), "offenders=${leakScan.size}")

            val corruptRows = Qa50Contract.parseCorrupt(text("corrupt.tsv"))
            val canonical = corruptRows.first { it.first == "valid-control" }.second
            val canonicalOk = try {
                GuideParser.parse(ByteArrayInputStream(canonical), includeDescriptions = false)
                true
            } catch (ignored: Throwable) {
                false
            }
            var rejected = 0
            var corruptCount = 0
            for ((name, payload) in corruptRows) {
                if (name == "valid-control") continue
                corruptCount++
                val refused = try {
                    GuideParser.parse(ByteArrayInputStream(payload), includeDescriptions = false)
                    false
                } catch (ignored: Throwable) {
                    true
                }
                if (refused) rejected++
            }
            check("CORRUPT_FAIL_CLOSED", canonicalOk && rejected == corruptCount, "rejected=$rejected")

            val caseLines = text("cases.tsv").split('\n').dropLastWhile { it.isEmpty() }
            val headerOk = caseLines.firstOrNull() == CASE_HEADER
            val fieldCountsOk = caseLines.drop(1).all { it.split('\t').size == 8 }
            check("HEADER_STRICT", headerOk)
            check("FIELD_COUNT", fieldCountsOk)
            check("CASE_COUNT", caseLines.size - 1 == Qa50Contract.CASES)
            if (!headerOk || !fieldCountsOk) {
                emit("FIRST\t$first")
                emit("VERDICT\tFAIL\tfirst=$first")
                exitProcess(1)
            }
            val cases = Qa50Contract.parseCases(text("cases.tsv"))
            val dims = Qa50Contract.parseDimensions(text("dimensions.tsv"))
            fun dim(key: String): String = dims.getValue(key)
            fun longDim(key: String): Long = dim(key).toLong()
            fun intDim(key: String): Int = dim(key).toInt()
            fun idSet(key: String): Set<String> = dim(key).split(',').filter { it.isNotEmpty() }.toSet()

            val edgeIds = cases
                .filter { it.expected in setOf("EXCLUDE_WINDOW_EDGE_END", "EXCLUDE_WINDOW_EDGE_START") }
                .map { it.id }.toSet()
            val edgeClaimedAdmitted = cases.filter { it.expected.startsWith("ADMIT") }.map { it.id }.toSet()
            check("WINDOW_EDGE_CLASS", edgeIds == idSet("windowEdgeExcludedIds"), "rows=${edgeIds.size}")

            val window = GuideWindow(longDim("windowFrom"), longDim("windowTo"))
            fun admittedIds(snapshot: com.devtunasoft.tuneros.core.GuideSnapshot): Set<String> =
                snapshot.programs.mapNotNull { program ->
                    if (program.title.startsWith("Show-")) {
                        program.title.substringAfter("Show-").substringBefore("-")
                    } else {
                        null
                    }
                }.toSet()

            val firstSnap = GuideParser.parse(
                ByteArrayInputStream(bytes.getValue("guide.xmltv")),
                maxPrograms = intDim("maxPrograms"),
                maxChannels = intDim("maxChannels"),
                window = window,
            )
            val admitted = admittedIds(firstSnap)
            val overflowClaimed = cases.filter { it.expected == "OVERFLOW" }.map { it.id }.toSet()
            val excludedClaimed = cases
                .filter { it.expected.startsWith("EXCLUDE") }
                .map { it.id }.toSet()
            check(
                "CONTRACT_ADMISSION",
                admitted == edgeClaimedAdmitted &&
                    admitted == idSet("admittedIds") &&
                    overflowClaimed.none { it in admitted } &&
                    excludedClaimed.none { it in admitted },
                "admitted=${admitted.size}",
            )

            val omittedWarnings = firstSnap.warnings.count { it.contains("programmes omitted: limit ") }
            val omittedCount = firstSnap.warnings
                .firstOrNull { it.contains("programmes omitted: limit ") }
                ?.substringBefore(' ')?.toIntOrNull() ?: -1
            check(
                "OVERFLOW_ACCOUNTING",
                omittedWarnings == 1 && omittedCount == intDim("omittedExpected") &&
                    overflowClaimed.size == omittedCount,
                "omitted=$omittedCount",
            )
            val channelOmitted = firstSnap.warnings
                .firstOrNull { it.contains("channel names omitted: limit ") }
                ?.substringBefore(' ')?.toIntOrNull() ?: -1
            check(
                "CHANNEL_CAP",
                channelOmitted == intDim("channelOmittedExpected") &&
                    firstSnap.channelNames.size == intDim("maxChannels"),
                "names=${firstSnap.channelNames.size}",
            )
            val titleRow = firstSnap.programs.firstOrNull { it.title.startsWith("Show-c0011-") }
            val descRow = firstSnap.programs.firstOrNull { it.title.startsWith("Show-c0012") }
            val longName = firstSnap.channelNames["chB"]
            check(
                "TRUNCATION_WARNINGS",
                titleRow?.title?.length == intDim("maxTitleChars") &&
                    descRow?.description?.length == intDim("maxDescChars") &&
                    longName?.length == intDim("maxChannelNameChars") &&
                    firstSnap.warnings.count { it.contains("titles shortened to ") } == intDim("truncatedTitleExpected") &&
                    firstSnap.warnings.count { it.contains("descriptions shortened to ") } == intDim("truncatedDescriptionExpected") &&
                    firstSnap.warnings.count { it.contains("channel names shortened to ") } == intDim("truncatedChannelNameExpected"),
            )
            val invalidCount = firstSnap.warnings
                .firstOrNull { it.contains("programmes had invalid timestamps") }
                ?.substringBefore(' ')?.toIntOrNull() ?: -1
            check("INVALID_TIME_COUNT", invalidCount == intDim("invalidTimestampExpected"), "invalid=$invalidCount")
            check(
                "CAP_BINDS",
                intDim("admittedExpected") == intDim("maxPrograms") && admitted.size == intDim("admittedExpected"),
                "cap=${intDim("maxPrograms")}",
            )

            var cyclesStable = true
            var heapBaseline = 0L
            var heapMax = 0L
            var threadBaseline = 0
            var threadMax = 0
            repeat(intDim("cycles")) { cycle ->
                val snap = GuideParser.parse(
                    ByteArrayInputStream(bytes.getValue("guide.xmltv")),
                    maxPrograms = intDim("maxPrograms"),
                    maxChannels = intDim("maxChannels"),
                    window = window,
                )
                if (admittedIds(snap) != idSet("admittedIds")) cyclesStable = false
                System.gc()
                try {
                    Thread.sleep(40L)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val threads = Thread.activeCount()
                if (cycle == 0) {
                    heapBaseline = used
                    threadBaseline = threads
                }
                heapMax = maxOf(heapMax, used)
                threadMax = maxOf(threadMax, threads)
            }
            check("REPEAT_CYCLES", cyclesStable, "cycles=${intDim("cycles")}")
            check(
                "HEAP_BOUND",
                heapMax < longDim("heapLimitBytes") && heapMax - heapBaseline < longDim("heapDriftBytes"),
                "used=$heapMax",
            )
            check(
                "THREAD_BOUND",
                threadMax <= intDim("threadLimit") && threadMax - threadBaseline <= 2,
                "threads=$threadMax",
            )
            val staticStores = GuideParser::class.java.declaredFields.count { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    (MutableCollection::class.java.isAssignableFrom(field.type) ||
                        Map::class.java.isAssignableFrom(field.type))
            }
            check("STATIC_STATE", staticStores == 0, "stores=$staticStores")

            val manifest = Qa50Contract.parseManifest(text("sha256.txt"))
            val manifestOk = manifest.size == 4 && manifest.all { (hash, name) ->
                name in bytes && Qa50Contract.sha256(bytes.getValue(name)) == hash
            }
            check("MANIFEST_CONSISTENT", manifestOk, "entries=${manifest.size}")
            check(
                "PINNED_DIGEST",
                dim("casesSha256") == Qa50Contract.sha256(bytes.getValue("cases.tsv")) &&
                    dim("guideSha256") == Qa50Contract.sha256(bytes.getValue("guide.xmltv")) &&
                    dim("corruptSha256") == Qa50Contract.sha256(bytes.getValue("corrupt.tsv")),
            )
            val generated = Qa50Contract.generateFiles()
            val identical = names.all { name -> generated.getValue(name).contentEquals(bytes.getValue(name)) }
            check("BYTE_IDENTITY", identical, "files=${names.size}")

            val postScan = Qa50Contract.privacyOffenders(lines.joinToString("\n"))
            check("DIAGNOSTIC_SCAN", postScan.isEmpty(), "offenders=${postScan.size}")
        } catch (error: Throwable) {
            if (first == "NONE") first = "HOST_EXCEPTION"
            emit("CHECK\tHOST_EXCEPTION\tFAIL\tclass=${error.javaClass.simpleName}")
            failures++
            checks++
        }

        emit("FIRST\t$first")
        emit("SUMMARY\tchecks=$checks\tfail=$failures\tfirst=$first")
        emit("VERDICT\t${if (failures == 0) "PASS" else "FAIL"}\tfirst=$first")
        exitProcess(if (failures == 0) 0 else 1)
    }
}
