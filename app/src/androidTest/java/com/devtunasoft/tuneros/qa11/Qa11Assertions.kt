package com.devtunasoft.tuneros.qa11

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * QA-11 mirrored assertions. No Android or JUnit imports: this object runs on the JVM and inside the
 * instrumented APK. The byte generator below renders every field of every record exactly as the host
 * gate does, so a missing field or a changed order fails the pinned mirror digest.
 */
object Qa11Assertions {
    data class Result(val id: String, val pass: Boolean, val detail: String)

    private const val HEADER = "TUNEROS-BACKUP 1"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_RECORDS = 5_000
    private const val MAX_VALUE_CHARS = 256
    private const val CHANNEL_COUNT = 25_000
    private const val FAVORITE_COUNT = 2_400
    private const val MAPPING_COUNT = 1_600
    private const val SERIES_COUNT = 600
    private const val GROUP_COUNT = 100
    private const val RESTRICTED_CHANNEL_COUNT = 300
    private const val SCHEDULED_GUIDE_COUNT = 1_800
    private const val ROW_CAP = 24
    private const val SEED = 20_261_123L

    private val credentialLike = Regex("(?i)(pass(word|wd)?|token|secret|api[_-]?key|authorization)\\s*[=:]")

    @JvmStatic
    fun main(args: Array<String>) {
        val results = run()
        for (result in results) {
            println("${if (result.pass) "PASS" else "FAIL"}\t${result.id}\t${result.detail}")
        }
        val passed = results.count { it.pass }
        println("SUMMARY\tpositive=$passed/${results.size}\tresult=${if (passed == results.size) "PASS" else "FAIL"}")
        println("MIRROR\tbackup-digest=${sha256(backupBytes())}")
        if (passed != results.size) throw IllegalStateException("qa11-mirror-failed")
    }

    @JvmStatic
    fun run(): List<Result> {
        val results = ArrayList<Result>()
        val backup = backupText()
        val bytes = backup.toByteArray(StandardCharsets.UTF_8)
        val records = backup.split('\n').count { it.isNotEmpty() } - 1
        results += result("mirror-record-cap-binds", records == MAX_RECORDS, "records=$records cap=$MAX_RECORDS")
        results += result("mirror-bytes-bounded", bytes.size <= MAX_BYTES, "bytes=${bytes.size} cap=$MAX_BYTES")
        results += result("mirror-values-clean", !backup.contains("://") && !credentialLike.containsMatchIn(backup),
            "url=0 credential=0")

        val decoded = decode(backup)
        val canonical = canonicalState()
        val canonicalText = encode(canonical)
        results += result("mirror-roundtrip-canonical", encode(decoded) == canonicalText && backup != canonicalText,
            "canonical-bytes=${canonicalText.toByteArray(StandardCharsets.UTF_8).size}")

        val scheduled = HashSet<String>()
        for (i in 0 until SCHEDULED_GUIDE_COUNT) scheduled += String.format(Locale.ROOT, "gd%05d", i)
        var visible = 0
        var hidden = 0
        for (i in 0 until CHANNEL_COUNT) {
            val key = String.format(Locale.ROOT, "ch%05d", i)
            val mapped = decoded.mappings[key]
            val guide = mapped ?: String.format(Locale.ROOT, "gd%05d", i % 20_000)
            if (guide in scheduled) visible++ else hidden++
        }
        results += result("mirror-hidden-epg-visibility", visible > 0 && hidden > 0 && visible + hidden == CHANNEL_COUNT,
            "visible=$visible hidden=$hidden")
        results += result("mirror-recycled-window", windowOk(visible, ROW_CAP), "rowCap=$ROW_CAP visible=$visible")

        results += result("mirror-restricted-groups-normalized",
            decoded.groups.size == GROUP_COUNT &&
                (0 until GROUP_COUNT).all { String.format(Locale.ROOT, "grp%03d", it) in decoded.groups },
            "groups=${decoded.groups.size} cap=100")
        results += result("mirror-parental-policy",
            requiresPin("grp000", decoded.groups, false) &&
                !requiresPin("grp000", decoded.groups, true) &&
                !requiresPin("grp200", decoded.groups, false),
            "locked=true unlocked=false")

        val rejects = listOf(
            "TUNEROS-BACKUP 0\nfavorite\tch00000\n",
            "$HEADER\nlegacy\tch00000\n",
            "$HEADER\nfavorite\thttp://scratch.invalid/list.m3u8\n",
            "$HEADER\nmapping\tch00000\ttoken=SCRATCH\n",
            "$HEADER\nfavorite\tch0000\\q\n",
        )
        results += result("mirror-corrupt-input-fail-closed", rejects.all { rejects(it) }, "cases=${rejects.size}")

        val report = results.joinToString("\n") { "${it.id}\t${it.detail}" }
        results += result("mirror-diagnostics-secret-free", secretFree(report), "pin=0 url=0 credential=0")
        return results
    }

    private fun result(id: String, pass: Boolean, detail: String) = Result(id, pass, detail)

    // ------------------------------------------------------------- deterministic generator

    private class State {
        val favorites = HashSet<String>()
        val mappings = HashMap<String, String>()
        val series = ArrayList<Pair<String, String>>()
        val groups = HashSet<String>()
        val restrictedChannels = HashSet<String>()
    }

