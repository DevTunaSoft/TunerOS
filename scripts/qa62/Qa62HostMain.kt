package com.devtunasoft.tuneros.qa62

import com.devtunasoft.tuneros.core.TimeshiftCapture
import com.devtunasoft.tuneros.core.TimeshiftRing
import com.devtunasoft.tuneros.core.TimeshiftWindow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.system.exitProcess

private class TrackedStream : ByteArrayOutputStream() {
    var closed = false
    override fun close() { closed = true; super.close() }
}

private class FakeRing : TimeshiftRing {
    val journal = ArrayList<String>()
    val files = LinkedHashMap<String, TrackedStream>()

    override fun create(fileName: String): OutputStream {
        journal += "create:$fileName"
        val stream = TrackedStream()
        files[fileName] = stream
        return stream
    }

    override fun delete(fileName: String) {
        journal += "delete:$fileName"
        files.remove(fileName)
    }

    override fun replacePlaylist(text: String) { journal += "playlist:$text" }

    fun playlistWrites(): Int = journal.count { it.startsWith("playlist:") }
}

/** Host entry point. Compiles the real production TimeshiftCapture and TimeshiftWindow. */
object Qa62HostMain {
    private val emitted = ArrayList<String>()

    private fun say(line: String) {
        emitted += line
        println(line)
    }

    private fun safeCheck(code: String, block: () -> Qa62Check): Qa62Check =
        try {
            block()
        } catch (error: Throwable) {
            Qa62Check(code, false, "exception=${error.javaClass.simpleName}")
        }

    private fun oversizeCheck(): Qa62Check {
        val ring = FakeRing()
        val capture = TimeshiftCapture(TimeshiftWindow(3, 100L), ring) { 0L }
        var caught = ""
        try {
            capture.onSegmentComplete(1L, 1.0, 200L, false)
        } catch (error: IOException) {
            caught = error.javaClass.simpleName
        } catch (error: Throwable) {
            caught = "OTHER:" + error.javaClass.simpleName
        }
        val ok = caught == "IOException" && ring.playlistWrites() == 0
        return Qa62Check("OVERSIZE_REJECT", ok, "exception=$caught,playlists=${ring.playlistWrites()}")
    }

    private fun finishIdempotentCheck(): Qa62Check {
        val ring = FakeRing()
        val capture = TimeshiftCapture(TimeshiftWindow(3, 5_000L), ring) { 0L }
        capture.onSegmentComplete(1L, 1.0, 100L, false)
        capture.finish()
        val afterFirst = ring.journal.toList()
        capture.finish()
        val afterSecond = ring.journal.toList()
        val ok = afterSecond == afterFirst && ring.playlistWrites() == 2
        return Qa62Check("FINISH_IDEMPOTENT", ok, "playlists=${ring.playlistWrites()},repeatJournal=${afterSecond.size - afterFirst.size}")
    }

    private fun nextFileCheck(): Qa62Check {
        val ring = FakeRing()
        val capture = TimeshiftCapture(TimeshiftWindow(3, 5_000L), ring) { 0L }
        capture.onSegmentComplete(1L, 1.0, 100L, false)
        var reached = false
        try {
            capture.output.write(byteArrayOf(7, 8))
            reached = ring.files["seg-1.ts"]?.toByteArray()?.contentEquals(byteArrayOf(7, 8)) == true
        } catch (error: Throwable) {
            reached = false
        }
        return Qa62Check("NEXT_FILE_OPEN", reached, "target=seg-1.ts")
    }

    private fun playlistPublishCheck(): Qa62Check {
        val ring = FakeRing()
        var clock = 1_000L
        val capture = TimeshiftCapture(TimeshiftWindow(3, 5_000L), ring) { clock }
        for (i in 1..3) {
            clock += 1_000L
            capture.onSegmentComplete(i.toLong(), 1.0, 100L, false)
        }
        val playlists = ring.journal.filter { it.startsWith("playlist:") }
        val ok = playlists.size == 3 && playlists.all { it.contains("#EXTM3U") && it.contains("#EXT-X-MEDIA-SEQUENCE:") }
        return Qa62Check("PLAYLIST_PUBLISH", ok, "playlists=${playlists.size}")
    }

    private fun finishOpenDeleteCheck(): Qa62Check {
        val ring = FakeRing()
        val capture = TimeshiftCapture(TimeshiftWindow(3, 5_000L), ring) { 0L }
        capture.finish()
        val ok = ring.journal.count { it == "delete:seg-0.ts" } == 1 && ring.playlistWrites() == 1
        return Qa62Check("FINISH_OPEN_DELETE", ok, "openDeleted=${ring.journal.contains("delete:seg-0.ts")}")
    }

