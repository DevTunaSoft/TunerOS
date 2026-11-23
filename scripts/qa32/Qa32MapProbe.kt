package com.devtunasoft.tuneros
import java.io.File

private data class MapCase(val id: String, val raw: String, val expected: String)

/** Drives the real GuideMappingStore compiled from the pinned MAP-04 commit with host-only seams. */
object Qa32MapProbe {
    private fun rows(path: String): List<List<String>> = File(path).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }

    @JvmStatic fun main(args: Array<String>) {
        val exp = rows(args[0]).associate { it[0] to it[1] }
        fun exp(key: String): String = exp[key] ?: throw IllegalStateException("missing-expectation:$key")
        val cases = rows(args[1]).associate { row -> row[0] to MapCase(row[0], row[1], row[2]) }
        val golden = File(args[2]).readText().trimEnd('\r', '\n')
        val fixed = File(args[3]).readText()
        val base = File(args[4]).readText()
        val canary = args[5]

        var checks = 0
        var passed = 0
        var failed = 0
        var first = "NONE"
        fun check(id: String, body: () -> Pair<Boolean, String>) {
            checks++
            val result = try { body() } catch (t: Throwable) { false to "exception=" + t.javaClass.simpleName }
            if (result.first) {
                passed++
                println("CHECK\tPASS\t$id\t${result.second}")
            } else {
                failed++
                if (first == "NONE") first = id
                println("CHECK\tFAIL\t$id\t${result.second}")
            }
        }
        fun count(text: String, needle: String): Int {
            var n = 0
            var i = text.indexOf(needle, 0)
            while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
            return n
        }
        fun expectedMap(spec: String): Map<String, String> {
            if (spec == "EMPTY") return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (entry in spec.split(",")) {
                if (entry.isEmpty()) continue
                val pair = entry.split("=", limit = 2)
                out[pair[0]] = pair[1]
            }
            return out
        }
        fun runCase(id: String): Map<String, String> {
            val c = cases[id] ?: throw IllegalStateException("missing-case:$id")
            val storage = HostStorage(c.raw)
            val store = GuideMappingStore(storage, realCodec())
            return store.load()
        }

        // Semantic checks first: mutations must reach these, not a later structural check.
        check("M_NONSTRING_RECOVERY") {
            val want = expectedMap(cases.getValue("nonstring").expected)
            val got = runCase("nonstring")
            (got == want) to "entries=${got.size} expected=${want.size}"
        }
        check("M_BOOLNULL_RECOVERY") {
            val want = expectedMap(cases.getValue("boolnull").expected)
            val got = runCase("boolnull")
            (got == want) to "entries=${got.size} expected=${want.size}"
        }
        check("M_VALID_LOAD") {
            val want = expectedMap(cases.getValue("valid_pair").expected)
            val got = runCase("valid_pair")
            (got == want) to "entries=${got.size} expected=${want.size}"
        }
        check("M_TOTAL_CORRUPTION") {
            val got = runCase("total_corrupt")
            got.isEmpty() to "entries=${got.size} expected=0"
        }
        check("M_EMPTY_PAYLOAD") {
            val got = runCase("empty_payload")
            got.isEmpty() to "entries=${got.size} expected=0"
        }
        check("M_GOLDEN_SAVE") {
            val storage = HostStorage(null)
            val store = GuideMappingStore(storage, realCodec())
            val mappings = linkedMapOf("live.channel" to "guide.live", "stale.channel" to "guide.stale")
            val ok = store.save(mappings)
            (ok && storage.raw == golden && storage.writes == exp("map.save.writes").toInt()) to
                "commit=$ok writes=${storage.writes} golden-bytes=${golden.length}"
        }
        check("M_STALE_KEY_NOT_PRUNED") {
            val storage = HostStorage(null)
            val store = GuideMappingStore(storage, realCodec())
            store.save(linkedMapOf("live.channel" to "guide.live", "stale.channel" to "guide.stale"))
            val got = store.load()
            val want = setOf("live.channel", "stale.channel")
            (got.keys == want && storage.removes == exp("map.load.removes").toInt() &&
                golden.contains("stale.channel") && golden.contains("guide.stale")) to
                "keys=${got.size} removes=${storage.removes}"
        }
        check("M_LOAD_NO_WRITE") {
            val c = cases.getValue("valid_pair")
            val storage = HostStorage(c.raw)
            val store = GuideMappingStore(storage, realCodec())
            val w0 = storage.writes
            val r0 = storage.removes
            store.load()
            (storage.writes == w0 && storage.removes == r0) to
                "writes=${storage.writes} removes=${storage.removes}"
        }
        check("M_SOURCE_JSON_GET") {
            val n = count(fixed, "json.get(channelId)")
            (n == exp("map.json_get.count").toInt()) to "count=$n expected=${exp("map.json_get.count")}"
        }
        check("M_SOURCE_STRING_FILTER") {
            val n = count(fixed, "if (value is String)")
            (n == exp("map.string_filter.count").toInt()) to "count=$n expected=${exp("map.string_filter.count")}"
        }
        check("M_SOURCE_JSON_GETSTRING_ABSENT") {
            val n = count(fixed, "json.getString(channelId)")
            (n == exp("map.json_getstring.count").toInt()) to "count=$n expected=${exp("map.json_getstring.count")}"
        }
        check("M_FORMAT_CONTRACT") {
            val needles = listOf(exp("map.storage.key"), "json.put(channelId, guideId)",
                "json.toString()", "PREFS_NAME = \"" + exp("map.prefs.name") + "\"")
            val bad = needles.count { count(fixed, it) != 1 || count(base, it) != 1 }
            (bad == 0) to "needles=${needles.size} mismatched=$bad"
        }
        check("M_PRIVACY_CANARY") {
            val leaked = canary.isNotBlank() && (fixed.contains(canary) || golden.contains(canary))
            (!leaked) to "marker-absent=${!leaked}"
        }
        println("PROBE_SUMMARY\tMAP04\tchecks=$checks\tpass=$passed\tfail=$failed\tfirst=$first")
    }

    private fun realCodec(): GuideMappingStore.Codec {
        val cls = Class.forName("com.devtunasoft.tuneros.GuideMappingStore\$JsonCodec")
        val field = cls.getDeclaredField("INSTANCE")
        field.isAccessible = true
        return field.get(null) as GuideMappingStore.Codec
    }

    private class HostStorage(var raw: String?) : GuideMappingStore.Storage {
        var writes = 0
            private set
        var removes = 0
            private set
        override fun read(): String? = raw
        override fun write(value: String): Boolean { writes++; raw = value; return true }
        override fun remove(): Boolean { removes++; raw = null; return true }
    }
}
