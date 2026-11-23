package com.devtunasoft.tuneros.qa18

import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideDoctor
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.StreamEvidence
import com.devtunasoft.tuneros.core.TransportFault
import java.security.MessageDigest
import java.util.Locale

/** A named fail-closed rejection. The detail is a code, never raw input. */
class Qa18Failure(val code: String, val detail: String) : RuntimeException(detail)

private fun reject(code: String, detail: String): Nothing = throw Qa18Failure(code, detail)

/**
 * QA-18 shared, Android-free contract and generator.
 *
 * The slice is the fail-closed ingestion of corrupt or truncated diagnostic evidence envelopes
 * before they are allowed to reach the real StreamDoctor and GuideDoctor shareable reports.
 * Every sensitive marker is derived from a fixed seed at run time; no literal secret, feed
 * address, credential, PIN or recording payload exists in this source or in the fixture.
 * The same source compiles into the host gate and into the instrumented test APK.
 */
object Qa18Assertions {
    const val SEED = "QA18-FIXED-SEED-2026-11-23"
    const val VERSION = "qa18-envelope-v1"
    const val EXPECTED_ENVELOPE_SHA256 = "9ab6745720c354b799bd6af4234605d5961c9945842e5984befb5d154a073fde"

    const val RECORDS_STREAM = 256
    const val RECORDS_GUIDE = 64
    const val RECORDS_WARNING = 32
    const val RECORDS_TOTAL = RECORDS_STREAM + RECORDS_GUIDE + RECORDS_WARNING
    const val CORRUPT_CASES = 15
    const val MAX_RECORD_BYTES = 160
    const val MAX_ENVELOPE_BYTES = 131_072
    const val MAX_STREAM_REPORT_CHARS = 400
    const val MAX_GUIDE_REPORT_CHARS = 2_048
    const val MAX_DIAGNOSTIC_LINES = 32
    const val CYCLES = 6
    const val MAX_HEAP_DRIFT_BYTES = 16L * 1024 * 1024
    const val MAX_THREAD_DRIFT = 2
    const val LAX_ACCEPT_CASE = "bad-digest"

    const val HEADER_VERSION = 1
    const val KIND_STREAM = 1
    const val KIND_GUIDE = 2
    const val KIND_WARNING = 3

    data class Marker(
        val token: String,
        val pin: String,
        val payload: String,
        val host: String,
        val tag: String,
        val digest: String,
    ) {
        /** Every forbidden fragment is synthetic and derived; none is a real provider value. */
        val forbidden: List<String>
            get() = listOf(token, "pin=$pin", payload, host, "://", "Bearer ", "token=", "credential=", tag)
    }

    data class ParsedRecord(
        val kind: Int,
        val code: Int,
        val status: Int,
        val transport: Int,
        val contentType: String,
        val markerText: String,
    )

    data class Parsed(val records: List<ParsedRecord>, val bytes: Int)

    data class CorruptCase(val name: String, val code: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = other is CorruptCase &&
            name == other.name && code == other.code && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (name.hashCode() * 31 + code.hashCode()) * 31 + payload.contentHashCode()
    }

    private val HEADER_MAGIC = byteArrayOf('Q'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(), '8'.code.toByte())
    private const val RECORD_MAGIC = 0x51413138

