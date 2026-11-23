package com.devtunasoft.tuneros.qa22

import com.devtunasoft.tuneros.core.HlsAes128
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/** QA-22 host gate: bounded HLS AES-128 admission and streamed segment decryption. */
object Qa22Gate {
    const val SEED = 20261123L
    private const val LARGE_PLAIN_BYTES = 262_160
    private const val CHUNK_BYTES = 64 * 1024
    private val ACCEPT_KINDS = setOf("VALID_ONE_SHOT", "VALID_CHUNK1", "EMPTY")
    private val REJECT_CODES = mapOf(
        "TRUNCATED" to "TRUNCATED_REJECT",
        "CORRUPT_PAD" to "CORRUPT_PAD_REJECT",
        "WRONG_KEY" to "WRONG_KEY_REJECT",
    )
    private val DATA_FILES = listOf("contract.txt", "iv-cases.tsv", "segments.tsv", "large.ct")
    private val ALL_FILES = listOf(
        "contract.txt", "dimensions.tsv", "iv-cases.tsv", "segments.tsv", "large.ct", "sha256.txt",
    )
    private val DIMENSION_KEYS = listOf(
        "fixture_version", "iv_cases", "segments", "large_cipher_bytes", "large_plain_sha256", "manifest_sha256",
    )
    private val IV_HEADER = "id\tsequence\texplicit\texpect"
    private val SEGMENT_HEADER = "id\tkind\tcipherHex\tchunk\texpect\tplainSha256"
    private val DIMENSION_HEADER = "field\tvalue"
    private val urlPattern = Regex("(?i)(https?|ftp|file)://")
    private val secretPattern = Regex(
        "(?i)(password|passwd|secret|credential|personal[_-]?code|access[_-]?marker|aes[_-]?key|drm[_-]?key)\\s*[:=]",
    )
    private val hex = HexFormat.of()

    class GateFailure(val code: String) : Exception(code)

    private fun bad(code: String): Nothing = throw GateFailure(code)

    private fun ByteArray.toHex(): String = hex.formatHex(this)

    private fun fromHex(text: String): ByteArray = hex.parseHex(text)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun derivedKey(): ByteArray = ByteArray(16) { index ->
        ((SEED ushr (8 * (index % 4))) xor (index * 37 + 11).toLong()).toByte()
    }

    private fun wrongKey(): ByteArray = ByteArray(16) { (derivedKey()[it].toInt() xor 0x5A).toByte() }

    private fun derivedIv(): ByteArray = ByteArray(16) { index ->
        ((SEED ushr (8 * (index % 4))) xor (index * 53 + 29).toLong()).toByte()
    }

    private fun derivedPayloadSentinel(): String = "qa22payload" + Integer.toHexString((SEED xor 0x5A5A5A5AL).toInt())

