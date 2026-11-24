package com.devtunasoft.tuneros.qa53

import com.devtunasoft.tuneros.core.PlaylistParser
import java.security.MessageDigest

/**
 * QA-53 shared, framework-free contract. It generates the bounded scratch fixture, parses it
 * strictly, and drives the real compiled PlaylistParser.parseReport. No provider address,
 * account, PIN or recording payload is used: every host is an RFC 2606 .invalid name and every
 * marker is a synthetic scratch token derived from the fixed seed.
 */
object Qa53Contract {
    const val FILE_COUNT = 6
    const val CASE_COUNT = 36
    const val CORRUPT_COUNT = 8
    const val HEADER_ROWS = 520
    const val HEADER_CAP = 512
    const val CYCLES = 4
    const val ADMITTED_PER_CYCLE = 24
    const val REFUSED_PER_CYCLE = 12
    const val HEADER_ADMITTED = HEADER_ROWS * 2
    const val SEED = "QA53-FIXED-SEED-2026-11-24"
    const val HOST = "qa53-host.invalid"
    const val MARKER_PREFIX = "QA53M-"

    val DATA_FILES = listOf("contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.tsv", "headers.tsv")
    val ALL_FILES = DATA_FILES + "sha256.txt"

    const val CASE_HEADER =
        "id\tsource\tguide\tname\tdisplay\tlogoMode\tgroup\turlMode\theaderMode\texpect\tnote"
    const val CORRUPT_HEADER = "id\tkind\tnote"
    const val HEADER_HEADER = "seq\tvalue"
    const val DIM_HEADER = "key\tvalue"

    data class Check(val code: String, val ok: Boolean, val detail: String = "")
    data class Result(val checks: List<Check>) {
        val failures: Int get() = checks.count { !it.ok }
        val first: String get() = checks.firstOrNull { !it.ok }?.code ?: "NONE"
    }

    data class Case(
        val id: String,
        val source: String,
        val guide: String,
        val name: String,
        val display: String,
        val logoMode: String,
        val group: String,
        val urlMode: String,
        val headerMode: String,
        val expect: String,
        val note: String,
    )

    data class HeaderRow(val seq: Int, val value: String)

    private val families = listOf(
        "ADMIT", "SCHEME_UPPER", "SCHEME_FTP", "CTRL", "BLANK_NAME",
        "LOGO_LONG", "LOGO_BAD", "GROUP", "HEADERS",
    )
    private val hexLower = "0123456789abcdef".toCharArray()

    fun cases(): List<Case> {
        val out = ArrayList<Case>(CASE_COUNT)
        var n = 1
        for (family in families) {
            for (rep in 0 until 4) {
                val id = "c" + n.toString().padStart(3, '0')
                val source = if (rep % 2 == 0) "qa53-src-a" else "qa53-src-b"
                val guide = "qa53-g" + n.toString().padStart(3, '0')
                val name = "QA53 Channel " + n.toString().padStart(3, '0')
                val display = if (family == "BLANK_NAME") "" else name
                val logoMode = when (family) {
                    "LOGO_LONG" -> "LONG"
                    "LOGO_BAD" -> "BAD"
                    else -> "OK"
                }
                val group = if (family == "GROUP") {
                    "qa53-group-" + (rep % 2).toString()
                } else {
                    "-"
                }
                val urlMode = when (family) {
                    "SCHEME_UPPER" -> "UPPER"
                    "SCHEME_FTP" -> "FTP"
                    "CTRL" -> "CTRL"
                    else -> "OK"
                }
                val headerMode = if (family != "HEADERS") {
                    "NONE"
                } else {
                    when (rep) {
                        0 -> "UA"
                        1 -> "REF"
                        2 -> "BOTH"
                        else -> "NONE"
                    }
                }
                val expect = if (family == "SCHEME_FTP" || family == "CTRL" || family == "BLANK_NAME") {
                    "REFUSE"
                } else {
                    "ADMIT"
                }
                out += Case(id, source, guide, name, display, logoMode, group, urlMode, headerMode,
                    expect, "family=$family rep=$rep")
                n++
            }
        }
        return out
    }

    private fun streamUrl(mode: String, id: String): String {
        val scheme = if (mode == "FTP") "ftp" else if (mode == "UPPER") "HTTP" else "http"
        val raw = if (mode == "CTRL") "\u0001" else ""
        return scheme + "://" + HOST + "/live/" + id + raw + ".ts"
    }

