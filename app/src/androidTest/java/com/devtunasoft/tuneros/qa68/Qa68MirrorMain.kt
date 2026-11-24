package com.devtunasoft.tuneros.qa68

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * QA-68 Android-free mirror. The same byte generator and bounded truth-table checks
 * compile into the instrumented APK and run on the JVM before the AGP attempt.
 * Diagnostics carry check ids, counts and digests only.
 */
object Qa68MirrorMain {
    private const val CASES_DIGEST = "8d27f772577c2b1daf4ef06576934fc0c725c91738817880a8c859297347ffbc"
    private const val CORRUPT_DIGEST = "02c0f27c263caa915540b12d95ae0eea42a3aabb027a33ae4d9b2f31207eb298"
    private const val DIMENSIONS_DIGEST = "77ea4233210a2811b6458148e8ab6721aa1b4c22ebf19c8b5cd39b96ba5b7f56"
    private const val MANIFEST_DIGEST = "03f9125f17f1bf8efd92bf8f072521fc5443354f0a9b10bbbaca4bedd1243bc4"
    private const val AGGREGATE_DIGEST = "26c8e5b8947f3db4e232b199827abadd903f44a530427a7fa1f1e5e7b1400861"
    private const val CASE_COUNT = 64
    private const val FAMILY_COUNT = 8
    private const val CHAIN_CAP = 12
    private const val CAUSE_DEPTH = 8
    private const val SEED = 20261124

    private data class Expected(val status: String, val type: String, val transport: String)
    private data class Case(val id: String, val tokens: List<String>, val first: String?, val expected: Expected)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun derivedMarker(): String {
        var h = 2166136261L
        for (b in SEED.toString().toByteArray(StandardCharsets.US_ASCII)) {
            h = ((h xor (b.toLong() and 0xffL)) * 16777619L) and 0xffffffffL
        }
        return "QA68-" + java.lang.Long.toHexString(h).padStart(8, '0')
    }

    private fun sanitize(raw: String?): String? {
        val v = (raw ?: return null).substringBefore(';').trim().lowercase()
        if (v.length !in 3..64 || v.count { it == '/' } != 1) return null
        if (v.startsWith('/') || v.endsWith('/')) return null
        if (v.any { !(it in 'a'..'z' || it in '0'..'9' || it == '/' || it == '.' || it == '+' || it == '-') }) return null
        return v
    }

    private fun label(value: String): String? = when (value) {
        "DNS", "TIMEOUT", "REFUSED", "TLS" -> value
        else -> null
    }

    private fun expectedFor(tokens: List<String>, first: String?): Expected {
        var status: String? = null
        var type: String? = null
        var transport: String? = null
        for (index in 0 until minOf(tokens.size, CAUSE_DEPTH)) {
            val t = tokens[index]
            if (t.startsWith("I")) {
                if (status == null) status = t.substring(1)
            } else if (t.startsWith("M")) {
                if (type == null) type = sanitize(String(unhex(t.substring(1)) ?: ByteArray(0), StandardCharsets.UTF_8))
            } else {
                val l = label(t)
                if (l != null && transport == null) transport = l
            }
        }
        return Expected(status ?: "-", (sanitize(first) ?: type) ?: "-", transport ?: "-")
    }

