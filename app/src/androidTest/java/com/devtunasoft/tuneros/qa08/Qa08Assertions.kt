package com.devtunasoft.tuneros.qa08

import java.security.MessageDigest

/**
 * QA-08 compiled assertions. Pure Kotlin/JDK only: no JUnit, no AndroidX, no product sources.
 * The model mirrors the host gate: bounded overlapping reservations, half-open and padded
 * conflict windows, earliest-active-overlap selection, duplicate/capped series rules and
 * capped expansion. Every assertion is also runnable on the JVM via [main].
 */
object Qa08Assertions {
    private const val SCHEDULE_COUNT = 480
    private const val RULE_ROWS = 64
    private const val PROGRAM_COUNT = 900
    private const val SCHEDULE_BOUND = 600
    private const val RULE_BOUND = 80
    private const val PROGRAM_BOUND = 1000
    private const val MAX_RULES = 50
    private const val MAX_RESERVATIONS = 500
    private const val BASE = 1_800_000_000L
    private const val NOW = BASE + 20L * 180L
    private const val PAD = 60
    private const val CANONICAL_DIGEST = "04935fd051d68d65bf0838ecb41307cec40508d8e132ce8082fca550967930e7"

    private val ACTIVE = setOf("PREPARING", "RECOVERING", "SCHEDULED", "STARTED")
    private val TERMINAL = setOf("COMPLETED", "PARTIAL", "FAILED", "CANCELLED")

    data class Check(val id: String, val pass: Boolean, val detail: String)

    private data class Schedule(
        val id: String, val channel: String, val title: String,
        val start: Long, val end: Long, val state: String,
        val startPad: Int, val endPad: Int,
    ) {
        val captureStart: Long get() = start - startPad
        val captureEnd: Long get() = end + endPad
        fun line(): String = listOf(
            id, channel, title, start.toString(), end.toString(),
            state, startPad.toString(), endPad.toString(),
        ).joinToString("\t")
    }

    private data class RuleRow(val title: String, val channel: String, val created: Long) {
        fun line(seq: Int): String = listOf(seq.toString(), title, channel, created.toString()).joinToString("\t")
        fun key(): String = normalize(title) + "|" + channel
    }

    private data class Program(val seq: Int, val channel: String, val title: String, val start: Long, val end: Long) {
        fun line(): String = listOf(seq.toString(), channel, title, start.toString(), end.toString()).joinToString("\t")
    }

    private class Model {
        val schedules = ArrayList<Schedule>(SCHEDULE_COUNT)
        val rules = ArrayList<RuleRow>(RULE_ROWS)
        val programs = ArrayList<Program>(PROGRAM_COUNT)
    }

    private class Metrics {
        var rawPairs = 0L
        var paddedOnlyPairs = 0L
        var expired = 0L
        var unmatched = 0L
        var duplicateSkipped = 0L
        var duplicates = 0L
        var distinctRules = 0L
        var effectiveRules = 0L
        var planned = 0L
        var reserved = 0L
        var conflictSkipped = 0L
        var terminalOnly = 0L
        var selectionDigest = ""
    }

    private fun normalize(title: String): String {
        val out = StringBuilder(title.length)
        var pending = false
        for (ch in title) {
            if (ch.isLetterOrDigit()) {
                if (pending && out.isNotEmpty()) out.append(' ')
                out.append(ch.lowercaseChar())
                pending = false
            } else {
                pending = true
            }
        }
        return out.toString()
    }

    private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart < bEnd && bStart < aEnd

