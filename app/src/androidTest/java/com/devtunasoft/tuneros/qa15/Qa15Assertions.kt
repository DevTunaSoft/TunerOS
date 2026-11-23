package com.devtunasoft.tuneros.qa15

import java.io.Writer

/**
 * Dependency-free mirror of QA-15's deterministic fixture contract.
 * Runs on the JVM and inside the instrumented test APK; no Android imports here.
 */
object Qa15Assertions {
    // Pinned by scripts/qa15/run.sh from the committed manifest after generation.
    private const val EXPECTED_SIDECAR = "e24c659da2376533e2df19088195fa07bba3b692b7e54d4cf6cea6d6f24c3d93"
    private val forbidden = Regex(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|pin=|drm=|provider\\.)",
    )

    private class PrefixSuffixWriter : Writer() {
        val prefix = StringBuilder()
        val tail = StringBuilder()
        var chars = 0
            private set

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            for (index in offset until offset + length) {
                val value = buffer[index]
                if (prefix.length < 256) prefix.append(value)
                tail.append(value)
                if (tail.length > 64) tail.delete(0, tail.length - 64)
                chars++
            }
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun occurrences(haystack: CharSequence, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    fun reportLines(): List<String> {
        val lines = ArrayList<String>(16)
        fun check(ok: Boolean, code: String, detail: String) {
            lines.add("CHECK\t${if (ok) "PASS" else "FAIL"}\t$code\t$detail")
        }

        val digest = Qa15Fixture.sidecarDigest()
        check(digest == EXPECTED_SIDECAR, "MIRROR_DIGEST", "digest=$digest")
        check(Qa15Fixture.CHANNELS == 4096, "CHANNEL_BOUND", "channels=${Qa15Fixture.CHANNELS}")
        check(
            Qa15Fixture.PROGRAMS_PER_CHANNEL == 24,
            "PROGRAM_BOUND",
            "programs=${Qa15Fixture.PROGRAMS_PER_CHANNEL}",
        )
        check(Qa15Fixture.CYCLES >= 3, "CYCLE_BOUND", "cycles=${Qa15Fixture.CYCLES}")
        check(Qa15Fixture.WINDOW_TO > Qa15Fixture.WINDOW_FROM, "WINDOW_GUARD", "slots=${Qa15Fixture.WINDOW_SLOTS}")

        val sidecar = java.io.StringWriter()
        Qa15Fixture.writeSidecar(sidecar)
        val sidecarText = sidecar.toString()
        val sidecarLines = sidecarText.lineSequence().count()
        check(sidecarLines == Qa15Fixture.CHANNELS + 4, "SIDECAR_LINES", "lines=$sidecarLines")
        check(!forbidden.containsMatchIn(sidecarText), "PRIVACY_SIDECAR", "clean=1")

        val starts = (4 until 4 + Qa15Fixture.WINDOW_SLOTS).map {
            Qa15Fixture.BASE_EPOCH + it * Qa15Fixture.SLOT_SECONDS
        }
        check(starts.zipWithNext().all { pair -> pair.first < pair.second }, "ORDER_STARTS", "slots=${starts.size}")
        val titles = (0 until Qa15Fixture.WINDOW_SLOTS).map { Qa15Fixture.title(0, it) }
        check(titles.toSet().size == Qa15Fixture.WINDOW_SLOTS, "TITLE_UNIQUE", "titles=${titles.size}")

        var cursor = Qa15Fixture.WINDOW_FROM
        var cells = 0
        var contiguous = true
        for (start in starts) {
            val clippedStart = maxOf(Qa15Fixture.WINDOW_FROM, start)
            val clippedEnd = minOf(Qa15Fixture.WINDOW_TO, start + Qa15Fixture.SLOT_SECONDS)
            if (clippedEnd <= clippedStart) continue
            if (clippedStart != cursor) contiguous = false
            cells++
            cursor = clippedEnd
        }
        if (cursor < Qa15Fixture.WINDOW_TO) cells++
        check(contiguous && cursor == Qa15Fixture.WINDOW_TO, "ROW_CONTIGUOUS", "cells=$cells")
        check(cells <= Qa15Fixture.MAX_ROW_CELLS, "ROW_CELL_BOUND", "cells=$cells")

        val capture = PrefixSuffixWriter()
        Qa15Fixture.writeXml(capture)
        val doctype = "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">"
        check(occurrences(capture.prefix, doctype) == 1, "DOCTYPE_SINGLE", "count=1")
        check(
            capture.prefix.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") &&
                !forbidden.containsMatchIn(capture.prefix),
            "PROLOG_SHAPE",
            "bytes=${capture.chars}",
        )
        check(capture.tail.toString().endsWith("</tv>\n"), "XML_CLOSING", "tail=ok")
        check(capture.chars > 0, "FIXTURE_NONEMPTY", "chars=${capture.chars}")

        lines.add("DIGEST\t$digest")
        return lines
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = reportLines()
        lines.forEach(::println)
        val failed = lines.count { it.startsWith("CHECK\tFAIL\t") }
        System.exit(if (failed == 0) 0 else 1)
    }
}
