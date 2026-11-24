package com.devtunasoft.tuneros.qa69

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * QA-69 Android-free mirror: the single deterministic fixture generator and its contract checks.
 * The host gate compiles this same source, so the host and the instrumented runner derive identical
 * bytes from the same constants instead of copying a generator into two languages.
 *
 * All names are invented scratch names. No provider address, credential, PIN, protected-content key
 * or recording payload is represented here.
 */
object Qa69Mirror {
    const val SEED: Int = 20261124
    const val ID_COUNT: Int = 48
    const val ORPHAN_PAGE_CAP: Int = 16
    const val LIMIT_LOWER: Int = 1
    const val LIMIT_UPPER: Int = 128
    private const val BASE_EPOCH: Long = 1_800_000_000L

    private val DATA_FILES = listOf("dimensions.tsv", "cases.tsv")
    val ALL_FILES = listOf(".gitattributes", "README.md", "contract.txt") + DATA_FILES + listOf("sha256.txt")

    data class Row(
        val id: String,
        val title: String,
        val channel: String,
        val ssk: String,
        val start: Long,
        val end: Long,
        val state: String,
        val exact: Boolean,
        val detail: String?,
    )

    fun derivedMarker(): String {
        var hash = 2166136261L
        for (byte in SEED.toString().toByteArray(StandardCharsets.US_ASCII)) {
            hash = ((hash xor (byte.toLong() and 0xffL)) * 16777619L) and 0xffffffffL
        }
        return "QA69-" + java.lang.Long.toHexString(hash).padStart(8, '0')
    }

    private fun idOf(index: Int): String = "c" + index.toString().padStart(4, '0')

    val rows: List<Row> by lazy {
        (0 until ID_COUNT).map { index ->
            val state = when {
                index % 4 == 0 -> "PREPARING"
                index % 4 == 1 -> "RECOVERING"
                index % 4 == 2 -> "SCHEDULED"
                index % 8 == 3 -> "STARTED"
                else -> "FAILED"
            }
            val start = BASE_EPOCH + index * 120L
            Row(
                id = idOf(index),
                title = "scratch show " + index.toString().padStart(4, '0'),
                channel = "scratch-channel-" + (index % 6).toString().padStart(2, '0'),
                ssk = "ssk-" + index.toString().padStart(4, '0'),
                start = start,
                end = start + 90L,
                state = state,
                exact = index % 2 == 0,
                detail = if (index % 5 == 0) "scratch detail " + index.toString().padStart(4, '0') else null,
            )
        }
    }

    val orphanIds: List<String> by lazy {
        rows.filter { it.state == "PREPARING" || it.state == "RECOVERING" }.map { it.id }.sorted()
    }

    val afterId: String by lazy { orphanIds[2] }

    val page1Ids: List<String> by lazy { orphanIds.take(ORPHAN_PAGE_CAP) }

    val page2Ids: List<String> by lazy { orphanIds.filter { it > afterId }.take(ORPHAN_PAGE_CAP) }

    fun sha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    private fun renderCases(): String {
        val out = StringBuilder()
        out.append("qa69-cases-v1\n")
        out.append("id\ttitle\tchannel\tssk\tstart\tend\tstate\texact\tdetail\n")
        for (row in rows) {
            out.append(row.id).append('\t')
            out.append(row.title).append('\t')
            out.append(row.channel).append('\t')
            out.append(row.ssk).append('\t')
            out.append(row.start).append('\t')
            out.append(row.end).append('\t')
            out.append(row.state).append('\t')
            out.append(if (row.exact) '1' else '0').append('\t')
            out.append(row.detail ?: "").append('\n')
        }
        return out.toString()
    }

    private fun renderDimensions(casesSha: String): String {
        val out = StringBuilder()
        out.append("qa69-dimensions-v1\n")
        out.append("ID_COUNT\t").append(ID_COUNT).append('\n')
        out.append("ORPHAN_PAGE_CAP\t").append(ORPHAN_PAGE_CAP).append('\n')
        out.append("LIMIT_LOWER\t").append(LIMIT_LOWER).append('\n')
        out.append("LIMIT_UPPER\t").append(LIMIT_UPPER).append('\n')
        out.append("CASES_DIGEST\t").append(casesSha).append('\n')
        out.append("AFTER_ID\t").append(afterId).append('\n')
        out.append("PAGE1_IDS\t").append(page1Ids.joinToString(",")).append('\n')
        out.append("PAGE2_IDS\t").append(page2Ids.joinToString(",")).append('\n')
        return out.toString()
    }

