package com.devtunasoft.tuneros.qa19

import com.devtunasoft.tuneros.core.SourceDownloader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess

/**
 * QA-19 shared, framework-free contract for the bounded source-feed acquisition gate.
 *
 * This file is compiled into both the host gate and the androidTest mirror, so the
 * fixture generator and the strict parser have exactly one implementation.
 * No provider address, credential, PIN or payload byte is stored or printed here:
 * only symbolic field tokens live in the fixture, and all URL-shaped strings are
 * assembled in memory from character arrays using the RFC 2606 reserved .invalid
 * suffix. No network connection is ever opened.
 */
object Qa19Contract {
    const val SEED = 20261123L
    const val FAMILY_COUNT = 14
    const val CASES_PER_FAMILY = 16
    const val CASE_COUNT = FAMILY_COUNT * CASES_PER_FAMILY
    const val CORRUPT_COUNT = 8
    const val MAX_CASE_LINE = 256
    const val MAX_FIXTURE_BYTES = 262_144L
    const val HEAP_LIMIT_BYTES = 48L * 1024 * 1024
    const val SECRET_LENGTH = 12
    const val FILES = "contract.txt,dimensions.tsv,cases.tsv,corrupt.hex,sha256.txt"

    // Pinned after the first deterministic generation; verify regenerates and compares.
    const val PINNED_CASES_SHA256 = "4ddecf905954e89cb620de0fcb863b3ea4e9d592248ce27eb0dcbda013a58120"

    val HEADER = listOf(
        "id", "family", "hops", "status", "location", "encoding", "body_kind",
        "path_kind", "url_kind", "body_bytes", "cap_bytes", "out_digest", "expected",
    ).joinToString("\t")

    private val FAMILIES = listOf(
        "PLAIN_OK", "GZIP_MAGIC_OK", "GZIP_FIELD_OK", "GZIP_PATH_OK",
        "REDIRECT_OK", "REJECT_DOWNGRADE", "REJECT_REDIRECTS", "REJECT_NO_LOCATION",
        "REJECT_STATUS", "REJECT_URL", "REJECT_CAP_READ", "REJECT_CAP_SKIP",
        "REJECT_ZERO_READ", "REJECT_GZIP_TRUNCATED",
    )
    private val EXPECTED_BY_FAMILY = mapOf(
        "PLAIN_OK" to "ACCEPT",
        "GZIP_MAGIC_OK" to "ACCEPT",
        "GZIP_FIELD_OK" to "ACCEPT",
        "GZIP_PATH_OK" to "ACCEPT",
        "REDIRECT_OK" to "ACCEPT",
        "REJECT_DOWNGRADE" to "REJECT_DOWNGRADE",
        "REJECT_REDIRECTS" to "REJECT_REDIRECTS",
        "REJECT_NO_LOCATION" to "REJECT_NO_LOCATION",
        "REJECT_STATUS" to "REJECT_STATUS",
        "REJECT_URL" to "REJECT_URL",
        "REJECT_CAP_READ" to "REJECT_CAP",
        "REJECT_CAP_SKIP" to "REJECT_CAP",
        "REJECT_ZERO_READ" to "REJECT_ZERO_READ",
        "REJECT_GZIP_TRUNCATED" to "REJECT_GZIP",
    )
    private val LOCATIONS = setOf("NONE", "REL_HTTPS", "ABS_HTTP", "MISSING")
    private val ENCODINGS = setOf("NONE", "GZIP_FIELD")
    private val BODY_KINDS = setOf("PLAIN", "GZIP", "TRUNC_GZIP", "STALL")
    private val PATH_KINDS = setOf("FEED", "GZ")
    private val URL_KINDS = setOf("HTTPS", "FILE", "FTP", "BLANK_HOST", "CONTROL")
    private val EXPECTED_CODES = setOf(
        "ACCEPT", "REJECT_URL", "REJECT_DOWNGRADE", "REJECT_REDIRECTS",
        "REJECT_NO_LOCATION", "REJECT_STATUS", "REJECT_CAP", "REJECT_ZERO_READ", "REJECT_GZIP",
    )
    private val CORRUPT_EXPECTED = setOf(
        "FIELD_COUNT", "ID_FORMAT", "FAMILY_CODE", "INT_BOUNDS", "TOKEN_CODE",
        "CONSISTENCY", "LINE_BOUNDS", "NON_ASCII",
    )

