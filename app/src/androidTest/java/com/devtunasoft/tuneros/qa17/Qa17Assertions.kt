package com.devtunasoft.tuneros.qa17

import java.io.File
import java.security.MessageDigest

/**
 * QA-17 JVM/instrumentation mirror of the host gate's bounded SAF destination contract.
 *
 * It uses the same pinned seed, digests and fail-closed boundaries. No external feed address,
 * access marker, personal code, key or media payload is stored or printed. On a device no fixture
 * path is available, so only the seed-derived boundary checks run; the host runner supplies the
 * committed fixture path for the full digest checks.
 */
object Qa17Assertions {
    const val VERSION = "qa17-fixture-v1"
    const val SEED = "QA17-FIXED-SEED-2026-11-23"
    const val ALL_DIGEST = "0c76de5afa1fe62c386e6b308fae2206afce023515c2c00e251a75c094aaac2c"
    const val MIRROR_DIGEST = "2f0884cb8b4ef07b6585c83a9c3b405f9a478e4dda56a579355b955d2d421032"
    const val SEED_TAG = "3a4544d0be99"
    const val SAF_AUTHORITY = "com.android.externalstorage.documents"

    const val ROOT_A = "/scratch/qa17/internal/recordings"
    const val ROOT_B = "/scratch/qa17/external/recordings"

    private val FILES = listOf(
        "contract.txt", "dimensions.tsv", "prefs.tsv", "volumes.tsv",
        "grants.tsv", "locations.tsv", "corrupt.tsv"
    )

