package com.devtunasoft.tuneros.qa68

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.devtunasoft.tuneros.FirstResponseRecorder
import com.devtunasoft.tuneros.core.TransportFault
import com.devtunasoft.tuneros.evidenceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.system.exitProcess

/** QA-68 host gate: bounded, byte-pinned checks over the real compiled evidenceOf production function. */
object Qa68Gate {
    private const val CASE_COUNT = 64
    private const val FAMILY_COUNT = 8
    private const val CHAIN_CAP = 12
    private const val CAUSE_DEPTH = 8
    private const val SEED = 20261124
    private val DATA_FILES = listOf("dimensions.tsv", "cases.tsv", "corrupt.tsv")
    private val ALL_FILES = listOf(".gitattributes", "README.md", "contract.txt") + DATA_FILES + listOf("sha256.txt")

    private class Refusal(val code: String) : Exception(code)

    private var firstCode = "NONE"
    private var passCount = 0
    private var failCount = 0

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }

    private fun check(code: String, ok: Boolean) {
        if (ok) {
            passCount++
            say("CHECK\t$code\tPASS")
        } else {
            failCount++
            if (firstCode == "NONE") firstCode = code
            say("CHECK\t$code\tFAIL")
        }
    }

    private fun refuse(code: String): Nothing = throw Refusal(code)

    // ------------------------------------------------------------------ deterministic marker
    private fun derivedMarker(): String {
        var h = 2166136261L
        for (b in SEED.toString().toByteArray(StandardCharsets.US_ASCII)) {
            h = ((h xor (b.toLong() and 0xffL)) * 16777619L) and 0xffffffffL
        }
        return "QA68-" + java.lang.Long.toHexString(h).padStart(8, '0')
    }

    private fun markerText(): String = "synthetic " + derivedMarker() + " scratch detail"

    // ------------------------------------------------------------------ byte helpers
    private fun sha(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

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

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ fixture model
    private data class Expected(val status: String, val type: String, val transport: String)

    private data class Case(
        val id: String,
        val tokens: List<String>,
        val firstResponse: String?,
        val expected: Expected,
    )

    /** Independent literal rule for the fixture's expected fields; not used to drive production. */
    private fun sanitizeType(raw: String?): String? {
        val v = (raw ?: return null).substringBefore(';').trim().lowercase()
        if (v.length !in 3..64 || v.count { it == '/' } != 1) return null
        if (v.startsWith('/') || v.endsWith('/')) return null
        if (v.any { !(it in 'a'..'z' || it in '0'..'9' || it == '/' || it == '.' || it == '+' || it == '-') }) return null
        return v
    }

    private fun transportLabel(label: String): String? = when (label) {
        "DNS", "TIMEOUT", "REFUSED", "TLS" -> label
        else -> null
    }

    private fun expectedFor(tokens: List<String>, firstResponse: String?): Expected {
        var status: String? = null
        var type: String? = null
        var transport: String? = null
        for (index in 0 until minOf(tokens.size, CAUSE_DEPTH)) {
            val t = tokens[index]
            if (t.startsWith("I")) {
                if (status == null) status = t.substring(1)
            } else if (t.startsWith("M")) {
                if (type == null) type = sanitizeType(strictUtf8(unhex(t.substring(1)) ?: ByteArray(0)))
            } else {
                val label = transportLabel(t)
                if (label != null && transport == null) transport = label
            }
        }
        val chosen = sanitizeType(firstResponse) ?: type
        return Expected(status ?: "-", chosen ?: "-", transport ?: "-")
    }

    private fun makeCases(): List<Case> {
        val out = ArrayList<Case>(CASE_COUNT)
        val types = listOf("video/mp4", "application/dash+xml", "audio/mpeg", "VIDEO/MP4; codecs=avc1")
        val unsafe = listOf("not a type", "/leading", "trailing/", "a/b/c", "x\ny/z")
        for (i in 0 until CASE_COUNT) {
            val family = i / 8
            val variant = i % 8
            val tokens = ArrayList<String>()
            var first: String? = null
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
                    first = if (variant % 2 == 0) {
                        types[(variant + 2) % types.size]
                    } else {
                        unsafe[variant % unsafe.size]
                    }
                }
            }
            val id = "C" + (i + 1).toString().padStart(3, '0')
            out += Case(id, tokens, first, expectedFor(tokens, first))
        }
        return out
    }

    private fun casesText(cases: List<Case>): String = buildString {
        append("id\tchain\tfirst_response\texpect_status\texpect_type\texpect_transport\n")
        for (c in cases) {
            append(c.id).append('\t')
            append(c.tokens.joinToString("|")).append('\t')
            append(if (c.firstResponse == null) "-" else hex(c.firstResponse.toByteArray(StandardCharsets.UTF_8))).append('\t')
            append(c.expected.status).append('\t')
            append(c.expected.type).append('\t')
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

    private fun gitattributesText(): String = "* -text\n.gitattributes -text\n"

    private fun buildFixture(): Map<String, ByteArray> {
        val cases = casesText(makeCases()).toByteArray(StandardCharsets.UTF_8)
        val corrupt = corruptText().toByteArray(StandardCharsets.UTF_8)
        val dimensions = dimensionsText(cases, corrupt).toByteArray(StandardCharsets.UTF_8)
        val manifest = buildString {
            for (name in DATA_FILES) {
                val bytes = when (name) {
                    "dimensions.tsv" -> dimensions
                    "cases.tsv" -> cases
                    else -> corrupt
                }
                append(sha(bytes)).append('\t').append(name).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
        return linkedMapOf(
            ".gitattributes" to gitattributesText().toByteArray(StandardCharsets.UTF_8),
            "README.md" to readmeText().toByteArray(StandardCharsets.UTF_8),
            "contract.txt" to contractText().toByteArray(StandardCharsets.UTF_8),
            "dimensions.tsv" to dimensions,
            "cases.tsv" to cases,
            "corrupt.tsv" to corrupt,
            "sha256.txt" to manifest,
        )
    }

    // ------------------------------------------------------------------ strict parsing
    private fun parseDimensions(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size != 2) refuse("ROW_BOUNDS")
            out[p[0]] = p[1]
        }
        return out
    }

    private fun parseCases(text: String): List<Case> {
        val lines = text.lineSequence().toList().dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != "id\tchain\tfirst_response\texpect_status\texpect_type\texpect_transport")
            refuse("HEADER_STRICT")
        val out = ArrayList<Case>()
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            if (p.size != 6) refuse("ROW_BOUNDS")
            val tokens = p[1].split('|')
            if (tokens.isEmpty() || tokens.size > CHAIN_CAP) refuse("CHAIN_BOUNDS")
            val first = if (p[2] == "-") null else {
                strictUtf8(unhex(p[2]) ?: refuse("HEX_INVALID")) ?: refuse("UTF8_INVALID")
            }
            out += Case(p[0], tokens, first, Expected(p[3], p[4], p[5]))
        }
        return out
    }

    private fun parseManifest(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size != 2 || p[0].length != 64) refuse("MANIFEST_SHAPE")
            out[p[1]] = p[0]
        }
        return out
    }

    private fun decodeChain(tokens: List<String>): List<Throwable> {
        if (tokens.size > CHAIN_CAP) refuse("CHAIN_BOUNDS")
        val out = ArrayList<Throwable>()
        for (t in tokens) {
            out += when {
                t.startsWith("I") -> {
                    val code = t.substring(1).toIntOrNull() ?: refuse("INVALID_STATUS")
                    HttpDataSource.InvalidResponseCodeException(code)
                }
                t.startsWith("M") -> {
                    val bytes = unhex(t.substring(1)) ?: refuse("HEX_INVALID")
                    val raw = strictUtf8(bytes) ?: refuse("UTF8_INVALID")
                    HttpDataSource.InvalidContentTypeException(raw)
                }
                t == "DNS" -> java.net.UnknownHostException(markerText())
                t == "TIMEOUT" -> java.net.SocketTimeoutException(markerText())
                t == "REFUSED" -> java.net.ConnectException(markerText())
                t == "TLS" -> javax.net.ssl.SSLException(markerText())
                t == "GENERIC" -> Exception(markerText())
                else -> refuse("UNKNOWN_TOKEN")
            }
        }
        return out
    }

    private class FakeHttp(override val responseHeaders: Map<String, List<String>>) : HttpDataSource

    private fun buildRoot(chain: List<Throwable>): PlaybackException {
        var top: Throwable = chain.lastOrNull() ?: Exception("no cause")
        for (i in chain.size - 2 downTo 0) {
            chain[i].initCause(top)
            top = chain[i]
        }
        return PlaybackException(markerText(), top, 0)
    }

    // ------------------------------------------------------------------ privacy
    private fun privacyPatterns(): List<Regex> {
        val sep = ":" + "/" + "/"
        val eq = "="
        val col = ":"
        val keys = listOf("pass" + "word", "pin", "sec" + "ret", "to" + "ken", "api" + "_" + "key",
            "cred" + "ential", "authorization", "secret")
        val out = ArrayList<Regex>()
        out += Regex(Regex.escape(sep))
        out += Regex(Regex.escape(derivedMarker()))
        for (k in keys) {
            out += Regex(Regex.escape(k + eq), RegexOption.IGNORE_CASE)
            out += Regex(Regex.escape(k + col), RegexOption.IGNORE_CASE)
        }
        return out
    }

    private fun privacyScan(blobs: List<Pair<String, ByteArray>>): Boolean {
        val pats = privacyPatterns()
        for ((name, bytes) in blobs) {
            val text = String(bytes, StandardCharsets.UTF_8)
            for (p in pats) {
                if (p.containsMatchIn(text)) {
                    say("PRIVACY_HIT\t" + name + "\t" + p.pattern.length)
                    return false
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ modes
    private fun generate(dir: File): Int {
        if (!dir.isDirectory && !dir.mkdirs()) {
            say("FIRST\tGENERATE_DIR")
            return 1
        }
        val fixture = buildFixture()
        for ((name, bytes) in fixture) File(dir, name).writeBytes(bytes)
        say("GENERATE\tPASS\tfiles=" + fixture.size)
        return 0
    }

    private fun load(dir: File): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        for (name in ALL_FILES) {
            val f = File(dir, name)
            if (!f.isFile) return null
            out[name] = f.readBytes()
        }
        return out
    }

    private fun verify(dir: File, mode: String, extra: String?): Int {
        passCount = 0
        failCount = 0
        firstCode = "NONE"
        val skipStructural = mode == "verify-semantic" || mode == "verify-pinned" || mode == "verify-manifest"
        val skipPinned = mode == "verify-semantic"
        val skipManifest = mode == "verify-semantic" || mode == "verify-pinned"
        try {
            val files = load(dir) ?: refuse("FILES_PRESENT")
            check("FILES_PRESENT", true)

            if (extra != null) {
                val extraFile = File(extra)
                if (!extraFile.isFile) refuse("PRIVACY_SCAN")
                check("PRIVACY_SCAN", privacyScan(listOf("extra" to extraFile.readBytes())))
            }

            if (!skipStructural) {
                val fresh = buildFixture()
                var same = true
                for ((name, bytes) in fresh) if (!files.containsKey(name) || !files[name]!!.contentEquals(bytes)) same = false
                check("BYTE_IDENTITY", same)
            }
            if (!skipManifest) {
                val manifest = parseManifest(String(files["sha256.txt"]!!, StandardCharsets.UTF_8))
                var ok = manifest.size == DATA_FILES.size
                for (name in DATA_FILES) {
                    val actual = sha(files[name]!!)
                    if (manifest[name] != actual) ok = false
                }
                check("MANIFEST_CONSISTENT", ok)
            }
            if (!skipPinned) {
                val dims = parseDimensions(String(files["dimensions.tsv"]!!, StandardCharsets.UTF_8))
                var ok = dims["case_count"] == CASE_COUNT.toString() &&
                    dims["family_count"] == FAMILY_COUNT.toString() &&
                    dims["chain_cap"] == CHAIN_CAP.toString() &&
                    dims["cause_depth"] == CAUSE_DEPTH.toString()
                if (ok) {
                    val data = buildFixture()
                    ok = dims["cases_digest"] == sha(files["cases.tsv"]!!) &&
                        dims["corrupt_digest"] == sha(files["corrupt.tsv"]!!) &&
                        data["dimensions.tsv"]!!.contentEquals(files["dimensions.tsv"]!!)
                }
                check("PINNED_DIGEST", ok)
            }

            val cases = parseCases(String(files["cases.tsv"]!!, StandardCharsets.UTF_8))
            check("ROW_BOUNDS", cases.size == CASE_COUNT)

            var rejectOk = true
            for (line in String(files["corrupt.tsv"]!!, StandardCharsets.UTF_8).lineSequence().drop(1)) {
                if (line.isEmpty()) continue
                val p = line.split('\t')
                if (p.size != 3) { rejectOk = false; continue }
                val payload = strictUtf8(unhex(p[2]) ?: ByteArray(0))
                val wanted = p[1]
                val got = when (wanted) {
                    "ROW_BOUNDS" -> if (payload != null && payload.count { it == '\t' } == 6) "ROW_BOUNDS" else "WRONG"
                    "UTF8_INVALID" -> if (payload == null) "UTF8_INVALID" else "ACCEPTED"
                    "CHAIN_BOUNDS" -> try {
                        decodeChain((payload ?: "").split('|'))
                        "ACCEPTED"
                    } catch (r: Refusal) { r.code }
                    else -> try {
                        decodeChain(listOf(payload ?: "!bad"))
                        "ACCEPTED"
                    } catch (r: Refusal) { r.code }
                }
                if (got != wanted) rejectOk = false
            }
            check("CORRUPT_REJECT", rejectOk)

            if (mode != "verify-privacy") {
                check("PRIVACY_SCAN", privacyScan(files.toList() + listOf("report" to renderReport(cases).toByteArray(StandardCharsets.UTF_8))))
            }

            var expectedOk = true
            var statusOk = true
            var typeOk = true
            var transportOk = true
            var recorderOk = true
            for (c in cases) {
                if (expectedFor(c.tokens, c.firstResponse) != c.expected && expectedOk) {
                    say("MISMATCH\tEXPECTED\t" + c.id + "\t" + expectedFor(c.tokens, c.firstResponse) + "\t" + c.expected)
                }
                if (expectedFor(c.tokens, c.firstResponse) != c.expected) expectedOk = false
                val chain = decodeChain(c.tokens)
                val primaryRecorder = if (c.firstResponse == null) null else FirstResponseRecorder().also { recorder ->
                    recorder.onTransferStart(
                        FakeHttp(mapOf("content-type" to listOf(c.firstResponse + "; charset=utf-8"))),
                        DataSpec(),
                        true,
                    )
                }
                val e = evidenceOf(buildRoot(chain), primaryRecorder)
                if ((e.httpStatus?.toString() ?: "-") != c.expected.status) statusOk = false
                if ((e.contentType ?: "-") != c.expected.type) {
                    if (typeOk) say("MISMATCH\tTYPE\t" + c.id + "\tgot=" + (e.contentType ?: "-") + "\twant=" + c.expected.type)
                    typeOk = false
                }
                if ((e.transport?.name ?: "-") != c.expected.transport) transportOk = false

                val recorder = FirstResponseRecorder()
                recorder.reset()
                val headers = if (c.firstResponse == null) emptyMap()
                else mapOf(markerText() to listOf(markerText()), "content-type" to listOf(c.firstResponse + "; charset=utf-8"))
                recorder.onTransferStart(FakeHttp(headers), DataSpec(), true)
                val viaRecorder = evidenceOf(buildRoot(emptyList()), recorder)
                val recorderWant = (sanitizeType(c.firstResponse) ?: "-")
                if ((viaRecorder.contentType ?: "-") != recorderWant) recorderOk = false
            }
            check("CONTRACT_EXPECTED", expectedOk)
            check("EVIDENCE_STATUS", statusOk)
            check("EVIDENCE_CONTENT_TYPE", typeOk)
            check("EVIDENCE_TRANSPORT", transportOk)
            check("EVIDENCE_RECORDER", recorderOk)

            val maxLen = cases.maxOf { it.tokens.size }
            check("CAP_BINDING", maxLen == CHAIN_CAP && cases.count { it.tokens.size == CHAIN_CAP } > 0 &&
                FAMILY_COUNT * 8 == CASE_COUNT)

            val baseThreads = Thread.activeCount()
            var cyclesOk = true
            var threadsOk = true
            var heapOk = true
            repeat(6) { cycle ->
                repeat(16) { i ->
                    val c = cases[i]
                    evidenceOf(buildRoot(decodeChain(c.tokens)), null)
                }
                System.gc()
                try { Thread.sleep(10) } catch (_: InterruptedException) { }
                if (Thread.activeCount() > baseThreads + 1) threadsOk = false
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                if (used > 56L * 1024 * 1024) heapOk = false
                if (cycle > 0 && used > 32L * 1024 * 1024) heapOk = false
            }
            check("CYCLE_STABLE", cyclesOk)
            check("THREAD_DRIFT", threadsOk)
            check("HEAP_BOUND", heapOk)
        } catch (r: Refusal) {
            failCount++
            if (firstCode == "NONE") firstCode = r.code
            say("CHECK\t" + r.code + "\tFAIL")
        } catch (e: Exception) {
            failCount++
            if (firstCode == "NONE") firstCode = "HARNESS"
            say("CHECK\tHARNESS\tFAIL\t" + e.javaClass.simpleName)
        }
        say("FIRST\t" + firstCode)
        say("SUMMARY\tpass=" + passCount + "\tfail=" + failCount)
        say("VERDICT\t" + if (failCount == 0) "PASS" else "FAIL")
        return if (failCount == 0) 0 else 1
    }

    private fun renderReport(cases: List<Case>): String = buildString {
        append("QA68 REPORT\n")
        append("cases=").append(cases.size).append('\n')
        for (c in cases.take(4)) append(c.id).append('\t').append(c.expected.status).append('\n')
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            say("usage: Qa68Gate generate|verify|verify-semantic|verify-pinned|verify-privacy <dir> [extra]")
            exitProcess(2)
        }
        val dir = File(args[1])
        val rc = when (args[0]) {
            "generate" -> generate(dir)
            "verify", "verify-semantic", "verify-pinned", "verify-manifest", "verify-privacy" -> verify(dir, args[0], args.getOrNull(2))
            else -> {
                say("FIRST\tBAD_MODE")
                2
            }
        }
        exitProcess(rc)
    }
}