    private fun patternBytes(length: Int): ByteArray = ByteArray(length) { index ->
        ((index * 31 + (SEED xor (index.toLong() shl 7))).toInt() and 0xFF).toByte()
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    private fun rejects(cipherText: ByteArray, key: ByteArray, iv: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(cipherText)
        false
    } catch (refused: Exception) {
        true
    }

    private fun corruptingByte(base: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val damaged = base.copyOf()
        for (mask in 1..255) {
            damaged[damaged.lastIndex] = (base[base.lastIndex].toInt() xor mask).toByte()
            if (rejects(damaged, key, iv)) return damaged
        }
        bad("CORRUPT_VECTOR_UNAVAILABLE")
    }

    private data class IvCase(val id: String, val sequence: Long, val explicit: String?, val expect: String)

    private data class SegmentCase(
        val id: String, val kind: String, val cipherHex: String, val chunk: Int,
        val expect: String, val plainSha: String,
    )

    private class Built(val files: Map<String, ByteArray>)

    private fun build(): Built {
        val key = derivedKey()
        val iv = derivedIv()
        val contract = StringBuilder()
            .append("QA-22 scratch fixture for bounded HLS segment admission.\n")
            .append("Every address-like value is symbolic; no feed address, access marker or personal code is stored.\n")
            .append("Ciphertext is generated from synthetic byte patterns; no private media text is present.\n")
            .append("Rows carry ids, digests and outcomes only. Fixture bytes are LF encoded.\n")
            .toString().toByteArray(StandardCharsets.UTF_8)

        val ivText = StringBuilder().append(IV_HEADER).append('\n')
        fun ivRow(id: String, sequence: Long, explicit: String?, expect: String) {
            ivText.append(id).append('\t').append(sequence).append('\t').append(explicit ?: "NONE")
                .append('\t').append(expect).append('\n')
        }
        val goodIv = iv.toHex()
        ivRow("SEQ_ZERO", 0L, null, "ACCEPT")
        ivRow("SEQ_258", 258L, null, "ACCEPT")
        ivRow("SEQ_HIGH", 0x0102030405060708L, null, "ACCEPT")
        ivRow("HEX_LOWER", 99L, "0x$goodIv", "ACCEPT")
        ivRow("HEX_UPPER", 100L, "0X${goodIv.uppercase()}", "ACCEPT")
        ivRow("HEX_MIXED", 101L, "0x${goodIv.take(16)}${goodIv.drop(16).uppercase()}", "ACCEPT")
        ivRow("NO_PREFIX", 102L, goodIv, "REJECT")
        ivRow("SHORT_HEX", 103L, "0x${goodIv.dropLast(1)}", "REJECT")
        ivRow("LONG_HEX", 104L, "0x${goodIv}00", "REJECT")
        ivRow("NON_HEX", 105L, "0xg${goodIv.drop(1)}", "REJECT")
        ivRow("EMPTY_TEXT", 106L, "", "REJECT")
        ivRow("PREFIX_ONLY", 107L, "0x", "REJECT")

        val plain = patternBytes(4_097)
        val cipherText = encrypt(plain, key, iv)
        val emptyPlain = ByteArray(0)
        val emptyCipher = encrypt(emptyPlain, key, iv)
        val corrupt = corruptingByte(cipherText, key, iv)
        val segmentText = StringBuilder().append(SEGMENT_HEADER).append('\n')
        fun segmentRow(id: String, kind: String, ct: ByteArray, chunk: Int, expect: String, plainDigest: String) {
            segmentText.append(id).append('\t').append(kind).append('\t').append(ct.toHex())
                .append('\t').append(chunk).append('\t').append(expect).append('\t').append(plainDigest).append('\n')
        }
        val plainDigest = digest(plain)
        segmentRow("VALID_ONE_SHOT", "VALID_ONE_SHOT", cipherText, 0, "ACCEPT", plainDigest)
        segmentRow("VALID_CHUNK1", "VALID_CHUNK1", cipherText, 1, "ACCEPT", plainDigest)
        segmentRow("EMPTY", "EMPTY", emptyCipher, 16, "ACCEPT", digest(emptyPlain))
        segmentRow("TRUNCATED", "TRUNCATED", cipherText.copyOf(cipherText.size - 5), 64, "REJECT", "NONE")
        segmentRow("CORRUPT_PAD", "CORRUPT_PAD", corrupt, 0, "REJECT", "NONE")
        segmentRow("WRONG_KEY", "WRONG_KEY", cipherText, 0, "REJECT", "NONE")

        val largeCipher = encrypt(patternBytes(LARGE_PLAIN_BYTES), key, iv)
        val data = linkedMapOf(
            "contract.txt" to contract,
            "iv-cases.tsv" to ivText.toString().toByteArray(StandardCharsets.UTF_8),
            "segments.tsv" to segmentText.toString().toByteArray(StandardCharsets.UTF_8),
            "large.ct" to largeCipher,
        )
        val manifest = StringBuilder()
        for (name in DATA_FILES) manifest.append(digest(data.getValue(name))).append("  ").append(name).append('\n')
        val manifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)
        val dimensions = StringBuilder().append(DIMENSION_HEADER).append('\n')
        dimensions.append("fixture_version\t1\n")
        dimensions.append("iv_cases\t12\n")
        dimensions.append("segments\t6\n")
        dimensions.append("large_cipher_bytes\t").append(largeCipher.size).append('\n')
        dimensions.append("large_plain_sha256\t").append(digest(patternBytes(LARGE_PLAIN_BYTES))).append('\n')
        dimensions.append("manifest_sha256\t").append(digest(manifestBytes)).append('\n')
        data["sha256.txt"] = manifestBytes
        data["dimensions.tsv"] = dimensions.toString().toByteArray(StandardCharsets.UTF_8)
        return Built(data)
    }

    private fun rows(text: String, header: String, fields: Int, code: String): List<List<String>> {
        val parts = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        if (parts.isEmpty() || parts[0] != header) bad(code)
        return parts.drop(1).map { line ->
            val columns = line.split('\t')
            if (columns.size != fields) bad("FIELD_COUNT")
            columns
        }
    }