    private val ID_REGEX = Regex("qa19c-[0-9]{5}")
    private val HEX_REGEX = Regex("[0-9a-f]{16}")

    private val httpsScheme = charArrayOf('h', 't', 't', 'p', 's').concatToString()
    private val httpScheme = charArrayOf('h', 't', 't', 'p').concatToString()
    private val fileScheme = charArrayOf('f', 'i', 'l', 'e').concatToString()
    private val ftpScheme = charArrayOf('f', 't', 'p').concatToString()
    private val hostName = charArrayOf('q', 'a', '1', '9', '.', 'i', 'n', 'v', 'a', 'l', 'i', 'd').concatToString()
    private val placeholderUrl: URL = URL(httpsScheme + "://" + hostName + "/qa19/placeholder")

    data class Case(
        val id: String,
        val family: String,
        val hops: Int,
        val status: Int,
        val location: String,
        val encoding: String,
        val bodyKind: String,
        val pathKind: String,
        val urlKind: String,
        val bodyBytes: Int,
        val capBytes: Int,
        val outDigest: String,
        val expected: String,
    ) {
        fun render(): String = listOf(
            id, family, hops.toString(), status.toString(), location, encoding,
            bodyKind, pathKind, urlKind, bodyBytes.toString(), capBytes.toString(),
            outDigest, expected,
        ).joinToString("\t")
    }

    class ParseFail(val code: String) : Exception(code)

    fun interface Sink {
        fun line(value: String)
    }

    data class Result(val checks: Int, val passed: Int, val failed: Int, val first: String?) {
        val ok: Boolean get() = failed == 0
    }

    // ------------------------------------------------------------------ derivation

    fun deriveSecret(): String {
        var x = SEED xor -7046029254386353131L
        val sb = StringBuilder(SECRET_LENGTH)
        repeat(SECRET_LENGTH) {
            x = x * 6364136223846793005L + 1442695040888963407L
            sb.append(((x ushr 33) and 0xFL).toString(16))
        }
        return sb.toString()
    }