    private fun buildModel(): Model {
        val model = Model()
        val states = arrayOf("SCHEDULED", "PREPARING", "STARTED", "RECOVERING")
        val terminalStates = arrayOf("COMPLETED", "CANCELLED", "FAILED", "PARTIAL")
        for (i in 0 until SCHEDULE_COUNT) {
            val terminal = i % 7 == 0
            val slot = if (terminal) 60L + (i % 8) else (i % 48).toLong()
            val start = BASE + slot * 180L
            val end = start + if (terminal) 900L else 300L + (i % 5) * 120L
            val state = if (terminal) terminalStates[(i / 7) % 4] else states[i % 4]
            model.schedules += Schedule(
                "qa08sch-%04d".format(i), "qa-ch-%02d".format(i % 64),
                "QA Series %02d".format(i % 16), start, end, state,
                if (i % 4 == 0) PAD else 0, if (i % 5 == 0) PAD else 0,
            )
        }
        for (i in 0 until RULE_ROWS) {
            val channel = if (i % 17 == 0) "qa-ch-%02d".format(i % 64) else "*"
            model.rules += RuleRow("QA Series %02d".format(i % 56), channel, BASE + i)
        }
        var lastSlot = 0
        var lastChannel = ""
        for (i in 0 until PROGRAM_COUNT) {
            var slot = i % 120
            var channelIndex = i % 64
            var titleIndex = i % 56
            if (i % 97 == 0) titleIndex = 59
            if (i % 64 == 63 && i > 0) {
                slot = lastSlot
                channelIndex = lastChannel.substring(lastChannel.length - 2).toInt()
                titleIndex = 1
            }
            val channel = "qa-ch-%02d".format(channelIndex)
            val start = BASE + slot * 180L
            model.programs += Program(i, channel, "QA Series %02d".format(titleIndex), start, start + 600L + (i % 3) * 120L)
            lastSlot = slot
            lastChannel = channel
        }
        return model
    }

    private fun analyze(model: Model): Metrics {
        val m = Metrics()
        for (i in model.schedules.indices) {
            for (j in i + 1 until model.schedules.size) {
                val a = model.schedules[i]
                val b = model.schedules[j]
                if (overlaps(a.start, a.end, b.start, b.end)) m.rawPairs++
                else if (overlaps(a.captureStart, a.captureEnd, b.captureStart, b.captureEnd)) m.paddedOnlyPairs++
            }
        }
        val unique = LinkedHashMap<String, RuleRow>()
        for (rule in model.rules) unique.putIfAbsent(rule.key(), rule)
        m.duplicates = (model.rules.size - unique.size).toLong()
        m.distinctRules = unique.size.toLong()
        m.effectiveRules = minOf(MAX_RULES, unique.size).toLong()
        val effective = unique.values.take(MAX_RULES)

        val seen = HashSet<String>()
        val planned = ArrayList<Program>()
        for (program in model.programs) {
            if (program.start <= NOW) { m.expired++; continue }
            val titleKey = normalize(program.title)
            val matched = effective.any { rule ->
                (rule.channel == "*" || rule.channel == program.channel) && normalize(rule.title) == titleKey
            }
            if (!matched) { m.unmatched++; continue }
            if (!seen.add(program.channel + "|" + program.start)) { m.duplicateSkipped++; continue }
            planned += program
        }
        m.planned = minOf(MAX_RESERVATIONS, planned.size).toLong()

        var rolling = 1469598103934665603L
        for (program in planned.take(MAX_RESERVATIONS)) {
            val pad = if (program.seq % 3 == 0) PAD else 0
            val captureStart = program.start - pad
            val captureEnd = program.end + pad
            var selected: Schedule? = null
            var terminalOnly = false
            for (schedule in model.schedules) {
                if (schedule.state !in ACTIVE) {
                    if (overlaps(schedule.start, schedule.end, program.start, program.end)) terminalOnly = true
                    continue
                }
                if (!overlaps(schedule.captureStart, schedule.captureEnd, captureStart, captureEnd)) continue
                if (selected == null || schedule.start < selected.start ||
                    (schedule.start == selected.start && schedule.id < selected.id)
                ) selected = schedule
            }
            if (selected == null) {
                m.reserved++
                if (terminalOnly) m.terminalOnly++
            } else {
                m.conflictSkipped++
                for (ch in selected.id) rolling = (rolling xor ch.code.toLong()) * 1099511628211L
                rolling = (rolling xor '\t'.code.toLong()) * 1099511628211L
            }
        }
        m.selectionDigest = "%016x".format(rolling)
        return m
    }