    private val SAFE_CODES = intArrayOf(-102, 1002, 2003, 2004, 3001, 4001, 5001, 6001, 9999)
    private val SAFE_STATUSES = intArrayOf(-1, 200, 404, 503)
    private val SAFE_TRANSPORTS = intArrayOf(0, 1, 2, 3, 4)
    private val SAFE_TYPES = arrayOf("application/vnd.qa18+bin", "video/mp4", "text/html")
    private val VALID_CODES = setOf(
        "HDR_MAGIC", "HDR_VERSION", "HDR_COUNT", "REC_MAGIC", "REC_KIND", "REC_CODE",
        "REC_STATUS", "REC_TRANSPORT", "REC_CT_LEN", "REC_MARKER_LEN", "REC_CT_ENCODING",
        "REC_MARKER_ENCODING", "REC_DIGEST", "REC_CAP", "TRUNCATED", "EXTRA_BYTES",
    )

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /** Derives all sensitive-looking values from one pinned seed, never from user or provider data. */
    fun marker(): Marker {
        val h = sha256("$SEED|qa18-marker-v1")
        val pin = (h.substring(10, 18).toLong(16) % 100_000_000L).toString().padStart(8, '0')
        return Marker(
            token = "QA18SYNTH_" + h.take(10),
            pin = pin,
            payload = "QA18REC_" + h.substring(18, 26),
            host = "qa18-feed.invalid",
            tag = h.substring(26, 34),
            digest = h.take(16),
        )
    }