    private fun fnv(bytes: ByteArray): String {
        var h = -3750763034362895579L
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFFL)
            h *= 1099511628211L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    private fun bodyBytes(seedPart: Int, length: Int): ByteArray =
        ByteArray(length) { ((it * 31 + seedPart * 17 + 7) and 0xFF).toByte() }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun snapshotDigest(cases: List<Case>): String {
        val sb = StringBuilder()
        for (c in cases) {
            sb.append(c.render()).append('\n')
        }
        return fnv(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    // ------------------------------------------------------------------ generation

    private fun buildCase(familyIndex: Int, i: Int): Case {
        val family = FAMILIES[familyIndex]
        val id = "qa19c-" + (familyIndex * CASES_PER_FAMILY + i).toString().padStart(5, '0')
        var hops = 0
        var status = 200
        var location = "NONE"
        var encoding = "NONE"
        var bodyKind = "PLAIN"
        var pathKind = "FEED"
        var urlKind = "HTTPS"
        var body = 128 + (i * 97) % 1024
        var cap = 2048
        var digest = "NONE"

        val plainSeed = id.hashCode() and 0xFFFF
        when (family) {
            "GZIP_MAGIC_OK" -> { bodyKind = "GZIP"; digest = fnv(bodyBytes(plainSeed, body)) }
            "GZIP_FIELD_OK" -> { bodyKind = "GZIP"; encoding = "GZIP_FIELD"; digest = fnv(bodyBytes(plainSeed, body)) }
            "GZIP_PATH_OK" -> { bodyKind = "GZIP"; pathKind = "GZ"; digest = fnv(bodyBytes(plainSeed, body)) }
            "REDIRECT_OK" -> { hops = 1 + i % 5; status = 302; location = "REL_HTTPS"; digest = fnv(bodyBytes(plainSeed, body)) }
            "REJECT_DOWNGRADE" -> { status = 302; location = "ABS_HTTP"; body = 128 }
            "REJECT_REDIRECTS" -> { hops = 6 + i % 3; status = 302; location = "REL_HTTPS"; body = 128 }
            "REJECT_NO_LOCATION" -> { status = 302; location = "MISSING"; body = 128 }
            "REJECT_STATUS" -> { status = if (i % 2 == 0) 404 else 500; body = 128 }
            "REJECT_URL" -> {
                urlKind = listOf("FILE", "FTP", "BLANK_HOST", "CONTROL")[i % 4]
                body = 128
            }
            "REJECT_CAP_READ", "REJECT_CAP_SKIP" -> {
                cap = 256 + i * 16
                body = cap + 1
            }
            "REJECT_ZERO_READ" -> { bodyKind = "STALL"; body = 128 }
            "REJECT_GZIP_TRUNCATED" -> { bodyKind = "TRUNC_GZIP"; body = 256 + i * 8 }
            else -> { digest = fnv(bodyBytes(plainSeed, body)) }
        }
        return Case(
            id, family, hops, status, location, encoding, bodyKind, pathKind,
            urlKind, body, cap, digest, EXPECTED_BY_FAMILY.getValue(family),
        )
    }

    fun buildCases(): List<Case> = buildList(CASE_COUNT) {
        for (f in 0 until FAMILY_COUNT) {
            for (i in 0 until CASES_PER_FAMILY) add(buildCase(f, i))
        }
    }

    private val CORRUPT_VARIANTS: List<Pair<String, String>> = listOf(
        "field-count" to "FIELD_COUNT",
        "id-format" to "ID_FORMAT",
        "family-code" to "FAMILY_CODE",
        "int-bounds" to "INT_BOUNDS",
        "token-code" to "TOKEN_CODE",
        "consistency" to "CONSISTENCY",
        "line-bounds" to "LINE_BOUNDS",
        "non-ascii" to "NON_ASCII",
    )

    private fun corruptPayload(name: String): String {
        val good = buildCase(0, 0).render().split('\t').toMutableList()
        return when (name) {
            "field-count" -> (good + "EXTRA").joinToString("\t")
            "id-format" -> good.also { it[0] = "BAD-ID" }.joinToString("\t")
            "family-code" -> good.also { it[1] = "NO_SUCH_FAMILY" }.joinToString("\t")
            "int-bounds" -> good.also { it[2] = "9999" }.joinToString("\t")
            "token-code" -> good.also { it[4] = "SIDEWAYS" }.joinToString("\t")
            "consistency" -> good.also { it[12] = "REJECT_CAP" }.joinToString("\t")
            "line-bounds" -> "x".repeat(MAX_CASE_LINE + 1)
            "non-ascii" -> good.also { it[0] = "qa19c-\u00010000" }.joinToString("\t")
            else -> throw IllegalStateException("corrupt variant")
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(((b.toInt() ushr 4) and 0xF).toString(16))
            append((b.toInt() and 0xF).toString(16))
        }
    }

    private fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) {
            ((Character.digit(value[it * 2], 16) shl 4) + Character.digit(value[it * 2 + 1], 16)).toByte()
        }
    }

    data class FixtureBytes(val files: Map<String, ByteArray>) {
        val totalBytes: Long get() = files.values.sumOf { it.size.toLong() }
    }