    private fun makeCases(): List<Case> {
        val out = ArrayList<Case>(CASE_COUNT)
        val types = listOf("video/mp4", "application/dash+xml", "audio/mpeg", "VIDEO/MP4; codecs=avc1")
        val unsafe = listOf("not a type", "/leading", "trailing/", "a/b/c", "x\ny/z")
        for (i in 0 until CASE_COUNT) {
            val family = i / 8
            val variant = i % 8
            val tokens = ArrayList<String>()
            var firstResponse: String? = null
            when (family) {
                0 -> tokens += "I" + (400 + variant)
                1 -> { tokens += "I" + (500 + variant); tokens += "I" + (600 + variant) }
                2 -> tokens += "M" + hex(types[variant % types.size].toByteArray(StandardCharsets.UTF_8))
                3 -> {
                    tokens += "M" + hex(types[variant % types.size].toByteArray(StandardCharsets.UTF_8))
                    tokens += "M" + hex(types[(variant + 1) % types.size].toByteArray(StandardCharsets.UTF_8))
                }
                4 -> tokens += listOf("DNS", "TIMEOUT", "REFUSED", "TLS", "GENERIC")[variant % 5]
                5 -> {
                    tokens += "I" + (400 + variant)
                    tokens += "M" + hex(types[variant % types.size].toByteArray(StandardCharsets.UTF_8))
                    tokens += listOf("DNS", "TIMEOUT", "REFUSED", "TLS")[variant % 4]
                }
                6 -> {
                    repeat(CHAIN_CAP - 1) { tokens += "GENERIC" }
                    if (variant % 2 == 0) tokens += "I404"
                    else tokens += "M" + hex(types[0].toByteArray(StandardCharsets.UTF_8))
                }
                7 -> {
                    tokens += "M" + hex(types[variant % types.size].toByteArray(StandardCharsets.UTF_8))
                    firstResponse = if (variant % 2 == 0) types[(variant + 2) % types.size]
                    else unsafe[variant % unsafe.size]
                }
            }
            out += Case(
                "C" + (i + 1).toString().padStart(3, '0'), tokens, firstResponse,
                expectedFor(tokens, firstResponse),
            )
        }
        return out
    }

    private fun casesText(cases: List<Case>): String = buildString {
        append("id\tchain\tfirst_response\texpect_status\texpect_type\texpect_transport\n")
        for (c in cases) {
            append(c.id).append('\t').append(c.tokens.joinToString("|")).append('\t')
            append(if (c.first == null) "-" else hex(c.first.toByteArray(StandardCharsets.UTF_8))).append('\t')
            append(c.expected.status).append('\t').append(c.expected.type).append('\t')
            append(c.expected.transport).append('\n')
        }
    }

    private fun corruptText(): String = buildString {
        append("id\tcode\tpayload_hex\n")
        append("X01\tINVALID_STATUS\t").append(hex("Iabc".toByteArray(StandardCharsets.US_ASCII))).append('\n')
        append("X02\tHEX_INVALID\t").append(hex("Mzz".toByteArray(StandardCharsets.US_ASCII))).append('\n')
        append("X03\tUNKNOWN_TOKEN\t").append(hex("X99".toByteArray(StandardCharsets.US_ASCII))).append('\n')
        append("X04\tCHAIN_BOUNDS\t").append(hex(List(13) { "GENERIC" }.joinToString("|").toByteArray(StandardCharsets.US_ASCII))).append('\n')
        append("X05\tROW_BOUNDS\t").append(hex("a\tb\tc\td\te\tf\tg".toByteArray(StandardCharsets.US_ASCII))).append('\n')
        append("X06\tUTF8_INVALID\tff\n")
    }

    private fun contractText(): String = (
        "QA-68 scratch contract. Bounded playback-failure evidence extraction.\n" +
            "The host gate compiles the unmodified app source and calls evidenceOf directly.\n" +
            "All names and counters are synthetic scratch values. No provider address,\n" +
            "credential, personal identification number, protected-content key or recorded\n" +
            "media payload is represented here. Identifiers are invented for this gate.\n"
        )

    private fun dimensionsText(cases: ByteArray, corrupt: ByteArray): String = buildString {
        append("case_count\t").append(CASE_COUNT).append('\n')
        append("family_count\t").append(FAMILY_COUNT).append('\n')
        append("chain_cap\t").append(CHAIN_CAP).append('\n')
        append("cause_depth\t").append(CAUSE_DEPTH).append('\n')
        append("cases_digest\t").append(sha(cases)).append('\n')
        append("corrupt_digest\t").append(sha(corrupt)).append('\n')
    }

    private fun readmeText(): String = (
        "# QA-68 fixture\n\n" +
            "Synthetic, bounded scratch data for the QA-68 host gate. Regenerated byte-for-byte\n" +
            "from constants by `Qa68Gate generate`. The verifier never writes into this directory.\n" +
            "All identifiers are invented. No provider address, credential, personal identification\n" +
            "number, protected-content key or recording payload is stored here.\n"
        )