    /** True when no forbidden synthetic fragment is present. */
    fun clean(text: String): Boolean {
        val m = marker()
        return m.forbidden.none { fragment -> text.contains(fragment) }
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun getInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun getShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    /** One length-delimited evidence record; its digest covers every preceding byte of that record. */
    fun recordBytes(kind: Int, code: Int, status: Int, transport: Int, contentType: String, markerText: String): ByteArray {
        val typeBytes = contentType.toByteArray(Charsets.US_ASCII)
        val markerBytes = markerText.toByteArray(Charsets.US_ASCII)
        if (typeBytes.size > 64 || markerBytes.size > 256) reject("REC_CAP", "builder-cap")
        val withDigest = ByteArray(16 + typeBytes.size + 2 + markerBytes.size + 32)
        putInt(withDigest, 0, RECORD_MAGIC)
        withDigest[4] = kind.toByte()
        putInt(withDigest, 5, code)
        putInt(withDigest, 9, status)
        withDigest[13] = transport.toByte()
        putShort(withDigest, 14, typeBytes.size)
        System.arraycopy(typeBytes, 0, withDigest, 16, typeBytes.size)
        val markerOffset = 16 + typeBytes.size
        putShort(withDigest, markerOffset, markerBytes.size)
        System.arraycopy(markerBytes, 0, withDigest, markerOffset + 2, markerBytes.size)
        val prefixLength = markerOffset + 2 + markerBytes.size
        val digest = MessageDigest.getInstance("SHA-256").digest(withDigest.copyOf(prefixLength))
        System.arraycopy(digest, 0, withDigest, prefixLength, 32)
        if (withDigest.size > MAX_RECORD_BYTES) reject("REC_CAP", "record-cap")
        return withDigest
    }

    /** The pinned, bounded envelope: 9-byte header followed by exactly RECORDS_TOTAL records. */
    fun envelopeBytes(): ByteArray {
        val m = marker()
        val out = java.io.ByteArrayOutputStream(48_000)
        val header = ByteArray(9)
        System.arraycopy(HEADER_MAGIC, 0, header, 0, 4)
        header[4] = HEADER_VERSION.toByte()
        putInt(header, 5, RECORDS_TOTAL)
        out.write(header)
        for (index in 0 until RECORDS_TOTAL) {
            val kind: Int
            val code: Int
            val status: Int
            val transport: Int
            val type: String
            when {
                index < RECORDS_STREAM -> {
                    kind = KIND_STREAM
                    code = SAFE_CODES[index % SAFE_CODES.size]
                    status = SAFE_STATUSES[index % SAFE_STATUSES.size]
                    transport = SAFE_TRANSPORTS[index % SAFE_TRANSPORTS.size]
                    type = SAFE_TYPES[index % SAFE_TYPES.size]
                }
                index < RECORDS_STREAM + RECORDS_GUIDE -> {
                    kind = KIND_GUIDE
                    code = 0
                    status = -1
                    transport = 0
                    type = ""
                }
                else -> {
                    kind = KIND_WARNING
                    code = 0
                    status = -1
                    transport = 0
                    type = ""
                }
            }
            val markerText = if (kind == KIND_WARNING && index % 8 == 0) {
                "qa18scheme://${m.host}/guide?token=${m.token}&pin=${m.pin}#$index"
            } else {
                m.token + ":" + index.toString().padStart(4, '0')
            }
            out.write(recordBytes(kind, code, status, transport, type, markerText))
        }
        val result = out.toByteArray()
        if (result.size > MAX_ENVELOPE_BYTES) reject("REC_CAP", "envelope-cap")
        return result
    }

    /** A small canonical envelope used as the base of every corrupt variant. */
    fun canonicalBytes(): ByteArray {
        val m = marker()
        val out = java.io.ByteArrayOutputStream(512)
        val header = ByteArray(9)
        System.arraycopy(HEADER_MAGIC, 0, header, 0, 4)
        header[4] = HEADER_VERSION.toByte()
        putInt(header, 5, 3)
        out.write(header)
        out.write(recordBytes(KIND_STREAM, 2004, 404, 1, "text/html", m.token + ":0"))
        out.write(recordBytes(KIND_GUIDE, 0, -1, 0, "", m.token + ":1"))
        out.write(recordBytes(KIND_WARNING, 0, -1, 0, "", "safe scratch warning 2"))
        return out.toByteArray()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    fun unhex(text: String): ByteArray {
        if (text.length % 2 != 0 || text.any { it !in '0'..'9' && it !in 'a'..'f' }) reject("CORRUPT_HEX", "hex")
        return ByteArray(text.length / 2) { index ->
            ((Character.digit(text[index * 2], 16) shl 4) or Character.digit(text[index * 2 + 1], 16)).toByte()
        }
    }

    /** Named byte-level mutants, each derived from the small canonical document. */
    fun corruptVariants(): List<CorruptCase> {
        val base = canonicalBytes()
        val firstRecord = 9
        fun changed(edit: (ByteArray) -> Unit): ByteArray = base.copyOf().also(edit)
        val variants = ArrayList<CorruptCase>(CORRUPT_CASES)
        variants += CorruptCase("bad-header-magic", "HDR_MAGIC", changed { it[0] = (it[0].toInt() xor 1).toByte() })
        variants += CorruptCase("bad-version", "HDR_VERSION", changed { it[4] = 2 })
        variants += CorruptCase("zero-count", "HDR_COUNT", changed { putInt(it, 5, 0) })
        variants += CorruptCase("large-count", "HDR_COUNT", changed { putInt(it, 5, RECORDS_TOTAL + 1) })
        variants += CorruptCase("bad-record-magic", "REC_MAGIC", changed { it[firstRecord] = (it[firstRecord].toInt() xor 1).toByte() })
        variants += CorruptCase("bad-kind", "REC_KIND", changed { it[firstRecord + 4] = 9 })
        variants += CorruptCase("bad-code", "REC_CODE", changed { putInt(it, firstRecord + 5, 30_000) })
        variants += CorruptCase("bad-status", "REC_STATUS", changed { putInt(it, firstRecord + 9, 99) })
        variants += CorruptCase("bad-transport", "REC_TRANSPORT", changed { it[firstRecord + 13] = 9 })
        variants += CorruptCase("bad-ct-len", "REC_CT_LEN", changed { putShort(it, firstRecord + 14, 65) })
        variants += CorruptCase("bad-marker-len", "REC_MARKER_LEN", changed { putShort(it, firstRecord + 16 + 9, 257) })
        variants += CorruptCase("bad-digest", "REC_DIGEST", changed {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte()
        })
        variants += CorruptCase("truncated", "TRUNCATED", base.copyOf(base.size - 5))
        variants += CorruptCase("extra-bytes", "EXTRA_BYTES", base.copyOf(base.size + 1))
        variants += CorruptCase("bad-ct-encoding", "REC_CT_ENCODING", changed { it[firstRecord + 16] = 0xff.toByte() })
        if (variants.size != CORRUPT_CASES) reject("CORRUPT_COUNT", "builder-count")
        if (variants.any { it.code !in VALID_CODES }) reject("CORRUPT_TABLE", "builder-code")
        return variants
    }

    /**
     * Strict envelope decoder. ignoreDigest is the deliberately lax control only: it proves that
     * the strict digest rejection is meaningful rather than an incidental length failure.
     */
    fun parseEnvelope(data: ByteArray, ignoreDigest: Boolean = false): Parsed {
        if (data.size < 9) reject("TRUNCATED", "header")
        if (!data.copyOfRange(0, 4).contentEquals(HEADER_MAGIC)) reject("HDR_MAGIC", "header")
        if (data[4] != HEADER_VERSION.toByte()) reject("HDR_VERSION", "header")
        val count = getInt(data, 5)
        if (count < 1 || count > RECORDS_TOTAL) reject("HDR_COUNT", "header")
        val records = ArrayList<ParsedRecord>(count)
        var offset = 9
        repeat(count) {
            if (offset + 14 > data.size) reject("TRUNCATED", "record-prefix")
            if (getInt(data, offset) != RECORD_MAGIC) reject("REC_MAGIC", "record")
            val kind = data[offset + 4].toInt() and 0xff
            if (kind != KIND_STREAM && kind != KIND_GUIDE && kind != KIND_WARNING) reject("REC_KIND", "record")
            val code = getInt(data, offset + 5)
            if (code < -20_000 || code > 20_000) reject("REC_CODE", "record")
            val status = getInt(data, offset + 9)
            if (status != -1 && status !in 100..599) reject("REC_STATUS", "record")
            val transport = data[offset + 13].toInt() and 0xff
            if (transport !in 0..4) reject("REC_TRANSPORT", "record")
            val typeLength = getShort(data, offset + 14)
            if (typeLength !in 0..64) reject("REC_CT_LEN", "record")
            val markerLength = getShort(data, offset + 16 + typeLength)
            if (markerLength !in 0..256) reject("REC_MARKER_LEN", "record")
            val prefixLength = 16 + typeLength + 2 + markerLength
            val recordLength = prefixLength + 32
            if (offset + recordLength > data.size) reject("TRUNCATED", "record-body")
            if (recordLength > MAX_RECORD_BYTES) reject("REC_CAP", "record")
            for (i in 0 until typeLength) {
                val byte = data[offset + 16 + i].toInt() and 0xff
                if (byte !in 0x21..0x7e) reject("REC_CT_ENCODING", "record")
            }
            for (i in 0 until markerLength) {
                val byte = data[offset + 18 + typeLength + i].toInt() and 0xff
                if (byte !in 0x20..0x7e) reject("REC_MARKER_ENCODING", "record")
            }
            if (!ignoreDigest) {
                val expected = MessageDigest.getInstance("SHA-256")
                    .digest(data.copyOfRange(offset, offset + prefixLength))
                val actual = data.copyOfRange(offset + prefixLength, offset + recordLength)
                if (!expected.contentEquals(actual)) reject("REC_DIGEST", "record")
            }
            val type = String(data, offset + 16, typeLength, Charsets.US_ASCII)
            val markerText = String(data, offset + 18 + typeLength, markerLength, Charsets.US_ASCII)
            records += ParsedRecord(kind, code, status, transport, type, markerText)
            offset += recordLength
        }
        if (offset != data.size) reject("EXTRA_BYTES", "trailer")
        return Parsed(records, data.size)
    }

    fun contractText(): String = buildString {
        append("QA-18 synthetic scratch fixture for corrupt diagnostic evidence ingestion.\n")
        append("Envelope header: QA18 + version byte + big-endian record count.\n")
        append("Each record carries a sha256 digest over its own fields; every value is bounded.\n")
        append("All markers are derived from the pinned seed; no real feed address, credential, PIN, key or payload.\n")
    }

    fun corruptText(): String = buildString {
        for (variant in corruptVariants()) {
            append(variant.name).append('\t').append(variant.code).append('\t').append(hex(variant.payload)).append('\n')
        }
    }

    fun dimensionsText(): String {
        val envelope = envelopeBytes()
        return buildString {
            append("version=").append(VERSION).append('\n')
            append("seed=").append(SEED).append('\n')
            append("recordsStream=").append(RECORDS_STREAM).append('\n')
            append("recordsGuide=").append(RECORDS_GUIDE).append('\n')
            append("recordsWarning=").append(RECORDS_WARNING).append('\n')
            append("recordsTotal=").append(RECORDS_TOTAL).append('\n')
            append("envelopeBytes=").append(envelope.size).append('\n')
            append("envelopeSha256=").append(sha256(envelope)).append('\n')
            append("corruptCases=").append(CORRUPT_CASES).append('\n')
            append("markerDigest=").append(marker().digest).append('\n')
            append("laxAcceptCase=").append(LAX_ACCEPT_CASE).append('\n')
            append("cycles=").append(CYCLES).append('\n')
            append("maxRecordBytes=").append(MAX_RECORD_BYTES).append('\n')
            append("maxEnvelopeBytes=").append(MAX_ENVELOPE_BYTES).append('\n')
            append("maxHeapDriftBytes=").append(MAX_HEAP_DRIFT_BYTES).append('\n')
            append("maxThreadDrift=").append(MAX_THREAD_DRIFT).append('\n')
            append("maxStreamReportChars=").append(MAX_STREAM_REPORT_CHARS).append('\n')
            append("maxGuideReportChars=").append(MAX_GUIDE_REPORT_CHARS).append('\n')
            append("maxDiagnosticLines=").append(MAX_DIAGNOSTIC_LINES).append('\n')
        }
    }

    /** contract, dimensions and corrupt tables are text; envelopes.bin is the only binary file. */
    val DATA_FILES = listOf("contract.txt", "dimensions.tsv", "corrupt.hex", "envelopes.bin")

    fun buildFiles(): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files["contract.txt"] = contractText().toByteArray(Charsets.UTF_8)
        files["dimensions.tsv"] = dimensionsText().toByteArray(Charsets.UTF_8)
        files["corrupt.hex"] = corruptText().toByteArray(Charsets.UTF_8)
        files["envelopes.bin"] = envelopeBytes()
        return files
    }

    fun manifestBytes(files: Map<String, ByteArray>): ByteArray = buildString {
        for (name in DATA_FILES) append(sha256(files.getValue(name))).append("  ").append(name).append('\n')
    }.toByteArray(Charsets.UTF_8)

    /** Rehydrates the parsed evidence into the real, pure-Kotlin doctor report functions. */
    fun reports(parsed: Parsed): Pair<List<String>, String> {
        val m = marker()
        val streams = ArrayList<String>(RECORDS_STREAM)
        val channels = ArrayList<Channel>(RECORDS_GUIDE)
        val warnings = ArrayList<String>(RECORDS_WARNING)
        val names = LinkedHashMap<String, String>(RECORDS_GUIDE)
        val programs = ArrayList<GuideProgram>(RECORDS_GUIDE)
        for (record in parsed.records) {
            when (record.kind) {
                KIND_STREAM -> {
                    val fault = when (record.transport) {
                        1 -> TransportFault.DNS
                        2 -> TransportFault.TIMEOUT
                        3 -> TransportFault.REFUSED
                        4 -> TransportFault.TLS
                        else -> null
                    }
                    val status = if (record.status == -1) null else record.status
                    val type = StreamDoctor.safeContentType(record.contentType)
                    streams += StreamDoctor.report(record.code, StreamEvidence(status, type, fault))
                }
                KIND_GUIDE -> {
                    val id = "qa18." + channels.size.toString().padStart(4, '0')
                    val name = "Scratch Channel " + channels.size.toString().padStart(4, '0')
                    channels += Channel("QC" + channels.size.toString().padStart(4, '0'), name, m.token + ":" + channels.size, id)
                    names[id] = name
                    programs += GuideProgram(id, "Scratch Show", 1_700_000_000L, 1_700_001_800L)
                }
                KIND_WARNING -> warnings += if (record.markerText.contains("://")) {
                    // The real doctor drops warnings with a scheme; the synthetic value stays in the fixture only.
                    record.markerText
                } else {
                    "Scratch warning " + warnings.size
                }
            }
        }
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), GuideSnapshot(programs, names, warnings), 1_700_000_900L)
        val guideReport = GuideDoctor.summaryText(diagnosis, warnings, 1_700_000_900L)
        return Pair(streams, guideReport)
    }

    /** Dependency-free assertions shared by the host gate and the compiled device mirror. */
    fun run(): List<String> {
        val lines = ArrayList<String>(10)
        fun check(id: String) { lines += "PASS\t$id" }
        val envelope = envelopeBytes()
        if (sha256(envelope) != EXPECTED_ENVELOPE_SHA256) reject("MIRROR_DIGEST", "pinned-digest")
        check("mirror-envelope-digest")
        val parsed = parseEnvelope(envelope)
        if (parsed.records.size != RECORDS_TOTAL) reject("RECORD_COUNT", "count")
        check("mirror-record-count")
        val streams = parsed.records.count { it.kind == KIND_STREAM }
        val guides = parsed.records.count { it.kind == KIND_GUIDE }
        val warnings = parsed.records.count { it.kind == KIND_WARNING }
        if (streams != RECORDS_STREAM || guides != RECORDS_GUIDE || warnings != RECORDS_WARNING) {
            reject("RECORD_COUNTS", "kinds")
        }
        check("mirror-kind-counts")
        val (streamReports, guideReport) = reports(parsed)
        if (streamReports.size != RECORDS_STREAM || streamReports.any { !clean(it) || it.length > MAX_STREAM_REPORT_CHARS }) {
            reject("STREAM_REPORT_PRIVACY", "reports")
        }
        check("mirror-stream-privacy")
        if (!clean(guideReport) || guideReport.length > MAX_GUIDE_REPORT_CHARS) reject("GUIDE_REPORT_PRIVACY", "report")
        check("mirror-guide-privacy")
        for (variant in corruptVariants()) {
            val rejected = try {
                parseEnvelope(variant.payload)
                false
            } catch (failure: Qa18Failure) {
                failure.code == variant.code
            }
            if (!rejected) reject("CORRUPT_REJECT", variant.name)
        }
        check("mirror-corrupt-reject")
        val laxCase = corruptVariants().first { it.name == LAX_ACCEPT_CASE }
        val strictRejected = try {
            parseEnvelope(laxCase.payload)
            false
        } catch (failure: Qa18Failure) {
            failure.code == laxCase.code
        }
        val laxAccepted = try {
            parseEnvelope(laxCase.payload, ignoreDigest = true)
            true
        } catch (failure: Qa18Failure) {
            false
        }
        if (!strictRejected || !laxAccepted) reject("LAX_ACCEPT", LAX_ACCEPT_CASE)
        check("mirror-lax-control")
        if (clean(marker().token)) reject("NEG_LEAK_DETECTOR", "detector")
        check("mirror-leak-control")
        return lines
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val lines = run()
            for (line in lines) println(line)
            println("SUMMARY\t${lines.size}/${lines.size}\tPASS")
        } catch (failure: Qa18Failure) {
            println("FIRST\t${failure.code}\t${failure.detail}")
            println("SUMMARY\tFAIL\t${failure.code}")
            System.exit(1)
        }
    }
}