    private fun digest(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fixtureBytes(model: Model, metrics: Metrics): ByteArray {
        val schedules = StringBuilder("qa08-schedules-v1\n")
        for (row in model.schedules) schedules.append(row.line()).append('\n')
        val rules = StringBuilder("qa08-rules-v1\n")
        for ((index, row) in model.rules.withIndex()) rules.append(row.line(index)).append('\n')
        val programs = StringBuilder("qa08-programs-v1\n")
        for (row in model.programs) programs.append(row.line()).append('\n')
        val manifest = StringBuilder("qa08-manifest-v1\n")
            .append("schedules=$SCHEDULE_COUNT\n")
            .append("rules=$RULE_ROWS\n")
            .append("programs=$PROGRAM_COUNT\n")
            .append("raw-conflict-pairs=${metrics.rawPairs}\n")
            .append("padded-only-pairs=${metrics.paddedOnlyPairs}\n")
            .append("expired=${metrics.expired}\n")
            .append("unmatched=${metrics.unmatched}\n")
            .append("duplicate-skipped=${metrics.duplicateSkipped}\n")
            .append("duplicates=${metrics.duplicates}\n")
            .append("distinct-rules=${metrics.distinctRules}\n")
            .append("effective-rules=${metrics.effectiveRules}\n")
            .append("planned=${metrics.planned}\n")
            .append("reserved=${metrics.reserved}\n")
            .append("conflict-skipped=${metrics.conflictSkipped}\n")
            .append("terminal-only=${metrics.terminalOnly}\n")
            .append("selection-digest=${metrics.selectionDigest}\n")
        val all = schedules.toString() + rules.toString() + programs.toString() + manifest.toString()
        val sha = StringBuilder()
        for (part in listOf(schedules.toString(), rules.toString(), programs.toString(), manifest.toString())) {
            sha.append(digest(part.toByteArray(Charsets.UTF_8))).append("  ")
            sha.append(
                when (part) {
                    schedules.toString() -> "schedules.tsv"
                    rules.toString() -> "rules.tsv"
                    programs.toString() -> "programs.tsv"
                    else -> "manifest.txt"
                }
            ).append('\n')
        }
        return (all + sha.toString()).toByteArray(Charsets.UTF_8)
    }

    fun runAll(): List<Check> {
        val model = buildModel()
        val m = analyze(model)
        val checks = ArrayList<Check>(12)
        checks += Check("qa08-fixture-digest", digest(fixtureBytes(model, m)) == CANONICAL_DIGEST,
            "algorithm=SHA-256 scope=5-scratch-fixtures")
        checks += Check("qa08-row-bounds",
            model.schedules.size == 480 && model.rules.size == 64 && model.programs.size == 900 &&
                model.schedules.size <= SCHEDULE_BOUND && model.rules.size <= RULE_BOUND &&
                model.programs.size <= PROGRAM_BOUND,
            "schedules=${model.schedules.size} rules=${model.rules.size} programs=${model.programs.size}")
        checks += Check("qa08-conflict-halfopen", m.rawPairs == 11574L, "pairs=${m.rawPairs}")
        checks += Check("qa08-padded-conflict", m.paddedOnlyPairs == 369L, "pairs=${m.paddedOnlyPairs}")
        checks += Check("qa08-overlap-selection", m.selectionDigest == "19808cb65184d824", "digest-bytes=16")
        checks += Check("qa08-terminal-not-blocking", m.terminalOnly == 83L && m.reserved >= m.terminalOnly,
            "terminal-only=${m.terminalOnly} reserved=${m.reserved}")
        checks += Check("qa08-duplicate-rules", m.duplicates == 7L && m.distinctRules == 57L,
            "duplicates=${m.duplicates} distinct=${m.distinctRules}")
        checks += Check("qa08-rule-cap", m.effectiveRules == 50L.toLong() && m.distinctRules > MAX_RULES,
            "effective=${m.effectiveRules} cap=$MAX_RULES")
        checks += Check("qa08-expansion-cap", m.planned == 500L && m.planned <= MAX_RESERVATIONS,
            "planned=${m.planned} cap=$MAX_RESERVATIONS")
        checks += Check("qa08-expansion-dedupe", m.duplicateSkipped == 10L, "collisions=${m.duplicateSkipped}")
        checks += Check("qa08-expansion-conflict",
            m.conflictSkipped == 159L && m.reserved + m.conflictSkipped == m.planned,
            "reserved=${m.reserved} conflict-skipped=${m.conflictSkipped} planned=${m.planned}")
        val report = checks.joinToString("\n") { "${it.id}\t${it.pass}\t${it.detail}" }
        val privateTokenPresent = report.contains(Regex("(?i)(https?://|password|passwd|secret|api[_-]?key|bearer|token=|drm|provider\\.)"))
        checks += Check("qa08-privacy-diagnostics", !privateTokenPresent, "scratch-identifiers-only=true")
        return checks
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val checks = runAll()
        for (check in checks) {
            println("${if (check.pass) "PASS" else "FAIL"}\t${check.id}\t${check.detail}")
        }
        val passed = checks.count { it.pass }
        println("SUMMARY\tpositive=$passed/${checks.size}\tresult=${if (passed == checks.size) "PASS" else "FAIL"}")
        if (passed != checks.size) System.exit(1)
    }
}