    private fun fixture(): Map<String, ByteArray> {
        val cases = casesText(makeCases()).toByteArray(StandardCharsets.UTF_8)
        val corrupt = corruptText().toByteArray(StandardCharsets.UTF_8)
        val dimensions = dimensionsText(cases, corrupt).toByteArray(StandardCharsets.UTF_8)
        val manifest = buildString {
            for (name in listOf("dimensions.tsv", "cases.tsv", "corrupt.tsv")) {
                val bytes = when (name) {
                    "dimensions.tsv" -> dimensions
                    "cases.tsv" -> cases
                    else -> corrupt
                }
                append(sha(bytes)).append('\t').append(name).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
        return linkedMapOf(
            ".gitattributes" to "* -text\n.gitattributes -text\n".toByteArray(StandardCharsets.UTF_8),
            "README.md" to readmeText().toByteArray(StandardCharsets.UTF_8),
            "contract.txt" to contractText().toByteArray(StandardCharsets.UTF_8),
            "dimensions.tsv" to dimensions,
            "cases.tsv" to cases,
            "corrupt.tsv" to corrupt,
            "sha256.txt" to manifest,
        )
    }

    private class Sink {
        val text = StringBuilder()
        var failed = 0
            private set
        var passed = 0
            private set

        fun check(code: String, ok: Boolean) {
            if (ok) {
                passed++
                text.append("CHECK\t").append(code).append("\tPASS\n")
            } else {
                failed++
                text.append("CHECK\t").append(code).append("\tFAIL\n")
            }
        }
    }

    fun run(): Pair<String, Boolean> {
        val sink = Sink()
        val files = fixture()
        sink.check("FIXTURE_FILES", files.size == 7)
        sink.check("CASES_DIGEST", sha(files.getValue("cases.tsv")) == CASES_DIGEST)
        sink.check("CORRUPT_DIGEST", sha(files.getValue("corrupt.tsv")) == CORRUPT_DIGEST)
        sink.check("DIMENSIONS_DIGEST", sha(files.getValue("dimensions.tsv")) == DIMENSIONS_DIGEST)
        sink.check("MANIFEST_DIGEST", sha(files.getValue("sha256.txt")) == MANIFEST_DIGEST)
        val aggregate = sha(files.values.fold(ByteArray(0)) { a, b -> a + b })
        sink.check("AGGREGATE_DIGEST", aggregate == AGGREGATE_DIGEST)

        val lines = String(files.getValue("cases.tsv"), StandardCharsets.UTF_8)
            .lineSequence().toList().dropLastWhile { it.isEmpty() }
        sink.check("HEADER_STRICT", lines.firstOrNull() == "id\tchain\tfirst_response\texpect_status\texpect_type\texpect_transport")
        sink.check("ROW_BOUNDS", lines.size == CASE_COUNT + 1)
        var truthOk = true
        var capOk = true
        var depthOk = true
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            if (p.size != 6) { truthOk = false; continue }
            val tokens = p[1].split('|')
            if (tokens.size > CHAIN_CAP) capOk = false
            val first = if (p[2] == "-") null else String(unhex(p[2]) ?: ByteArray(0), StandardCharsets.UTF_8)
            val expected = Expected(p[3], p[4], p[5])
            if (expectedFor(tokens, first) != expected) truthOk = false
            if (tokens.size == CHAIN_CAP && tokens.dropLast(1).all { it == "GENERIC" }) {
                val seen = tokens.take(CAUSE_DEPTH)
                if (seen.any { it.startsWith("I") || it.startsWith("M") }) depthOk = false
            }
        }
        sink.check("CONTRACT_TRUTH_TABLE", truthOk)
        sink.check("CAP_BINDING", capOk)
        sink.check("DEPTH_BOUND", depthOk)

        val scheme = ":" + "/" + "/"
        var privacy = true
        for ((_, bytes) in files) {
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.contains(scheme) || text.contains(derivedMarker())) privacy = false
        }
        sink.check("PRIVACY_SCAN", privacy)

        sink.text.append("SUMMARY\tpass=").append(sink.passed).append("\tfail=").append(sink.failed).append('\n')
        sink.text.append("VERDICT\t").append(if (sink.failed == 0) "PASS" else "FAIL").append('\n')
        return sink.text.toString() to (sink.failed == 0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = run()
        print(result.first)
        System.out.flush()
        exitProcess(if (result.second) 0 else 1)
    }
}