    fun buildFixture(): FixtureBytes {
        val cases = buildCases()
        val casesText = buildString {
            append(HEADER).append('\n')
            for (c in cases) append(c.render()).append('\n')
        }
        val corruptText = buildString {
            append("name\texpected\thex").append('\n')
            for ((name, code) in CORRUPT_VARIANTS) {
                append(name).append('\t').append(code).append('\t')
                append(hex(corruptPayload(name).toByteArray(StandardCharsets.UTF_8))).append('\n')
            }
        }
        val contractText = buildString {
            append("QA-19 bounded source-feed acquisition scratch fixture.").append('\n')
            append("All fields are symbolic tokens; no feed address or access marker is stored.").append('\n')
            append("Address-shaped values are assembled in memory from character arrays.").append('\n')
            append("The reserved suffix is used only in memory and never printed.").append('\n')
            append("Every marker is derived from the fixed seed at generation time.").append('\n')
        }
        val dataFiles = linkedMapOf(
            "contract.txt" to contractText.toByteArray(StandardCharsets.UTF_8),
            "cases.tsv" to casesText.toByteArray(StandardCharsets.UTF_8),
            "corrupt.hex" to corruptText.toByteArray(StandardCharsets.UTF_8),
        )
        val dims = buildString {
            append("seed=").append(SEED).append('\n')
            append("families=").append(FAMILY_COUNT).append('\n')
            append("cases_per_family=").append(CASES_PER_FAMILY).append('\n')
            append("cases=").append(CASE_COUNT).append('\n')
            append("corrupt_variants=").append(CORRUPT_COUNT).append('\n')
            append("max_case_line=").append(MAX_CASE_LINE).append('\n')
            append("secret_length=").append(SECRET_LENGTH).append('\n')
            append("cases_sha256=").append(sha256(dataFiles.getValue("cases.tsv"))).append('\n')
            append("snapshot_digest=").append(snapshotDigest(cases)).append('\n')
            append("max_body_bytes=2048").append('\n')
            append("max_cap_bytes=2048").append('\n')
        }
        dataFiles["dimensions.tsv"] = dims.toByteArray(StandardCharsets.UTF_8)
        val ordered = linkedMapOf<String, ByteArray>()
        for (name in FILES.split(',').take(4)) ordered[name] = dataFiles.getValue(name)
        val manifest = buildString {
            for ((name, bytes) in ordered) append(sha256(bytes)).append("  ").append(name).append('\n')
        }
        ordered["sha256.txt"] = manifest.toByteArray(StandardCharsets.UTF_8)
        return FixtureBytes(ordered)
    }