    private fun parseIvCases(bytes: ByteArray): List<IvCase> =
        rows(String(bytes, StandardCharsets.UTF_8), IV_HEADER, 4, "IV_HEADER_STRICT").map { c ->
            val sequence = c[1].toLongOrNull() ?: bad("INT_BOUNDS")
            val expect = c[3]
            if (expect != "ACCEPT" && expect != "REJECT") bad("ENUM_STRICT")
            IvCase(c[0], sequence, if (c[2] == "NONE") null else c[2], expect)
        }

    private fun parseSegments(bytes: ByteArray): List<SegmentCase> =
        rows(String(bytes, StandardCharsets.UTF_8), SEGMENT_HEADER, 6, "SEGMENT_HEADER_STRICT").map { c ->
            val chunk = c[3].toIntOrNull() ?: bad("INT_BOUNDS")
            if (chunk < 0 || chunk > 65_536) bad("INT_BOUNDS")
            if (c[4] != "ACCEPT" && c[4] != "REJECT") bad("ENUM_STRICT")
            if (c[2].length % 2 != 0 || !c[2].all { it in '0'..'9' || it in 'a'..'f' }) bad("HEX_STRICT")
            SegmentCase(c[0], c[1], c[2], chunk, c[4], c[5])
        }

    private fun parseDimensions(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (c in rows(String(bytes, StandardCharsets.UTF_8), DIMENSION_HEADER, 2, "DIMENSION_HEADER_STRICT")) {
            if (c[0] !in DIMENSION_KEYS) bad("ENUM_STRICT")
            if (result.put(c[0], c[1]) != null) bad("FIELD_COUNT")
        }
        if (result.size != DIMENSION_KEYS.size) bad("FIELD_COUNT")
        return result
    }