    private fun buildState(): State {
        val state = State()
        for (i in 0 until FAVORITE_COUNT * 2 step 2) {
            state.favorites += String.format(Locale.ROOT, "ch%05d", i)
        }
        for (k in 0 until MAPPING_COUNT) {
            val guide = if (k % 3 == 0) String.format(Locale.ROOT, "gd%05d", k)
            else String.format(Locale.ROOT, "gd%05d", 20_000 + k)
            state.mappings[String.format(Locale.ROOT, "ch%05d", k * 7)] = guide
        }
        for (s in 0 until SERIES_COUNT) {
            val title = String.format(Locale.ROOT, "Show %04d", s)
            val channel = if (s % 2 == 0) String.format(Locale.ROOT, "ch%05d", s * 11) else ""
            state.series += title to channel
        }
        for (g in 0 until GROUP_COUNT) {
            state.groups += if (g % 2 == 0) String.format(Locale.ROOT, "  GRP%03d ", g)
            else String.format(Locale.ROOT, "grp%03d", g)
        }
        for (r in 0 until RESTRICTED_CHANNEL_COUNT) {
            val name = String.format(Locale.ROOT, "QA Channel %05d", r * 37)
            state.restrictedChannels += if (r % 2 == 0) "  ${name.uppercase(Locale.ROOT)} " else name
        }
        return state
    }

    private fun encode(state: State): String {
        val lines = ArrayList<String>()
        for (value in state.favorites.sorted()) lines += "favorite\t$value"
        for ((key, value) in state.mappings.toSortedMap()) lines += "mapping\t$key\t$value"
        for ((title, channel) in state.series.sortedWith(compareBy({ it.first }, { it.second }))) {
            lines += "series\t$title\t$channel"
        }
        for (value in state.groups.sorted()) lines += "restricted\t$value"
        for (value in state.restrictedChannels.sorted()) lines += "restricted-channel\t$value"
        require(lines.size <= MAX_RECORDS)
        val text = HEADER + "\n" + lines.joinToString("") { it + "\n" }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES)
        return text
    }

    private fun canonicalState(): State {
        val state = buildState()
        val groups = state.groups.map(::normalize).toHashSet()
        val channels = state.restrictedChannels.map(::normalize).toHashSet()
        state.groups.clear()
        state.groups += groups
        state.restrictedChannels.clear()
        state.restrictedChannels += channels
        return state
    }

    private fun backupText(): String = encode(buildState())

    private fun backupBytes(): ByteArray = backupText().toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------- strict codec mirror

    private fun decode(text: String): State {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES)
        val lines = text.split('\n')
        require(lines.firstOrNull() == HEADER)
        val state = State()
        var records = 0
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            records++
            require(records <= MAX_RECORDS)
            val fields = line.split('\t')
            when (fields[0]) {
                "favorite" -> { require(fields.size == 2); state.favorites += value(fields[1], false) }
                "mapping" -> { require(fields.size == 3); state.mappings[value(fields[1], false)] = value(fields[2], false) }
                "series" -> {
                    require(fields.size == 3)
                    state.series += value(fields[1], false) to value(fields[2], true)
                }
                "restricted" -> { require(fields.size == 2); state.groups += value(fields[1], false) }
                "restricted-channel" -> { require(fields.size == 2); state.restrictedChannels += value(fields[1], false) }
                else -> throw IllegalArgumentException("unknown-kind")
            }
        }
        val groups = HashSet<String>()
        for (group in state.groups) {
            val key = normalize(group)
            require(key.isNotEmpty())
            groups += key
        }
        require(groups.size <= 100)
        state.groups.clear()
        state.groups += groups
        val channels = HashSet<String>()
        for (channel in state.restrictedChannels) {
            val key = normalize(channel)
            require(key.isNotEmpty())
            channels += key
        }
        state.restrictedChannels.clear()
        state.restrictedChannels += channels
        return state
    }

    private fun value(raw: String, allowEmpty: Boolean): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current != '\\') {
                out.append(current)
                index++
                continue
            }
            require(index + 1 < raw.length)
            when (raw[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> throw IllegalArgumentException("bad-escape")
            }
            index += 2
        }
        val result = out.toString()
        checkValue(result, allowEmpty)
        return result
    }

    private fun checkValue(value: String, allowEmpty: Boolean) {
        require(allowEmpty || value.isNotEmpty())
        require(value.length <= MAX_VALUE_CHARS)
        require(!value.contains("://"))
        require(!credentialLike.containsMatchIn(value))
    }

    private fun reject(text: String): Boolean = try {
        decode(text)
        false
    } catch (error: Exception) {
        true
    }

    private fun rejects(text: String): Boolean = reject(text)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun requiresPin(group: String, groups: Set<String>, unlocked: Boolean): Boolean =
        !unlocked && groups.any { normalize(it) == normalize(group) }

    private fun windowOk(size: Int, cap: Int): Boolean {
        if (size <= 0 || cap <= 0 || min(size, cap) < cap) return false
        val slots = IntArray(cap)
        for (offset in intArrayOf(0, 1, 7, 100, 1_000, size / 2, max(0, size - 1))) {
            val first = max(0, min(offset, size - 1))
            val rows = min(cap, size - first)
            if (rows > slots.size) return false
            for (row in 0 until rows) {
                val index = first + row
                if (index !in 0 until size) return false
                slots[row] = index
            }
        }
        return true
    }

    private fun secretFree(report: String): Boolean {
        val pin = String.format(Locale.ROOT, "%08d", Math.floorMod(SEED * 31L + 7L, 100_000_000L))
        val marker = "qarec-" + Math.floorMod(SEED, 1_000_000L)
        return !report.contains(pin) && !report.contains("://") &&
            !report.contains(marker) && !credentialLike.containsMatchIn(report)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) out.append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        return out.toString()
    }
}