    /** Every byte of every fixture file comes from these constants, in this order. */
    fun generateBytes(): Map<String, ByteArray> {
        val cases = bytes(renderCases())
        val dimensions = bytes(renderDimensions(sha(cases)))
        val manifest = bytes(sha(dimensions) + "\tdimensions.tsv\n" + sha(cases) + "\tcases.tsv\n")
        return linkedMapOf(
            ".gitattributes" to bytes("* -text\n.gitattributes -text\n"),
            "README.md" to bytes(
                "QA-69 scratch fixture. Invented names only; no provider address, credential, PIN,\n" +
                    "protected-content key or recording payload is present. Regenerated byte-for-byte.\n"
            ),
            "contract.txt" to bytes(
                "qa69-contract-v1\n" +
                    "The rows describe a bounded scheduled-recording preparation set. Only rows in\n" +
                    "PREPARING or RECOVERING are orphaned work. Enumeration is ordered by id, is\n" +
                    "exclusive of the after id and is bounded by the page cap. A claim may move only\n" +
                    "an inert reservation with the same id and scratch source marker into RECOVERING.\n" +
                    "Deletion removes only a RECOVERING row with the same scratch source marker.\n" +
                    "Diagnostics carry check ids, counts and digests only.\n"
            ),
            "dimensions.tsv" to dimensions,
            "cases.tsv" to cases,
            "sha256.txt" to manifest,
        )
    }

    private fun lines(text: String): List<String> =
        text.split('\n').dropLastWhile { it.isEmpty() }

    fun parseDimensions(text: String): Map<String, String>? {
        val all = lines(text)
        if (all.isEmpty() || all[0] != "qa69-dimensions-v1") return null
        val result = LinkedHashMap<String, String>()
        for (line in all.drop(1)) {
            val fields = line.split('\t', limit = 2)
            if (fields.size != 2) return null
            result[fields[0]] = fields[1]
        }
        return result
    }

    fun parseCases(text: String): List<Row>? {
        val all = lines(text)
        if (all.size < 2 || all[0] != "qa69-cases-v1") return null
        if (all[1] != "id\ttitle\tchannel\tssk\tstart\tend\tstate\texact\tdetail") return null
        val result = ArrayList<Row>()
        for (line in all.drop(2)) {
            val fields = line.split('\t', limit = 9)
            if (fields.size != 9) return null
            val start = fields[4].toLongOrNull() ?: return null
            val end = fields[5].toLongOrNull() ?: return null
            val exact = when (fields[7]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            result += Row(
                id = fields[0], title = fields[1], channel = fields[2], ssk = fields[3],
                start = start, end = end, state = fields[6], exact = exact,
                detail = fields[8].ifEmpty { null },
            )
        }
        return result
    }

    fun parseManifest(text: String): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        for (line in lines(text)) {
            val fields = line.split('\t', limit = 2)
            if (fields.size != 2) return null
            result[fields[1]] = fields[0]
        }
        return result
    }

    /** Contract-only checks shared by the host verifier and the instrumented mirror. */
    fun computeChecks(): Pair<String, Boolean> {
        val report = StringBuilder()
        var pass = 0
        var fail = 0
        fun check(code: String, ok: Boolean) {
            if (ok) pass++ else fail++
            report.append("CHECK\t").append(code).append('\t').append(if (ok) "PASS" else "FAIL").append('\n')
        }
        val generated = generateBytes()
        val cases = parseCases(String(generated.getValue("cases.tsv"), StandardCharsets.UTF_8))
        val dimensions = parseDimensions(String(generated.getValue("dimensions.tsv"), StandardCharsets.UTF_8))
        val manifest = parseManifest(String(generated.getValue("sha256.txt"), StandardCharsets.UTF_8))
        check("GENERATED_FILES", generated.size == ALL_FILES.size && ALL_FILES.all { generated.containsKey(it) })
        check("CASES_PARSE", cases != null)
        check("DIMENSIONS_PARSE", dimensions != null)
        check("MANIFEST_PARSE", manifest != null)
        if (cases != null) {
            check("CASE_COUNT", cases.size == ID_COUNT)
            check("ORPHAN_COUNT", orphanIds.size == 24)
            check("FIELD_WIDTH", cases.all { it.id.length == 5 && it.ssk.length == 8 && it.end > it.start })
        }
        if (dimensions != null) {
            check("PINNED_CAPS", dimensions["ID_COUNT"] == ID_COUNT.toString() &&
                dimensions["ORPHAN_PAGE_CAP"] == ORPHAN_PAGE_CAP.toString() &&
                dimensions["LIMIT_LOWER"] == LIMIT_LOWER.toString() &&
                dimensions["LIMIT_UPPER"] == LIMIT_UPPER.toString())
            check("PINNED_CASES_DIGEST", dimensions["CASES_DIGEST"] == sha(generated.getValue("cases.tsv")))
            check("PINNED_AFTER_ID", dimensions["AFTER_ID"] == afterId)
        }
        check("PAGE1_COUNT", page1Ids.size == ORPHAN_PAGE_CAP)
        check("PAGE2_COUNT", page2Ids.size == ORPHAN_PAGE_CAP && page2Ids.none { it == afterId })
        if (manifest != null) {
            check("MANIFEST_MATCH", DATA_FILES.all { manifest[it] == sha(generated.getValue(it)) })
        }
        report.append("SUMMARY\ttests=").append(pass + fail)
            .append("\tpass=").append(pass).append("\tfail=").append(fail).append('\n')
        report.append("MIRROR_DIGEST\t").append(sha(generated.getValue("cases.tsv"))).append('\n')
        val text = report.toString()
        return text to (fail == 0)
    }

    fun runChecks(): Pair<String, Boolean> {
        val result = computeChecks()
        print(result.first)
        System.out.flush()
        return result
    }

    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        if (!runChecks().second) throw IllegalStateException("QA-69 mirror failed")
    }
}