    private fun sha(value: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value)
        val digits = "0123456789abcdef"
        val out = StringBuilder()
        for (byte in bytes) {
            out.append(digits[(byte.toInt() shr 4) and 0xf])
            out.append(digits[byte.toInt() and 0xf])
        }
        return out.toString()
    }

    private fun sha(value: String): String = sha(value.toByteArray(Charsets.UTF_8))

    private fun marker(tag: String): String = sha("$SEED|$tag").substring(0, 12)

    private fun bad(value: String): Boolean = value.any {
        it.code < 0x20 || it.code == 0x7f || it == ' ' || it == '"' || it == '%'
    }

    private fun decode(value: String): String =
        value.replace("%2F", "/").replace("%3A", ":").replace("%25", "%")

    /** Mirrors the host pref classifier; rejects anything but a complete platform tree id. */
    fun classifyPref(raw: String): String {
        if (raw.isEmpty()) return "BLANK"
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return "MALFORMED"
        if (raw.substring(0, schemeEnd) != "content") return "BAD_SCHEME"
        val rest = raw.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return "MALFORMED"
        if (rest.substring(0, slash) != SAF_AUTHORITY) return "BAD_AUTHORITY"
        val path = rest.substring(slash + 1)
        if (!path.startsWith("tree/")) return "TRUNCATED"
        val encoded = path.substring(5)
        if (encoded.isEmpty()) return "TRUNCATED"
        val treeId = decode(encoded)
        if (bad(treeId)) return "MALFORMED"
        if (treeId.contains("..") || !treeId.contains(":")) return "BAD_TREE_ID"
        return "ACCEPT"
    }

    /** Boundary-exact tree containment: a sibling prefix is not inside the grant. */
    fun contained(treeId: String, documentId: String): Boolean {
        if (treeId.isEmpty() || documentId.isEmpty() || bad(treeId) || bad(documentId)) return false
        return if (treeId.endsWith(":")) {
            documentId.startsWith(treeId)
        } else {
            documentId == treeId || documentId.startsWith("$treeId/")
        }
    }

    /** Only files strictly under one of the two recordings roots are reachable. */
    fun internalAllowed(path: String): Boolean {
        if (path.isEmpty() || bad(path) || path.contains("..") || path.contains("\\")) return false
        return listOf(ROOT_A, ROOT_B).any { root ->
            path.startsWith("$root/") && path.length > root.length + 1
        }
    }

    fun mirrorDigest(): String = sha("$VERSION|$SEED|${marker("mirror-model")}")

    private fun digestFile(file: File): String = sha(file.readBytes())

    /** Runs the mirror. Returns true when every executed check passes. */
    fun run(fixtureDir: String?, sink: (String) -> Unit = ::println): Boolean {
        val results = ArrayList<Pair<String, Boolean>>()
        fun check(code: String, ok: Boolean) {
            results.add(code to ok)
        }

        val sampleTree = marker("mirror-tree") + ":" + marker("mirror-root")
        check("MIRROR_SEED", marker("seed-tag") == SEED_TAG)
        check("MIRROR_DIGEST", mirrorDigest() == MIRROR_DIGEST)
        check("MIRROR_VERSION", VERSION == "qa17-fixture-v1")
        check("PREF_BLANK", classifyPref("") == "BLANK")
        check("PREF_BAD_SCHEME", classifyPref("content-x://$SAF_AUTHORITY/tree/x:y") == "BAD_SCHEME")
        check("PREF_BAD_AUTHORITY", classifyPref("content://scratch.invalid/tree/x:y") == "BAD_AUTHORITY")
        check("PREF_TRUNCATED", classifyPref("content://$SAF_AUTHORITY/tree/") == "TRUNCATED")
        check("PREF_ACCEPT", classifyPref("content://$SAF_AUTHORITY/tree/$sampleTree") == "ACCEPT")
        check("PREF_BAD_ESCAPE", classifyPref("content://$SAF_AUTHORITY/tree/$sampleTree%ZZ") == "MALFORMED")
        check("TREE_CONTAINED", contained(sampleTree, "$sampleTree/document"))
        check("TREE_SIBLING_REJECTED", !contained("$sampleTree/sub", "$sampleTree/sub-evil/document"))
        check("TREE_EMPTY_REJECTED", !contained("", "$sampleTree/document"))
        check("INTERNAL_ALLOWED", internalAllowed("$ROOT_A/${marker("mirror-file")}"))
        check("INTERNAL_TRAVERSAL_REJECTED", !internalAllowed("$ROOT_A/../outside/${marker("mirror-file")}"))
        check("INTERNAL_BOUNDARY_REJECTED", !internalAllowed("$ROOT_A-other/${marker("mirror-file")}"))

        if (fixtureDir != null) {
            val dir = File(fixtureDir)
            val dimensions = File(dir, "dimensions.tsv").readLines()
                .filter { it.contains('=') }
                .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
            check("MIRROR_DIMENSIONS",
                dimensions["model.mirror.digest"] == MIRROR_DIGEST &&
                dimensions["fixture.version"] == VERSION &&
                dimensions["seed.tag"] == SEED_TAG &&
                dimensions["prefs.rows"] == "64" &&
                dimensions["locations.rows"] == "96" &&
                dimensions["corrupt.rows"] == "12")
            val all = StringBuilder()
            var digestFailure = false
            for (name in FILES) {
                val digest = digestFile(File(dir, name))
                all.append(name).append(':').append(digest).append('\n')
            }
            check("MIRROR_ALL_DIGEST", sha(all.toString()) == ALL_DIGEST)
            val manifest = File(dir, "sha256.txt").readLines()
                .filter { it.contains('\t') }
                .associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
            for (name in FILES) {
                if (manifest[name] != digestFile(File(dir, name))) digestFailure = true
            }
            check("MIRROR_SHA_PIN", !digestFailure && manifest["all"] == ALL_DIGEST)
        }

        val pass = results.count { it.second }
        val fail = results.size - pass
        for ((code, ok) in results) {
            sink("CHECK\t${if (ok) "PASS" else "FAIL"}\t$code\t1")
        }
        sink("DIGEST\t$MIRROR_DIGEST")
        sink("SUMMARY\tpositive=$pass\tfailed=$fail")
        return fail == 0 && pass > 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val ok = run(if (args.isNotEmpty()) args[0] else null)
        if (!ok) System.exit(1)
    }

    /** Used by the instrumentation wrapper to build its bounded result bundle. */
    fun reportLines(): List<String> {
        val captured = ArrayList<String>()
        run(null) { line -> captured.add(line) }
        return captured
    }
}
