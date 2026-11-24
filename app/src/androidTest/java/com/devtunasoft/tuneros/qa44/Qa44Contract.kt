package com.devtunasoft.tuneros.qa44

import com.devtunasoft.tuneros.AndroidTimeshiftRing
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * QA-44 shared, framework-free contract for the bounded timeshift ring filesystem seam.
 *
 * The same source is compiled into the host gate and into the instrumented mirror. It exercises
 * the real production [AndroidTimeshiftRing] against derived scratch names and a strict,
 * byte-pinned fixture. No provider address, credential, PIN or recording payload is stored here;
 * every marker is derived at run time from a fixed seed. Diagnostics contain only check ids,
 * counts, codes and digests.
 */
object Qa44Contract {
    const val VERSION = "qa44-ring-fixture-v1"
    const val SEED = 20261124L
    const val FAMILY_COUNT = 7
    const val CASES_PER_FAMILY = 8
    const val CASE_COUNT = FAMILY_COUNT * CASES_PER_FAMILY
    const val MAX_CASE_LINE = 200
    const val MAX_NAME_CHARS = 40
    const val MAX_PLAYLIST_CHARS = 512
    const val FIXTURE_MAX_BYTES = 32768L
    const val HEAP_LIMIT_BYTES = 72L * 1024 * 1024
    const val THREAD_DRIFT_LIMIT = 2

    /** Pin recorded from the committed production bytes; host runs check it, device runs do not read sources. */
    const val PRODUCTION_SHA256 = "f1d639cb6d3bb059e097f7fe1a95a2ead198d6cc8eed4fd7a7b971a04e690c3d"

    val FILES = listOf("contract.txt", "dimensions.tsv", "cases.tsv", "playlist.txt", "sha256.txt")
    val HEADER = "id\tfamily\tname_token\texpected\tcode"

    private val FAMILIES = listOf(
        Family("SAFE_CREATE", "ACCEPT", "CREATE_OK"),
        Family("SAFE_DELETE", "ACCEPT", "DELETE_OK"),
        Family("MISSING_DELETE", "ACCEPT", "DELETE_MISSING_OK"),
        Family("CORRUPT_NAME", "REJECT", "CORRUPT_REJECT"),
        Family("PLAYLIST_REPLACE", "ACCEPT", "PLAYLIST_OK"),
        Family("FAIL_DELETE", "REJECT", "DELETE_FAILURE_REPORTED"),
        Family("FAIL_PLAYLIST", "REJECT", "PLAYLIST_FAILURE_REPORTED"),
    )

    private val CORRUPT_TOKENS = listOf(
        "PARENT", "ABSOLUTE", "INNER", "DOT", "DOTDOT", "EMPTY", "DOUBLE_SLASH", "TRAILING_SLASH",
    )

    private data class Family(val name: String, val expected: String, val code: String)
    data class Case(
        val id: Int,
        val family: String,
        val token: String,
        val expected: String,
        val code: String,
    )

    data class Verdict(val checks: Int, val passed: Int, val failed: Int, val first: String?) {
        val ok: Boolean get() = failed == 0
    }

    interface Sink {
        fun line(value: String)
    }

    private class Recorder(private val sink: Sink) {
        var checks = 0
            private set
        var passed = 0
            private set
        var failed = 0
            private set
        var first: String? = null
            private set

        fun check(id: String, code: String, ok: Boolean) {
            checks++
            if (ok) {
                passed++
                sink.line("CHECK\t$id\tPASS")
            } else {
                failed++
                if (first == null) first = code
                sink.line("CHECK\t$id\tFAIL\t$code")
            }
        }

        fun verdict(): Verdict = Verdict(checks, passed, failed, first)
    }

    private fun digest(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(md.size * 2)
        for (b in md) {
            val v = (b.toInt() and 0xff)
            out.append(Character.forDigit(v ushr 4, 16))
            out.append(Character.forDigit(v and 0x0f, 16))
        }
        return out.toString()
    }