    private fun journalCheck(events: List<Qa62Event>, dims: Map<String, String>): Qa62Check {
        val maxSegments = dims.getValue("maxSegments").toInt()
        val maxBytes = dims.getValue("maxBytes").toLong()
        val reference = Qa62Contract.reference(events, maxSegments, maxBytes)
        val ring = FakeRing()
        var index = 0
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments, maxBytes), ring) {
            val event = events[index - 1]
            event.programStart + event.durationMs
        }
        for (event in events) {
            index++
            capture.output.write(1)
            capture.onSegmentComplete(event.sequence, event.durationMs / 1000.0, event.bytes, event.flag == "D")
        }
        val ok = ring.journal == reference.journal
        val mismatch = if (ok) "none" else "first-diff=${ring.journal.zip(reference.journal).indexOfFirst { it.first != it.second }}"
        return Qa62Check("CONTRACT_JOURNAL", ok, "journalLines=${ring.journal.size},expected=${reference.journal.size},$mismatch")
    }

    private fun sourceContractCheck(prodDir: File): Qa62Check {
        val captureFile = File(prodDir, "TimeshiftCapture.kt")
        val engineFile = File(prodDir, "HlsCaptureEngine.kt")
        if (!captureFile.isFile || !engineFile.isFile) return Qa62Check("PROD_SOURCE_CONTRACT", false, "source-missing")
        val capture = captureFile.readText(Charsets.UTF_8)
        val engine = engineFile.readText(Charsets.UTF_8)
        val snippets = listOf(
            "evicted.forEach { ring.delete(it.fileName) }",
            "if (evicted.any { it.fileName == name })",
            "if (finished) return",
            "openName?.let { ring.delete(it) }",
            "ring.replacePlaylist(window.playlist())",
        )
        val ok = snippets.all { capture.contains(it) } &&
            capture.contains("openNextFile()") &&
            engine.contains("fun onSegmentComplete(sequence: Long, durationSeconds: Double, bytes: Long, discontinuity: Boolean)")
        return Qa62Check("PROD_SOURCE_CONTRACT", ok, "snippets=${snippets.size},interfaceSignature=yes")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "verify" }
        val fixtureDir = File(args.getOrElse(1) { "." })
        val prodDir = File(args.getOrElse(2) { "." })
        val threadsBefore = Thread.activeCount()
        if (mode == "generate") {
            val names = Qa62Contract.writeFiles(fixtureDir)
            say("GENERATE\tPASS\tfiles=${names.size}")
            exitProcess(0)
        }
        val checks = ArrayList<Qa62Check>()
        checks += Qa62Contract.pureChecks(fixtureDir, mode)
        val dimText = String(File(fixtureDir, "dimensions.tsv").readBytes(), Charsets.UTF_8)
        val eventText = String(File(fixtureDir, "events.tsv").readBytes(), Charsets.UTF_8)
        val dims = Qa62Contract.parseDimensions(dimText)
        val events = Qa62Contract.parseEvents(eventText)
        val dimsSafe = dims ?: mapOf(
            "maxSegments" to Qa62Contract.MAX_SEGMENTS.toString(),
            "maxBytes" to Qa62Contract.MAX_BYTES.toString(),
        )
        val eventsSafe = events ?: emptyList()
        checks += safeCheck("OVERSIZE_REJECT") { oversizeCheck() }
        checks += safeCheck("FINISH_IDEMPOTENT") { finishIdempotentCheck() }
        checks += safeCheck("NEXT_FILE_OPEN") { nextFileCheck() }
        checks += safeCheck("PLAYLIST_PUBLISH") { playlistPublishCheck() }
        checks += safeCheck("FINISH_OPEN_DELETE") { finishOpenDeleteCheck() }
        val journal = arrayOfNulls<Qa62Check>(1)
        var workerError = ""
        val worker = Thread({
            try {
                journal[0] = journalCheck(eventsSafe, dimsSafe)
            } catch (error: Throwable) {
                workerError = error.javaClass.simpleName
            }
        }, "qa62-worker")
        worker.start()
        worker.join(30_000L)
        checks += journal[0] ?: Qa62Check("CONTRACT_JOURNAL", false, "worker-error=${workerError.ifEmpty { "timeout" }}")
        checks += sourceContractCheck(prodDir)
        var first = "NONE"
        var pass = 0
        var fail = 0
        for (check in checks) {
            val status = if (check.ok) "PASS" else "FAIL"
            if (check.ok) pass++ else {
                fail++
                if (first == "NONE") first = check.code
                say("MISMATCH\t${check.code}\t${check.detail}")
            }
            say("CHECK\t${check.code}\t$status\t${check.detail}")
        }
        say("FIRST\t$first")
        val runtime = Runtime.getRuntime()
        say("MEM\theapMax=${runtime.maxMemory()}\tpeakUsed=${runtime.totalMemory() - runtime.freeMemory()}")
        say("THREADS\tbefore=$threadsBefore\tafter=${Thread.activeCount()}")
        val privacyHits = Qa62Contract.checkPrivacy(emitted)
        if (privacyHits.isNotEmpty()) {
            say("CHECK\tREPORT_PRIVACY_CLEAN\tFAIL\thits=${privacyHits.joinToString(",")}")
            say("FIRST\tREPORT_PRIVACY_CLEAN")
            fail++
            say("SUMMARY\tpass=$pass\tfail=$fail")
            say("VERDICT\tFAIL")
            exitProcess(1)
        }
        say("CHECK\tREPORT_PRIVACY_CLEAN\tPASS\tpatterns=5,derived-marker=yes")
        pass++
        say("SUMMARY\tpass=$pass\tfail=$fail")
        say("VERDICT\t${if (fail == 0) "PASS" else "FAIL"}")
        exitProcess(if (fail == 0) 0 else 1)
    }
}