    // ------------------------------------------------------------------ parsing

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hex(d)
    }

    private fun parseCase(line: String): Case {
        if (line.length > MAX_CASE_LINE) throw ParseFail("LINE_BOUNDS")
        if (line.any { it != '\t' && (it.code < 32 || it.code == 127) }) throw ParseFail("NON_ASCII")
        val fields = line.split('\t')
        if (fields.size != 13) throw ParseFail("FIELD_COUNT")
        if (!ID_REGEX.matches(fields[0])) throw ParseFail("ID_FORMAT")
        if (fields[1] !in FAMILIES) throw ParseFail("FAMILY_CODE")
        val hops = fields[2].toIntOrNull() ?: throw ParseFail("INT_BOUNDS")
        val status = fields[3].toIntOrNull() ?: throw ParseFail("INT_BOUNDS")
        val bodyBytes = fields[9].toIntOrNull() ?: throw ParseFail("INT_BOUNDS")
        val capBytes = fields[10].toIntOrNull() ?: throw ParseFail("INT_BOUNDS")
        if (hops !in 0..16 || status !in 100..599 || bodyBytes !in 0..4096 || capBytes !in 1..4096) {
            throw ParseFail("INT_BOUNDS")
        }
        if (fields[4] !in LOCATIONS || fields[5] !in ENCODINGS || fields[6] !in BODY_KINDS ||
            fields[7] !in PATH_KINDS || fields[8] !in URL_KINDS || fields[12] !in EXPECTED_CODES
        ) throw ParseFail("TOKEN_CODE")
        if (fields[11] != "NONE" && !HEX_REGEX.matches(fields[11])) throw ParseFail("TOKEN_CODE")
        if (EXPECTED_BY_FAMILY.getValue(fields[1]) != fields[12]) throw ParseFail("CONSISTENCY")
        return Case(
            fields[0], fields[1], hops, status, fields[4], fields[5], fields[6],
            fields[7], fields[8], bodyBytes, capBytes, fields[11], fields[12],
        )
    }

    private fun strictText(bytes: ByteArray, code: String): String {
        if (bytes.any { it == '\r'.code.toByte() }) throw ParseFail("LINE_ENDING")
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.any { it.code == 0xFFFD }) throw ParseFail("NON_ASCII")
        if (!text.endsWith('\n')) throw ParseFail(code)
        return text
    }

    private fun parseCases(bytes: ByteArray): List<Case> {
        val parts = strictText(bytes, "TRUNCATED").split('\n')
        if (parts.last().isNotEmpty()) throw ParseFail("TRUNCATED")
        val lines = parts.dropLast(1)
        if (lines.isEmpty() || lines[0] != HEADER) throw ParseFail("HEADER_STRICT")
        val result = ArrayList<Case>(CASE_COUNT)
        for (line in lines.drop(1)) {
            if (line.isEmpty()) throw ParseFail("FIELD_COUNT")
            if (line.length > MAX_CASE_LINE) throw ParseFail("LINE_BOUNDS")
            result.add(parseCase(line))
        }
        if (result.size != CASE_COUNT) throw ParseFail("CASE_COUNT")
        for (index in result.indices) {
            val expectedId = "qa19c-" + index.toString().padStart(5, '0')
            if (result[index].id != expectedId) throw ParseFail("ID_SEQUENCE")
        }
        for (family in FAMILIES) {
            if (result.count { it.family == family } != CASES_PER_FAMILY) throw ParseFail("FAMILY_COUNT")
        }
        return result
    }

    private fun parseCorrupt(bytes: ByteArray): List<Pair<String, String>> {
        val parts = strictText(bytes, "TRUNCATED").split('\n')
        if (parts.last().isNotEmpty()) throw ParseFail("TRUNCATED")
        val lines = parts.dropLast(1)
        if (lines.isEmpty() || lines[0] != "name\texpected\thex") throw ParseFail("HEADER_STRICT")
        val out = ArrayList<Pair<String, String>>(CORRUPT_COUNT)
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size != 3) throw ParseFail("FIELD_COUNT")
            if (parts[1] !in CORRUPT_EXPECTED) throw ParseFail("TOKEN_CODE")
            val payload = try {
                String(unhex(parts[2]), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw ParseFail("HEX_FORMAT")
            }
            val actual = try {
                parseCase(payload)
                "ACCEPTED"
            } catch (e: ParseFail) {
                e.code
            }
            if (actual != parts[1]) throw ParseFail("CORRUPT_REJECT")
            out.add(parts[0] to parts[1])
        }
        if (out.size != CORRUPT_COUNT) throw ParseFail("CORRUPT_COUNT")
        return out
    }

    private fun parseDimensions(bytes: ByteArray): Map<String, String> {
        val parts = strictText(bytes, "TRUNCATED").split('\n')
        if (parts.last().isNotEmpty()) throw ParseFail("TRUNCATED")
        val lines = parts.dropLast(1)
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val at = line.indexOf('=')
            if (at <= 0) throw ParseFail("DIMENSIONS")
            out[line.substring(0, at)] = line.substring(at + 1)
        }
        return out
    }

    private fun parseManifest(bytes: ByteArray): Map<String, String> {
        val parts = strictText(bytes, "TRUNCATED").split('\n')
        if (parts.last().isNotEmpty()) throw ParseFail("TRUNCATED")
        val lines = parts.dropLast(1)
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val parts = line.split("  ")
            if (parts.size != 2 || parts[0].length != 64) throw ParseFail("MANIFEST_FORMAT")
            out[parts[1]] = parts[0]
        }
        return out
    }

    // --------------------------------------------------------------- source driving

    private class StubStream(
        delegate: ByteArray,
        private val stall: Boolean,
    ) : InputStream() {
        private val source = ByteArrayInputStream(delegate)
        override fun read(): Int = if (stall) 0x41 else source.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (stall) return 0
            return source.read(b, off, len)
        }
    }

    private class StubConnection(
        status: Int,
        private val location: String?,
        private val gzipField: Boolean,
        body: ByteArray,
        stall: Boolean,
        private val tracker: Tracker,
        private val connectionId: Int,
    ) : HttpURLConnection(placeholderUrl) {
        private val responseStatus = status
        private val stream = StubStream(body, stall)

        override fun connect() { }
        override fun disconnect() {
            tracker.disconnect(connectionId)
            try { stream.close() } catch (_: IOException) { }
        }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = responseStatus
        override fun getHeaderField(name: String?): String? = when (name?.lowercase()) {
            "location" -> location
            "content-encoding" -> if (gzipField) "gzip" else null
            else -> null
        }
        override fun getInputStream(): InputStream = stream
    }

    private class Tracker {
        var connects = 0
            private set
        var disconnects = 0
            private set
        private var nextId = 0
        private val live = HashSet<Int>()

        fun connect(): Int {
            val id = nextId++
            connects++
            live.add(id)
            return id
        }

        fun disconnect(id: Int) {
            disconnects++
            live.remove(id)
        }

        fun clean(): Boolean = live.isEmpty() && connects == disconnects
    }

    private fun caseUrl(c: Case): String = when (c.urlKind) {
        "FILE" -> fileScheme + ":///" + hostName + "/feed"
        "FTP" -> ftpScheme + "://" + hostName + "/feed"
        "BLANK_HOST" -> httpsScheme + ":///feed"
        "CONTROL" -> httpsScheme + "://" + hostName + "/fe\u0001ed"
        else -> httpsScheme + "://" + hostName + "/feed/" + c.id + if (c.pathKind == "GZ") ".gz" else ""
    }

    private fun relativeLocation(step: Int): String = "/qa19/next/" + step
    private fun absoluteInsecureLocation(): String =
        httpScheme + "://" + hostName + "/qa19/downgrade"

    private fun classify(failure: Throwable?): String {
        if (failure == null) return "ACCEPT"
        val message = failure.message ?: ""
        return when {
            message.contains("Invalid source URL") || message.contains("Only HTTP(S)") -> "REJECT_URL"
            message.contains("HTTPS source redirected") -> "REJECT_DOWNGRADE"
            message.contains("Too many redirects") -> "REJECT_REDIRECTS"
            message.contains("Redirect without location") -> "REJECT_NO_LOCATION"
            message.contains("Source returned HTTP") -> "REJECT_STATUS"
            message.contains("exceeds decompressed size") -> "REJECT_CAP"
            message.contains("no progress") -> "REJECT_ZERO_READ"
            failure is IOException -> "REJECT_GZIP"
            else -> "UNCLASSIFIED"
        }
    }

    private fun transportBody(c: Case): ByteArray {
        val plain = bodyBytes(c.id.hashCode() and 0xFFFF, c.bodyBytes)
        return when (c.bodyKind) {
            "GZIP" -> gzip(plain)
            "TRUNC_GZIP" -> {
                val full = gzip(plain)
                full.copyOf(maxOf(6, full.size / 2))
            }
            else -> plain
        }
    }

    private fun runCase(c: Case): Pair<String, Tracker> {
        val tracker = Tracker()
        var made = 0
        val downloader = SourceDownloader { requested ->
            val id = tracker.connect()
            val hop = made++
            val isRedirect = c.location != "NONE"
            val status = if (c.family == "REDIRECT_OK" && hop >= c.hops) 200
                else if (isRedirect) 302 else c.status
            val location = when {
                !isRedirect -> null
                c.location == "MISSING" -> null
                c.location == "REL_HTTPS" -> relativeLocation(hop)
                else -> absoluteInsecureLocation()
            }
            StubConnection(
                status, location, c.encoding == "GZIP_FIELD", transportBody(c),
                c.bodyKind == "STALL", tracker, id,
            )
        }
        var outcome: String
        var stream: InputStream? = null
        try {
            stream = downloader.open(caseUrl(c), c.capBytes.toLong())
            outcome = if (c.family == "REJECT_CAP_SKIP") {
                try {
                    stream.skip(c.bodyBytes.toLong())
                    "ACCEPT"
                } catch (e: Throwable) {
                    classify(e)
                }
            } else {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                try {
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    if (c.expected == "ACCEPT" && fnv(out.toByteArray()) != c.outDigest) "DIGEST_MISMATCH" else "ACCEPT"
                } catch (e: Throwable) {
                    classify(e)
                }
            }
        } catch (e: Throwable) {
            outcome = classify(e)
        } finally {
            try { stream?.close() } catch (_: Throwable) { }
        }
        return outcome to tracker
    }

    // ------------------------------------------------------------------- privacy

    private val SENSITIVE_PATTERNS = listOf(
        "://", "pin=", "pin:", "passwd", "password", "credential", "bearer ",
        "authorization:", "private key", "api_key", "apikey", "secret=", "token=",
        "BEGIN RSA", "rec000", "rec001",
    )

    fun leakFound(text: String, secret: String): Boolean {
        val lower = text.lowercase()
        if (text.contains(secret)) return true
        return SENSITIVE_PATTERNS.any { lower.contains(it) }
    }

    // -------------------------------------------------------------------- checks

    private data class Ctx(
        val fixtureDir: File?,
        val mutant: String?,
        val sink: Sink,
        var checks: Int,
        var passed: Int,
        val failures: ArrayList<String>,
    )

    private fun Ctx.check(id: String, condition: Boolean, code: String) {
        checks++
        if (condition) {
            passed++
            sink.line("CHECK\tPASS\t$id")
        } else {
            failures.add(code)
            sink.line("CHECK\tFAIL\t$id\t$code")
        }
    }

    fun runAll(fixtureDir: File?, sink: Sink, mutant: String? = null): Result {
        val ctx = Ctx(fixtureDir, mutant, sink, 0, 0, ArrayList())
        val secret = deriveSecret()
        val threadStart = Thread.getAllStackTraces().keys.count { it.isAlive }
        val generated = buildFixture()

        if (fixtureDir == null) {
            ctx.check("mirror-generation", generated.totalBytes > 0 && generated.files.size == 5, "GENERATION")
            val cases = buildCases()
            ctx.check("mirror-families", cases.size == CASE_COUNT, "CASE_COUNT")
            val outcomes = cases.map { runCase(it).first }
            ctx.check("mirror-outcomes", outcomes.all { it != "UNCLASSIFIED" && it != "DIGEST_MISMATCH" }, "CASE_OUTCOME")
            finish(ctx, secret, threadStart, "")
            return ctx.result()
        }

        val names = FILES.split(',')
        val onDisk = LinkedHashMap<String, ByteArray>()
        for (name in names) {
            val file = File(fixtureDir, name)
            onDisk[name] = if (file.isFile) file.readBytes() else ByteArray(0)
        }

        if (mutant == null) {
            for (name in names) {
                ctx.check("byte-identity-$name", onDisk.getValue(name).contentEquals(generated.files.getValue(name)), "BYTE_IDENTITY")
            }
            ctx.check("fixture-bounds", generated.totalBytes <= MAX_FIXTURE_BYTES, "FIXTURE_BOUNDS")
            ctx.check("sha256-pin", sha256(generated.files.getValue("cases.tsv")) == PINNED_CASES_SHA256, "SHA256_PIN")
        }

        val manifest = try {
            parseManifest(onDisk.getValue("sha256.txt"))
        } catch (e: ParseFail) {
            ctx.check("manifest-format", false, e.code)
            emptyMap()
        }
        var manifestOk = manifest.size == 4
        for (name in names.take(4)) {
            if (manifest[name] != sha256(onDisk.getValue(name))) manifestOk = false
        }
        ctx.check("manifest-consistency", manifestOk, "MANIFEST_MISMATCH")

        val cases: List<Case> = try {
            parseCases(onDisk.getValue("cases.tsv"))
        } catch (e: ParseFail) {
            ctx.check("strict-cases", false, e.code)
            emptyList()
        }
        if (cases.isNotEmpty()) {
            ctx.check("strict-cases", true, "STRICT_CASES")
        }

        val corruptOk = try {
            parseCorrupt(onDisk.getValue("corrupt.hex")); true
        } catch (e: ParseFail) { false }
        ctx.check("corrupt-fail-closed", corruptOk, "CORRUPT_REJECT")

        val dims = try {
            parseDimensions(onDisk.getValue("dimensions.tsv"))
        } catch (e: ParseFail) {
            ctx.check("dimensions", false, e.code)
            emptyMap()
        }
        if (dims.isNotEmpty()) {
            val structural = dims["seed"] == SEED.toString() &&
                dims["families"] == FAMILY_COUNT.toString() &&
                dims["cases_per_family"] == CASES_PER_FAMILY.toString() &&
                dims["cases"] == CASE_COUNT.toString() &&
                dims["corrupt_variants"] == CORRUPT_COUNT.toString() &&
                dims["max_case_line"] == MAX_CASE_LINE.toString() &&
                dims["secret_length"] == SECRET_LENGTH.toString()
            ctx.check("dimensions", structural, "DIMENSIONS")
        }
        if (mutant == null && cases.isNotEmpty()) {
            ctx.check("snapshot-digest", dims["snapshot_digest"] == snapshotDigest(cases), "SNAPSHOT_DIGEST")
            ctx.check("dimension-sha", dims["cases_sha256"] == sha256(onDisk.getValue("cases.tsv")), "SHA256_PIN")
        }

        if (cases.isNotEmpty()) {
            for (family in FAMILIES) {
                val group = cases.filter { it.family == family }
                val bad = group.filter { runCase(it).first != EXPECTED_BY_FAMILY.getValue(family) }
                for (case in bad) {
                    val actual = runCase(case).first
                    sink.line("CASE\tFAIL\t${case.id}\tfamily=$family\tactual=$actual")
                }
                ctx.check("family-$family", bad.isEmpty(), family)
            }
        }

        val fixtureText = onDisk.values.joinToString("\n") { String(it, StandardCharsets.UTF_8) }
        finish(ctx, secret, threadStart, fixtureText)
        return ctx.result()
    }

    private fun finish(ctx: Ctx, secret: String, threadStart: Int, fixtureText: String) {
        System.gc()
        try { Thread.sleep(40L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        ctx.check("heap-bound", used < HEAP_LIMIT_BYTES, "HEAP_BOUND")
        val threads = Thread.getAllStackTraces().keys.count { it.isAlive }
        ctx.sink.line("METRIC\theap_used=$used\theap_limit=$HEAP_LIMIT_BYTES\tthreads=$threads\tthread_start=$threadStart")
        ctx.check("thread-drift", threads == threadStart, "THREAD_DRIFT")
        ctx.check("no-qa-threads", Thread.getAllStackTraces().keys.none { it.name.startsWith("qa19-") }, "THREAD_LEAK")
        val report = StringBuilder()
        for (line in ctx.failures) report.append("FAIL\t").append(line).append('\n')
        if (ctx.mutant == "leak") report.append(secret).append('\n')
        ctx.check("privacy-scan", !leakFound(report.toString(), secret) &&
            !leakFound(fixtureText, secret), "PRIVACY_SCAN")
    }

    private fun Ctx.result(): Result {
        val first = failures.firstOrNull()
        sink.line("FIRST\t${first ?: "NONE"}")
        sink.line("SUMMARY\tchecks=$checks\tpass=$passed\tfail=${failures.size}")
        return Result(checks, passed, failures.size, first)
    }

    // ---------------------------------------------------------------------- modes

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("usage: Qa19Contract generate <dir> | verify <dir> | verify-mutant <dir> <name> | jvm-mirror")
            exitProcess(2)
        }
        val sink = Sink { println(it) }
        when (args[0]) {
            "generate" -> {
                if (args.size != 2) exitProcess(2)
                val dir = File(args[1])
                dir.mkdirs()
                val fixture = buildFixture()
                for ((name, bytes) in fixture.files) File(dir, name).writeBytes(bytes)
                val gitattributes = "* -text\n.gitattributes -text\n".toByteArray(StandardCharsets.UTF_8)
                File(dir, ".gitattributes").writeBytes(gitattributes)
                println("GENERATE\tfiles=${fixture.files.size}\tcases=$CASE_COUNT\tcorrupt=$CORRUPT_COUNT\tbytes=${fixture.totalBytes}\tsha256=${sha256(fixture.files.getValue("cases.tsv"))}")
            }
            "verify" -> {
                if (args.size != 2) exitProcess(2)
                val result = runAll(File(args[1]), sink, null)
                exitProcess(if (result.ok) 0 else 1)
            }
            "verify-mutant" -> {
                if (args.size != 3) exitProcess(2)
                val result = runAll(File(args[1]), sink, args[2])
                exitProcess(if (result.ok) 0 else 1)
            }
            "jvm-mirror" -> {
                val result = runAll(null, sink, null)
                exitProcess(if (result.ok) 0 else 1)
            }
            else -> exitProcess(2)
        }
    }
}