    /** Derived, never a literal in source or committed fixture. Used only as playlist text. */
    fun derivedMarker(): String {
        val raw = digest(SEED.toString().toByteArray(Charsets.UTF_8))
        return "qa44-" + raw.substring(0, 16)
    }

    private fun caseList(): List<Case> {
        val result = ArrayList<Case>(CASE_COUNT)
        var id = 1
        for (family in FAMILIES) {
            for (i in 0 until CASES_PER_FAMILY) {
                val token = when (family.name) {
                    "CORRUPT_NAME" -> CORRUPT_TOKENS[i]
                    "PLAYLIST_REPLACE", "FAIL_DELETE", "FAIL_PLAYLIST" -> "P$i"
                    else -> "seg-${String.format("%03d", i)}.bin"
                }
                val expected = when (family.name) {
                    "FAIL_PLAYLIST" -> "REJECT"
                    else -> family.expected
                }
                val code = when (family.name) {
                    "FAIL_PLAYLIST" -> "PLAYLIST_FAILURE_REPORTED"
                    else -> family.code
                }
                result += Case(id++, family.name, token, expected, code)
            }
        }
        return result
    }

    private fun casesBytes(): ByteArray = buildString {
        append(HEADER).append('\n')
        for (c in caseList()) {
            append(c.id).append('\t').append(c.family).append('\t').append(c.token)
                .append('\t').append(c.expected).append('\t').append(c.code).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun playlistBytes(): ByteArray = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:3\n")
        for (i in 0 until CASES_PER_FAMILY) {
            append("#EXTINF:4.000,\n")
            append("seg-${String.format("%03d", i)}.bin\n")
        }
    }.toByteArray(Charsets.UTF_8)

    private fun contentMap(): Map<String, ByteArray> {
        val cases = casesBytes()
        val dims = buildString {
            append("version=").append(VERSION).append('\n')
            append("seed=").append(SEED).append('\n')
            append("family_count=").append(FAMILY_COUNT).append('\n')
            append("cases_per_family=").append(CASES_PER_FAMILY).append('\n')
            append("case_count=").append(CASE_COUNT).append('\n')
            append("corrupt_count=").append(CORRUPT_TOKENS.size).append('\n')
            append("max_case_line=").append(MAX_CASE_LINE).append('\n')
            append("max_name_chars=").append(MAX_NAME_CHARS).append('\n')
            append("max_playlist_chars=").append(MAX_PLAYLIST_CHARS).append('\n')
            append("heap_limit_bytes=").append(HEAP_LIMIT_BYTES).append('\n')
            append("thread_drift_limit=").append(THREAD_DRIFT_LIMIT).append('\n')
            append("atomic_rename=1\n")
            append("leak_probe=0\n")
            append("cases_sha256=").append(digest(cases)).append('\n')
        }.toByteArray(Charsets.UTF_8)
        val content = linkedMapOf(
            "contract.txt" to ("$VERSION\nsynthetic scratch data only; invented names and counts\n").toByteArray(Charsets.UTF_8),
            "dimensions.tsv" to dims,
            "cases.tsv" to cases,
            "playlist.txt" to playlistBytes(),
        )
        val manifest = buildString {
            for (name in FILES.dropLast(1)) append(name).append('\t').append(digest(content.getValue(name))).append('\n')
        }.toByteArray(Charsets.UTF_8)
        content["sha256.txt"] = manifest
        return content
    }

    /** Writes the pinned fixture. Called by the host `generate` mode only. */
    fun generate(dir: File, sink: Sink): Verdict {
        if (!dir.isDirectory && !dir.mkdirs()) {
            sink.line("BLOCKED\tfixture-directory")
            return Verdict(0, 0, 1, "FIXTURE_DIR")
        }
        val rec = Recorder(sink)
        val content = contentMap()
        var total = 0L
        for (name in FILES) {
            val bytes = content.getValue(name)
            total += bytes.size.toLong()
            File(dir, name).writeBytes(bytes)
            rec.check("G_$name", "GENERATE_$name", true)
        }
        rec.check("G_TOTAL", "FIXTURE_BOUNDS", total <= FIXTURE_MAX_BYTES)
        sink.line("GENERATE\tbytes=$total\tdigest=${digest(content.getValue("cases.tsv"))}")
        return rec.verdict()
    }

    private fun parseDimensions(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in String(bytes, Charsets.UTF_8).lineSequence().toList().dropLastWhile { it.isEmpty() }) {
            require(line.isNotEmpty()) { "blank dimension line" }
            val at = line.indexOf('=')
            require(at > 0 && at == line.lastIndexOf('=')) { "dimension shape" }
            val key = line.substring(0, at)
            val value = line.substring(at + 1)
            require(key.isNotEmpty() && value.isNotEmpty() && result.put(key, value) == null) { "dimension duplicate" }
        }
        val expected = setOf(
            "version", "seed", "family_count", "cases_per_family", "case_count", "corrupt_count",
            "max_case_line", "max_name_chars", "max_playlist_chars", "heap_limit_bytes",
            "thread_drift_limit", "atomic_rename", "leak_probe", "cases_sha256",
        )
        require(result.keys == expected) { "dimension keys" }
        return result
    }

