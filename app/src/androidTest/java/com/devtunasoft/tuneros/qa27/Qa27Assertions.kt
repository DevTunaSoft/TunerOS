package com.devtunasoft.tuneros.qa27

/**
 * QA-27 Android-free mirror of the bounded EPG colour token assertions.
 * Compiles on the JVM and into the instrumented APK with no test dependency.
 */
object Qa27Assertions {
    private val tokens = arrayOf(
        "TIMELINE_BACKGROUND" to "00000000",
        "TIMELINE_BORDER" to "FF344963",
        "TIMELINE_TEXT" to "FFFFFFFF",
        "TIMELINE_NOW_MARKER" to "FFFF7440",
        "TIMELINE_CELL_NORMAL" to "FF1C3048",
        "TIMELINE_CELL_EMPTY" to "FF142030",
        "TIMELINE_CELL_FOCUSED" to "FF1F67BE",
    )
    private val fills = arrayOf("TIMELINE_CELL_NORMAL", "TIMELINE_CELL_EMPTY", "TIMELINE_CELL_FOCUSED")

    private fun ch(v: String, i: Int): Int = v.substring(i, i + 2).toInt(16)
    private fun lin(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    private fun lum(v: String): Double =
        0.2126 * lin(ch(v, 2)) + 0.7152 * lin(ch(v, 4)) + 0.0722 * lin(ch(v, 6))

    private fun contrast(a: String, b: String): Int {
        val hi = maxOf(lum(a), lum(b))
        val lo = minOf(lum(a), lum(b))
        return Math.floor(1000 * (hi + 0.05) / (lo + 0.05)).toInt()
    }

    private fun distance(a: String, b: String): Int {
        val r = ch(a, 2) - ch(b, 2)
        val g = ch(a, 4) - ch(b, 4)
        val bl = ch(a, 6) - ch(b, 6)
        return Math.round(Math.sqrt((r * r + g * g + bl * bl).toDouble())).toInt()
    }

    fun run(): List<Pair<String, Boolean>> {
        val map = tokens.toMap()
        val text = map.getValue("TIMELINE_TEXT")
        val complete = map.size == 7 && tokens.size == 7
        var hex = true
        for (v in map.values) hex = hex && v.length == 8 && v.all { it in '0'..'9' || it in 'A'..'F' }
        var alpha = map.getValue("TIMELINE_BACKGROUND") == "00000000"
        for ((role, value) in map) {
            if (role != "TIMELINE_BACKGROUND" && !value.startsWith("FF")) alpha = false
        }
        var contrastOk = true
        for (role in fills) if (contrast(text, map.getValue(role)) < 4500) contrastOk = false
        var distinct = true
        for (i in fills.indices) {
            for (j in i + 1 until fills.size) {
                if (distance(map.getValue(fills[i]), map.getValue(fills[j])) < 12) distinct = false
            }
        }
        var marker = true
        for (role in fills) if (distance(map.getValue("TIMELINE_NOW_MARKER"), map.getValue(role)) < 24) marker = false
        if (distance(map.getValue("TIMELINE_NOW_MARKER"), text) < 24) marker = false
        var border = distance(map.getValue("TIMELINE_BORDER"), "00000000") >= 16
        for (role in fills) if (distance(map.getValue("TIMELINE_BORDER"), map.getValue(role)) < 16) border = false
        return listOf(
            "MIRROR_COMPLETE" to complete,
            "MIRROR_ARGB" to hex,
            "MIRROR_ALPHA" to alpha,
            "MIRROR_CONTRAST" to contrastOk,
            "MIRROR_DISTINCTION" to distinct,
            "MIRROR_MARKER" to marker,
            "MIRROR_BORDER" to border,
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val results = run()
        for ((id, ok) in results) println("CHECK\t$id\t${if (ok) "PASS" else "FAIL"}")
        val pass = results.count { it.second }
        println("SUMMARY\tPASS=$pass\tFAILED=${results.size - pass}")
        if (pass != results.size) System.exit(1)
    }
}
