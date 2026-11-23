package com.devtunasoft.tuneros.core
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Calls the real private resolveHttp on the pinned CORE-49 engine with synthetic canaries. */
object Qa32UriProbe {
    @JvmStatic fun main(args: Array<String>) {
        val exp = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }.associate { it[0] to it[1] }
        fun exp(key: String): String = exp[key] ?: throw IllegalStateException("missing-expectation:$key")
        val cases = File(args[1]).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }.associate { row -> row[0] to Pair(row[1], row[2]) }
        val fixed = File(args[2]).readText()
        val base = File(args[3]).readText()
        val canary = args[4]
        val scheme = listOf('h', 't', 't', 'p', 's').joinToString("")
        val generic = exp("core.generic.message")

        var checks = 0
        var passed = 0
        var failed = 0
        var first = "NONE"
        fun check(id: String, body: () -> Pair<Boolean, String>) {
            checks++
            val result = try { body() } catch (t: Throwable) { false to "exception=" + t.javaClass.simpleName }
            if (result.first) {
                passed++
                println("CHECK\tPASS\t$id\t${result.second}")
            } else {
                failed++
                if (first == "NONE") first = id
                println("CHECK\tFAIL\t$id\t${result.second}")
            }
        }
        fun count(text: String, needle: String): Int {
            var n = 0
            var i = text.indexOf(needle, 0)
            while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
            return n
        }
        fun prepare(raw: String): String = raw.replace("{SCHEME}", scheme)
            .replace("{CANARY}", canary).replace("\\u0007", 7.toChar().toString())
        fun causeOf(id: String): Throwable? {
            val pair = cases[id] ?: throw IllegalStateException("missing-case:$id")
            val method = HlsCaptureEngine::class.java.getDeclaredMethod(
                "resolveHttp", String::class.java, String::class.java)
            method.isAccessible = true
            return try {
                method.invoke(HlsCaptureEngine(), prepare(pair.first), prepare(pair.second))
                null
            } catch (e: InvocationTargetException) {
                e.targetException
            }
        }

        check("C_CANARY_ABSENT_RELATIVE") {
            val cause = causeOf("malformed_relative")
            val safe = cause is IllegalArgumentException && cause.message == generic &&
                !cause.toString().contains(canary)
            safe to "class=${cause?.javaClass?.simpleName ?: "none"} generic-message=${cause?.message == generic}"
        }
        check("C_CANARY_ABSENT_BASE") {
            val cause = causeOf("malformed_base")
            val safe = cause is IllegalArgumentException && cause.message == generic &&
                !cause.toString().contains(canary)
            safe to "class=${cause?.javaClass?.simpleName ?: "none"} generic-message=${cause?.message == generic}"
        }
        check("C_MESSAGE_NO_PLACEHOLDER") {
            (!generic.contains(canary) && !generic.contains("{CANARY}")) to "generic-length=${generic.length}"
        }
        check("C_SOURCE_GENERIC_CONST") {
            val needle = "private const val MALFORMED_RESOURCE = \"" + generic + "\""
            val n = count(fixed, needle)
            (n == exp("core.const.count").toInt()) to "count=$n expected=${exp("core.const.count")}"
        }
        check("C_SOURCE_TRY_CATCH") {
            val n = count(fixed, "catch (_: Exception)")
            (n == exp("core.catch.count").toInt()) to "count=$n expected=${exp("core.catch.count")}"
        }
        check("C_SOURCE_THROW_GENERIC") {
            val n = count(fixed, "throw IllegalArgumentException(MALFORMED_RESOURCE)")
            (n == exp("core.throw.generic.count").toInt()) to "count=$n expected=${exp("core.throw.generic.count")}"
        }
        check("C_BASE_NO_GUARD") {
            val n = count(base, "MALFORMED_RESOURCE")
            (n == exp("core.base.guard.count").toInt()) to "count=$n expected=${exp("core.base.guard.count")}"
        }
        println("PROBE_SUMMARY\tCORE49\tchecks=$checks\tpass=$passed\tfail=$failed\tfirst=$first")
    }
}