    private fun parseManifest(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val lineRegex = Regex("^([0-9a-f]{64})  ([a-z0-9.\\-]+)$")
        for (line in String(bytes, StandardCharsets.UTF_8).split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }) {
            val match = lineRegex.matchEntire(line) ?: bad("MANIFEST_FORMAT")
            if (result.put(match.groupValues[2], match.groupValues[1]) != null) bad("FIELD_COUNT")
        }
        if (result.keys.toSet() != DATA_FILES.toSet()) bad("FIELD_COUNT")
        return result
    }

    private fun readFixtures(dir: File): Map<String, ByteArray> {
        val files = dir.listFiles()?.filter { it.name != ".gitattributes" }?.associateBy { it.name }
            ?: bad("FIXTURE_DIR_MISSING")
        if (files.keys.toSet() != ALL_FILES.toSet()) bad("FIXTURE_FILE_SET")
        return ALL_FILES.associateWith { name -> files.getValue(name).readBytes() }
    }

    private class Report {
        val lines = mutableListOf<String>()
        var checks = 0
        var pass = 0
        var fail = 0
        var first: String? = null

        fun check(id: String, ok: Boolean) {
            checks++
            if (ok) {
                pass++
                lines.add("CHECK\tPASS\t$id")
            } else {
                fail++
                lines.add("CHECK\tFAIL\t$id")
                if (first == null) first = id
            }
        }

        fun thrown(code: String) {
            fail++
            lines.add("FAIL\t$code")
            if (first == null) first = code
        }

        fun emit(summary: Boolean = true) {
            if (summary) lines.add(
                "SUMMARY\tchecks=$checks\tpass=$pass\tfail=$fail\tfirst=${first ?: "NONE"}",
            )
            for (line in lines) println(line)
        }
    }

    private class BoundedSink : OutputStream() {
        private val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set
        var closed = false
            private set

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            hash.update(bytes, off, len)
            count += len
        }

        override fun close() {
            closed = true
        }

        fun hex(): String = hash.digest().toHex()
    }

    private class SegmentOutcome(val accepted: Boolean, val plainSha: String, val reported: Long, val sinkCount: Long)

    private fun decryptCase(case: SegmentCase, key: ByteArray, iv: ByteArray): SegmentOutcome {
        val sink = BoundedSink()
        val decryptor = HlsAes128.Decryptor(key, iv, sink)
        val cipherText = try {
            fromHex(case.cipherHex)
        } catch (error: IllegalArgumentException) {
            bad("HEX_STRICT")
        }
        var offset = 0
        val piece = if (case.chunk == 0) cipherText.size.coerceAtLeast(1) else case.chunk
        while (offset < cipherText.size) {
            val size = minOf(piece, cipherText.size - offset)
            decryptor.write(cipherText, offset, size)
            offset += size
        }
        val accepted = try {
            decryptor.finish()
            true
        } catch (refused: IOException) {
            false
        }
        val result = SegmentOutcome(accepted, sink.hex(), decryptor.plaintextBytes, sink.count)
        decryptor.close()
        return result
    }

    private fun oracleIv(case: IvCase): ByteArray? = if (case.explicit == null) {
        val iv = ByteArray(16)
        val sequence = ByteBuffer.allocate(8).putLong(case.sequence).array()
        System.arraycopy(sequence, 0, iv, 8, 8)
        iv
    } else {
        val raw = case.explicit
        val expectedLength = 34
        if (raw.length != expectedLength || !raw.startsWith("0x", ignoreCase = true)) null else {
            val digits = raw.substring(2)
            if (digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fromHex(digits) else null
        }
    }

    private fun privacyClean(diagnostics: String, fixtures: Map<String, ByteArray>, injected: String?): Boolean {
        val haystack = StringBuilder(diagnostics)
        if (injected != null) haystack.append('\n').append(injected)
        for (bytes in fixtures.values) haystack.append('\n').append(String(bytes, StandardCharsets.ISO_8859_1))
        val text = haystack.toString()
        val keyHex = derivedKey().toHex()
        return !text.contains(keyHex) &&
            !text.contains(derivedPayloadSentinel()) &&
            !urlPattern.containsMatchIn(text) &&
            !secretPattern.containsMatchIn(text)
    }

    private fun boundedCycles(large: ByteArray, pinnedDigest: String, report: Report) {
        val startThreads = Thread.activeCount()
        var baseline = -1L
        var ok = true
        repeat(4) { cycle ->
            val sink = BoundedSink()
            val decryptor = HlsAes128.Decryptor(derivedKey(), derivedIv(), sink)
            var offset = 0
            while (offset < large.size) {
                val size = minOf(CHUNK_BYTES, large.size - offset)
                decryptor.write(large, offset, size)
                offset += size
            }
            decryptor.finish()
            decryptor.close()
            ok = ok && sink.hex() == pinnedDigest && decryptor.plaintextBytes == sink.count
            System.gc()
            Thread.sleep(30)
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (cycle == 0) baseline = used else ok = ok && used - baseline <= 8L * 1024 * 1024
            ok = ok && Thread.activeCount() <= startThreads + 2
        }
        val leaked = Thread.getAllStackTraces().keys.any { it.name.startsWith("qa22-") }
        report.check("BOUNDED_HEAP_THREADS", ok && !leaked)
    }

    private fun noStaticCaches(): Boolean = HlsAes128::class.java.declaredFields.none { field ->
        Modifier.isStatic(field.modifiers) &&
            (Collection::class.java.isAssignableFrom(field.type) ||
                Map::class.java.isAssignableFrom(field.type) ||
                field.type.isArray)
    }

    private fun verify(mode: String, dir: File, report: Report) {
        val committed = readFixtures(dir)
        val built = build()
        val dimensions = parseDimensions(committed.getValue("dimensions.tsv"))
        if (mode == "verify-mutant-raw") {
            val manifest = parseManifest(committed.getValue("sha256.txt"))
            val manifestOk = digest(committed.getValue("sha256.txt")) == dimensions.getValue("manifest_sha256") &&
                DATA_FILES.all { digest(committed.getValue(it)) == manifest[it] }
            report.check("MANIFEST_MISMATCH", manifestOk)
            return
        }
        if (mode == "verify") {
            report.check(
                "BYTE_IDENTITY",
                ALL_FILES.all { built.files.getValue(it).contentEquals(committed.getValue(it)) },
            )
        }
        val manifest = parseManifest(committed.getValue("sha256.txt"))
        if (mode == "verify") {
            report.check(
                "MANIFEST_DATA",
                digest(committed.getValue("sha256.txt")) == dimensions.getValue("manifest_sha256") &&
                    DATA_FILES.all { digest(committed.getValue(it)) == manifest[it] },
            )
        }
        val ivCases = parseIvCases(committed.getValue("iv-cases.tsv"))
        val segments = parseSegments(committed.getValue("segments.tsv"))
        report.check(
            "DIMENSIONS",
            dimensions.getValue("fixture_version") == "1" &&
                dimensions.getValue("iv_cases").toIntOrNull() == ivCases.size &&
                dimensions.getValue("segments").toIntOrNull() == segments.size &&
                ivCases.size == 12 && segments.size == 6,
        )

        var defaultIvOk = true
        var explicitIvOk = true
        var rejectIvOk = true
        val ivCategories = ivCases.map { case ->
            when {
                case.expect == "REJECT" -> "REJECT"
                case.explicit == null -> "DEFAULT"
                else -> "EXPLICIT"
            }
        }.toSet()
        val segmentKinds = segments.map { it.kind }.toSet()
        report.check(
            "CASE_COVERAGE",
            setOf("DEFAULT", "EXPLICIT", "REJECT").all { it in ivCategories } &&
                (ACCEPT_KINDS + REJECT_CODES.keys).all { it in segmentKinds },
        )
        for (case in ivCases) {
            val actual = try {
                HlsAes128.initializationVector(case.explicit, case.sequence)
            } catch (failure: Exception) {
                null
            }
            val oracle = oracleIv(case)
            if (case.expect == "REJECT") {
                rejectIvOk = rejectIvOk && actual == null
            } else if (case.explicit == null) {
                defaultIvOk = defaultIvOk && actual != null && actual.contentEquals(oracle)
            } else {
                explicitIvOk = explicitIvOk && actual != null && actual.contentEquals(oracle)
            }
        }
        report.check("IV_DEFAULT", defaultIvOk)
        report.check("IV_EXPLICIT", explicitIvOk)
        report.check("IV_REJECT", rejectIvOk)

        val key = derivedKey()
        val iv = derivedIv()
        var vectorOk = true
        var truncatedOk = true
        var paddingOk = true
        var wrongKeyOk = true
        var countOk = true
        var expectOk = true
        for (case in segments) {
            val outcome = if (case.kind == "WRONG_KEY") {
                decryptCase(case, wrongKey(), iv)
            } else {
                decryptCase(case, key, iv)
            }
            countOk = countOk && outcome.reported == outcome.sinkCount
            when (case.kind) {
                in ACCEPT_KINDS -> {
                    if (case.expect != "ACCEPT") expectOk = false
                    vectorOk = vectorOk && outcome.accepted && outcome.plainSha == case.plainSha
                }
                else -> {
                    val code = REJECT_CODES[case.kind] ?: bad("ENUM_STRICT")
                    if (case.expect != "REJECT") expectOk = false
                    val rejected = !outcome.accepted
                    when (code) {
                        "TRUNCATED_REJECT" -> truncatedOk = truncatedOk && rejected
                        "CORRUPT_PAD_REJECT" -> paddingOk = paddingOk && rejected
                        "WRONG_KEY_REJECT" -> wrongKeyOk = wrongKeyOk && rejected
                    }
                }
            }
        }
        report.check("DECRYPT_VECTOR", vectorOk)
        report.check("TRUNCATED_REJECT", truncatedOk)
        report.check("CORRUPT_PAD_REJECT", paddingOk)
        report.check("WRONG_KEY_REJECT", wrongKeyOk)
        report.check("PLAINTEXT_COUNT", countOk)
        report.check("SEGMENT_EXPECT", expectOk)

        val tracking = BoundedSink()
        HlsAes128.Decryptor(key, iv, tracking).close()
        report.check("SINK_LEFT_OPEN", !tracking.closed)

        val large = committed.getValue("large.ct")
        val pinnedCap = dimensions.getValue("large_cipher_bytes").toIntOrNull() ?: bad("INT_BOUNDS")
        val capOk = large.size == pinnedCap && pinnedCap == built.files.getValue("large.ct").size
        report.check("CAP_BINDING", capOk)
        if (capOk) boundedCycles(large, dimensions.getValue("large_plain_sha256"), report)
        report.check("NO_STATIC_CACHE", noStaticCaches())

        val injected = if (mode == "verify-leak") derivedKey().toHex() else null
        report.check("PRIVACY_SCAN", privacyClean(report.lines.joinToString("\n"), committed, injected))
    }

    private fun generate(dir: File, report: Report) {
        val built = build()
        Files.createDirectories(dir.toPath())
        for (name in ALL_FILES) {
            val bytes = built.files.getValue(name)
            Files.write(dir.resolve(name).toPath(), bytes)
            report.lines.add("GENERATE\t$name\tbytes=${bytes.size}\tsha256=${digest(bytes)}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("FAIL\tUSAGE\tmode plus fixture directory required")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        val report = Report()
        try {
            if (mode == "generate") {
                generate(dir, report)
                report.emit(summary = false)
                return
            }
            if (mode !in setOf("verify", "verify-mutant", "verify-mutant-raw", "verify-leak")) {
                bad("MODE_STRICT")
            }
            verify(mode, dir, report)
        } catch (failure: GateFailure) {
            report.thrown(failure.code)
        } catch (error: Throwable) {
            report.thrown("GATE_EXCEPTION:${error.javaClass.simpleName}")
        }
        report.emit()
        exitProcess(if (report.first == null) 0 else 1)
    }
}