    private fun logoUrl(mode: String, id: String): String = when (mode) {
        "LONG" -> "http://" + HOST + "/logo/" + "q".repeat(2100) + id
        "BAD" -> "ftp://" + HOST + "/logo/" + id + ".png"
        else -> "http://" + HOST + "/logo/" + id + ".png"
    }

    private fun agentValue(id: String): String =
        "qa53-agent-" + (id.drop(1).toInt() % 3).toString()

    private fun refererValue(id: String): String =
        "qa53-ref-" + (id.drop(1).toInt() % 2).toString()

    fun casePlaylist(c: Case): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXTINF:-1 tvg-id=\"").append(c.guide).append("\" tvg-logo=\"")
        sb.append(logoUrl(c.logoMode, c.id)).append('"')
        if (c.group != "-") sb.append(" group-title=\"").append(c.group).append('"')
        sb.append(',').append(c.display).append('\n')
        if (c.headerMode == "UA" || c.headerMode == "BOTH") {
            sb.append("#EXTVLCOPT:http-user-agent=").append(agentValue(c.id)).append('\n')
        }
        if (c.headerMode == "REF" || c.headerMode == "BOTH") {
            sb.append("#EXTVLCOPT:http-referrer=").append(refererValue(c.id)).append('\n')
        }
        sb.append(streamUrl(c.urlMode, c.id)).append('\n')
        return sb.toString()
    }

    private fun corruptPlaylist(kind: String): String {
        val url = "http://" + HOST + "/corrupt.ts"
        return when (kind) {
            "NO_METADATA" -> "#EXTM3U\n$url\n"
            "FTP_URL" -> "#EXTM3U\n#EXTINF:-1,Name\nftp://" + HOST + "/x.ts\n"
            "CTRL_URL" -> "#EXTM3U\n#EXTINF:-1,Name\nhttp://" + HOST + "/x\u0001.ts\n"
            "BLANK_NAME" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"qa53-x\",\n$url\n"
            "BLANK_URL" -> "#EXTM3U\n#EXTINF:-1,Name\n\n"
            "ADMIT_URL" -> "#EXTM3U\n#EXTINF:-1,Name\n$url\n"
            "OVER_LINE" -> "#EXTM3U\n#EXTINF:-1 " + "a".repeat(40_000) + ",Name\n$url\n"
            else -> "#EXTM3U\n$url\n"
        }
    }

    private fun rejects(kind: String): Boolean = try {
        when (kind) {
            "BAD_CAP" -> {
                PlaylistParser.parseReport("#EXTM3U\n".reader(), 0)
                false
            }
            "CAP_OVER" -> {
                PlaylistParser.parseReport("#EXTM3U\n".reader(), PlaylistParser.MAX_CHANNELS + 1)
                false
            }
            else -> PlaylistParser.parseReport(corruptPlaylist(kind).reader(), 16).channels.isEmpty()
        }
    } catch (error: Throwable) {
        true
    }

    private fun headerRows(): List<HeaderRow> = (0 until HEADER_ROWS).map { seq ->
        HeaderRow(seq, "qa53-h-" + seq.toString())
    }

    private fun headerPlaylist(rows: List<HeaderRow>): String {
        val sb = StringBuilder("#EXTM3U\n")
        for (row in rows) {
            repeat(2) { occurrence ->
                sb.append("#EXTINF:-1 tvg-id=\"hg").append(row.seq).append("\",Header ")
                sb.append(row.seq).append('\n')
                sb.append("#EXTVLCOPT:http-user-agent=").append(row.value).append('\n')
                sb.append("http://").append(HOST).append("/h/").append(row.seq)
                sb.append('/').append(occurrence).append(".ts\n")
            }
        }
        return sb.toString()
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun digest(vararg parts: ByteArray): String {
        val total = parts.sumOf { it.size }
        val alloy = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, alloy, offset, part.size)
            offset += part.size
        }
        val raw = MessageDigest.getInstance("SHA-256").digest(alloy)
        val chars = CharArray(raw.size * 2)
        for (i in raw.indices) {
            val value = raw[i].toInt() and 0xff
            chars[i * 2] = hexLower[value ushr 4]
            chars[i * 2 + 1] = hexLower[value and 0x0f]
        }
        return String(chars)
    }

    private fun shortDigest(value: String): String {
        val raw = digest(value)
        val chars = CharArray(24)
        for (i in 0 until 12) {
            val b = raw[i].toInt() and 0xff
            chars[i * 2] = hexLower[b ushr 4]
            chars[i * 2 + 1] = hexLower[b and 0x0f]
        }
        return String(chars)
    }

    fun aggregateDigest(files: Map<String, ByteArray>): String = digest(
        files.getValue("cases.tsv"), files.getValue("corrupt.tsv"), files.getValue("headers.tsv"),
    )

    fun derivedToken(seed: String = SEED): String {
        val raw = digest(seed)
        val chars = CharArray(16)
        for (i in 0 until 8) {
            val b = raw[i].toInt() and 0xff
            chars[i * 2] = hexLower[b ushr 4]
            chars[i * 2 + 1] = hexLower[b and 0x0f]
        }
        return MARKER_PREFIX + String(chars)
    }

    private val sensitive = listOf(
        Regex("(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer |credential|pin=|recording[_ -]?payload)"),
    )

    fun privacyOffenders(text: String, seed: String = SEED): List<String> {
        val found = ArrayList<String>()
        for (pattern in sensitive) if (pattern.containsMatchIn(text)) found += pattern.pattern
        if (text.contains(derivedToken(seed))) found += "derived-token"
        return found
    }

    fun generateFiles(): Map<String, ByteArray> {
        val rows = cases()
        val casesText = buildString {
            append(CASE_HEADER).append('\n')
            for (c in rows) {
                append(c.id).append('\t').append(c.source).append('\t').append(c.guide).append('\t')
                append(c.name).append('\t').append(c.display).append('\t').append(c.logoMode).append('\t')
                append(c.group).append('\t').append(c.urlMode).append('\t').append(c.headerMode).append('\t')
                append(c.expect).append('\t').append(c.note).append('\n')
            }
        }
        val corruptText = buildString {
            append(CORRUPT_HEADER).append('\n')
            append("c901\tNO_METADATA\tno pending metadata\n")
            append("c902\tFTP_URL\tunsupported scheme\n")
            append("c903\tCTRL_URL\tcontrol byte in address\n")
            append("c904\tBLANK_NAME\tblank display name\n")
            append("c905\tBLANK_URL\tblank address\n")
            append("c906\tBAD_CAP\tzero channel cap\n")
            append("c907\tCAP_OVER\tcap above hard ceiling\n")
            append("c908\tOVER_LINE\toverlong line\n")
        }
        val headersText = buildString {
            append(HEADER_HEADER).append('\n')
            for (row in headerRows()) append(row.seq).append('\t').append(row.value).append('\n')
        }
        val aggregate = digest(
            casesText.toByteArray(Charsets.UTF_8),
            corruptText.toByteArray(Charsets.UTF_8),
            headersText.toByteArray(Charsets.UTF_8),
        )
        val dimensionsText = buildString {
            append(DIM_HEADER).append('\n')
            append("markerSeed\t").append(SEED).append('\n')
            append("caseCount\t").append(CASE_COUNT).append('\n')
            append("corruptCount\t").append(CORRUPT_COUNT).append('\n')
            append("headerRows\t").append(HEADER_ROWS).append('\n')
            append("cycles\t").append(CYCLES).append('\n')
            append("admittedPerCycle\t").append(ADMITTED_PER_CYCLE).append('\n')
            append("refusedPerCycle\t").append(REFUSED_PER_CYCLE).append('\n')
            append("headerAdmitted\t").append(HEADER_ADMITTED).append('\n')
            append("aggregateSha256\t").append(aggregate).append('\n')
        }
        val contractText = buildString {
            append("QA-53 synthetic scratch fixture for bounded M3U metadata hygiene.\n")
            append("All hosts use the reserved .invalid suffix; all names are invented scratch data.\n")
            append("Header values are generated from the marker seed; no real marker is present here.\n")
            append("dimensions.tsv uses tab-separated keys; every other table has an exact header.\n")
            append("The verifier parses these bytes and drives the real compiled core parser.\n")
        }
        val data = linkedMapOf(
            "contract.txt" to contractText,
            "dimensions.tsv" to dimensionsText,
            "cases.tsv" to casesText,
            "corrupt.tsv" to corruptText,
            "headers.tsv" to headersText,
        )
        val bytes = LinkedHashMap<String, ByteArray>()
        for (entry in data) bytes[entry.key] = entry.value.toByteArray(Charsets.UTF_8)
        val manifest = buildString {
            for (entry in bytes) append(digest(entry.value)).append('\t').append(entry.key).append('\n')
        }
        bytes["sha256.txt"] = manifest.toByteArray(Charsets.UTF_8)
        return bytes
    }

    private fun text(files: Map<String, ByteArray>, name: String): String? =
        files[name]?.toString(Charsets.UTF_8)

    private fun rowsOf(text: String?): List<String>? = text?.lineSequence()?.toList()
        ?.dropLastWhile { it.isEmpty() }

    private fun parseCases(text: String?): List<Case>? {
        val ls = rowsOf(text) ?: return null
        val out = ArrayList<Case>()
        for (line in ls.drop(1)) {
            if (line.count { it == '\t' } != 10) return null
            val f = line.split('\t')
            out += Case(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10])
        }
        return out
    }

    private fun parseCorrupt(text: String?): List<Pair<String, String>>? {
        val ls = rowsOf(text) ?: return null
        val out = ArrayList<Pair<String, String>>()
        for (line in ls.drop(1)) {
            if (line.count { it == '\t' } != 2) return null
            val f = line.split('\t')
            out += f[0] to f[1]
        }
        return out
    }

    private fun parseHeaders(text: String?): List<HeaderRow>? {
        val ls = rowsOf(text) ?: return null
        val out = ArrayList<HeaderRow>()
        for (line in ls.drop(1)) {
            if (line.count { it == '\t' } != 1) return null
            val f = line.split('\t')
            out += HeaderRow(f[0].toIntOrNull() ?: return null, f[1])
        }
        return out
    }

    private fun parseDimensions(text: String?): Map<String, String>? {
        val ls = rowsOf(text) ?: return null
        val out = LinkedHashMap<String, String>()
        for (line in ls.drop(1)) {
            if (line.count { it == '\t' } != 1) return null
            val f = line.split('\t')
            out[f[0]] = f[1]
        }
        return out
    }

    private fun manifestConsistent(files: Map<String, ByteArray>): Boolean {
        val raw = text(files, "sha256.txt") ?: return false
        val lines = rowsOf(raw) ?: return false
        if (lines.size != DATA_FILES.size) return false
        for (index in DATA_FILES.indices) {
            val fields = lines[index].split('\t')
            if (fields.size != 2 || fields[1] != DATA_FILES[index]) return false
            val actual = files[DATA_FILES[index]] ?: return false
            if (fields[0] != digest(actual)) return false
        }
        return true
    }

    fun runChecks(files: Map<String, ByteArray>, skip: Set<String> = emptySet()): Result {
        val checks = ArrayList<Check>()
        fun add(code: String, ok: Boolean, detail: String = "") {
            if (code !in skip) checks += Check(code, ok, detail)
        }

        add("MANIFEST_CONSISTENT", manifestConsistent(files))

        val regenerated = generateFiles()
        val identical = ALL_FILES.all { name ->
            val committed = files[name] ?: return@all false
            committed.contentEquals(regenerated.getValue(name))
        }
        add("BYTE_IDENTITY", identical, "files=${ALL_FILES.size}")

        val dimsText = text(files, "dimensions.tsv")
        val casesText = text(files, "cases.tsv")
        val corruptText = text(files, "corrupt.tsv")
        val headersText = text(files, "headers.tsv")
        val headersOk = rowsOf(headersText)?.firstOrNull() == HEADER_HEADER
        add(
            "HEADER_STRICT",
            rowsOf(dimsText)?.firstOrNull() == DIM_HEADER &&
                rowsOf(casesText)?.firstOrNull() == CASE_HEADER &&
                rowsOf(corruptText)?.firstOrNull() == CORRUPT_HEADER && headersOk,
        )

        val dims = parseDimensions(dimsText)
        val cases = parseCases(casesText)
        val corrupt = parseCorrupt(corruptText)
        val headers = parseHeaders(headersText)
        add("FIELD_COUNT", dims != null && cases != null && corrupt != null && headers != null)

        add(
            "CASE_COUNT",
            cases?.size == CASE_COUNT && corrupt?.size == CORRUPT_COUNT &&
                headers?.size == HEADER_ROWS && dims?.get("caseCount") == CASE_COUNT.toString() &&
                dims["corruptCount"] == CORRUPT_COUNT.toString() &&
                dims["headerRows"] == HEADER_ROWS.toString(),
        )

        add("CORRUPT_FAIL_CLOSED", corrupt?.all { rejects(it.second) } == true,
            "rows=${corrupt?.size ?: 0}")

        val prose = (text(files, "contract.txt").orEmpty() + "\n" + dimsText.orEmpty())
        add("PRIVACY_SCAN", privacyOffenders(prose, dims?.get("markerSeed") ?: SEED).isEmpty())

        add("PINNED_DIGEST", dims?.get("aggregateSha256") == aggregateDigest(files))

        if (cases == null || dims == null) {
            add("CONTRACT_ADMISSION", false)
            add("STABLE_IDENTITY", false)
            add("REPEATED_AGGREGATION", false)
            add("HEADER_INTERN_CAP", false)
            return Result(checks)
        }

        val occurrences = HashMap<String, Int>()
        var admitted = 0
        var refused = 0
        var identityOk = true
        var admissionOk = true
        for (c in cases) {
            val imported = try {
                PlaylistParser.parseReport(casePlaylist(c).reader(), 16, c.source)
            } catch (error: Throwable) {
                null
            }
            val got = imported?.channels?.size ?: 0
            val expectedAdmit = c.expect == "ADMIT"
            val gotAdmit = got == 1
            if (gotAdmit != expectedAdmit) admissionOk = false
            if (expectedAdmit && gotAdmit) {
                admitted++
                val channel = imported!!.channels[0]
                val expectedLogo = if (c.logoMode == "OK") logoUrl("OK", c.id) else null
                val expectedGroup = if (c.group == "-") null else c.group
                val expectedAgent = if (c.headerMode == "UA" || c.headerMode == "BOTH") {
                    agentValue(c.id)
                } else {
                    null
                }
                val expectedRef = if (c.headerMode == "REF" || c.headerMode == "BOTH") {
                    refererValue(c.id)
                } else {
                    null
                }
                if (channel.name != c.name || channel.guideId != c.guide ||
                    channel.logoUrl != expectedLogo || channel.group != expectedGroup ||
                    channel.userAgent != expectedAgent || channel.referer != expectedRef
                ) admissionOk = false
                val idOracle = shortDigest(c.source + "\u0000" + c.guide + "\u0000" +
                    streamUrl(c.urlMode, c.id))
                val base = "g\u0000" + c.guide
                val occurrence = occurrences[base] ?: 0
                occurrences[base] = occurrence + 1
                val keyOracle = shortDigest(base + "\u0000" + occurrence.toString())
                if (channel.id != idOracle || channel.stableKey != keyOracle) identityOk = false
            } else if (!expectedAdmit) {
                refused++
            }
        }
        add("CONTRACT_ADMISSION", admissionOk, "admitted=$admitted refused=$refused")
        add("STABLE_IDENTITY", identityOk)

        val cycleCount = dims["cycles"]?.toIntOrNull() ?: -1
        val expectedAdmitted = dims["admittedPerCycle"]?.toIntOrNull() ?: -1
        val expectedRefused = dims["refusedPerCycle"]?.toIntOrNull() ?: -1
        var aggregationOk = cycleCount > 0 && expectedAdmitted == ADMITTED_PER_CYCLE &&
            expectedRefused == REFUSED_PER_CYCLE
        var baseline: Set<String>? = null
        repeat(maxOf(cycleCount, 0)) {
            val ids = HashSet<String>()
            var accepted = 0
            var rejected = 0
            for (c in cases) {
                val imported = try {
                    PlaylistParser.parseReport(casePlaylist(c).reader(), 16, c.source)
                } catch (error: Throwable) {
                    null
                }
                if (imported?.channels?.size == 1) {
                    accepted++
                    ids += imported.channels[0].id
                } else {
                    rejected++
                }
            }
            if (accepted != expectedAdmitted || rejected != expectedRefused) aggregationOk = false
            if (baseline == null) baseline = ids else if (baseline != ids) aggregationOk = false
            if (ids.size != expectedAdmitted) aggregationOk = false
        }
        add("REPEATED_AGGREGATION", aggregationOk, "cycles=$cycleCount")

        if (headers == null) {
            add("HEADER_INTERN_CAP", false)
            return Result(checks)
        }
        val importedHeaders = try {
            PlaylistParser.parseReport(headerPlaylist(headers).reader(), HEADER_ADMITTED, "qa53-headers")
        } catch (error: Throwable) {
            null
        }
        var capOk = importedHeaders?.channels?.size == HEADER_ADMITTED
        if (capOk) {
            for (row in headers) {
                val first = importedHeaders!!.channels[row.seq * 2].userAgent
                val second = importedHeaders.channels[row.seq * 2 + 1].userAgent
                if ((first === second) != (row.seq < HEADER_CAP)) {
                    capOk = false
                    break
                }
            }
        }
        add("HEADER_INTERN_CAP", capOk, "channels=${importedHeaders?.channels?.size ?: 0}")
        return Result(checks)
    }
}
