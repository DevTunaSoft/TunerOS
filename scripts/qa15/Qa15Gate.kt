package com.devtunasoft.tuneros.qa15

import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.TimelineLayout
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/**
 * QA-15 host gate: repeated large-scratch EPG import under a constrained heap.
 *
 * Compiled against the real pure-Kotlin core parser/index/layout sources; no product
 * file is modified. Only counts, check ids and digests are emitted. Modes:
 *   generate <manifest-dir> <fixture-file>
 *   verify   <manifest-dir> <scratch-dir>
 */
object Qa15Gate {
    private val forbidden = Regex(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|pin=|drm=|provider\\.)",
    )
    private const val MAX_HEAP_DRIFT_BYTES = 8L * 1024 * 1024
    private const val MAX_THREAD_DRIFT = 2
    private const val MAX_REPORT_LINES = 1024
    private val expectedOrder = compareBy<GuideProgram> { it.startEpochSeconds }
        .thenBy { it.endEpochSeconds }

    private class Report {
        val lines = ArrayList<String>(MAX_REPORT_LINES)
        var checks = 0
        var failures = 0
        var first = ""

        fun check(ok: Boolean, code: String, detail: String) {
            if (lines.size >= MAX_REPORT_LINES) return
            checks++
            if (ok) {
                lines.add("CHECK\tPASS\t$code\t$detail")
                return
            }
            failures++
            if (first.isEmpty()) first = code
            lines.add("CHECK\tFAIL\t$code\t$detail")
        }

        fun note(tag: String, detail: String) {
            if (lines.size < MAX_REPORT_LINES) lines.add("$tag\t$detail")
        }

        fun emit() {
            if (lines.any { forbidden.containsMatchIn(it) }) {
                // Never print a line that might carry a sensitive-looking token.
                lines.clear()
                failures++
                if (first.isEmpty()) first = "PRIVACY_REPORT"
                lines.add("CHECK\tFAIL\tPRIVACY_REPORT\treport-scan")
            }
            for (line in lines) println(line)
            println("FIRST\t${first.ifEmpty { "NONE" }}")
            val positives = lines.count { it.startsWith("CHECK\tPASS\t") }
            println("SUMMARY\tpositive=$positives\tfailed=$failures\ttotal=$checks")
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readManifest(path: Path): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        Files.readAllLines(path, StandardCharsets.UTF_8).forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) {
                result[line.substring(0, index).trim()] = line.substring(index + 1).trim()
            }
        }
        return result
    }

    private fun generate(manifestDir: Path, fixture: Path) {
        Files.createDirectories(manifestDir)
        Files.createDirectories(fixture.parent)
        Files.newBufferedWriter(fixture, StandardCharsets.UTF_8).use { Qa15Fixture.writeXml(it) }
        val bytes = Files.size(fixture)
        val manifest = listOf(
            "version=${Qa15Fixture.VERSION}",
            "seed=${Qa15Fixture.SEED}",
            "channels=${Qa15Fixture.CHANNELS}",
            "programs-per-channel=${Qa15Fixture.PROGRAMS_PER_CHANNEL}",
            "slot-seconds=${Qa15Fixture.SLOT_SECONDS}",
            "window-from=${Qa15Fixture.WINDOW_FROM}",
            "window-to=${Qa15Fixture.WINDOW_TO}",
            "cycles=${Qa15Fixture.CYCLES}",
            "corrupt-variants=${Qa15Fixture.CORRUPT_VARIANTS}",
            "fixture-bytes=$bytes",
            "fixture-sha256=${sha256(fixture)}",
            "sidecar-sha256=${Qa15Fixture.sidecarDigest()}",
        )
        Files.write(
            manifestDir.resolve("manifest.txt"),
            manifest,
            StandardCharsets.UTF_8,
        )
        println("GENERATE\tbytes=$bytes\tsha256=${sha256(fixture)}")
    }

    private fun expectReject(block: () -> Unit) {
        val rejected = try {
            block()
            false
        } catch (error: IllegalArgumentException) {
            true
        }
        // Mutant target: inverting this condition must trip the GUARD_PROBE check.
        if (!rejected) throw AssertionError("reject-guard")
    }

    private fun guardAlive(): Boolean = try {
        expectReject { throw IllegalArgumentException("probe") }
        true
    } catch (error: AssertionError) {
        false
    }

    private fun heapAfterGc(): Long {
        System.gc()
        Thread.sleep(20)
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun privacyHits(path: Path): Int {
        var hits = 0
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (forbidden.containsMatchIn(line)) hits++
            }
        }
        return hits
    }

    private fun replaceInPrefix(original: ByteArray, needle: String, replacement: String): ByteArray {
        val head = String(original, 0, minOf(original.size, 256), StandardCharsets.UTF_8)
        if (!head.contains(needle)) return original
        val patched = head.replace(needle, replacement)
        val tailStart = patched.length - (replacement.length - needle.length)
        val result = ByteArray(original.size + (replacement.length - needle.length))
        patched.toByteArray(StandardCharsets.UTF_8).copyInto(result)
        original.copyInto(result, tailStart, minOf(original.size, 256))
        return result
    }

    /** Small well-formed scratch document used for fail-closed mutations, bounded to a few KiB. */
    private fun smallCanonical(): ByteArray {
        val text = StringBuilder()
        text.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        text.append("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n")
        text.append("<tv>\n")
        for (channel in 0 until 8) {
            val id = Qa15Fixture.guideId(channel)
            text.append("<channel id=\"$id\"><display-name>Scratch Channel $channel</display-name></channel>\n")
        }
        for (channel in 0 until 8) {
            for (slot in 0 until 4) {
                val start = Qa15Fixture.BASE_EPOCH + slot * Qa15Fixture.SLOT_SECONDS
                text.append("<programme channel=\"${Qa15Fixture.guideId(channel)}\" ")
                text.append("start=\"${start}\" stop=\"${start + Qa15Fixture.SLOT_SECONDS}\">")
                text.append("<title>${Qa15Fixture.title(channel, slot)}</title></programme>\n")
            }
        }
        text.append("</tv>\n")
        return text.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun corruptVariants(canonical: ByteArray): List<Pair<String, ByteArray>> {
        val doctype = "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">"
        val badDoctype = "<!DOCTYPE tv SYSTEM \"other.dtd\">"
        val subset = "<!DOCTYPE tv SYSTEM \"xmltv.dtd\" [<!ENTITY x \"y\">]>"
        val bom = byteArrayOf(0xff.toByte())
        val doubled = (doctype + "\n" + doctype + "\n").toByteArray(StandardCharsets.UTF_8)
        return listOf(
            "truncated" to canonical.copyOf(canonical.size - 256),
            "unbalanced" to (canonical + "<".toByteArray(StandardCharsets.UTF_8)),
            "bad-doctype" to replaceInPrefix(canonical, doctype, badDoctype),
            "internal-subset" to replaceInPrefix(canonical, doctype, subset),
            "bad-encoding" to (bom + canonical),
            "double-doctype" to (doubled + canonical),
        )
    }

    private fun parseRejects(bytes: ByteArray): Boolean = try {
        GuideParser.parse(ByteArrayInputStream(bytes), includeDescriptions = false)
        false
    } catch (error: Throwable) {
        true
    }

    private fun verify(manifestDir: Path, scratchDir: Path) {
        val report = Report()
        Files.createDirectories(scratchDir)
        val manifest = readManifest(manifestDir.resolve("manifest.txt"))
        val expected = mapOf(
            "version" to Qa15Fixture.VERSION,
            "seed" to Qa15Fixture.SEED.toString(),
            "channels" to Qa15Fixture.CHANNELS.toString(),
            "programs-per-channel" to Qa15Fixture.PROGRAMS_PER_CHANNEL.toString(),
            "slot-seconds" to Qa15Fixture.SLOT_SECONDS.toString(),
            "window-from" to Qa15Fixture.WINDOW_FROM.toString(),
            "window-to" to Qa15Fixture.WINDOW_TO.toString(),
            "cycles" to Qa15Fixture.CYCLES.toString(),
            "corrupt-variants" to Qa15Fixture.CORRUPT_VARIANTS.toString(),
        )
        report.check(expected.all { manifest[it.key] == it.value }, "MANIFEST_MATCH", "fields=${expected.size}")
        report.check(guardAlive(), "GUARD_PROBE", "reject-guard")

        val scratchFixture = scratchDir.resolve("fixture.xmltv")
        Files.newBufferedWriter(scratchFixture, StandardCharsets.UTF_8).use { Qa15Fixture.writeXml(it) }
        // Privacy is checked before byte/digest so a sensitive fixture token is named first.
        report.check(privacyHits(scratchFixture) == 0, "PRIVACY_FIXTURE", "hits=0")
        val bytes = Files.size(scratchFixture)
        val digest = sha256(scratchFixture)
        report.check(bytes == manifest["fixture-bytes"]?.toLongOrNull(), "FIXTURE_REGEN_BYTES", "bytes=$bytes")
        report.check(digest == manifest["fixture-sha256"], "FIXTURE_REGEN_DIGEST", "digest=$digest")
        report.check(
            Qa15Fixture.sidecarDigest() == manifest["sidecar-sha256"],
            "MIRROR_DIGEST",
            "sidecar=match",
        )
        report.check(Qa15Fixture.CYCLES >= 3, "CYCLE_COUNT", "cycles=${Qa15Fixture.CYCLES}")

        val heap = LongArray(Qa15Fixture.CYCLES)
        val threads = IntArray(Qa15Fixture.CYCLES)
        repeat(Qa15Fixture.CYCLES) { cycle ->
            var snapshot: GuideSnapshot? = null
            try {
                BufferedInputStream(Files.newInputStream(scratchFixture), 1 shl 16).use { input ->
                    snapshot = GuideParser.parse(input, includeDescriptions = false)
                }
                val guide = snapshot!!
                guide.prepareIndex()
                report.check(
                    guide.programs.size == Qa15Fixture.CHANNELS * Qa15Fixture.PROGRAMS_PER_CHANNEL,
                    "PROGRAM_COUNT",
                    "cycle=$cycle count=${guide.programs.size}",
                )
                report.check(
                    guide.channelNames.size == Qa15Fixture.CHANNELS,
                    "CHANNEL_COUNT",
                    "cycle=$cycle count=${guide.channelNames.size}",
                )
                var ordered = 0
                var covered = 0
                var contiguous = 0
                var bounded = 0
                var inRange = 0
                var sampled = 0
                for (channel in 0 until Qa15Fixture.CHANNELS step 397) {
                    val id = Qa15Fixture.guideId(channel)
                    val rows = guide.programsBetween(id, Qa15Fixture.WINDOW_FROM, Qa15Fixture.WINDOW_TO)
                    sampled++
                    if (rows == rows.sortedWith(expectedOrder)) ordered++
                    if (rows.size == Qa15Fixture.WINDOW_SLOTS) covered++
                    val timeline = TimelineLayout.rowFromSorted(rows, Qa15Fixture.WINDOW_FROM, Qa15Fixture.WINDOW_TO)
                    if (timeline.isNotEmpty() &&
                        timeline.first().startEpochSeconds == Qa15Fixture.WINDOW_FROM &&
                        timeline.last().endEpochSeconds == Qa15Fixture.WINDOW_TO
                    ) contiguous++
                    if (timeline.zipWithNext().all { pair ->
                        pair.first.endEpochSeconds == pair.second.startEpochSeconds
                    }) inRange++
                    if (timeline.size <= Qa15Fixture.MAX_ROW_CELLS) bounded++
                }
                report.check(ordered == sampled, "ORDER_SORTED", "cycle=$cycle rows=$sampled ok=$ordered")
                report.check(covered == sampled, "WINDOW_ROWS", "cycle=$cycle rows=$sampled ok=$covered")
                report.check(contiguous == sampled, "ROW_COVERS_WINDOW", "cycle=$cycle rows=$sampled ok=$contiguous")
                report.check(inRange == sampled, "ROW_CONTIGUOUS", "cycle=$cycle rows=$sampled ok=$inRange")
                report.check(bounded == sampled, "ROW_CELL_BOUND", "cycle=$cycle rows=$sampled ok=$bounded")
                expectReject {
                    guide.programsBetween(Qa15Fixture.guideId(0), Qa15Fixture.WINDOW_FROM, Qa15Fixture.WINDOW_FROM)
                }
                report.check(true, "WINDOW_GUARD", "cycle=$cycle zero-width-rejected")
            } catch (error: Throwable) {
                report.check(false, "IMPORT_FAILED", "cycle=$cycle kind=${error.javaClass.simpleName}")
            } finally {
                snapshot = null
            }
            heap[cycle] = heapAfterGc()
            threads[cycle] = Thread.getAllStackTraces().keys.count { it.isAlive }
        }
        val baselineHeap = heap[0]
        val drift = (1 until heap.size).maxOf { kotlin.math.abs(heap[it] - baselineHeap) }
        report.check(drift <= MAX_HEAP_DRIFT_BYTES, "HEAP_DRIFT", "drift=$drift limit=$MAX_HEAP_DRIFT_BYTES")
        val baselineThreads = threads[0]
        val threadDrift = (1 until threads.size).maxOf { kotlin.math.abs(threads[it] - baselineThreads) }
        report.check(threadDrift <= MAX_THREAD_DRIFT, "THREAD_STABLE", "drift=$threadDrift")
        report.check(
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("qa15-") },
            "NO_QA_THREAD_LEAK",
            "threads=stable",
        )

        val registryFields = GuideParser::class.java.declaredFields.filter { field ->
            MutableCollection::class.java.isAssignableFrom(field.type) ||
                Map::class.java.isAssignableFrom(field.type) || field.type.isArray
        }
        report.check(registryFields.isEmpty(), "REGISTRY_FREE", "fields=${registryFields.size}")

        val canonical = smallCanonical()
        report.check(!parseRejects(canonical), "SMALL_FIXTURE_OK", "bytes=${canonical.size}")
        val variants = corruptVariants(canonical)
        report.check(
            variants.size == Qa15Fixture.CORRUPT_VARIANTS,
            "CORRUPT_VARIANT_COUNT",
            "variants=${variants.size}",
        )
        variants.forEach { (name, mutated) ->
            report.check(parseRejects(mutated), "FAIL_CLOSED", "variant=$name")
        }

        report.note("DIGEST", "fixture=$digest sidecar=${Qa15Fixture.sidecarDigest()}")
        report.emit()
        exitProcess(if (report.failures == 0) 0 else 1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3 || (args[0] != "generate" && args[0] != "verify")) {
            println("usage: Qa15Gate generate <manifest-dir> <fixture-file> | verify <manifest-dir> <scratch-dir>")
            exitProcess(2)
        }
        try {
            if (args[0] == "generate") generate(Paths.get(args[1]), Paths.get(args[2]))
            else verify(Paths.get(args[1]), Paths.get(args[2]))
        } catch (error: IOException) {
            println("CHECK\tFAIL\tIO_FAILED\tkind=${error.javaClass.simpleName}")
            exitProcess(2)
        }
    }
}
