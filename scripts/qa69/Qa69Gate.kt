package com.devtunasoft.tuneros.qa69

import android.content.Context
import com.devtunasoft.tuneros.RecordingScheduleEntry
import com.devtunasoft.tuneros.RecordingScheduleStore
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * QA-69 host gate: bounded, byte-pinned checks over the real compiled RecordingScheduleStore
 * orphaned-preparation claim/delete state machine. No behaviour is reimplemented here; the only
 * fake is a small scratch SQLite stand-in that evaluates the SQL the production store emits.
 */
object Qa69Gate {
    private val threadStart = Thread.activeCount()
    private val diagnostics = StringBuilder()
    private var firstCode = "NONE"
    private var passCount = 0
    private var failCount = 0

    private val sensitive = listOf(
        Regex("""(?i)(?:password|passwd|pwd|token|secret|credential|authorization|bearer)[^\p{Alnum}]{0,3}[=:]"""),
        Regex("""(?i)\bpin[^\p{Alnum}]{0,3}[=:]"""),
        Regex("""(?i)(?:drm|widevine|playready|fairplay|keyid|license)[^\p{Alnum}]{0,3}[=:]"""),
        Regex("""(?i)(?:payload|media-data|recording-bytes)[^\p{Alnum}]{0,3}[=:]"""),
        Regex("""[A-Za-z][A-Za-z0-9+.-]*://"""),
    )