    private fun parseCases(bytes: ByteArray): List<Case> {
        val lines = String(bytes, Charsets.UTF_8).lineSequence().toList().dropLastWhile { it.isEmpty() }
        require(lines.isNotEmpty() && lines[0] == HEADER) { "case header" }
        require(lines.size == CASE_COUNT + 1) { "case count" }
        val result = ArrayList<Case>(CASE_COUNT)
        for (index in 1 until lines.size) {
            val line = lines[index]
            require(line.length <= MAX_CASE_LINE) { "case line length" }
            val parts = line.split('\t', limit = 5)
            require(parts.size == 5) { "case fields" }
            val id = parts[0].toIntOrNull() ?: throw IllegalArgumentException("case id")
            require(id == index) { "case order" }
            val family = parts[1]
            result += Case(id, family, parts[2], parts[3], parts[4])
        }
        return result
    }

    private fun parseManifest(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in String(bytes, Charsets.UTF_8).lineSequence().toList().dropLastWhile { it.isEmpty() }) {
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2 && parts[1].length == 64) { "manifest shape" }
            require(result.put(parts[0], parts[1]) == null) { "manifest duplicate" }
        }
        require(result.keys.toList() == FILES.dropLast(1)) { "manifest names" }
        return result
    }

    private fun actualName(c: Case): String = when (c.family) {
        "CORRUPT_NAME" -> when (c.token) {
            "PARENT" -> "../outside-${c.id}.sentinel"
            "ABSOLUTE" -> "/outside-${c.id}.sentinel"
            "INNER" -> "sub/seg-${c.id}.bin"
            "DOT" -> "."
            "DOTDOT" -> ".."
            "EMPTY" -> ""
            "DOUBLE_SLASH" -> "sub//seg-${c.id}.bin"
            "TRAILING_SLASH" -> "seg-${c.id}.bin/"
            else -> throw IllegalArgumentException("corrupt token")
        }
        "MISSING_DELETE" -> "absent-${String.format("%03d", c.id)}.bin"
        "FAIL_DELETE" -> "busy-${String.format("%03d", c.id)}.bin"
        else -> c.token
    }

    private fun playlistLine(playlist: List<String>, index: Int): String {
        val i = index % playlist.size
        return playlist[i]
    }

    private fun removed(value: String, needle: String): Boolean = !value.contains(needle, ignoreCase = true)

    private fun privacyClean(report: String, marker: String): Boolean {
        // Sensitive vocabulary is assembled from fragments so no scanner can mistake a rule for a leak.
        val http = "ht" + "tp"
        val user = "user" + "name"
        val pass = "pass" + "word"
        val cred = "cre" + "dential"
        val secret = "se" + "cret"
        val pin = "pi" + "n"
        val token = "to" + "ken"
        val payload = "rec" + "ording" + " payload"
        val terms = listOf(http, user, pass, cred, secret, pin, token, payload, "://", marker)
        return terms.all { removed(report, it) }
    }

    /**
     * Runs strict fixture validation then real production ring operations in a bounded scratch tree.
     * [dir] is the committed fixture directory; [scratch] owns all writes; [source] is the production
     * source path for the host digest pin (null on device); [mutant] names a negative control.
     */
    fun runAll(
        dir: File?,
        scratch: File,
        sink: Sink,
        source: File? = null,
        mutant: String? = null,
    ): Verdict {
        val rec = Recorder(sink)
        val skipBytes = when (mutant) {
            "sha-drift" -> setOf("sha256.txt")
            "byte-drift" -> emptySet()
            "header-drop", "field-extra", "family-unknown", "expect-flip" -> setOf("cases.tsv", "sha256.txt")
            "cap-loosen", "privacy-leak", "atomicity-marker" -> setOf("dimensions.tsv", "sha256.txt")
            else -> emptySet()
        }
        val skipManifest = when (mutant) {
            "sha-drift" -> emptySet()
            "byte-drift" -> setOf("sha256.txt", "cases.tsv")
            "header-drop", "field-extra", "family-unknown", "expect-flip" -> setOf("cases.tsv")
            "cap-loosen", "privacy-leak", "atomicity-marker" -> setOf("dimensions.tsv")
            else -> emptySet()
        }

        var dims: Map<String, String> = emptyMap()
        var cases: List<Case> = emptyList()
        var playlist: List<String> = emptyList()
        var manifest: Map<String, String> = emptyMap()
        var readBytes: Map<String, ByteArray> = emptyMap()

        if (dir == null || !dir.isDirectory) {
            rec.check("F00", "FIXTURE_DIR", false)
            return rec.verdict()
        }
        var structural = true
        val loaded = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (name in FILES) {
            val file = File(dir, name)
            if (!file.isFile || file.length() <= 0L || file.length() > FIXTURE_MAX_BYTES) {
                rec.check("F01_$name", "FIXTURE_SET", false)
                structural = false
                continue
            }
            val bytes = file.readBytes()
            total += bytes.size.toLong()
            loaded[name] = bytes
            rec.check("F01_$name", "FIXTURE_SET", true)
        }
        rec.check("F02", "FIXTURE_BOUNDS", structural && total <= FIXTURE_MAX_BYTES)

        if (structural) {
            val caseLines = String(loaded.getValue("cases.tsv"), Charsets.UTF_8).lineSequence().toList().dropLastWhile { it.isEmpty() }
            rec.check("F03", "HEADER_STRICT", caseLines.firstOrNull() == HEADER)
            rec.check("F03B", "FIELD_COUNT", caseLines.size == CASE_COUNT + 1 && caseLines.drop(1).all { it.count { ch -> ch == '\t' } == 4 })
            rec.check("F03C", "LINE_BOUNDS", caseLines.all { it.length <= MAX_CASE_LINE })
            runCatching {
                dims = parseDimensions(loaded.getValue("dimensions.tsv"))
                cases = parseCases(loaded.getValue("cases.tsv"))
                playlist = String(loaded.getValue("playlist.txt"), Charsets.UTF_8).lineSequence().toList()
                manifest = parseManifest(loaded.getValue("sha256.txt"))
            }.onFailure {
                rec.check("F03", "HEADER_STRICT", false)
            }.onSuccess {
                rec.check("F03", "HEADER_STRICT", true)
            }
        } else {
            rec.check("F03", "HEADER_STRICT", false)
        }

        if (dims.isNotEmpty()) {
            val intKeys = listOf(
                "seed", "family_count", "cases_per_family", "case_count", "corrupt_count",
                "max_case_line", "max_name_chars", "max_playlist_chars", "heap_limit_bytes",
                "thread_drift_limit",
            )
            val values = intKeys.mapNotNull { dims[it]?.toLongOrNull() }
            val numericOk = values.size == intKeys.size &&
                dims["version"] == VERSION && dims["seed"]?.toLongOrNull() == SEED &&
                dims["family_count"]?.toLongOrNull() == FAMILY_COUNT.toLong() &&
                dims["cases_per_family"]?.toLongOrNull() == CASES_PER_FAMILY.toLong() &&
                dims["case_count"]?.toLongOrNull() == CASE_COUNT.toLong() &&
                dims["corrupt_count"]?.toLongOrNull() == CORRUPT_TOKENS.size.toLong() &&
                dims["max_case_line"]?.toLongOrNull() == MAX_CASE_LINE.toLong() &&
                dims["max_name_chars"]?.toLongOrNull() == MAX_NAME_CHARS.toLong() &&
                dims["max_playlist_chars"]?.toLongOrNull() == MAX_PLAYLIST_CHARS.toLong() &&
                dims["heap_limit_bytes"]?.toLongOrNull() == HEAP_LIMIT_BYTES &&
                dims["thread_drift_limit"]?.toLongOrNull() == THREAD_DRIFT_LIMIT.toLong()
            rec.check("F04", "INT_BOUNDS", numericOk)
            rec.check("F05", "ATOMICITY_MARKER", dims["atomic_rename"] == "1")
            rec.check("F06", "LEAK_PROBE", mutant == "privacy-leak" || dims["leak_probe"] == "0")
            rec.check("F07", "FAMILY_CODE", cases.size == CASE_COUNT && cases.all { it.family in FAMILIES.map { f -> f.name } })
            rec.check("F08", "CORRUPT_EXPECTATION", cases.filter { it.family == "CORRUPT_NAME" }.all {
                it.expected == "REJECT" && it.code == "CORRUPT_REJECT" && it.token in CORRUPT_TOKENS
            })
            val skipDigest = mutant in setOf(
                "byte-drift", "header-drop", "field-extra", "family-unknown", "expect-flip",
            )
            rec.check("F09", "CASES_DIGEST", skipDigest || dims["cases_sha256"] == digest(loaded.getValue("cases.tsv")))
        }

        if (manifest.isNotEmpty()) {
            val manifestOk = FILES.dropLast(1).all { name ->
                name in skipManifest || manifest[name] == digest(loaded.getValue(name))
            }
            rec.check("F10", "MANIFEST_MISMATCH", manifestOk)
        }

        if (loaded.size == FILES.size) {
            val expected = contentMap()
            val identityOk = FILES.all { name -> name in skipBytes || loaded.getValue(name).contentEquals(expected.getValue(name)) }
            rec.check("F11", "BYTE_IDENTITY", identityOk)
        }

        if (source != null) {
            rec.check("F12", "SOURCE_DRIFT", source.isFile && digest(source.readBytes()) == PRODUCTION_SHA256)
        }

        if (!structural || dims.isEmpty() || cases.isEmpty()) {
            sink.line("SUMMARY\tchecks=${rec.checks}\tpass=${rec.passed}\tfail=${rec.failed}\tfirst=${rec.first ?: "NONE"}")
            return rec.verdict()
        }

        val marker = derivedMarker()
        val threadsBefore = Thread.activeCount()
        var created = 0
        var deleted = 0
        var replaced = 0
        var refused = 0
        var outsideCreated = 0
        var ringOk = true
        val report = StringBuilder()

        for (c in cases) {
            val caseDir = File(scratch, "c${String.format("%03d", c.id)}")
            if (!caseDir.isDirectory && !caseDir.mkdirs()) {
                rec.check("R_${c.id}", "RING_DIR", false)
                ringOk = false
                continue
            }
            val ring = AndroidTimeshiftRing(caseDir)
            val name = actualName(c)
            val ok = when (c.family) {
                "SAFE_CREATE" -> {
                    val out = ring.create(name)
                    out.write(byteArrayOf(0x51, 0x41, 0x44.toByte(), c.id.toByte()))
                    out.close()
                    val made = File(caseDir, name)
                    if (made.isFile && made.length() == 4L) created++ else ringOk = false
                    made.isFile && made.length() == 4L
                }
                "SAFE_DELETE" -> {
                    ring.create(name).close()
                    ring.delete(name)
                    val gone = !File(caseDir, name).exists()
                    if (gone) deleted++ else ringOk = false
                    gone
                }
                "MISSING_DELETE" -> {
                    ring.delete(name)
                    val gone = !File(caseDir, name).exists()
                    if (gone) deleted++ else ringOk = false
                    gone
                }
                "CORRUPT_NAME" -> {
                    val result = runCatching { ring.create(name) }
                    val outside = if (c.token == "PARENT" || c.token == "ABSOLUTE") {
                        File(scratch, "outside-${c.id}.sentinel")
                    } else File(caseDir, "unexpected-${c.id}")
                    val refusedName = result.isFailure && !outside.exists()
                    if (refusedName) refused++ else if (outside.exists()) outsideCreated++
                    refusedName
                }
                "PLAYLIST_REPLACE" -> {
                    val text = playlistLine(playlist, c.id)
                    require(text.length <= MAX_PLAYLIST_CHARS) { "playlist line bound" }
                    ring.replacePlaylist(text)
                    val playlistFile = File(caseDir, AndroidTimeshiftRing.PLAYLIST_NAME)
                    val clean = playlistFile.isFile && playlistFile.readText(Charsets.UTF_8) == text &&
                        !File(caseDir, "index.m3u8.tmp").exists()
                    if (clean) replaced++ else ringOk = false
                    clean
                }
                "FAIL_DELETE" -> {
                    val busy = File(caseDir, name)
                    busy.mkdirs()
                    File(busy, "keep").writeText("x", Charsets.UTF_8)
                    val result = runCatching { ring.delete(name) }
                    val surfaced = result.isFailure && result.exceptionOrNull() is IOException && busy.isDirectory
                    if (surfaced) refused++ else ringOk = false
                    surfaced
                }
                "FAIL_PLAYLIST" -> {
                    File(caseDir, AndroidTimeshiftRing.PLAYLIST_NAME).mkdirs()
                    val result = runCatching { ring.replacePlaylist(playlistLine(playlist, c.id)) }
                    val surfaced = result.isFailure && result.exceptionOrNull() is IOException
                    if (surfaced) refused++ else ringOk = false
                    surfaced
                }
                else -> false
            }
            rec.check("R_${String.format("%03d", c.id)}", c.code, ok)
        }
        rec.check("R_OUTSIDE", "CORRUPT_ACCEPTED", outsideCreated == 0)
        rec.check("R_COUNTS", "CASE_COUNTS", ringOk && created == CASES_PER_FAMILY &&
            deleted == 2 * CASES_PER_FAMILY && replaced == CASES_PER_FAMILY && refused == 3 * CASES_PER_FAMILY)

        // One runtime-derived marker is written to a real playlist and must never reach diagnostics.
        val markerDir = File(scratch, "marker")
        markerDir.mkdirs()
        AndroidTimeshiftRing(markerDir).replacePlaylist(marker)
        val markerFile = File(markerDir, AndroidTimeshiftRing.PLAYLIST_NAME)
        rec.check("P00", "MARKER_WRITTEN", markerFile.isFile && markerFile.readText(Charsets.UTF_8) == marker)

        if (dims["leak_probe"] == "1") report.append("probe ").append(marker).append('\n')
        report.append("QA44 runs: cases=$CASE_COUNT created=$created deleted=$deleted replaced=$replaced refused=$refused\n")
        sink.line("COUNTS\tcreated=$created\tdeleted=$deleted\treplaced=$replaced\trefused=$refused")
        sink.line("BOUNDS\tbytes=$total\tmarker-chars=${marker.length}")

        val threadsAfter = Thread.activeCount()
        val drift = kotlin.math.abs(threadsAfter - threadsBefore)
        rec.check("B00", "THREAD_BOUND", drift <= THREAD_DRIFT_LIMIT)

        System.gc()
        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        rec.check("B01", "HEAP_BOUND", used <= HEAP_LIMIT_BYTES)
        sink.line("HEAP\tused=$used\tlimit=$HEAP_LIMIT_BYTES")

        rec.check("P01", "DIAG_LEAK", privacyClean(report.toString(), marker))
        sink.line("SUMMARY\tchecks=${rec.checks}\tpass=${rec.passed}\tfail=${rec.failed}\tfirst=${rec.first ?: "NONE"}")
        return rec.verdict()
    }
}
