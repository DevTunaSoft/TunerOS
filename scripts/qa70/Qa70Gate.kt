package com.devtunasoft.tuneros.qa70

import com.devtunasoft.tuneros.AndroidDashCaptureSource
import com.devtunasoft.tuneros.AndroidHlsCaptureSource
import com.devtunasoft.tuneros.HttpConnectionStub
import com.devtunasoft.tuneros.HttpStatusException
import com.devtunasoft.tuneros.core.DashSegmentNotPublishedException
import com.devtunasoft.tuneros.core.HlsByteRange
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/** Host-only driver: model checks plus real production calls through a socket-free connection double. */
object Qa70Gate {
    private val NL = Char(10).toString()
    private val HEX = "0123456789abcdef"

    @JvmStatic
    fun main(args: Array<String>) { exitProcess(run(args)) }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (value in bytes) {
            val v = value.toInt() and 0xff
            out.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private fun act(c: Qa70MirrorMain.Case): String {
        val connection = HttpConnectionStub(c.body, c.status, c.id)
        var opened = false
        val open: (String, HlsByteRange?) -> HttpURLConnection = { _, _ ->
            if (c.method == "C" && c.status == 404) throw HttpStatusException(c.status)
            opened = true
            connection
        }
        val freeBytes: () -> Long = { Long.MAX_VALUE / 4L }
        val dash = AndroidDashCaptureSource(open, freeBytes)
        val hls = AndroidHlsCaptureSource(open, freeBytes)
        var value: String
        try {
            value = when (c.method) {
                "R" -> {
                    val bytes = dash.loadRange("scratch-" + c.id, HlsByteRange(c.length, c.offset))
                    "OK:" + hex(bytes)
                }
                "K" -> {
                    val bytes = hls.loadKey("scratch-" + c.id)
                    "OK:" + hex(bytes)
                }
                "C" -> {
                    val output = ByteArrayOutputStream()
                    dash.copySegment("scratch-" + c.id, null, output, { false }, { })
                    if (output.size() == 0) "NOTPUBLISHED" else "WROTE_BYTES"
                }
                else -> "BAD_METHOD"
            }
        } catch (e: HttpStatusException) {
            value = "STATUS:" + e.code
        } catch (e: DashSegmentNotPublishedException) {
            value = "NOTPUBLISHED"
        } catch (e: IOException) {
            value = "REFUSED:" + (e.message ?: "")
        } catch (e: Exception) {
            value = "THREW:" + e.javaClass.simpleName
        }
        return value + if (opened && !connection.disconnected) ":NO_DISCONNECT" else ""
    }

    private fun expected(c: Qa70MirrorMain.Case): String = when (c.expect) {
        "OK_RANGE", "OK_KEY" -> "OK:" + if (c.detail == "-") "" else c.detail
        "REFUSE_RANGE_BOUNDS" -> "REFUSED:DASH index range is out of bounds"
        "REFUSE_RANGE_STATUS" -> "REFUSED:DASH index range was not honoured"
        "HTTP_STATUS" -> "STATUS:" + c.detail
        "REFUSE_RANGE_SHORT" -> "REFUSED:DASH index range is short"
        "REFUSE_KEY_SIZE" -> "REFUSED:HLS key response is larger than an AES-128 key"
        "NOT_PUBLISHED" -> "NOTPUBLISHED"
        else -> "UNKNOWN"
    }

    private fun familyCheck(
        id: String,
        cases: List<Qa70MirrorMain.Case>,
    ): Check {
        for (c in cases) {
            if (act(c) != expected(c)) return Check(id, false)
        }
        return Check(id, true)
    }

    private fun heapThreadCheck(casesBytes: ByteArray): Check {
        val before = Thread.activeCount()
        var cycles = 0
        val worker = Thread({
            repeat(6) {
                cycles += Qa70MirrorMain.modelCycle(casesBytes)
            }
        }, "qa70-heap-worker")
        worker.start()
        worker.join(10_000L)
        val after = Thread.activeCount()
        val usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L)
        val leaked = Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("qa70-") }
        return Check(
            "HEAP_THREAD_BOUND",
            !worker.isAlive && !leaked && cycles > 0 && after - before <= 2 && usedMb <= 40L,
        )
    }

    private fun run(args: Array<String>): Int {
        if (args.size < 2) {
            print("USAGE\tgenerate|verify|verify-semantic|verify-manifest|verify-pinned\t<dir>" + NL)
            return 2
        }
        val dir = File(args[1])
        if (args[0] == "generate") {
            Qa70MirrorMain.regenerate(dir)
            print("GENERATE\tPASS\tfiles=7" + NL)
            return 0
        }
        val structural = args[0] == "verify"
        val manifest = args[0] == "verify" || args[0] == "verify-manifest"
        val pinned = args[0] == "verify" || args[0] == "verify-pinned"
        if (!structural && !manifest && !pinned && args[0] != "verify-semantic") {
            print("BAD_MODE" + NL)
            return 2
        }
        val extra = if (args.size > 2) File(args[2]) else null
        val checks = Qa70MirrorMain.checks(dir, structural, manifest, pinned, extra).toMutableList()

        if (!checks.any { !it.ok }) {
            val parsed = Qa70MirrorMain.parseCases(
                String(File(dir, "cases.tsv").readBytes(), StandardCharsets.UTF_8),
            ).first
            checks += familyCheck("RANGE_OK", parsed.filter { it.expect == "OK_RANGE" })
            checks += familyCheck(
                "RANGE_BOUNDS",
                parsed.filter { it.expect == "REFUSE_RANGE_BOUNDS" },
            )
            checks += familyCheck("RANGE_STATUS", parsed.filter { it.expect == "REFUSE_RANGE_STATUS" })
            checks += familyCheck("HTTP_STATUS", parsed.filter { it.expect == "HTTP_STATUS" })
            checks += familyCheck("RANGE_SHORT", parsed.filter { it.expect == "REFUSE_RANGE_SHORT" })
            checks += familyCheck(
                "KEY_OK",
                parsed.filter { it.expect == "OK_KEY" },
            )
            checks += familyCheck("KEY_SIZE", parsed.filter { it.expect == "REFUSE_KEY_SIZE" })
            checks += familyCheck("NOT_PUBLISHED", parsed.filter { it.expect == "NOT_PUBLISHED" })
            checks += heapThreadCheck(File(dir, "cases.tsv").readBytes())
        }

        val sb = StringBuilder()
        for (c in checks) {
            sb.append("CHECK").append('\t').append(c.id).append('\t')
                .append(if (c.ok) "PASS" else "FAIL").append(NL)
        }
        val failed = checks.count { !it.ok }
        sb.append("FIRST").append('\t').append(checks.firstOrNull { !it.ok }?.id ?: "NONE").append(NL)
        sb.append("SUMMARY").append("\tchecks=").append(checks.size)
            .append("\tpass=").append(checks.size - failed).append("\tfail=").append(failed).append(NL)
        print(sb.toString())
        return if (failed == 0) 0 else 1
    }
}