    private fun say(line: String) {
        println(line)
        diagnostics.append(line).append('\n')
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

    private fun finish(): Nothing {
        say("THREADS\tstart=$threadStart\tend=${Thread.activeCount()}")
        say("FIRST\t$firstCode")
        say("SUMMARY\ttests=${passCount + failCount}\tpass=$passCount\tfail=$failCount")
        exitProcess(if (failCount == 0 && firstCode == "NONE") 0 else 1)
    }

    private fun read(directory: File, name: String): ByteArray? = try {
        File(directory, name).readBytes()
    } catch (_: Exception) {
        null
    }

    private fun generate(directory: File) {
        directory.mkdirs()
        val generated = Qa69Mirror.generateBytes()
        for ((name, bytes) in generated) File(directory, name).writeBytes(bytes)
        say("GENERATED\tfiles=${generated.size}")
        exitProcess(0)
    }

    private fun entryOf(row: Qa69Mirror.Row): RecordingScheduleEntry = RecordingScheduleEntry(
        id = row.id,
        title = row.title,
        channelId = row.channel,
        secureSourceKey = row.ssk,
        startEpochSeconds = row.start,
        endEpochSeconds = row.end,
        state = row.state,
        exactAlarm = row.exact,
        lastError = row.detail,
    )

    private fun semantic(cases: List<Qa69Mirror.Row>, mode: String, extra: String?) {
        RecordingScheduleStore.qaDatabaseName = "qa69-scratch"
        val store = RecordingScheduleStore(Context())
        val cap = Qa69Mirror.ORPHAN_PAGE_CAP

        val corrupt = cases.first().copy(end = cases.first().start - 1)
        var corruptRejected = false
        try {
            store.insertIfAbsent(entryOf(corrupt))
        } catch (_: IllegalArgumentException) {
            corruptRejected = true
        }
        check("CORRUPT_REJECT", corruptRejected)

        var lowRejected = false
        var highRejected = false
        try {
            store.orphanedPreparationsAfter(null, Qa69Mirror.LIMIT_LOWER - 1)
        } catch (_: IllegalArgumentException) {
            lowRejected = true
        }
        try {
            store.orphanedPreparationsAfter(null, Qa69Mirror.LIMIT_UPPER + 1)
        } catch (_: IllegalArgumentException) {
            highRejected = true
        }
        check("LIMIT_BOUND", lowRejected && highRejected)

        var admitted = 0
        for (row in cases) if (store.insertIfAbsent(entryOf(row))) admitted++
        val duplicateRefused = !store.insertIfAbsent(entryOf(cases.first()))
        check("INSERT_ADMISSION", admitted == cases.size && duplicateRefused)

        val firstPage = store.orphanedPreparationsAfter(null, cap)
        check("ORPHAN_ENUM", firstPage.map { it.id } == Qa69Mirror.page1Ids)

        val afterPage = store.orphanedPreparationsAfter(Qa69Mirror.afterId, cap)
        check(
            "ORPHAN_AFTER",
            afterPage.map { it.id } == Qa69Mirror.page2Ids &&
                afterPage.none { it.id == Qa69Mirror.afterId },
        )

        val expectedKeys = Qa69Mirror.rows.filter { it.id in Qa69Mirror.orphanIds }
            .sortedBy { it.id }.take(cap).map { it.ssk }
        check("ORPHAN_KEY_FIELD", firstPage.map { it.secureSourceKey } == expectedKeys)

        val wider = store.orphanedPreparationsAfter(null, cap + 8)
        check("ORPHAN_COUNT", wider.size == Qa69Mirror.orphanIds.size)

        val preparing = cases.first { it.state == "PREPARING" }
        val recovering = cases.first { it.state == "RECOVERING" }
        val scheduled = cases.first { it.state == "SCHEDULED" }
        val wrongKeyClaim = store.markRecoveringIfOrphaned(preparing.id, recovering.ssk)
        val stillPreparing = store.get(preparing.id)?.state == "PREPARING"
        val claimed = store.markRecoveringIfOrphaned(preparing.id, preparing.ssk)
        val nowRecovering = store.get(preparing.id)?.state == "RECOVERING"
        val scheduledRefused = !store.markRecoveringIfOrphaned(scheduled.id, scheduled.ssk)
        val idempotent = store.markRecoveringIfOrphaned(preparing.id, preparing.ssk) &&
            store.get(preparing.id)?.state == "RECOVERING"
        check(
            "CLAIM_KEY_GUARD",
            !wrongKeyClaim && stillPreparing && claimed && nowRecovering &&
                scheduledRefused && idempotent,
        )

        val wrongStateDelete = !store.deleteRecovered(scheduled.id, scheduled.ssk) &&
            store.get(scheduled.id) != null
        val wrongKeyDelete = !store.deleteRecovered(preparing.id, recovering.ssk) &&
            store.get(preparing.id) != null
        val removed = store.deleteRecovered(preparing.id, preparing.ssk) &&
            store.get(preparing.id) == null
        val secondDelete = !store.deleteRecovered(preparing.id, preparing.ssk)
        check(
            "DELETE_STATE_GUARD",
            wrongStateDelete && wrongKeyDelete && removed && secondDelete,
        )

        check("MIRROR_CONTRACT", Qa69Mirror.computeChecks().second)

        if (mode == "verify-leak" && extra != null) {
            val raw = File(extra).readText(StandardCharsets.UTF_8).trim()
            say("RAW\t$raw")
        }
        val scanned = diagnostics.toString()
        val leaked = sensitive.any { it.containsMatchIn(scanned) } ||
            scanned.contains(Qa69Mirror.derivedMarker())
        check("PRIVACY_SCAN", !leaked)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "verify" }
        val directory = File(args.getOrElse(1) { "." })
        val extra = args.getOrNull(2)
        if (mode == "generate") generate(directory)

        val casesBytes = read(directory, "cases.tsv")
        val dimensionsBytes = read(directory, "dimensions.tsv")
        val manifestBytes = read(directory, "sha256.txt")
        val cases = casesBytes?.let { Qa69Mirror.parseCases(String(it, StandardCharsets.UTF_8)) }
        val dimensions = dimensionsBytes?.let { Qa69Mirror.parseDimensions(String(it, StandardCharsets.UTF_8)) }
        val manifest = manifestBytes?.let { Qa69Mirror.parseManifest(String(it, StandardCharsets.UTF_8)) }

        val skipByte = mode in setOf("verify-semantic", "verify-manifest", "verify-pinned", "verify-leak")
        val skipManifest = mode in setOf("verify-semantic", "verify-pinned", "verify-leak")
        val skipPinned = mode in setOf("verify-semantic", "verify-leak")

        if (casesBytes == null || dimensionsBytes == null || manifestBytes == null) {
            check("FILE_PRESENT", false)
            finish()
        }
        if (!skipByte) {
            val generated = Qa69Mirror.generateBytes()
            check(
                "BYTE_IDENTITY",
                Qa69Mirror.ALL_FILES.all { name ->
                    val committed = read(directory, name)
                    committed != null && committed.contentEquals(generated.getValue(name))
                },
            )
        }
        if (!skipManifest) {
            check(
                "MANIFEST_CONSISTENT",
                manifest != null && manifest.size == 2 &&
                    manifest["cases.tsv"] == Qa69Mirror.sha(casesBytes!!) &&
                    manifest["dimensions.tsv"] == Qa69Mirror.sha(dimensionsBytes!!),
            )
        }
        if (!skipPinned) {
            check(
                "PINNED_DIGEST",
                dimensions != null && dimensions["CASES_DIGEST"] == Qa69Mirror.sha(casesBytes!!),
            )
        }
        if (cases == null || dimensions == null || manifest == null) {
            check("HEADER_STRICT", false)
            finish()
        }
        check("FIXTURE_SHAPE", cases.size == Qa69Mirror.ID_COUNT && dimensions.size == 8)
        semantic(cases, mode, extra)
        finish()
    }
}
